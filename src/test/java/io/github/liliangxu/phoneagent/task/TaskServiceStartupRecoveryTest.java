package io.github.liliangxu.phoneagent.task;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import io.github.liliangxu.phoneagent.codex.CodexPhoneBridgeService;
import io.github.liliangxu.phoneagent.config.PhoneAgentDatabaseConfig;
import io.github.liliangxu.phoneagent.config.PhoneAgentProperties;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

class TaskServiceStartupRecoveryTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-11T04:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path runtimeDir;

    JdbcTemplate jdbc;
    JdbcTaskStateRepository repository;
    PhoneAgentMvpTestFixtures.SequentialTaskIdGenerator idGenerator;
    CodexPhoneBridgeService bridgeService;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + runtimeDir.getFileName() + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        new PhoneAgentDatabaseConfig().phoneAgentDatabaseInitializer(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        repository = new JdbcTaskStateRepository(jdbc);
        idGenerator = new PhoneAgentMvpTestFixtures.SequentialTaskIdGenerator("task-recover-");
        bridgeService = mock(CodexPhoneBridgeService.class);
    }

    @Test
    void assignedTaskIsRenotifiedAfterRestart() {
        PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient firstAmi =
                new PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient();
        TaskService first = newService(firstAmi, new PhoneAgentMvpTestFixtures.RecordingAsrJobQueue());
        TaskView task = first.createTask("恢复 ASSIGNED 状态");
        jdbc.update("UPDATE phone_tasks SET status = 'ASSIGNED', updated_at = CURRENT_TIMESTAMP WHERE task_id = ?", task.taskId());
        jdbc.update("UPDATE phone_slots SET status = 'RESERVED', current_task_id = ?, updated_at = CURRENT_TIMESTAMP WHERE slot = 1", task.taskId());
        reset(bridgeService);

        PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient recoveredAmi =
                new PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient();
        TaskService recovered = newService(recoveredAmi, new PhoneAgentMvpTestFixtures.RecordingAsrJobQueue());

        recovered.recoverLoadedState();

        TaskView recoveredTask = recovered.getTask(task.taskId()).orElseThrow();
        assertEquals(TaskStatus.NOTIFIED, recoveredTask.status());
        assertEquals(1, recoveredTask.slot());
        assertEquals(1, recoveredAmi.count("INUSE:1"));
        verify(bridgeService).onTaskNotified(task.taskId(), 1);
    }

    @Test
    void recordedTaskIsResubmittedToAsrAfterRestart() throws Exception {
        PhoneAgentMvpTestFixtures.RecordingAsrJobQueue firstAsr =
                new PhoneAgentMvpTestFixtures.RecordingAsrJobQueue();
        TaskService first = newService(new PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient(), firstAsr);
        TaskView task = first.createTask("恢复 RECORDED 状态");
        first.startSlot(1);
        first.startRecording(1, task.taskId());
        Path recording = runtimeDir.resolve("recordings").resolve(task.taskId() + ".wav");
        Files.createDirectories(recording.getParent());
        Files.writeString(recording, "voice");
        first.completeRecording(1, task.taskId());
        jdbc.update("UPDATE phone_tasks SET status = 'RECORDED', updated_at = CURRENT_TIMESTAMP WHERE task_id = ?", task.taskId());
        reset(bridgeService);

        PhoneAgentMvpTestFixtures.RecordingAsrJobQueue recoveredAsr =
                new PhoneAgentMvpTestFixtures.RecordingAsrJobQueue();
        TaskService recovered = newService(new PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient(), recoveredAsr);

        recovered.recoverLoadedState();

        assertEquals(TaskStatus.TRANSCRIBING, recovered.getTask(task.taskId()).orElseThrow().status());
        assertEquals(java.util.List.of(task.taskId()), recoveredAsr.submittedTaskIds);
        verify(bridgeService).onTaskRecorded(task.taskId());
    }

    @Test
    void recordedTaskRecoveryRefillsQueuedTaskAfterRestart() {
        TaskService first = newService(
                new PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient(),
                new PhoneAgentMvpTestFixtures.RecordingAsrJobQueue()
        );
        java.util.List<TaskView> created = new java.util.ArrayList<>();
        for (int i = 0; i < 9; i++) {
            created.add(first.createTask("恢复队列补位 " + i));
        }
        TaskView recorded = created.getFirst();
        TaskView queued = created.get(8);
        jdbc.update("UPDATE phone_tasks SET status = 'RECORDED', updated_at = CURRENT_TIMESTAMP WHERE task_id = ?", recorded.taskId());
        jdbc.update("UPDATE phone_slots SET status = 'IDLE', current_task_id = NULL, started_task_id = NULL, updated_at = CURRENT_TIMESTAMP WHERE slot = 1");
        reset(bridgeService);

        PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient recoveredAmi =
                new PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient();
        PhoneAgentMvpTestFixtures.RecordingAsrJobQueue recoveredAsr =
                new PhoneAgentMvpTestFixtures.RecordingAsrJobQueue();
        TaskService recovered = newService(recoveredAmi, recoveredAsr);

        recovered.recoverLoadedState();

        assertEquals(TaskStatus.TRANSCRIBING, recovered.getTask(recorded.taskId()).orElseThrow().status());
        TaskView refilled = recovered.getTask(queued.taskId()).orElseThrow();
        assertEquals(TaskStatus.NOTIFIED, refilled.status());
        assertEquals(1, refilled.slot());
        assertEquals(java.util.List.of(recorded.taskId()), recoveredAsr.submittedTaskIds);
        assertEquals(1, recoveredAmi.count("NOT_INUSE:1"));
        assertEquals(1, recoveredAmi.count("INUSE:1"));
        verify(bridgeService).onTaskRecorded(recorded.taskId());
        verify(bridgeService).onTaskNotified(queued.taskId(), 1);
    }

    @Test
    void createdTaskIsMarkedTaskCreateFailedAfterRestart() {
        TaskService first = newService(
                new PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient(),
                new PhoneAgentMvpTestFixtures.RecordingAsrJobQueue()
        );
        TaskView task = first.createTask("恢复 CREATED 状态");
        jdbc.update("UPDATE phone_tasks SET status = 'CREATED', task_audio_file = NULL, slot = NULL, updated_at = CURRENT_TIMESTAMP WHERE task_id = ?", task.taskId());
        reset(bridgeService);

        TaskService recovered = newService(
                new PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient(),
                new PhoneAgentMvpTestFixtures.RecordingAsrJobQueue()
        );

        recovered.recoverLoadedState();

        TaskView failed = recovered.getTask(task.taskId()).orElseThrow();
        assertEquals(TaskStatus.FAILED_TASK_CREATE, failed.status());
        assertEquals(FailureStage.INTERNAL, failed.failureStage());
        verify(bridgeService).onTaskFailure(task.taskId(), TaskStatus.FAILED_TASK_CREATE, failed.errorMessage());
    }

    @Test
    void assignedTaskWithHiddenReservedSlotIsPublishedAfterRestart() {
        TaskService first = newService(
                new PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient(),
                new PhoneAgentMvpTestFixtures.RecordingAsrJobQueue()
        );
        TaskView task = first.createTask("恢复隐藏 RESERVED 状态");
        jdbc.update("UPDATE phone_tasks SET status = 'ASSIGNED', slot = 1, updated_at = CURRENT_TIMESTAMP WHERE task_id = ?", task.taskId());
        jdbc.update("UPDATE phone_slots SET status = 'RESERVED', current_task_id = NULL, started_task_id = NULL, updated_at = CURRENT_TIMESTAMP WHERE slot = 1");
        reset(bridgeService);

        PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient recoveredAmi =
                new PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient();
        TaskService recovered = newService(recoveredAmi, new PhoneAgentMvpTestFixtures.RecordingAsrJobQueue());

        recovered.recoverLoadedState();

        TaskView recoveredTask = recovered.getTask(task.taskId()).orElseThrow();
        assertEquals(TaskStatus.NOTIFIED, recoveredTask.status());
        assertEquals(1, recoveredTask.slot());
        assertEquals("恢复隐藏 RESERVED 状态",
                assertDoesNotThrow(() -> Files.readString(runtimeDir.resolve("sounds").resolve("slots").resolve("slot-1.wav"))));
        assertEquals(task.taskId(), recovered.startSlot(1));
        assertEquals(1, recoveredAmi.count("INUSE:1"));
        verify(bridgeService).onTaskNotified(task.taskId(), 1);
    }

    @Test
    void restartedTaskIdGeneratorSkipsAlreadyLoadedTaskIds() {
        TaskService first = newService(new PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient(),
                new PhoneAgentMvpTestFixtures.RecordingAsrJobQueue());
        TaskView existing = first.createTask("历史任务不能被覆盖");
        idGenerator = new PhoneAgentMvpTestFixtures.SequentialTaskIdGenerator("task-recover-");

        TaskService recovered = newService(new PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient(),
                new PhoneAgentMvpTestFixtures.RecordingAsrJobQueue());
        TaskView created = recovered.createTask("重启后的新任务");

        assertEquals("task-recover-000001", existing.taskId());
        assertEquals("task-recover-000002", created.taskId());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM phone_tasks", Integer.class));
        assertEquals("历史任务不能被覆盖", jdbc.queryForObject(
                "SELECT text FROM phone_tasks WHERE task_id = ?",
                String.class,
                existing.taskId()));
    }

    @Test
    void customFourSlotRuntimeIgnoresHistoricalDatabaseSlotsWithoutRemappingTasks() {
        TaskService first = newService(new PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient(),
                new PhoneAgentMvpTestFixtures.RecordingAsrJobQueue());
        java.util.List<TaskView> existing = new java.util.ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            existing.add(first.createTask("历史 slot 任务 " + i));
        }
        PhoneAgentProperties fourSlotProperties = new PhoneAgentProperties();
        fourSlotProperties.getBlf().setExtensions(java.util.List.of("801", "802", "803", "804"));

        TaskService recovered = newService(
                new PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient(),
                new PhoneAgentMvpTestFixtures.RecordingAsrJobQueue(),
                fourSlotProperties
        );
        TaskView historicalSlotFiveTask = recovered.getTask(existing.get(4).taskId()).orElseThrow();
        TaskView newlyCreated = recovered.createTask("新配置下不应使用历史 slot 5");

        assertEquals(5, historicalSlotFiveTask.slot());
        assertThrows(InvalidSlotException.class, () -> recovered.startSlot(5));
        assertEquals(TaskStatus.QUEUED, newlyCreated.status());
        assertEquals(8, jdbc.queryForObject("SELECT COUNT(*) FROM phone_slots", Integer.class));
    }

    @Test
    void configuredSlotsAreInitializedEvenWhenRepositoryHasNoHistoricalSlotRows() {
        jdbc.update("DELETE FROM phone_slots");
        PhoneAgentProperties fourSlotProperties = new PhoneAgentProperties();
        fourSlotProperties.getBlf().setExtensions(java.util.List.of("801", "802", "803", "804"));
        TaskService recovered = newService(
                new PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient(),
                new PhoneAgentMvpTestFixtures.RecordingAsrJobQueue(),
                fourSlotProperties
        );

        for (int i = 1; i <= 4; i++) {
            TaskView assigned = recovered.createTask("缺失历史 slot 行 " + i);
            assertEquals(TaskStatus.NOTIFIED, assigned.status());
            assertEquals(i, assigned.slot());
        }

        TaskView queued = recovered.createTask("第五条应排队");
        assertEquals(TaskStatus.QUEUED, queued.status());
        assertThrows(InvalidSlotException.class, () -> recovered.startSlot(5));
    }

    @Test
    void assignedTaskOnNullOrInactiveConfiguredSlotIsNotRecoveredAfterRestart() {
        TaskService first = newService(
                new PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient(),
                new PhoneAgentMvpTestFixtures.RecordingAsrJobQueue()
        );
        TaskView nullSlotTask = first.createTask("ASSIGNED 但 slot 缺失");
        TaskView inactiveSlotTask = null;
        for (int i = 0; i < 4; i++) {
            inactiveSlotTask = first.createTask("历史 slot 任务 " + i);
        }
        jdbc.update("UPDATE phone_tasks SET status = 'ASSIGNED', slot = NULL, updated_at = CURRENT_TIMESTAMP WHERE task_id = ?", nullSlotTask.taskId());
        jdbc.update("UPDATE phone_tasks SET status = 'ASSIGNED', slot = 5, updated_at = CURRENT_TIMESTAMP WHERE task_id = ?", inactiveSlotTask.taskId());
        jdbc.update("UPDATE phone_slots SET status = 'RESERVED', current_task_id = ?, updated_at = CURRENT_TIMESTAMP WHERE slot = 5", inactiveSlotTask.taskId());
        PhoneAgentProperties fourSlotProperties = new PhoneAgentProperties();
        fourSlotProperties.getBlf().setExtensions(java.util.List.of("801", "802", "803", "804"));

        PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient recoveredAmi =
                new PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient();
        TaskService recovered = newService(
                recoveredAmi,
                new PhoneAgentMvpTestFixtures.RecordingAsrJobQueue(),
                fourSlotProperties
        );

        recovered.recoverLoadedState();

        assertEquals(TaskStatus.ASSIGNED, recovered.getTask(nullSlotTask.taskId()).orElseThrow().status());
        assertEquals(TaskStatus.ASSIGNED, recovered.getTask(inactiveSlotTask.taskId()).orElseThrow().status());
        assertEquals(0, recoveredAmi.count("INUSE:5"));
    }

    @Test
    void failedBlfRecoverySkipsTasksWithoutAudioOrWithInactiveConfiguredSlot() {
        TaskService first = newService(
                new PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient(),
                new PhoneAgentMvpTestFixtures.RecordingAsrJobQueue()
        );
        TaskView missingAudio = first.createTask("FAILED_BLF_NOTIFY 但没有音频");
        TaskView inactiveSlot = null;
        for (int i = 0; i < 4; i++) {
            inactiveSlot = first.createTask("FAILED_BLF_NOTIFY 历史 slot " + i);
        }
        jdbc.update("""
                        UPDATE phone_tasks
                        SET status = 'FAILED_BLF_NOTIFY', failure_stage = 'BLF_NOTIFY', error_message = 'startup skip',
                            task_audio_file = NULL, slot = NULL, updated_at = CURRENT_TIMESTAMP
                        WHERE task_id = ?
                        """,
                missingAudio.taskId());
        jdbc.update("""
                        UPDATE phone_tasks
                        SET status = 'FAILED_BLF_NOTIFY', failure_stage = 'BLF_NOTIFY', error_message = 'startup skip',
                            slot = 5, updated_at = CURRENT_TIMESTAMP
                        WHERE task_id = ?
                        """,
                inactiveSlot.taskId());
        PhoneAgentProperties fourSlotProperties = new PhoneAgentProperties();
        fourSlotProperties.getBlf().setExtensions(java.util.List.of("801", "802", "803", "804"));

        PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient recoveredAmi =
                new PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient();
        TaskService recovered = newService(
                recoveredAmi,
                new PhoneAgentMvpTestFixtures.RecordingAsrJobQueue(),
                fourSlotProperties
        );

        recovered.recoverLoadedState();

        assertEquals(TaskStatus.FAILED_BLF_NOTIFY, recovered.getTask(missingAudio.taskId()).orElseThrow().status());
        assertEquals(TaskStatus.FAILED_BLF_NOTIFY, recovered.getTask(inactiveSlot.taskId()).orElseThrow().status());
        assertEquals(0, recoveredAmi.count("INUSE:5"));
    }

    @Test
    void failedBlfRecoveryRetriesTaskWithAudioAndNoPersistedSlot() {
        TaskService first = newService(
                new PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient(),
                new PhoneAgentMvpTestFixtures.RecordingAsrJobQueue()
        );
        TaskView failed = first.createTask("FAILED_BLF_NOTIFY 可恢复");
        jdbc.update("""
                        UPDATE phone_tasks
                        SET status = 'FAILED_BLF_NOTIFY', failure_stage = 'BLF_NOTIFY', error_message = 'startup retry',
                            slot = NULL, updated_at = CURRENT_TIMESTAMP
                        WHERE task_id = ?
                        """,
                failed.taskId());

        PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient recoveredAmi =
                new PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient();
        TaskService recovered = newService(recoveredAmi, new PhoneAgentMvpTestFixtures.RecordingAsrJobQueue());

        recovered.recoverLoadedState();

        TaskView retried = recovered.getTask(failed.taskId()).orElseThrow();
        assertEquals(TaskStatus.NOTIFIED, retried.status());
        assertEquals(2, retried.slot());
        assertEquals(1, recoveredAmi.count("INUSE:2"));
    }

    private TaskService newService(
            PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient ami,
            PhoneAgentMvpTestFixtures.RecordingAsrJobQueue asr
    ) {
        return newService(ami, asr, new PhoneAgentProperties());
    }

    private TaskService newService(
            PhoneAgentMvpTestFixtures.RecordingAsteriskAmiClient ami,
            PhoneAgentMvpTestFixtures.RecordingAsrJobQueue asr,
            PhoneAgentProperties properties
    ) {
        return new TaskService(
                idGenerator,
                new PhoneAgentMvpTestFixtures.StubSpeechSynthesisService(runtimeDir.resolve("generated")),
                new SlotAudioStore(runtimeDir.resolve("sounds").resolve("slots")),
                new RecordingStore(runtimeDir.resolve("recordings")),
                ami,
                asr,
                repository,
                bridgeService,
                properties,
                CLOCK
        );
    }
}
