package io.github.liliangxu.phoneagent.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.liliangxu.phoneagent.config.PhoneAgentProperties;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskServiceApiContractTest {
    @TempDir
    Path runtimeDir;

    @Test
    void createTaskRejectsMissingBlankAndOversizedTextWithoutCreatingTask() {
        TaskService service = PhoneAgentMvpTestFixtures.newTaskService(runtimeDir);

        assertThrows(TaskValidationException.class, () -> service.createTask(null));
        assertThrows(TaskValidationException.class, () -> service.createTask(" \t\n "));
        assertThrows(TaskValidationException.class, () -> service.createTask("测".repeat(2001)));

        assertTrue(service.listTasks().isEmpty());
    }

    @Test
    void legacyConstructorUsesDefaultPhoneConfiguration() {
        TaskService service = new TaskService(
                new PhoneAgentMvpTestFixtures.SequentialTaskIdGenerator("task-legacy-"),
                new PhoneAgentMvpTestFixtures.StubSpeechSynthesisService(runtimeDir.resolve("generated")),
                new SlotAudioStore(runtimeDir.resolve("sounds").resolve("slots")),
                new RecordingStore(runtimeDir.resolve("recordings")),
                new PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient(),
                new PhoneAgentMvpTestFixtures.RecordingAsrJobQueue(),
                PhoneAgentMvpTestFixtures.FIXED_CLOCK
        );

        TaskView created = service.createTask("旧构造函数仍使用默认八个 BLF slot");

        assertEquals(TaskStatus.NOTIFIED, created.status());
        assertEquals(1, created.slot());
    }

    @Test
    void createTaskReturnsNotifiedSummaryForFreeSlotAndQueuedSummaryWhenAllSlotsBusy() {
        TaskService service = PhoneAgentMvpTestFixtures.newTaskService(runtimeDir);

        TaskView first = service.createTask("第一条通知");
        assertEquals("task-20260609-000001", first.taskId());
        assertEquals(TaskStatus.NOTIFIED, first.status());
        assertEquals(1, first.slot());
        assertEquals(PhoneAgentMvpTestFixtures.FIXED_CLOCK.instant(), first.createdAt().toInstant());

        for (int i = 2; i <= 8; i++) {
            TaskView assigned = service.createTask("通知 " + i);
            assertEquals(TaskStatus.NOTIFIED, assigned.status());
            assertEquals(i, assigned.slot());
        }

        TaskView queued = service.createTask("第九条等待通知");
        assertEquals("task-20260609-000009", queued.taskId());
        assertEquals(TaskStatus.QUEUED, queued.status());
        assertNull(queued.slot());
    }

    @Test
    void customFourSlotConfigQueuesFifthTaskAndRejectsSlotFiveCallback() {
        PhoneAgentProperties properties = new PhoneAgentProperties();
        properties.getBlf().setExtensions(java.util.List.of("801", "802", "803", "804"));
        TaskService service = PhoneAgentMvpTestFixtures.newTaskService(runtimeDir, properties);

        for (int i = 1; i <= 4; i++) {
            TaskView assigned = service.createTask("四键配置任务 " + i);
            assertEquals(TaskStatus.NOTIFIED, assigned.status());
            assertEquals(i, assigned.slot());
        }

        TaskView queued = service.createTask("第五条应排队");
        assertEquals(TaskStatus.QUEUED, queued.status());
        assertNull(queued.slot());
        assertThrows(InvalidSlotException.class, () -> service.startSlot(5));
    }

    @Test
    void getTaskReturnsStableContractFieldsAndEmptyForUnknownTask() {
        TaskService service = PhoneAgentMvpTestFixtures.newTaskService(runtimeDir);
        TaskView created = service.createTask("查询接口应返回原始文本和状态字段");

        Optional<TaskView> found = service.getTask(created.taskId());

        assertTrue(found.isPresent());
        assertEquals(created.taskId(), found.get().taskId());
        assertEquals("查询接口应返回原始文本和状态字段", found.get().text());
        assertEquals(TaskStatus.NOTIFIED, found.get().status());
        assertEquals(1, found.get().slot());
        assertNull(found.get().failureStage());
        assertNull(found.get().errorMessage());
        assertNull(found.get().recordingFile());
        assertNull(found.get().replyText());
        assertFalse(service.getTask("missing-task").isPresent());
    }

    @Test
    void blfNotifyFailureReturnsFailedTaskAndDoesNotLeakSlot() {
        PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient amiClient =
                new PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient();
        amiClient.failNextInUse = true;
        TaskService service = PhoneAgentMvpTestFixtures.newTaskService(
                runtimeDir,
                amiClient,
                new PhoneAgentMvpTestFixtures.RecordingAsrJobQueue()
        );

        TaskCreationException failure = assertThrows(TaskCreationException.class, () -> service.createTask("触发 BLF 失败"));
        assertEquals(TaskStatus.FAILED_BLF_NOTIFY, failure.task().status());
        assertEquals(FailureStage.BLF_NOTIFY, failure.task().failureStage());
        assertNull(failure.task().slot());
        assertEquals("", service.startSlot(1));

        TaskView next = service.createTask("slot 不能泄漏");
        assertEquals(TaskStatus.NOTIFIED, next.status());
        assertEquals(1, next.slot());
    }

    @Test
    void internalStartValidatesSlotReturnsTaskIdAndIsIdempotentUntilRecordingCompletes() {
        TaskService service = PhoneAgentMvpTestFixtures.newTaskService(runtimeDir);
        TaskView task = service.createTask("按 BLF 后播放的任务");

        assertThrows(InvalidSlotException.class, () -> service.startSlot(0));
        assertThrows(InvalidSlotException.class, () -> service.startSlot(9));

        assertEquals(task.taskId(), service.startSlot(1));
        assertEquals(task.taskId(), service.startSlot(1));
        assertEquals(TaskStatus.PICKED_UP, service.getTask(task.taskId()).orElseThrow().status());
        assertEquals("", service.startSlot(2));
    }

    @Test
    void recordingStartTransitionsToRecordingAndRejectsMismatchedTask() {
        TaskService service = PhoneAgentMvpTestFixtures.newTaskService(runtimeDir);
        TaskView task = service.createTask("开始录音状态可查询");
        service.startSlot(1);

        service.startRecording(1, task.taskId());
        service.startRecording(1, task.taskId());

        assertEquals(TaskStatus.RECORDING, service.getTask(task.taskId()).orElseThrow().status());
        assertThrows(TaskConflictException.class, () -> service.startRecording(1, "other-task"));
    }
}
