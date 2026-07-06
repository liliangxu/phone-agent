package io.github.liliangxu.phoneagent.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import io.github.liliangxu.phoneagent.config.PhoneAgentDatabaseConfig;
import io.github.liliangxu.phoneagent.config.PhoneAgentProperties;
import io.github.liliangxu.phoneagent.task.AsteriskAmiClient;
import io.github.liliangxu.phoneagent.task.AsrJobQueue;
import io.github.liliangxu.phoneagent.task.RecordingStore;
import io.github.liliangxu.phoneagent.task.SlotAudioStore;
import io.github.liliangxu.phoneagent.task.SpeechSynthesisService;
import io.github.liliangxu.phoneagent.task.SystemClockTaskIdGenerator;
import io.github.liliangxu.phoneagent.task.TaskService;
import io.github.liliangxu.phoneagent.task.TaskStatus;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexPhoneBridgeServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-11T04:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    JdbcTemplate jdbc;
    CodexSessionStore sessionStore;
    CodexPhoneBridgeService bridgeService;
    TaskService taskService;
    RecordingAmi ami;
    NoopGateway gateway;
    boolean failNextTts;
    boolean blockNextTts;
    CountDownLatch ttsBlocked;
    CountDownLatch releaseTts;
    CountDownLatch amiBlocked;
    CountDownLatch releaseAmi;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + tempDir.getFileName() + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        new PhoneAgentDatabaseConfig().phoneAgentDatabaseInitializer(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        JdbcCodexSessionRepository sessionRepository = new JdbcCodexSessionRepository(jdbc);
        sessionStore = new CodexSessionStore(tempDir.resolve("registry"), new ObjectMapper(), sessionRepository);
        PhoneAgentProperties properties = new PhoneAgentProperties();
        properties.setRuntimeDir(tempDir.resolve("runtime"));
        ami = new RecordingAmi();
        taskService = new TaskService(
                new SystemClockTaskIdGenerator(CLOCK),
                tts(),
                new SlotAudioStore(tempDir.resolve("runtime").resolve("sounds").resolve("slots")),
                new RecordingStore(tempDir.resolve("runtime").resolve("recordings")),
                ami,
                taskId -> {},
                new io.github.liliangxu.phoneagent.task.JdbcTaskStateRepository(jdbc),
                null,
                properties,
                CLOCK
        );
        gateway = new NoopGateway();
        bridgeService = new CodexPhoneBridgeService(
                new JdbcCodexPhoneBridgeRepository(jdbc),
                sessionStore,
                taskService,
                gateway,
                new CodexPromptFormatter(),
                properties,
                CLOCK,
                new DataSourceTransactionManager(dataSource)
        );
        injectBridgeService(taskService, bridgeService);
    }

    @Test
    void waitingUserCreatesOneBridgeAndOnePhoneTask() {
        CodexSessionRecord session = waitingSession("s1");
        sessionStore.put(session);

        bridgeService.reconcileWaitingSession(session);
        bridgeService.reconcileWaitingSession(session);

        List<BridgeSummary> bridges = bridgeService.latestSummaries("s1");
        assertEquals(1, bridges.size());
        assertEquals(BridgeStatus.NOTIFIED, bridges.getFirst().status());
        assertEquals(BridgePhase.IN_PROGRESS, bridges.getFirst().phase());
        assertNotNull(bridges.getFirst().taskId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM phone_tasks", Integer.class));
    }

    @Test
    void missingThreadStoresSessionLevelErrorWithoutBridge() {
        CodexSessionRecord session = waitingSession("s2");
        session.setThreadId(null);
        sessionStore.put(session);

        bridgeService.reconcileWaitingSession(session);

        CodexSessionRecord updated = sessionStore.get("s2").orElseThrow();
        assertEquals("WAITING_EVENT_THREAD_MISSING", updated.getPhoneBridgeErrorCode());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM codex_phone_bridges", Integer.class));
    }

    @Test
    void bridgeTaskAcceptsFullAssistantMessageBeyondPublicTaskLimit() {
        CodexSessionRecord session = waitingSession("s3");
        session.setLastAssistantMessage("长消息".repeat(1200));
        sessionStore.put(session);

        bridgeService.reconcileWaitingSession(session);

        String text = jdbc.queryForObject("SELECT text FROM phone_tasks", String.class);
        assertTrue(text.length() > 2000);
        assertNull(sessionStore.get("s3").orElseThrow().getPhoneBridgeErrorCode());
    }

    @Test
    void reconcileWaitingSessionDoesNotRefreshUpdatedAtWhenPhoneErrorIsAlreadyClear() {
        CodexSessionRecord session = waitingSession("s3-idempotent-clear");
        OffsetDateTime previousUpdate = OffsetDateTime.parse("2026-06-10T04:00:00Z");
        session.setStatus(CodexSessionStatus.IDLE);
        session.setUpdatedAt(previousUpdate);
        sessionStore.put(session);

        bridgeService.reconcileWaitingSession(session);

        assertEquals(previousUpdate.toInstant(), sessionStore.get("s3-idempotent-clear").orElseThrow().getUpdatedAt().toInstant());
    }

    @Test
    void reconcileWaitingSessionDoesNotRefreshUpdatedAtWhenPhoneErrorIsUnchanged() {
        CodexSessionRecord session = waitingSession("s3-idempotent-error");
        OffsetDateTime previousUpdate = OffsetDateTime.parse("2026-06-10T04:00:00Z");
        session.setThreadId(null);
        session.setPhoneBridgeErrorCode("WAITING_EVENT_THREAD_MISSING");
        session.setPhoneBridgeErrorMessage("Codex thread id has not been detected yet.");
        session.setUpdatedAt(previousUpdate);
        sessionStore.put(session);

        bridgeService.reconcileWaitingSession(session);

        assertEquals(previousUpdate.toInstant(), sessionStore.get("s3-idempotent-error").orElseThrow().getUpdatedAt().toInstant());
    }

    @Test
    void blfFailureKeepsBridgeTaskOwnershipAndFailureStatus() {
        CodexSessionRecord session = waitingSession("s4");
        sessionStore.put(session);
        ami.failNextInUse = true;

        bridgeService.reconcileWaitingSession(session);

        BridgeSummary bridge = bridgeService.latestSummaries("s4").getFirst();
        assertEquals(BridgeStatus.FAILED_BLF_NOTIFY, bridge.status());
        assertNotNull(bridge.taskId());
        assertEquals(CodexSessionStatus.COMPLETED, sessionStore.get("s4").orElseThrow().getStatus());
        assertEquals(bridge.taskId(), jdbc.queryForObject("SELECT task_id FROM phone_tasks", String.class));
        assertEquals(TaskStatus.FAILED_BLF_NOTIFY.name(), jdbc.queryForObject("SELECT status FROM phone_tasks", String.class));
    }

    @Test
    void startupRecoveryCreatesTaskForBridgeWithoutTaskId() {
        CodexSessionRecord session = waitingSession("s5");
        sessionStore.put(session);
        jdbc.update("""
                INSERT INTO codex_phone_bridges (
                    bridge_id, codex_session_id, thread_id, waiting_event_key,
                    last_assistant_message, status, created_at, updated_at
                )
                VALUES ('bridge-recover', 's5', 'thread-s5', '2026-06-11T04:00:00Z', '恢复通知', 'WAITING_DETECTED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);

        bridgeService.recoverIncompleteBridges();

        BridgeSummary bridge = bridgeService.latestSummaries("s5").getFirst();
        assertEquals(BridgeStatus.NOTIFIED, bridge.status());
        assertNotNull(bridge.taskId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM phone_tasks WHERE bridge_id = 'bridge-recover'", Integer.class));
    }

    @Test
    void startupRecoveryFailsTaskCreatedBridgeWithoutTaskId() {
        CodexSessionRecord session = waitingSession("s6");
        session.setStatus(CodexSessionStatus.WAITING_USER);
        sessionStore.put(session);
        jdbc.update("""
                INSERT INTO codex_phone_bridges (
                    bridge_id, codex_session_id, thread_id, waiting_event_key,
                    last_assistant_message, status, created_at, updated_at
                )
                VALUES ('bridge-task-created-without-task', 's6', 'thread-s6', '2026-06-11T04:00:00Z',
                        '恢复通知', 'TASK_CREATED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);

        bridgeService.recoverIncompleteBridges();

        BridgeSummary bridge = bridgeService.latestSummaries("s6").getFirst();
        assertEquals(BridgeStatus.FAILED_TASK_CREATE, bridge.status());
        assertNull(bridge.taskId());
        assertEquals(CodexSessionStatus.WAITING_USER, sessionStore.get("s6").orElseThrow().getStatus());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM phone_tasks WHERE bridge_id = 'bridge-task-created-without-task'", Integer.class));
    }

    @Test
    void taskCreatedBridgeSnapshotDoesNotMarkCompletedSessionWaiting() throws Exception {
        CodexSessionRecord session = waitingSession("s6-task-created-gate");
        sessionStore.put(session);
        CodexPhoneBridgeRecord bridge = new CodexPhoneBridgeRecord(
                "bridge-task-created-gate",
                "s6-task-created-gate",
                "thread-s6-task-created-gate",
                "2026-06-11T04:00:00Z",
                "恢复通知",
                BridgeStatus.TASK_CREATED,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                OffsetDateTime.now(CLOCK),
                OffsetDateTime.now(CLOCK)
        );

        Method gate = CodexPhoneBridgeService.class.getDeclaredMethod("markSessionWaitingIfBridgeLive", CodexPhoneBridgeRecord.class);
        gate.setAccessible(true);
        gate.invoke(bridgeService, bridge);

        assertEquals(CodexSessionStatus.COMPLETED, sessionStore.get("s6-task-created-gate").orElseThrow().getStatus());
    }

    @Test
    void startupRecoveryFailsTaskCreatedBridgeWithoutTaskIdEvenWhenSessionNoLongerWaiting() {
        CodexSessionRecord session = waitingSession("s7");
        session.setStatus(CodexSessionStatus.RUNNING);
        sessionStore.put(session);
        jdbc.update("""
                INSERT INTO codex_phone_bridges (
                    bridge_id, codex_session_id, thread_id, waiting_event_key,
                    last_assistant_message, status, created_at, updated_at
                )
                VALUES ('bridge-stale-task-created', 's7', 'thread-s7', 'old-key',
                        '过期通知', 'TASK_CREATED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);

        bridgeService.recoverIncompleteBridges();

        BridgeSummary bridge = bridgeService.latestSummaries("s7").getFirst();
        assertEquals(BridgeStatus.FAILED_TASK_CREATE, bridge.status());
        assertNull(bridge.taskId());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM phone_tasks WHERE bridge_id = 'bridge-stale-task-created'", Integer.class));
    }

    @Test
    void renotifyFailureAfterCancelledBridgeRecordsReplacementAudit() {
        CodexSessionRecord session = waitingSession("s8");
        sessionStore.put(session);
        bridgeService.reconcileWaitingSession(session);
        BridgeSummary notified = bridgeService.latestSummaries("s8").getFirst();

        bridgeService.cancel(notified.bridgeId());
        failNextTts = true;

        BridgeSummary failed = bridgeService.renotify(notified.bridgeId());

        assertEquals(BridgeStatus.FAILED_TASK_CREATE, failed.status());
        assertEquals(notified.taskId(), jdbc.queryForObject(
                "SELECT replaced_task_id FROM codex_phone_bridges WHERE bridge_id = ?",
                String.class,
                notified.bridgeId()));
        assertNull(jdbc.queryForObject(
                "SELECT cancelled_at FROM codex_phone_bridges WHERE bridge_id = ?",
                java.sql.Timestamp.class,
                notified.bridgeId()));
        assertEquals(CodexSessionStatus.COMPLETED, sessionStore.get("s8").orElseThrow().getStatus());
        assertNotNull(failed.taskId());
        assertEquals(TaskStatus.FAILED_TASK_CREATE.name(), jdbc.queryForObject(
                "SELECT status FROM phone_tasks WHERE task_id = ?",
                String.class,
                failed.taskId()));
    }

    @Test
    void concurrentRenotifyCannotCreateDuplicateReplacementTasks() throws Exception {
        CodexSessionRecord session = waitingSession("s9");
        sessionStore.put(session);
        bridgeService.reconcileWaitingSession(session);
        BridgeSummary notified = bridgeService.latestSummaries("s9").getFirst();
        bridgeService.cancel(notified.bridgeId());
        blockNextTts = true;
        ttsBlocked = new CountDownLatch(1);
        releaseTts = new CountDownLatch(1);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<BridgeSummary> firstRenotify = executor.submit(() -> bridgeService.renotify(notified.bridgeId()));
            assertTrue(ttsBlocked.await(5, TimeUnit.SECONDS));
            String inFlightTaskId = jdbc.queryForObject(
                    "SELECT task_id FROM codex_phone_bridges WHERE bridge_id = ?",
                    String.class,
                    notified.bridgeId());
            assertNotNull(inFlightTaskId);
            assertTrue(!notified.taskId().equals(inFlightTaskId));
            assertEquals(BridgeStatus.TASK_CREATED.name(), jdbc.queryForObject(
                    "SELECT status FROM codex_phone_bridges WHERE bridge_id = ?",
                    String.class,
                    notified.bridgeId()));

            CodexSessionException conflict = assertThrows(CodexSessionException.class, () -> bridgeService.renotify(notified.bridgeId()));
            assertEquals("BRIDGE_RENOTIFY_NOT_ALLOWED", conflict.error());

            releaseTts.countDown();
            BridgeSummary completed = firstRenotify.get();
            assertEquals(BridgeStatus.NOTIFIED, completed.status());
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM phone_tasks WHERE bridge_id = ?", Integer.class, notified.bridgeId()));
            assertEquals(1, jdbc.queryForObject("""
                    SELECT COUNT(*)
                      FROM phone_tasks
                     WHERE bridge_id = ? AND status IN ('QUEUED', 'ASSIGNED', 'NOTIFIED', 'PICKED_UP', 'RECORDING', 'RECORDED', 'TRANSCRIBING', 'ASR_DONE', 'REPLYING_TO_CODEX')
                    """, Integer.class, notified.bridgeId()));
        } finally {
            releaseTts.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void cancelDuringReplacementRenotifyDoesNotReviveWaitingSession() throws Exception {
        CodexSessionRecord session = waitingSession("s9-cancel-replacement-renotify");
        sessionStore.put(session);
        bridgeService.reconcileWaitingSession(session);
        BridgeSummary notified = bridgeService.latestSummaries("s9-cancel-replacement-renotify").getFirst();
        bridgeService.cancel(notified.bridgeId());
        blockNextTts = true;
        ttsBlocked = new CountDownLatch(1);
        releaseTts = new CountDownLatch(1);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<BridgeSummary> renotify = executor.submit(() -> bridgeService.renotify(notified.bridgeId()));
            assertTrue(ttsBlocked.await(5, TimeUnit.SECONDS));

            BridgeSummary cancelled = bridgeService.cancel(notified.bridgeId());
            releaseTts.countDown();
            BridgeSummary late = renotify.get();

            BridgeSummary finalBridge = bridgeService.latestSummaries("s9-cancel-replacement-renotify").getFirst();
            assertEquals(BridgeStatus.CANCELLED, cancelled.status());
            assertEquals(BridgeStatus.CANCELLED, late.status());
            assertEquals(BridgeStatus.CANCELLED, finalBridge.status());
            assertEquals(CodexSessionStatus.COMPLETED, sessionStore.get("s9-cancel-replacement-renotify").orElseThrow().getStatus());
            assertEquals(0, jdbc.queryForObject("""
                    SELECT COUNT(*)
                      FROM phone_tasks
                     WHERE bridge_id = ? AND status IN ('QUEUED', 'ASSIGNED', 'NOTIFIED', 'PICKED_UP', 'RECORDING', 'RECORDED', 'TRANSCRIBING', 'ASR_DONE', 'REPLYING_TO_CODEX')
                    """, Integer.class, notified.bridgeId()));
        } finally {
            releaseTts.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void cancelPersistsBridgeTaskAndSlotEvenWhenBlfResetFails() {
        CodexSessionRecord session = waitingSession("s10");
        sessionStore.put(session);
        bridgeService.reconcileWaitingSession(session);
        BridgeSummary notified = bridgeService.latestSummaries("s10").getFirst();
        ami.failNextNotInUse = true;

        BridgeSummary cancelled = bridgeService.cancel(notified.bridgeId());

        assertEquals(BridgeStatus.CANCELLED, cancelled.status());
        assertEquals(BridgePhase.CANCELLED, cancelled.phase());
        assertEquals(CodexSessionStatus.COMPLETED, sessionStore.get("s10").orElseThrow().getStatus());
        assertEquals(TaskStatus.CANCELLED.name(), jdbc.queryForObject(
                "SELECT status FROM phone_tasks WHERE task_id = ?",
                String.class,
                notified.taskId()));
        assertEquals("IDLE", jdbc.queryForObject("SELECT status FROM phone_slots WHERE slot = ?", String.class, notified.slot()));
        assertNull(jdbc.queryForObject("SELECT current_task_id FROM phone_slots WHERE slot = ?", String.class, notified.slot()));
    }

    @Test
    void cancelDuringTaskCreationPreventsLateBlfNotification() throws Exception {
        CodexSessionRecord session = waitingSession("s11");
        sessionStore.put(session);
        blockNextTts = true;
        ttsBlocked = new CountDownLatch(1);
        releaseTts = new CountDownLatch(1);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> creating = executor.submit(() -> bridgeService.reconcileWaitingSession(session));
            assertTrue(ttsBlocked.await(5, TimeUnit.SECONDS));
            BridgeSummary inFlight = bridgeService.latestSummaries("s11").getFirst();

            BridgeSummary cancelled = bridgeService.cancel(inFlight.bridgeId());
            releaseTts.countDown();
            creating.get();

            BridgeSummary finalBridge = bridgeService.latestSummaries("s11").getFirst();
            assertEquals(BridgeStatus.CANCELLED, cancelled.status());
            assertEquals(BridgeStatus.CANCELLED, finalBridge.status());
            assertEquals(TaskStatus.CANCELLED.name(), jdbc.queryForObject(
                    "SELECT status FROM phone_tasks WHERE task_id = ?",
                    String.class,
                    inFlight.taskId()));
            assertNotNull(jdbc.queryForObject(
                    "SELECT cancelled_at FROM codex_phone_bridges WHERE bridge_id = ?",
                    java.sql.Timestamp.class,
                    inFlight.bridgeId()));
            assertEquals(0, ami.count("INUSE:1"));
        } finally {
            releaseTts.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void renotifyFailedBlfTaskRepublishesTaskAudioBeforeBlfNotification() throws Exception {
        CodexSessionRecord session = waitingSession("s12");
        sessionStore.put(session);
        ami.failNextInUse = true;

        bridgeService.reconcileWaitingSession(session);
        BridgeSummary failed = bridgeService.latestSummaries("s12").getFirst();
        Files.writeString(tempDir.resolve("runtime").resolve("sounds").resolve("slots").resolve("slot-1.wav"), "stale audio");

        BridgeSummary renotified = bridgeService.renotify(failed.bridgeId());

        assertEquals(BridgeStatus.NOTIFIED, renotified.status());
        assertEquals(failed.taskId(), renotified.taskId());
        assertEquals("请选择下一步", Files.readString(tempDir.resolve("runtime").resolve("sounds").resolve("slots").resolve("slot-1.wav")));
    }

    @Test
    void renotifyFailedBlfTaskWithMissingAudioCreatesReplacementTask() throws Exception {
        CodexSessionRecord session = waitingSession("s13");
        sessionStore.put(session);
        ami.failNextInUse = true;

        bridgeService.reconcileWaitingSession(session);
        BridgeSummary failed = bridgeService.latestSummaries("s13").getFirst();
        Path failedAudio = Path.of(jdbc.queryForObject(
                "SELECT task_audio_file FROM phone_tasks WHERE task_id = ?",
                String.class,
                failed.taskId()));
        Files.deleteIfExists(failedAudio);

        BridgeSummary renotified = bridgeService.renotify(failed.bridgeId());

        assertEquals(BridgeStatus.NOTIFIED, renotified.status());
        assertTrue(!failed.taskId().equals(renotified.taskId()));
        assertEquals(failed.taskId(), jdbc.queryForObject(
                "SELECT replaced_task_id FROM codex_phone_bridges WHERE bridge_id = ?",
                String.class,
                failed.bridgeId()));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM phone_tasks WHERE bridge_id = ?", Integer.class, failed.bridgeId()));
    }

    @Test
    void cancelDuringFailedBlfRenotifyAmiPreventsLateTaskRevival() throws Exception {
        CodexSessionRecord session = waitingSession("s14");
        sessionStore.put(session);
        ami.failNextInUse = true;

        bridgeService.reconcileWaitingSession(session);
        BridgeSummary failed = bridgeService.latestSummaries("s14").getFirst();
        ami.blockNextInUse = true;
        amiBlocked = new CountDownLatch(1);
        releaseAmi = new CountDownLatch(1);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<BridgeSummary> retrying = executor.submit(() -> bridgeService.renotify(failed.bridgeId()));
            assertTrue(amiBlocked.await(5, TimeUnit.SECONDS));

            BridgeSummary cancelled = bridgeService.cancel(failed.bridgeId());
            releaseAmi.countDown();
            BridgeSummary late = retrying.get();

            BridgeSummary finalBridge = bridgeService.latestSummaries("s14").getFirst();
            assertEquals(BridgeStatus.CANCELLED, cancelled.status());
            assertEquals(BridgeStatus.CANCELLED, late.status());
            assertEquals(BridgeStatus.CANCELLED, finalBridge.status());
            assertEquals(TaskStatus.CANCELLED.name(), jdbc.queryForObject(
                    "SELECT status FROM phone_tasks WHERE task_id = ?",
                    String.class,
                    failed.taskId()));
            assertEquals(CodexSessionStatus.COMPLETED, sessionStore.get("s14").orElseThrow().getStatus());
            assertEquals("IDLE", jdbc.queryForObject("SELECT status FROM phone_slots WHERE slot = 1", String.class));
            assertNull(jdbc.queryForObject("SELECT current_task_id FROM phone_slots WHERE slot = 1", String.class));
            assertTrue(ami.count("NOT_INUSE:1") >= 1);
        } finally {
            releaseAmi.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void cancelDuringFailedBlfRenotifyAmiFailureKeepsTaskCancelled() throws Exception {
        CodexSessionRecord session = waitingSession("s15");
        sessionStore.put(session);
        ami.failNextInUse = true;

        bridgeService.reconcileWaitingSession(session);
        BridgeSummary failed = bridgeService.latestSummaries("s15").getFirst();
        ami.blockNextInUse = true;
        ami.failBlockedInUse = true;
        amiBlocked = new CountDownLatch(1);
        releaseAmi = new CountDownLatch(1);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<BridgeSummary> retrying = executor.submit(() -> bridgeService.renotify(failed.bridgeId()));
            assertTrue(amiBlocked.await(5, TimeUnit.SECONDS));

            BridgeSummary cancelled = bridgeService.cancel(failed.bridgeId());
            releaseAmi.countDown();
            BridgeSummary late = retrying.get();

            assertEquals(BridgeStatus.CANCELLED, cancelled.status());
            assertEquals(BridgeStatus.CANCELLED, late.status());
            assertEquals(TaskStatus.CANCELLED.name(), jdbc.queryForObject(
                    "SELECT status FROM phone_tasks WHERE task_id = ?",
                    String.class,
                    failed.taskId()));
            assertEquals(CodexSessionStatus.COMPLETED, sessionStore.get("s15").orElseThrow().getStatus());
            assertEquals("IDLE", jdbc.queryForObject("SELECT status FROM phone_slots WHERE slot = 1", String.class));
            assertNull(jdbc.queryForObject("SELECT current_task_id FROM phone_slots WHERE slot = 1", String.class));
        } finally {
            releaseAmi.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void asrSuccessRepliesToCodexByResumingThreadAndMarksSessionRunning() {
        CodexSessionRecord session = waitingSession("s16");
        sessionStore.put(session);
        bridgeService.reconcileWaitingSession(session);
        BridgeSummary notified = bridgeService.latestSummaries("s16").getFirst();

        bridgeService.onTaskAsrSuccess(notified.taskId(), "今天是几号");

        BridgeSummary replied = bridgeService.latestSummaries("s16").getFirst();
        CodexSessionRecord updatedSession = sessionStore.get("s16").orElseThrow();
        assertEquals(BridgeStatus.REPLIED_TO_CODEX, replied.status());
        assertEquals(BridgePhase.DONE, replied.phase());
        assertEquals(TaskStatus.REPLIED_TO_CODEX.name(), jdbc.queryForObject(
                "SELECT status FROM phone_tasks WHERE task_id = ?",
                String.class,
                notified.taskId()));
        assertEquals(1, gateway.resumedPrompts);
        assertEquals(0, gateway.submittedPrompts);
        assertEquals(phoneReplyPrompt("今天是几号"), gateway.lastPrompt);
        assertEquals(CodexSessionStatus.RUNNING, updatedSession.getStatus());
        assertFalse(updatedSession.isWaitingMarker());
    }

    @Test
    void asrSuccessStillRepliesWhenPollProjectedSameWaitingEventBackToCompleted() {
        CodexSessionRecord session = waitingSession("s16-completed-projection");
        sessionStore.put(session);
        bridgeService.reconcileWaitingSession(session);
        BridgeSummary notified = bridgeService.latestSummaries("s16-completed-projection").getFirst();
        sessionStore.update("s16-completed-projection", record -> {
            record.setStatus(CodexSessionStatus.COMPLETED);
            record.setWaitingMarker(true);
            return record;
        });

        bridgeService.onTaskAsrSuccess(notified.taskId(), "继续");

        BridgeSummary replied = bridgeService.latestSummaries("s16-completed-projection").getFirst();
        assertEquals(BridgeStatus.REPLIED_TO_CODEX, replied.status());
        assertEquals(1, gateway.resumedPrompts);
        assertTrue(gateway.lastPrompt.contains("用户通过电话语音回复了你上一轮的问题或请求"));
        assertTrue(gateway.lastPrompt.contains("继续"));
        assertFalse(gateway.lastPrompt.contains("用户通过 Phone Agent 发起一个新的请求"));
        assertEquals(TaskStatus.REPLIED_TO_CODEX.name(), jdbc.queryForObject(
                "SELECT status FROM phone_tasks WHERE task_id = ?",
                String.class,
                notified.taskId()));
    }

    @Test
    void stoppedCodexSessionReplyFailureReleasesWaitingSessionForRenotify() {
        CodexSessionRecord session = waitingSession("s16-stopped");
        sessionStore.put(session);
        bridgeService.reconcileWaitingSession(session);
        BridgeSummary notified = bridgeService.latestSummaries("s16-stopped").getFirst();
        gateway.tmuxAvailable = false;

        bridgeService.onTaskAsrSuccess(notified.taskId(), "继续");

        BridgeSummary failed = bridgeService.latestSummaries("s16-stopped").getFirst();
        assertEquals(BridgeStatus.FAILED_CODEX_SESSION_STOPPED, failed.status());
        assertEquals(BridgePhase.FAILED, failed.phase());
        assertTrue(failed.renotifyAllowed());
        assertEquals(CodexSessionStatus.COMPLETED, sessionStore.get("s16-stopped").orElseThrow().getStatus());
        assertEquals(TaskStatus.FAILED_CODEX_SESSION_STOPPED.name(), jdbc.queryForObject(
                "SELECT status FROM phone_tasks WHERE task_id = ?",
                String.class,
                notified.taskId()));
        assertEquals(0, gateway.submittedPrompts);
    }

    @Test
    void codexPasteFailureReleasesWaitingSessionForRenotify() {
        CodexSessionRecord session = waitingSession("s16-paste-failed");
        sessionStore.put(session);
        bridgeService.reconcileWaitingSession(session);
        BridgeSummary notified = bridgeService.latestSummaries("s16-paste-failed").getFirst();
        gateway.failSubmitPrompt = true;

        bridgeService.onTaskAsrSuccess(notified.taskId(), "继续");

        BridgeSummary failed = bridgeService.latestSummaries("s16-paste-failed").getFirst();
        assertEquals(BridgeStatus.FAILED_REPLY_TO_CODEX, failed.status());
        assertEquals(BridgePhase.FAILED, failed.phase());
        assertTrue(failed.renotifyAllowed());
        assertEquals(CodexSessionStatus.COMPLETED, sessionStore.get("s16-paste-failed").orElseThrow().getStatus());
        assertEquals(TaskStatus.FAILED_REPLY_TO_CODEX.name(), jdbc.queryForObject(
                "SELECT status FROM phone_tasks WHERE task_id = ?",
                String.class,
                notified.taskId()));
        assertEquals(1, gateway.resumedPrompts);
    }

    @Test
    void lateAsrSuccessDoesNotReviveCancelledBridgeOrReplyToCodex() {
        CodexSessionRecord session = waitingSession("s16-late-cancelled");
        sessionStore.put(session);
        bridgeService.reconcileWaitingSession(session);
        BridgeSummary notified = bridgeService.latestSummaries("s16-late-cancelled").getFirst();
        bridgeService.cancel(notified.bridgeId());

        bridgeService.onTaskAsrSuccess(notified.taskId(), "迟到回复");

        BridgeSummary cancelled = bridgeService.latestSummaries("s16-late-cancelled").getFirst();
        assertEquals(BridgeStatus.CANCELLED, cancelled.status());
        assertEquals(0, gateway.submittedPrompts);
        assertEquals(TaskStatus.CANCELLED.name(), jdbc.queryForObject(
                "SELECT status FROM phone_tasks WHERE task_id = ?",
                String.class,
                notified.taskId()));
    }

    @Test
    void asrNoReplyDoesNotReplyToCodexAndAllowsRenotify() throws Exception {
        CodexSessionRecord session = waitingSession("s19");
        sessionStore.put(session);
        bridgeService.reconcileWaitingSession(session);
        BridgeSummary notified = bridgeService.latestSummaries("s19").getFirst();

        assertEquals(notified.taskId(), taskService.startSlot(notified.slot()));
        taskService.startRecording(notified.slot(), notified.taskId());
        writeRecording(notified.taskId(), "voice");
        taskService.completeRecording(notified.slot(), notified.taskId());
        completeAsrSuccess(notified.taskId(), " ");

        BridgeSummary noReply = bridgeService.latestSummaries("s19").getFirst();
        assertEquals(BridgeStatus.NO_REPLY, noReply.status());
        assertEquals(BridgePhase.DONE, noReply.phase());
        assertTrue(noReply.renotifyAllowed());
        assertEquals(CodexSessionStatus.COMPLETED, sessionStore.get("s19").orElseThrow().getStatus());
        assertEquals(0, gateway.submittedPrompts);
        assertEquals(TaskStatus.NO_REPLY.name(), jdbc.queryForObject(
                "SELECT status FROM phone_tasks WHERE task_id = ?",
                String.class,
                notified.taskId()));

        assertEquals(notified.taskId(), noReply.taskId());

        BridgeSummary renotified = bridgeService.renotify(noReply.bridgeId());

        assertEquals(BridgeStatus.NOTIFIED, renotified.status());
        assertTrue(!notified.taskId().equals(renotified.taskId()));
        assertEquals(CodexSessionStatus.WAITING_USER, sessionStore.get("s19").orElseThrow().getStatus());
    }

    @Test
    void failedRecordingBridgeAllowsRenotifyWithReplacementTask() {
        CodexSessionRecord session = waitingSession("s20");
        sessionStore.put(session);
        bridgeService.reconcileWaitingSession(session);
        BridgeSummary notified = bridgeService.latestSummaries("s20").getFirst();

        assertEquals(notified.taskId(), taskService.startSlot(notified.slot()));
        taskService.startRecording(notified.slot(), notified.taskId());
        taskService.completeRecording(notified.slot(), notified.taskId());

        BridgeSummary failed = bridgeService.latestSummaries("s20").getFirst();
        assertEquals(BridgeStatus.FAILED_RECORDING, failed.status());
        assertEquals(BridgePhase.FAILED, failed.phase());
        assertTrue(failed.renotifyAllowed());
        assertEquals(CodexSessionStatus.COMPLETED, sessionStore.get("s20").orElseThrow().getStatus());

        BridgeSummary renotified = bridgeService.renotify(failed.bridgeId());

        assertEquals(BridgeStatus.NOTIFIED, renotified.status());
        assertTrue(!notified.taskId().equals(renotified.taskId()));
        assertEquals(notified.taskId(), jdbc.queryForObject(
                "SELECT replaced_task_id FROM codex_phone_bridges WHERE bridge_id = ?",
                String.class,
                failed.bridgeId()));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM phone_tasks WHERE bridge_id = ?", Integer.class, failed.bridgeId()));
    }

    @Test
    void pickedUpHangupBeforeRecordingCompletesBridgeAsNoReply() {
        CodexSessionRecord session = waitingSession("s20-before-recording-hangup");
        sessionStore.put(session);
        bridgeService.reconcileWaitingSession(session);
        BridgeSummary notified = bridgeService.latestSummaries("s20-before-recording-hangup").getFirst();

        assertEquals(notified.taskId(), taskService.startSlot(notified.slot()));
        taskService.completeRecording(notified.slot(), notified.taskId());

        BridgeSummary noReply = bridgeService.latestSummaries("s20-before-recording-hangup").getFirst();
        assertEquals(BridgeStatus.NO_REPLY, noReply.status());
        assertEquals(BridgePhase.DONE, noReply.phase());
        assertTrue(noReply.renotifyAllowed());
        assertEquals(CodexSessionStatus.COMPLETED, sessionStore.get("s20-before-recording-hangup").orElseThrow().getStatus());
        assertEquals(TaskStatus.NO_REPLY.name(), jdbc.queryForObject(
                "SELECT status FROM phone_tasks WHERE task_id = ?",
                String.class,
                notified.taskId()));
        assertEquals(0, gateway.submittedPrompts);
    }

    @Test
    void oldTaskNoReplyCallbackDoesNotOverwriteReplacementTask() {
        CodexSessionRecord session = waitingSession("s20-old-task-callback");
        sessionStore.put(session);
        bridgeService.reconcileWaitingSession(session);
        BridgeSummary notified = bridgeService.latestSummaries("s20-old-task-callback").getFirst();
        bridgeService.cancel(notified.bridgeId());
        BridgeSummary replacement = bridgeService.renotify(notified.bridgeId());

        bridgeService.onTaskNoReply(notified.taskId());

        BridgeSummary current = bridgeService.latestSummaries("s20-old-task-callback").getFirst();
        assertEquals(BridgeStatus.NOTIFIED, current.status());
        assertEquals(replacement.taskId(), current.taskId());
        assertTrue(!notified.taskId().equals(current.taskId()));
        assertEquals(CodexSessionStatus.WAITING_USER, sessionStore.get("s20-old-task-callback").orElseThrow().getStatus());
    }

    @Test
    void renotifyAfterReplyCreatesReplacementPhoneTask() {
        CodexSessionRecord session = waitingSession("s17");
        sessionStore.put(session);
        bridgeService.reconcileWaitingSession(session);
        BridgeSummary notified = bridgeService.latestSummaries("s17").getFirst();
        bridgeService.onTaskAsrSuccess(notified.taskId(), "继续");

        BridgeSummary renotified = bridgeService.renotify(notified.bridgeId());

        assertEquals(BridgeStatus.NOTIFIED, renotified.status());
        assertTrue(!notified.taskId().equals(renotified.taskId()));
        assertEquals(CodexSessionStatus.RUNNING, sessionStore.get("s17").orElseThrow().getStatus());
        assertEquals(notified.taskId(), jdbc.queryForObject(
                "SELECT replaced_task_id FROM codex_phone_bridges WHERE bridge_id = ?",
                String.class,
                notified.bridgeId()));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM phone_tasks WHERE bridge_id = ?", Integer.class, notified.bridgeId()));
    }

    @Test
    void staleJsonlWaitingEventDoesNotReopenSessionAfterPhoneReply() throws Exception {
        Path cwd = Files.createDirectories(tempDir.resolve("workspace"));
        Path sessionsDir = Files.createDirectories(tempDir.resolve("sessions"));
        Path jsonl = sessionsDir.resolve("rollout-2026-06-11T04-00-01-thread-s18.jsonl");
        Files.writeString(jsonl, """
                {"thread_id":"thread-s18","turn_context":{"cwd":"%s"}}
                {"type":"event_msg","timestamp":"2026-06-11T04:00:01Z","payload":{"type":"task_complete","last_agent_message":"请选择下一步"}}
                """.formatted(cwd));
        PhoneAgentProperties properties = new PhoneAgentProperties();
        properties.setRuntimeDir(tempDir.resolve("runtime"));
        properties.getCodex().setAllowedWorkspaceRoots(List.of(cwd));
        properties.getCodex().setSessionsDir(sessionsDir);
        CodexSessionService sessionService = new CodexSessionService(
                properties,
                sessionStore,
                new NoopGateway(),
                new CodexJsonlScanner(sessionsDir, new ObjectMapper()),
                bridgeService,
                CLOCK,
                3,
                5000,
                100
        );
        CodexSessionRecord session = waitingSession("s18");
        session.setCwd(cwd.toString());
        session.setJsonlPath(jsonl.toString());
        session.setTtydPid(123L);
        session.setTtydPort(49152);
        session.setLastRelevantEventTimestamp("2026-06-11T04:00:01Z");
        sessionStore.put(session);
        bridgeService.reconcileWaitingSession(session);
        BridgeSummary notified = bridgeService.latestSummaries("s18").getFirst();
        bridgeService.onTaskAsrSuccess(notified.taskId(), "继续");
        sessionStore.update("s18", record -> {
            record.setLastProcessedJsonlSize(0);
            record.setStatus(CodexSessionStatus.RUNNING);
            record.setWaitingMarker(false);
            return record;
        });

        sessionService.poll("s18");

        CodexSessionRecord updated = sessionStore.get("s18").orElseThrow();
        assertEquals(CodexSessionStatus.RUNNING, updated.getStatus());
        assertTrue(!updated.isWaitingMarker());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM codex_phone_bridges WHERE codex_session_id = 's18'", Integer.class));
        assertEquals(BridgeStatus.REPLIED_TO_CODEX, bridgeService.latestSummaries("s18").getFirst().status());
    }

    @Test
    void jsonlUserMessageCancelsCurrentWaitingBridgeUsingPreviousWaitingEventKey() throws Exception {
        Path cwd = Files.createDirectories(tempDir.resolve("workspace-external-reply"));
        Path sessionsDir = Files.createDirectories(tempDir.resolve("sessions-external-reply"));
        Path jsonl = sessionsDir.resolve("rollout-2026-06-11T04-00-01-thread-s21.jsonl");
        Files.writeString(jsonl, """
                {"thread_id":"thread-s21","turn_context":{"cwd":"%s"}}
                {"type":"event_msg","timestamp":"2026-06-11T04:00:01Z","payload":{"type":"task_complete","last_agent_message":"请选择下一步"}}
                """.formatted(cwd));
        PhoneAgentProperties properties = new PhoneAgentProperties();
        properties.setRuntimeDir(tempDir.resolve("runtime"));
        properties.getCodex().setAllowedWorkspaceRoots(List.of(cwd));
        properties.getCodex().setSessionsDir(sessionsDir);
        CodexSessionService sessionService = new CodexSessionService(
                properties,
                sessionStore,
                gateway,
                new CodexJsonlScanner(sessionsDir, new ObjectMapper()),
                bridgeService,
                CLOCK,
                3,
                5000,
                100
        );
        CodexSessionRecord session = waitingSession("s21");
        session.setCwd(cwd.toString());
        session.setJsonlPath(jsonl.toString());
        session.setThreadId("thread-s21");
        session.setLastProcessedJsonlSize(Files.size(jsonl));
        session.setLastRelevantEventTimestamp("2026-06-11T04:00:01Z");
        sessionStore.put(session);
        bridgeService.reconcileWaitingSession(session);
        BridgeSummary notified = bridgeService.latestSummaries("s21").getFirst();

        Files.writeString(jsonl, """
                {"type":"event_msg","timestamp":"2026-06-11T04:00:02Z","payload":{"type":"user_message","message":"我在 TUI 里回复"}}
                """, java.nio.file.StandardOpenOption.APPEND);
        sessionService.poll("s21");

        CodexSessionRecord updated = sessionStore.get("s21").orElseThrow();
        BridgeSummary cancelled = bridgeService.latestSummaries("s21").getFirst();
        assertEquals(CodexSessionStatus.RUNNING, updated.getStatus());
        assertTrue(!updated.isWaitingMarker());
        assertEquals(BridgeStatus.CANCELLED, cancelled.status());
        assertEquals("Codex session was answered outside Phone Agent", cancelled.errorMessage());
        assertEquals(TaskStatus.CANCELLED.name(), jdbc.queryForObject(
                "SELECT status FROM phone_tasks WHERE task_id = ?",
                String.class,
                notified.taskId()));
    }


    private CodexSessionRecord waitingSession(String id) {
        CodexSessionRecord record = new CodexSessionRecord();
        record.setId(id);
        record.setTitle(id);
        record.setCwd(tempDir.toString());
        record.setStatus(CodexSessionStatus.COMPLETED);
        record.setTmuxName("tmux-" + id);
        record.setThreadId("thread-" + id);
        record.setJsonlPath(tempDir.resolve(id + ".jsonl").toString());
        record.setLastProcessedJsonlSize(100);
        record.setLastRelevantEventTimestamp("2026-06-11T04:00:00Z");
        record.setLastAssistantMessage("请选择下一步");
        record.setCreatedAt(OffsetDateTime.now(CLOCK));
        record.setUpdatedAt(OffsetDateTime.now(CLOCK));
        return record;
    }

    private SpeechSynthesisService tts() {
        return (taskId, text) -> {
            if (blockNextTts) {
                blockNextTts = false;
                ttsBlocked.countDown();
                try {
                    if (!releaseTts.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Timed out unblocking TTS");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Interrupted while blocking TTS", e);
                }
            }
            if (failNextTts) {
                failNextTts = false;
                throw new IllegalStateException("TTS failed for test");
            }
            try {
                Path path = tempDir.resolve("tts").resolve(taskId + ".wav");
                Files.createDirectories(path.getParent());
                Files.writeString(path, text);
                return path;
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        };
    }

    private static void injectBridgeService(TaskService taskService, CodexPhoneBridgeService bridgeService) {
        try {
            Field field = TaskService.class.getDeclaredField("bridgeService");
            field.setAccessible(true);
            field.set(taskService, bridgeService);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Could not inject bridge service into task service test fixture", e);
        }
    }

    private void completeAsrSuccess(String taskId, String replyText) {
        try {
            java.lang.reflect.Method method = TaskService.class.getDeclaredMethod("completeAsrSuccess", String.class, String.class);
            method.setAccessible(true);
            method.invoke(taskService, taskId, replyText);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Could not complete ASR success through task service test fixture", e);
        }
    }

    private void writeRecording(String taskId, String body) throws Exception {
        Path recording = tempDir.resolve("runtime").resolve("recordings").resolve(taskId + ".wav");
        Files.createDirectories(recording.getParent());
        Files.writeString(recording, body);
    }

    private static String phonePrompt(String text) {
        return new CodexPromptFormatter().formatUserInput(text);
    }

    private static String phoneReplyPrompt(String text) {
        return new CodexPromptFormatter().formatPhoneReply(text, "zh-CN");
    }

    private final class RecordingAmi implements AsteriskAmiClient {
        private final List<String> commands = new java.util.concurrent.CopyOnWriteArrayList<>();
        boolean failNextInUse;
        boolean failNextNotInUse;
        boolean blockNextInUse;
        boolean failBlockedInUse;

        @Override
        public void setInUse(int slot) {
            commands.add("INUSE:" + slot);
            if (failNextInUse) {
                failNextInUse = false;
                throw new IllegalStateException("AMI command failed");
            }
            if (blockNextInUse) {
                blockNextInUse = false;
                amiBlocked.countDown();
                try {
                    if (!releaseAmi.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Timed out unblocking AMI");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Interrupted while blocking AMI", e);
                }
                if (failBlockedInUse) {
                    failBlockedInUse = false;
                    throw new IllegalStateException("AMI command failed after block");
                }
            }
        }

        @Override
        public void setNotInUse(int slot) {
            commands.add("NOT_INUSE:" + slot);
            if (failNextNotInUse) {
                failNextNotInUse = false;
                throw new IllegalStateException("AMI reset failed");
            }
        }

        @Override
        public void originateRingPhone() {
            commands.add("RING_PHONE");
        }

        long count(String command) {
            return commands.stream().filter(command::equals).count();
        }
    }

    private static final class NoopGateway implements CodexProcessGateway {
        int submittedPrompts;
        int resumedPrompts;
        String lastPrompt;
        boolean tmuxAvailable = true;
        boolean failSubmitPrompt;

        @Override public boolean commandAvailable(String command) { return true; }
        @Override public void startTmux(String tmuxCommand, String tmuxName, Path cwd, String codexCommand, String initialPrompt) {}
        @Override public void startResumeTmux(String tmuxCommand, String tmuxName, Path cwd, String codexCommand, String threadId) {}
        @Override public void resumeTmuxWithPrompt(String tmuxCommand, String tmuxName, Path cwd, String codexCommand, String threadId, Path promptFile) {
            resumedPrompts++;
            lastPrompt = readPrompt(promptFile);
            if (failSubmitPrompt) {
                throw new IllegalStateException("paste failed");
            }
        }
        @Override public TtydProcess startTtyd(String ttydCommand, String tmuxCommand, String tmuxName, int port) { return new TtydProcess(1); }
        @Override public void submitPrompt(String tmuxCommand, String tmuxName, Path promptFile) {
            submittedPrompts++;
            lastPrompt = readPrompt(promptFile);
            if (failSubmitPrompt) {
                throw new IllegalStateException("paste failed");
            }
        }
        @Override public boolean hasTmuxSession(String tmuxCommand, String tmuxName) { return tmuxAvailable; }
        @Override public boolean isTtydReady(long pid, int port, String tmuxName) { return true; }
        @Override public void killProcess(long pid) {}
        @Override public void killTmuxSession(String tmuxCommand, String tmuxName) {}
        @Override public int freePort() { return 49152; }

        private static String readPrompt(Path promptFile) {
            try {
                return Files.readString(promptFile);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
