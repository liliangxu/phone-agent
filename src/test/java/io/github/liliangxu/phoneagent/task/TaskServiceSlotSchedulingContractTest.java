package io.github.liliangxu.phoneagent.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskServiceSlotSchedulingContractTest {
    @TempDir
    Path runtimeDir;

    @Test
    void fillsEightSlotsInOrderAndQueuesNinthWithoutOverwritingActiveSlots() {
        TaskService service = PhoneAgentMvpTestFixtures.newTaskService(runtimeDir);
        List<TaskView> created = createTasks(service, 9);

        for (int slot = 1; slot <= 8; slot++) {
            TaskView task = created.get(slot - 1);
            assertEquals(TaskStatus.NOTIFIED, task.status());
            assertEquals(slot, task.slot());
            assertEquals(task.taskId(), service.startSlot(slot));
        }

        TaskView ninth = service.getTask(created.get(8).taskId()).orElseThrow();
        assertEquals(TaskStatus.QUEUED, ninth.status());
        assertNull(ninth.slot());
    }

    @Test
    void nonEmptyRecordingReleasesSlotKeepsHistoricalSlotAndRefillsFromQueue() throws IOException {
        PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient amiClient =
                new PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient();
        PhoneAgentMvpTestFixtures.RecordingAsrJobQueue asrJobQueue =
                new PhoneAgentMvpTestFixtures.RecordingAsrJobQueue();
        TaskService service = PhoneAgentMvpTestFixtures.newTaskService(runtimeDir, amiClient, asrJobQueue);
        List<TaskView> created = createTasks(service, 9);
        TaskView first = created.get(0);
        TaskView ninth = created.get(8);

        service.startSlot(1);
        service.startRecording(1, first.taskId());
        writeRecording(first.taskId(), "voice");

        RecordingCallbackResult result = service.completeRecording(1, first.taskId());

        assertEquals(RecordingCallbackResult.PROCESSED, result);
        TaskView completed = service.getTask(first.taskId()).orElseThrow();
        assertEquals(TaskStatus.TRANSCRIBING, completed.status());
        assertEquals(1, completed.slot());
        assertEquals("runtime/recordings/" + first.taskId() + ".wav", completed.recordingFile());
        assertEquals(List.of(first.taskId()), asrJobQueue.submittedTaskIds);

        TaskView refilled = service.getTask(ninth.taskId()).orElseThrow();
        assertEquals(TaskStatus.NOTIFIED, refilled.status());
        assertEquals(1, refilled.slot());
        assertEquals(ninth.taskId(), service.startSlot(1));
        assertEquals(1, amiClient.count("NOT_INUSE:1"));
        assertEquals(2, amiClient.count("INUSE:1"));
    }

    @Test
    void missingOrEmptyRecordingStillReleasesSlotAndRefillsButMarksTaskRecordingFailed() {
        PhoneAgentMvpTestFixtures.RecordingAsrJobQueue asrJobQueue =
                new PhoneAgentMvpTestFixtures.RecordingAsrJobQueue();
        TaskService service = PhoneAgentMvpTestFixtures.newTaskService(
                runtimeDir,
                new PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient(),
                asrJobQueue
        );
        List<TaskView> created = createTasks(service, 9);
        TaskView first = created.get(0);
        TaskView ninth = created.get(8);

        service.startSlot(1);
        service.startRecording(1, first.taskId());
        RecordingCallbackResult result = service.completeRecording(1, first.taskId());

        assertEquals(RecordingCallbackResult.PROCESSED, result);
        TaskView failed = service.getTask(first.taskId()).orElseThrow();
        assertEquals(TaskStatus.FAILED_RECORDING, failed.status());
        assertEquals(FailureStage.RECORDING, failed.failureStage());
        assertTrue(failed.errorMessage().contains("recording"));
        assertTrue(asrJobQueue.submittedTaskIds.isEmpty());

        TaskView refilled = service.getTask(ninth.taskId()).orElseThrow();
        assertEquals(TaskStatus.NOTIFIED, refilled.status());
        assertEquals(1, refilled.slot());
    }

    @Test
    void pickedUpHangupBeforeRecordingStartMarksNoReplyAndRefillsWithoutAsr() {
        PhoneAgentMvpTestFixtures.RecordingAsrJobQueue asrJobQueue =
                new PhoneAgentMvpTestFixtures.RecordingAsrJobQueue();
        TaskService service = PhoneAgentMvpTestFixtures.newTaskService(
                runtimeDir,
                new PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient(),
                asrJobQueue
        );
        List<TaskView> created = createTasks(service, 9);
        TaskView first = created.get(0);
        TaskView ninth = created.get(8);

        service.startSlot(1);
        RecordingCallbackResult result = service.completeRecording(1, first.taskId());

        assertEquals(RecordingCallbackResult.PROCESSED, result);
        TaskView noReply = service.getTask(first.taskId()).orElseThrow();
        assertEquals(TaskStatus.NO_REPLY, noReply.status());
        assertNull(noReply.failureStage());
        assertNull(noReply.errorMessage());
        assertTrue(asrJobQueue.submittedTaskIds.isEmpty());

        TaskView refilled = service.getTask(ninth.taskId()).orElseThrow();
        assertEquals(TaskStatus.NOTIFIED, refilled.status());
        assertEquals(1, refilled.slot());
        assertEquals(ninth.taskId(), service.startSlot(1));
    }

    @Test
    void duplicateRecordingCallbacksAreIdempotentAfterSlotHasBeenRefilled() throws IOException {
        PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient amiClient =
                new PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient();
        PhoneAgentMvpTestFixtures.RecordingAsrJobQueue asrJobQueue =
                new PhoneAgentMvpTestFixtures.RecordingAsrJobQueue();
        TaskService service = PhoneAgentMvpTestFixtures.newTaskService(runtimeDir, amiClient, asrJobQueue);
        List<TaskView> created = createTasks(service, 10);
        TaskView first = created.get(0);
        TaskView ninth = created.get(8);
        TaskView tenth = created.get(9);

        service.startSlot(1);
        service.startRecording(1, first.taskId());
        writeRecording(first.taskId(), "voice");

        assertEquals(RecordingCallbackResult.PROCESSED, service.completeRecording(1, first.taskId()));
        assertEquals(RecordingCallbackResult.DUPLICATE, service.completeRecording(1, first.taskId()));

        assertEquals(TaskStatus.NOTIFIED, service.getTask(ninth.taskId()).orElseThrow().status());
        assertEquals(1, service.getTask(ninth.taskId()).orElseThrow().slot());
        assertEquals(TaskStatus.QUEUED, service.getTask(tenth.taskId()).orElseThrow().status());
        assertNull(service.getTask(tenth.taskId()).orElseThrow().slot());
        assertEquals(1, amiClient.count("NOT_INUSE:1"));
        assertEquals(2, amiClient.count("INUSE:1"));
        assertEquals(List.of(first.taskId()), asrJobQueue.submittedTaskIds);
    }

    @Test
    void refillBlfFailureMarksRefilledTaskFailedAndReleasesSlot() throws IOException {
        PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient amiClient =
                new PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient();
        TaskService service = PhoneAgentMvpTestFixtures.newTaskService(
                runtimeDir,
                amiClient,
                new PhoneAgentMvpTestFixtures.RecordingAsrJobQueue()
        );
        List<TaskView> created = createTasks(service, 9);
        TaskView first = created.get(0);
        TaskView ninth = created.get(8);

        service.startSlot(1);
        service.startRecording(1, first.taskId());
        writeRecording(first.taskId(), "voice");
        amiClient.failNextInUse = true;

        assertEquals(RecordingCallbackResult.PROCESSED, service.completeRecording(1, first.taskId()));

        TaskView failedRefill = service.getTask(ninth.taskId()).orElseThrow();
        assertEquals(TaskStatus.FAILED_BLF_NOTIFY, failedRefill.status());
        assertEquals(FailureStage.BLF_NOTIFY, failedRefill.failureStage());

        TaskView next = service.createTask("释放后的 slot 可继续使用");
        assertEquals(TaskStatus.NOTIFIED, next.status());
        assertEquals(1, next.slot());
    }

    @Test
    void cancellingNotifiedTaskReleasesBlfAndRefillsQueue() {
        PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient amiClient =
                new PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient();
        TaskService service = PhoneAgentMvpTestFixtures.newTaskService(
                runtimeDir,
                amiClient,
                new PhoneAgentMvpTestFixtures.RecordingAsrJobQueue()
        );
        List<TaskView> created = createTasks(service, 9);
        TaskView first = created.get(0);
        TaskView ninth = created.get(8);

        service.cancelTask(first.taskId());

        assertEquals(TaskStatus.CANCELLED, service.getTask(first.taskId()).orElseThrow().status());
        TaskView refilled = service.getTask(ninth.taskId()).orElseThrow();
        assertEquals(TaskStatus.NOTIFIED, refilled.status());
        assertEquals(1, refilled.slot());
        assertEquals(List.of("INUSE:1", "INUSE:2", "INUSE:3", "INUSE:4", "INUSE:5", "INUSE:6", "INUSE:7", "INUSE:8",
                "NOT_INUSE:1", "INUSE:1"), amiClient.commands);
    }

    @Test
    void concurrentDuplicateRecordingCallbacksOnlyReleaseAndRefillOnce() throws Exception {
        PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient amiClient =
                new PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient();
        PhoneAgentMvpTestFixtures.RecordingAsrJobQueue asrJobQueue =
                new PhoneAgentMvpTestFixtures.RecordingAsrJobQueue();
        TaskService service = PhoneAgentMvpTestFixtures.newTaskService(runtimeDir, amiClient, asrJobQueue);
        List<TaskView> created = createTasks(service, 10);
        TaskView first = created.get(0);
        TaskView ninth = created.get(8);
        TaskView tenth = created.get(9);

        service.startSlot(1);
        service.startRecording(1, first.taskId());
        writeRecording(first.taskId(), "voice");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<RecordingCallbackResult> one = executor.submit(() -> completeWhenReleased(service, ready, start, first));
            Future<RecordingCallbackResult> two = executor.submit(() -> completeWhenReleased(service, ready, start, first));
            ready.await();
            start.countDown();

            List<RecordingCallbackResult> results = List.of(one.get(), two.get());

            assertTrue(results.contains(RecordingCallbackResult.PROCESSED));
            assertTrue(results.contains(RecordingCallbackResult.DUPLICATE));
            assertEquals(TaskStatus.NOTIFIED, service.getTask(ninth.taskId()).orElseThrow().status());
            assertEquals(1, service.getTask(ninth.taskId()).orElseThrow().slot());
            assertEquals(TaskStatus.QUEUED, service.getTask(tenth.taskId()).orElseThrow().status());
            assertNull(service.getTask(tenth.taskId()).orElseThrow().slot());
            assertEquals(1, amiClient.count("NOT_INUSE:1"));
            assertEquals(2, amiClient.count("INUSE:1"));
            assertEquals(List.of(first.taskId()), asrJobQueue.submittedTaskIds);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void createPathHidesTaskIdUntilAmiInUseCompletes() throws Exception {
        PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient amiClient =
                new PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient();
        amiClient.blockInUseCommand(1);
        TaskService service = PhoneAgentMvpTestFixtures.newTaskService(
                runtimeDir,
                amiClient,
                new PhoneAgentMvpTestFixtures.RecordingAsrJobQueue()
        );
        String taskId = "task-20260609-000001";

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<TaskView> create = executor.submit(() -> service.createTask("AMI 确认前不应被 Asterisk 查询"));
            amiClient.awaitBlockedInUse();

            assertEquals(TaskStatus.ASSIGNED, service.getTask(taskId).orElseThrow().status());
            assertEquals("", service.startSlot(1));

            amiClient.unblockInUse();
            create.get();
            assertEquals(taskId, service.startSlot(1));
            assertEquals(TaskStatus.PICKED_UP, service.getTask(taskId).orElseThrow().status());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void createPathRollsBackHiddenAssignmentWhenAmiInUseFails() {
        PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient amiClient =
                new PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient();
        amiClient.failNextInUse = true;
        TaskService service = PhoneAgentMvpTestFixtures.newTaskService(
                runtimeDir,
                amiClient,
                new PhoneAgentMvpTestFixtures.RecordingAsrJobQueue()
        );

        TaskCreationException failure = assertThrows(
                TaskCreationException.class,
                () -> service.createTask("AMI 置红失败后必须回滚 slot")
        );

        assertEquals(TaskStatus.FAILED_BLF_NOTIFY, failure.task().status());
        assertEquals(FailureStage.BLF_NOTIFY, failure.task().failureStage());
        assertNull(failure.task().slot());
        assertEquals("", service.startSlot(1));
        assertEquals(List.of("INUSE:1", "NOT_INUSE:1"), amiClient.commands);
    }

    @Test
    void refillReservedSlotStaysHiddenUntilAudioIsPublishedButTaskIsRecoverable() throws Exception {
        PhoneAgentMvpTestFixtures.BlockingSlotAudioStore slotAudioStore =
                new PhoneAgentMvpTestFixtures.BlockingSlotAudioStore(runtimeDir.resolve("sounds").resolve("slots"));
        PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient amiClient =
                new PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient();
        TaskService service = PhoneAgentMvpTestFixtures.newTaskService(
                runtimeDir,
                slotAudioStore,
                amiClient,
                new PhoneAgentMvpTestFixtures.RecordingAsrJobQueue()
        );
        List<TaskView> created = createTasks(service, 9);
        TaskView first = created.get(0);

        service.startSlot(1);
        service.startRecording(1, first.taskId());
        writeRecording(first.taskId(), "voice");
        slotAudioStore.blockNextPublish();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<RecordingCallbackResult> complete = executor.submit(() -> service.completeRecording(1, first.taskId()));
            slotAudioStore.awaitBlockedPublish();

            TaskView ninth = created.get(8);
            assertEquals("", service.startSlot(1));
            assertEquals(1, service.getTask(ninth.taskId()).orElseThrow().slot());
            assertEquals(TaskStatus.ASSIGNED, service.getTask(ninth.taskId()).orElseThrow().status());

            slotAudioStore.unblockPublish();
            assertEquals(RecordingCallbackResult.PROCESSED, complete.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cancellingRefillTaskWhilePublishIsBlockedPreventsLateSlotRebind() throws Exception {
        PhoneAgentMvpTestFixtures.BlockingSlotAudioStore slotAudioStore =
                new PhoneAgentMvpTestFixtures.BlockingSlotAudioStore(runtimeDir.resolve("sounds").resolve("slots"));
        PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient amiClient =
                new PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient();
        TaskService service = PhoneAgentMvpTestFixtures.newTaskService(
                runtimeDir,
                slotAudioStore,
                amiClient,
                new PhoneAgentMvpTestFixtures.RecordingAsrJobQueue()
        );
        List<TaskView> created = createTasks(service, 9);
        TaskView first = created.get(0);
        TaskView ninth = created.get(8);

        service.startSlot(1);
        service.startRecording(1, first.taskId());
        writeRecording(first.taskId(), "voice");
        slotAudioStore.blockNextPublish();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<RecordingCallbackResult> complete = executor.submit(() -> service.completeRecording(1, first.taskId()));
            slotAudioStore.awaitBlockedPublish();

            service.cancelTask(ninth.taskId());
            slotAudioStore.unblockPublish();

            assertEquals(RecordingCallbackResult.PROCESSED, complete.get());
            assertEquals(TaskStatus.CANCELLED, service.getTask(ninth.taskId()).orElseThrow().status());
            assertEquals("", service.startSlot(1));
            assertTrue(amiClient.count("NOT_INUSE:1") >= 2);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void refillHiddenAssignmentReturnsNewQueuedTaskIdOnlyAfterAmiInUseCompletes() throws Exception {
        PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient amiClient =
                new PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient();
        TaskService service = PhoneAgentMvpTestFixtures.newTaskService(
                runtimeDir,
                amiClient,
                new PhoneAgentMvpTestFixtures.RecordingAsrJobQueue()
        );
        List<TaskView> created = createTasks(service, 9);
        TaskView first = created.get(0);
        TaskView ninth = created.get(8);

        service.startSlot(1);
        service.startRecording(1, first.taskId());
        writeRecording(first.taskId(), "voice");
        amiClient.blockInUseCommand(9);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<RecordingCallbackResult> complete = executor.submit(() -> service.completeRecording(1, first.taskId()));
            amiClient.awaitBlockedInUse();

            assertEquals(TaskStatus.ASSIGNED, service.getTask(ninth.taskId()).orElseThrow().status());
            assertEquals("", service.startSlot(1));

            amiClient.unblockInUse();
            assertEquals(RecordingCallbackResult.PROCESSED, complete.get());
            assertEquals(ninth.taskId(), service.startSlot(1));
            assertFalse(first.taskId().equals(service.startSlot(1)));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cancellingRefillTaskDuringAmiInUsePreventsLateBlfState() throws Exception {
        PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient amiClient =
                new PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient();
        TaskService service = PhoneAgentMvpTestFixtures.newTaskService(
                runtimeDir,
                amiClient,
                new PhoneAgentMvpTestFixtures.RecordingAsrJobQueue()
        );
        List<TaskView> created = createTasks(service, 9);
        TaskView first = created.get(0);
        TaskView ninth = created.get(8);

        service.startSlot(1);
        service.startRecording(1, first.taskId());
        writeRecording(first.taskId(), "voice");
        amiClient.blockInUseCommand(9);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<RecordingCallbackResult> complete = executor.submit(() -> service.completeRecording(1, first.taskId()));
            amiClient.awaitBlockedInUse();

            service.cancelTask(ninth.taskId());
            amiClient.unblockInUse();

            assertEquals(RecordingCallbackResult.PROCESSED, complete.get());
            assertEquals(TaskStatus.CANCELLED, service.getTask(ninth.taskId()).orElseThrow().status());
            assertEquals("", service.startSlot(1));
            assertTrue(amiClient.count("NOT_INUSE:1") >= 2);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void finalRecordingCompletionDoesNotRequireRecordingStartCallback() throws IOException {
        TaskService service = PhoneAgentMvpTestFixtures.newTaskService(runtimeDir);
        TaskView task = service.createTask("录音开始通知失败也不能阻断最终完成");

        assertEquals(task.taskId(), service.startSlot(1));
        writeRecording(task.taskId(), "voice");

        assertEquals(RecordingCallbackResult.PROCESSED, service.completeRecording(1, task.taskId()));
        assertEquals(TaskStatus.TRANSCRIBING, service.getTask(task.taskId()).orElseThrow().status());
    }

    @Test
    void cancelledNotifiedTaskIgnoresLatePickupCallback() {
        TaskService service = PhoneAgentMvpTestFixtures.newTaskService(runtimeDir);
        TaskView task = service.createTask("取消后迟到接听不能改状态");

        service.cancelTask(task.taskId());

        assertEquals("", service.startSlot(1));
        TaskView cancelled = service.getTask(task.taskId()).orElseThrow();
        assertEquals(TaskStatus.CANCELLED, cancelled.status());
        assertEquals(1, cancelled.slot());
    }

    @Test
    void cancelledPickedUpTaskIgnoresLateRecordingCompletion() {
        PhoneAgentMvpTestFixtures.RecordingAsrJobQueue asrJobQueue =
                new PhoneAgentMvpTestFixtures.RecordingAsrJobQueue();
        TaskService service = PhoneAgentMvpTestFixtures.newTaskService(
                runtimeDir,
                new PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient(),
                asrJobQueue
        );
        TaskView task = service.createTask("取消后迟到挂机回调不能改状态");
        service.startSlot(1);

        service.cancelTask(task.taskId());

        assertThrows(TaskConflictException.class, () -> service.completeRecording(1, task.taskId()));
        TaskView cancelled = service.getTask(task.taskId()).orElseThrow();
        assertEquals(TaskStatus.CANCELLED, cancelled.status());
        assertNull(cancelled.failureStage());
        assertTrue(asrJobQueue.submittedTaskIds.isEmpty());
        assertEquals("", service.startSlot(1));
    }

    @Test
    void cancelledPickedUpTaskIgnoresLateRecordingStart() {
        TaskService service = PhoneAgentMvpTestFixtures.newTaskService(runtimeDir);
        TaskView task = service.createTask("取消后迟到录音开始不能改状态");
        service.startSlot(1);

        service.cancelTask(task.taskId());

        assertThrows(TaskConflictException.class, () -> service.startRecording(1, task.taskId()));
        assertEquals(TaskStatus.CANCELLED, service.getTask(task.taskId()).orElseThrow().status());
        assertEquals("", service.startSlot(1));
    }

    @Test
    void mismatchedRecordingCallbackDoesNotReleaseCurrentSlot() {
        TaskService service = PhoneAgentMvpTestFixtures.newTaskService(runtimeDir);
        TaskView task = service.createTask("不能错误释放的任务");
        service.startSlot(1);
        service.startRecording(1, task.taskId());

        assertThrows(TaskConflictException.class, () -> service.completeRecording(1, "other-task"));

        TaskView unchanged = service.getTask(task.taskId()).orElseThrow();
        assertEquals(TaskStatus.RECORDING, unchanged.status());
        assertEquals(1, unchanged.slot());
        assertEquals(task.taskId(), service.startSlot(1));
    }

    @Test
    void asrCompletionTransitionsFromTranscribingToDoneOrFailed() throws IOException {
        TaskService service = PhoneAgentMvpTestFixtures.newTaskService(runtimeDir);
        TaskView task = service.createTask("ASR 状态转换");
        service.startSlot(1);
        service.startRecording(1, task.taskId());
        writeRecording(task.taskId(), "voice");
        service.completeRecording(1, task.taskId());

        assertEquals(TaskStatus.TRANSCRIBING, service.getTask(task.taskId()).orElseThrow().status());

        service.completeAsrSuccess(task.taskId(), "收到");
        TaskView done = service.getTask(task.taskId()).orElseThrow();
        assertEquals(TaskStatus.ASR_DONE, done.status());
        assertEquals("收到", done.replyText());

        TaskView noReplyTask = service.createTask("ASR 无回复状态转换");
        service.startSlot(1);
        service.startRecording(1, noReplyTask.taskId());
        writeRecording(noReplyTask.taskId(), "voice");
        service.completeRecording(1, noReplyTask.taskId());
        service.completeAsrSuccess(noReplyTask.taskId(), "   ");

        TaskView noReply = service.getTask(noReplyTask.taskId()).orElseThrow();
        assertEquals(TaskStatus.NO_REPLY, noReply.status());
        assertNull(noReply.replyText());

        TaskView failedTask = service.createTask("ASR 失败状态转换");
        service.startSlot(1);
        service.startRecording(1, failedTask.taskId());
        writeRecording(failedTask.taskId(), "voice");
        service.completeRecording(1, failedTask.taskId());
        service.completeAsrFailure(failedTask.taskId(), "empty output");

        TaskView failed = service.getTask(failedTask.taskId()).orElseThrow();
        assertEquals(TaskStatus.FAILED_ASR, failed.status());
        assertEquals(FailureStage.ASR, failed.failureStage());
    }

    @Test
    void lateAsrCompletionDoesNotReviveCancelledTask() throws IOException {
        TaskService service = PhoneAgentMvpTestFixtures.newTaskService(runtimeDir);
        TaskView task = service.createTask("迟到 ASR 不应复活");
        service.startSlot(1);
        service.startRecording(1, task.taskId());
        writeRecording(task.taskId(), "voice");
        service.completeRecording(1, task.taskId());
        service.cancelTask(task.taskId());

        service.completeAsrSuccess(task.taskId(), "迟到回复");
        service.completeAsrFailure(task.taskId(), "迟到失败");

        TaskView cancelled = service.getTask(task.taskId()).orElseThrow();
        assertEquals(TaskStatus.CANCELLED, cancelled.status());
        assertNull(cancelled.replyText());
    }

    private static List<TaskView> createTasks(TaskService service, int count) {
        List<TaskView> tasks = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            tasks.add(service.createTask("通知 " + i));
        }
        return tasks;
    }

    private static RecordingCallbackResult completeWhenReleased(
            TaskService service,
            CountDownLatch ready,
            CountDownLatch start,
            TaskView task
    ) throws Exception {
        ready.countDown();
        start.await();
        return service.completeRecording(1, task.taskId());
    }

    private void writeRecording(String taskId, String body) throws IOException {
        Path recording = runtimeDir.resolve("recordings").resolve(taskId + ".wav");
        Files.createDirectories(recording.getParent());
        Files.writeString(recording, body);
    }
}
