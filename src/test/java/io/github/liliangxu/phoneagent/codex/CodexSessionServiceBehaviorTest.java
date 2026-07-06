package io.github.liliangxu.phoneagent.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.liliangxu.phoneagent.config.PhoneAgentProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexSessionServiceBehaviorTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-11T02:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    @Test
    void createRejectsCommandMissingBeforeStartingProcesses() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("workspace"));
        FakeGateway gateway = new FakeGateway();
        gateway.commandAvailable = false;
        CodexSessionService service = service(root, gateway, tempDir.resolve("sessions"));

        CodexSessionException failure = assertThrows(CodexSessionException.class,
                () -> service.create(new CreateCodexSessionRequest("x", root.toString(), null)));

        assertEquals("CODEX_COMMAND_NOT_FOUND", failure.error());
        assertTrue(gateway.startedTmux.isEmpty());
    }

    @Test
    void createReportsMissingTtydBeforeStartingProcesses() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("workspace"));
        FakeGateway gateway = new FakeGateway();
        gateway.unavailableCommands.add("ttyd");
        CodexSessionService service = service(root, gateway, tempDir.resolve("sessions"));

        CodexSessionException failure = assertThrows(CodexSessionException.class,
                () -> service.create(new CreateCodexSessionRequest("x", root.toString(), null)));

        assertEquals("CODEX_COMMAND_NOT_FOUND", failure.error());
        assertEquals("ttyd command not found", failure.getMessage());
        assertTrue(gateway.startedTmux.isEmpty());
    }

    @Test
    void createReportsMissingCodexBeforeStartingProcesses() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("workspace"));
        FakeGateway gateway = new FakeGateway();
        gateway.unavailableCommands.add("codex");
        CodexSessionService service = service(root, gateway, tempDir.resolve("sessions"));

        CodexSessionException failure = assertThrows(CodexSessionException.class,
                () -> service.create(new CreateCodexSessionRequest("x", root.toString(), null)));

        assertEquals("CODEX_COMMAND_NOT_FOUND", failure.error());
        assertEquals("codex command not found", failure.getMessage());
        assertTrue(gateway.startedTmux.isEmpty());
    }

    @Test
    void legacyJsonRegistryImportsOldHandledStatusAsCompleted() throws Exception {
        Path registry = Files.createDirectories(tempDir.resolve("legacy-registry"));
        Path root = Files.createDirectories(tempDir.resolve("workspace"));
        Files.writeString(registry.resolve("legacy.json"), """
                {
                  "id": "legacy",
                  "title": "legacy",
                  "cwd": "%s",
                  "status": "HANDLED",
                  "tmuxName": "phone-agent-codex-legacy",
                  "createdAt": "2026-06-11T02:00:00Z",
                  "updatedAt": "2026-06-11T02:00:00Z",
                  "startedAtEpochSecond": 1781143200
                }
                """.formatted(root.toString().replace("\\", "\\\\")));

        CodexSessionStore store = new CodexSessionStore(registry, new ObjectMapper());

        assertEquals(CodexSessionStatus.COMPLETED, store.get("legacy").orElseThrow().getStatus());
    }

    @Test
    void createUsesDirectoryNameAsDefaultTitleAndRejectsOversizedInputs() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("workspace"));
        FakeGateway gateway = new FakeGateway();
        CodexSessionService service = service(root, gateway, tempDir.resolve("sessions"));

        CodexSessionView created = service.create(new CreateCodexSessionRequest("", root.toString(), null));
        assertEquals("Codex 02:00:00", created.title());

        assertThrows(CodexSessionException.class,
                () -> service.create(new CreateCodexSessionRequest("x".repeat(121), root.toString(), null)));
        assertThrows(CodexSessionException.class,
                () -> service.create(new CreateCodexSessionRequest("x", root.toString(), "p".repeat(8001))));
    }

    @Test
    void createPassesInitialPromptToCodexStartAndLeavesSessionIdleUntilJsonlScan() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("workspace"));
        FakeGateway gateway = new FakeGateway();
        CodexSessionService service = service(root, gateway, tempDir.resolve("sessions"));

        CodexSessionView created = service.create(new CreateCodexSessionRequest("inbound", root.toString(), "帮我检查测试失败"));

        assertEquals(CodexSessionStatus.IDLE, created.status());
        assertEquals(List.of("帮我检查测试失败"), gateway.startedPrompts);
        assertEquals(0, gateway.submittedPrompts);
    }


    @Test
    void createWithoutRequestUsesDefaultWorkspaceUnderApplicationDirectory() throws Exception {
        Path appRoot = tempDir;
        Path defaultWorkspace = appRoot.resolve("workspace");
        FakeGateway gateway = new FakeGateway();
        CodexSessionService service = service(appRoot, gateway, tempDir.resolve("sessions"));
        String oldUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", appRoot.toString());
        try {
            CodexSessionView created = service.create(null);

            assertEquals(defaultWorkspace.toRealPath().toString(), created.cwd());
            assertTrue(Files.isDirectory(defaultWorkspace));
        } finally {
            System.setProperty("user.dir", oldUserDir);
        }
    }

    @Test
    void createRejectsSymlinkEscapeFromAllowedRoot() throws Exception {
        Path allowed = Files.createDirectories(tempDir.resolve("allowed"));
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Path link = allowed.resolve("escape");
        Files.createSymbolicLink(link, outside);
        CodexSessionService service = service(allowed, new FakeGateway(), tempDir.resolve("sessions"));

        CodexSessionException failure = assertThrows(CodexSessionException.class,
                () -> service.create(new CreateCodexSessionRequest("x", link.toString(), null)));

        assertEquals("CODEX_SESSION_VALIDATION_FAILED", failure.error());
    }

    @Test
    void pollKeepsCreateFailedAndCreatingAndDoesNotWriteTerminalHealthIntoSessionStatus() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("workspace"));
        FakeGateway gateway = new FakeGateway();
        CodexSessionStore store = new CodexSessionStore(tempDir.resolve("registry"), new ObjectMapper());
        CodexSessionService service = service(root, gateway, tempDir.resolve("sessions"), store);
        store.put(record("failed", CodexSessionStatus.CREATE_FAILED, root));
        store.put(record("creating", CodexSessionStatus.CREATING, root));
        store.put(record("missing-tmux", CodexSessionStatus.RUNNING, root));
        store.put(record("terminal", CodexSessionStatus.RUNNING, root));

        gateway.tmuxAlive = false;
        service.poll("failed");
        service.poll("creating");
        service.poll("missing-tmux");
        gateway.tmuxAlive = true;
        gateway.ttydReady = false;
        service.poll("terminal");

        assertEquals(CodexSessionStatus.CREATE_FAILED, store.get("failed").orElseThrow().getStatus());
        assertEquals(CodexSessionStatus.CREATING, store.get("creating").orElseThrow().getStatus());
        assertEquals(CodexSessionStatus.RUNNING, store.get("missing-tmux").orElseThrow().getStatus());
        assertEquals(CodexSessionStatus.RUNNING, store.get("terminal").orElseThrow().getStatus());
    }

    @Test
    void pollPreservesIdleWhenNoJsonlAndTerminalIsUnavailable() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("workspace"));
        FakeGateway gateway = new FakeGateway();
        CodexSessionStore store = new CodexSessionStore(tempDir.resolve("registry"), new ObjectMapper());
        CodexSessionService service = service(root, gateway, tempDir.resolve("sessions"), store);
        store.put(record("bad-ttyd", CodexSessionStatus.IDLE, root));

        gateway.tmuxAlive = true;
        gateway.ttydReady = false;
        service.poll("bad-ttyd");

        assertEquals(CodexSessionStatus.IDLE, store.get("bad-ttyd").orElseThrow().getStatus());
    }

    @Test
    void createRetriesAndCleansUpTtydReadinessFailures() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("workspace"));
        FakeGateway gateway = new FakeGateway();
        gateway.readyAfterStarts = 3;
        CodexSessionService service = fastService(root, gateway, tempDir.resolve("sessions"));

        CodexSessionView created = service.create(new CreateCodexSessionRequest("retry", root.toString(), null));

        assertEquals(CodexSessionStatus.IDLE, created.status());
        assertEquals(3, gateway.startedTtydCount);
        assertEquals(List.of(1001L, 1002L), gateway.killedPids);
    }

    @Test
    void createMarksSessionFailedAfterThreeTtydReadinessFailures() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("workspace"));
        FakeGateway gateway = new FakeGateway();
        gateway.readyAfterStarts = Integer.MAX_VALUE;
        CodexSessionStore store = new CodexSessionStore(tempDir.resolve("registry"), new ObjectMapper());
        CodexSessionService service = fastService(root, gateway, tempDir.resolve("sessions"), store);

        assertThrows(CodexSessionException.class,
                () -> service.create(new CreateCodexSessionRequest("fail", root.toString(), null)));

        assertEquals(3, gateway.startedTtydCount);
        assertEquals(List.of(1001L, 1002L, 1003L), gateway.killedPids);
        assertEquals(CodexSessionStatus.CREATE_FAILED, store.list().getFirst().getStatus());
    }

    @Test
    void ambiguousJsonlMatchRemainsVisibleOnSession() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("workspace"));
        Path sessions = Files.createDirectories(tempDir.resolve("sessions"));
        Path first = sessions.resolve("a/thread-a.jsonl");
        Path second = sessions.resolve("b/thread-b.jsonl");
        writeJsonl(first, root, "thread-a");
        writeJsonl(second, root, "thread-b");
        Files.setLastModifiedTime(first, java.nio.file.attribute.FileTime.from(CLOCK.instant().plusSeconds(1)));
        Files.setLastModifiedTime(second, java.nio.file.attribute.FileTime.from(CLOCK.instant().plusSeconds(1)));
        FakeGateway gateway = new FakeGateway();
        gateway.readyAfterStarts = 0;
        CodexSessionStore store = new CodexSessionStore(tempDir.resolve("registry"), new ObjectMapper());
        CodexSessionService service = service(root, gateway, sessions, store);
        store.put(record("ambiguous", CodexSessionStatus.RUNNING, root));

        service.poll("ambiguous");

        CodexSessionRecord updated = store.get("ambiguous").orElseThrow();
        assertEquals(CodexSessionStatus.RUNNING, updated.getStatus());
        assertTrue(updated.getErrorMessage().contains("Multiple Codex session candidates"));
    }

    @Test
    void pollPersistsCompletedSummaryAndClearsWaitingAfterUserMessage() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("workspace"));
        Path sessions = Files.createDirectories(tempDir.resolve("sessions"));
        Path jsonl = sessions.resolve("rollout-2026-thread-waiting.jsonl");
        writeJsonl(jsonl, root, "thread-waiting");
        Files.writeString(jsonl, """
                {"thread_id":"thread-waiting","turn_context":{"cwd":"%s"}}
                {"type":"event_msg","timestamp":"2026-06-11T02:00:01Z","payload":{"type":"task_complete","last_agent_message":"Please choose next step"}}
                """.formatted(root));
        Files.setLastModifiedTime(jsonl, java.nio.file.attribute.FileTime.from(CLOCK.instant().plusSeconds(1)));
        FakeGateway gateway = new FakeGateway();
        gateway.tmuxAlive = true;
        gateway.ttydReady = true;
        gateway.readyAfterStarts = 0;
        CodexSessionStore store = new CodexSessionStore(tempDir.resolve("registry"), new ObjectMapper());
        CodexSessionService service = service(root, gateway, sessions, store);
        store.put(record("waiting", CodexSessionStatus.RUNNING, root));

        service.poll("waiting");

        CodexSessionRecord waiting = store.get("waiting").orElseThrow();
        assertEquals(CodexSessionStatus.COMPLETED, waiting.getStatus());
        assertTrue(waiting.isWaitingMarker());
        assertEquals("Please choose next step", waiting.getLastAssistantMessage());
        assertTrue(waiting.getLastProcessedJsonlSize() > 0);
        assertEquals("2026-06-11T02:00:01Z", waiting.getLastRelevantEventTimestamp());

        Files.writeString(jsonl, """
                {"type":"event_msg","timestamp":"2026-06-11T02:00:02Z","payload":{"type":"user_message","message":"continue"}}
                """, java.nio.file.StandardOpenOption.APPEND);
        service.poll("waiting");

        CodexSessionRecord running = store.get("waiting").orElseThrow();
        assertEquals(CodexSessionStatus.RUNNING, running.getStatus());
        assertTrue(!running.isWaitingMarker());
        assertEquals("Please choose next step", running.getLastAssistantMessage());
        assertEquals("2026-06-11T02:00:02Z", running.getLastRelevantEventTimestamp());
    }

    @Test
    void pollKeepsCompletedStateWhenJsonlParseFails() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("workspace"));
        Path sessions = Files.createDirectories(tempDir.resolve("sessions"));
        Path jsonl = sessions.resolve("rollout-2026-thread-completed.jsonl");
        Files.createDirectories(jsonl.getParent());
        Files.writeString(jsonl, """
                {"thread_id":"thread-completed","turn_context":{"cwd":"%s"}}
                {"type":"event_msg","timestamp":"2026-06-11T02:00:01Z","payload":{"type":"task_complete","last_agent_message":"Please choose next step"}}
                """.formatted(root));
        long processedSize = Files.size(jsonl);
        Files.writeString(jsonl, "{\"bad\":\n", java.nio.file.StandardOpenOption.APPEND);
        CodexSessionStore store = new CodexSessionStore(tempDir.resolve("registry"), new ObjectMapper());
        CodexSessionRecord completed = record("completed-parse-error", CodexSessionStatus.COMPLETED, root);
        completed.setThreadId("thread-completed");
        completed.setJsonlPath(jsonl.toString());
        completed.setWaitingMarker(true);
        completed.setLastAssistantMessage("Please choose next step");
        completed.setLastProcessedJsonlSize(processedSize);
        completed.setLastRelevantEventTimestamp("2026-06-11T02:00:01Z");
        store.put(completed);
        CodexSessionService service = service(root, new FakeGateway(), sessions, store);

        service.poll("completed-parse-error");

        CodexSessionRecord updated = store.get("completed-parse-error").orElseThrow();
        assertEquals(CodexSessionStatus.COMPLETED, updated.getStatus());
        assertTrue(updated.isWaitingMarker());
        assertEquals(processedSize, updated.getLastProcessedJsonlSize());
        assertEquals("2026-06-11T02:00:01Z", updated.getLastRelevantEventTimestamp());
        assertEquals("JSONL parse error", updated.getErrorMessage());
    }

    @Test
    void pollDoesNotReuseJsonlAlreadyBoundToAnotherSession() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("workspace"));
        Path sessions = Files.createDirectories(tempDir.resolve("sessions"));
        Path jsonl = sessions.resolve("rollout-2026-06-11T02-00-01-019eaaac-5984-7c80-9620-dee5a411d675.jsonl");
        writeJsonl(jsonl, root, "019eaaac-5984-7c80-9620-dee5a411d675");
        Files.setLastModifiedTime(jsonl, java.nio.file.attribute.FileTime.from(CLOCK.instant().plusSeconds(1)));
        CodexSessionStore store = new CodexSessionStore(tempDir.resolve("registry"), new ObjectMapper());
        CodexSessionRecord owner = record("owner", CodexSessionStatus.RUNNING, root);
        owner.setThreadId("019eaaac-5984-7c80-9620-dee5a411d675");
        owner.setJsonlPath(jsonl.toAbsolutePath().normalize().toString());
        store.put(owner);
        store.put(record("unbound", CodexSessionStatus.RUNNING, root));
        CodexSessionService service = service(root, new FakeGateway(), sessions, store);

        service.poll("unbound");

        CodexSessionRecord updated = store.get("unbound").orElseThrow();
        assertNull(updated.getThreadId());
        assertNull(updated.getJsonlPath());
    }

    @Test
    void pollAllBindsNewestUnclaimedJsonlToNewestUnboundSessionFirst() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("workspace"));
        Path sessions = Files.createDirectories(tempDir.resolve("sessions"));
        Path jsonl = sessions.resolve("newer-session.jsonl");
        Files.createDirectories(jsonl.getParent());
        Files.writeString(jsonl, """
                {"thread_id":"019eaaac-5984-7c80-9620-dee5a411d675","turn_context":{"cwd":"%s"}}
                {"type":"event_msg","timestamp":"2026-06-11T02:01:03Z","payload":{"type":"task_complete","last_agent_message":"new session result"}}
                """.formatted(root));
        Files.setLastModifiedTime(jsonl, java.nio.file.attribute.FileTime.from(CLOCK.instant().plusSeconds(60)));

        CodexSessionStore store = new CodexSessionStore(tempDir.resolve("registry"), new ObjectMapper());
        CodexSessionRecord older = record("older-empty", CodexSessionStatus.IDLE, root);
        older.setCreatedAt(OffsetDateTime.now(CLOCK));
        older.setUpdatedAt(OffsetDateTime.now(CLOCK));
        older.setStartedAtEpochSecond(CLOCK.instant().getEpochSecond());
        CodexSessionRecord newer = record("newer-prompt", CodexSessionStatus.IDLE, root);
        newer.setCreatedAt(OffsetDateTime.now(CLOCK).plusSeconds(60));
        newer.setUpdatedAt(OffsetDateTime.now(CLOCK).plusSeconds(60));
        newer.setStartedAtEpochSecond(CLOCK.instant().plusSeconds(60).getEpochSecond());
        store.put(older);
        store.put(newer);

        CodexSessionService service = service(root, new FakeGateway(), sessions, store);
        service.pollAll();

        CodexSessionRecord unchangedOlder = store.get("older-empty").orElseThrow();
        CodexSessionRecord boundNewer = store.get("newer-prompt").orElseThrow();
        assertNull(unchangedOlder.getThreadId());
        assertNull(unchangedOlder.getJsonlPath());
        assertEquals("019eaaac-5984-7c80-9620-dee5a411d675", boundNewer.getThreadId());
        assertEquals(CodexSessionStatus.COMPLETED, boundNewer.getStatus());
        assertEquals("new session result", boundNewer.getLastAssistantMessage());
    }

    @Test
    void ensureTerminalRestartsTtydWhenTmuxStillExists() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("workspace"));
        CodexSessionStore store = new CodexSessionStore(tempDir.resolve("registry"), new ObjectMapper());
        CodexSessionRecord record = record("restore-ttyd", CodexSessionStatus.IDLE, root);
        record.setTtydPid(777L);
        store.put(record);
        FakeGateway gateway = new FakeGateway();
        gateway.tmuxAlive = true;
        gateway.readyAfterStarts = 1;
        CodexSessionService service = service(root, gateway, tempDir.resolve("sessions"), store);

        CodexSessionView restored = service.ensureTerminal("restore-ttyd");

        assertEquals("http://127.0.0.1:49152/", restored.ttydUrl());
        assertEquals(List.of(777L), gateway.killedPids);
        assertEquals(1, gateway.startedTtydCount);
        assertTrue(gateway.startedResumeTmux.isEmpty());
        assertEquals(CodexSessionStatus.IDLE, store.get("restore-ttyd").orElseThrow().getStatus());
    }

    @Test
    void ensureTerminalResumesCodexWhenTmuxIsGoneAndThreadIdExists() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("workspace"));
        CodexSessionStore store = new CodexSessionStore(tempDir.resolve("registry"), new ObjectMapper());
        CodexSessionRecord record = record("resume", CodexSessionStatus.COMPLETED, root);
        record.setThreadId("thread-resume");
        store.put(record);
        FakeGateway gateway = new FakeGateway();
        gateway.tmuxAlive = false;
        gateway.readyAfterStarts = 1;
        CodexSessionService service = service(root, gateway, tempDir.resolve("sessions"), store);

        CodexSessionView restored = service.ensureTerminal("resume");

        assertEquals("http://127.0.0.1:49152/", restored.ttydUrl());
        assertEquals(List.of("phone-agent-codex-resume:thread-resume"), gateway.startedResumeTmux);
        assertEquals(CodexSessionStatus.COMPLETED, store.get("resume").orElseThrow().getStatus());
    }

    @Test
    void ensureTerminalRejectsUnresumableSessionWithoutChangingStatus() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("workspace"));
        CodexSessionStore store = new CodexSessionStore(tempDir.resolve("registry"), new ObjectMapper());
        store.put(record("missing-thread", CodexSessionStatus.IDLE, root));
        FakeGateway gateway = new FakeGateway();
        gateway.tmuxAlive = false;
        CodexSessionService service = service(root, gateway, tempDir.resolve("sessions"), store);

        CodexSessionException failure = assertThrows(CodexSessionException.class,
                () -> service.ensureTerminal("missing-thread"));

        assertEquals("CODEX_SESSION_NOT_RESUMABLE", failure.error());
        assertEquals(CodexSessionStatus.IDLE, store.get("missing-thread").orElseThrow().getStatus());
        assertTrue(gateway.startedResumeTmux.isEmpty());
    }

    private CodexSessionService service(Path allowedRoot, FakeGateway gateway, Path sessionsDir) {
        return service(allowedRoot, gateway, sessionsDir, new CodexSessionStore(tempDir.resolve("registry"), new ObjectMapper()));
    }

    private CodexSessionService service(Path allowedRoot, FakeGateway gateway, Path sessionsDir, CodexSessionStore store) {
        return service(allowedRoot, gateway, sessionsDir, store, 5000, 100);
    }

    private CodexSessionService fastService(Path allowedRoot, FakeGateway gateway, Path sessionsDir) {
        return fastService(allowedRoot, gateway, sessionsDir, new CodexSessionStore(tempDir.resolve("registry"), new ObjectMapper()));
    }

    private CodexSessionService fastService(Path allowedRoot, FakeGateway gateway, Path sessionsDir, CodexSessionStore store) {
        return service(allowedRoot, gateway, sessionsDir, store, 1, 1);
    }

    private CodexSessionService service(Path allowedRoot, FakeGateway gateway, Path sessionsDir, CodexSessionStore store,
                                        long readinessTimeoutMillis, long readinessSleepMillis) {
        PhoneAgentProperties properties = new PhoneAgentProperties();
        properties.setRuntimeDir(tempDir.resolve("runtime"));
        properties.getCodex().setAllowedWorkspaceRoots(List.of(allowedRoot));
        properties.getCodex().setSessionsDir(sessionsDir);
        properties.getCodex().setRegistryDir(tempDir.resolve("registry"));
        return new CodexSessionService(properties, store, gateway, new CodexJsonlScanner(sessionsDir, new ObjectMapper()),
                CLOCK, 3, readinessTimeoutMillis, readinessSleepMillis);
    }

    private static CodexSessionRecord record(String id, CodexSessionStatus status, Path cwd) {
        CodexSessionRecord record = new CodexSessionRecord();
        record.setId(id);
        record.setTitle(id);
        record.setCwd(cwd.toString());
        record.setStatus(status);
        record.setTmuxName("phone-agent-codex-" + id);
        record.setTtydPid(123L);
        record.setTtydPort(49152);
        record.setTtydUrl("http://127.0.0.1:49152/");
        record.setCreatedAt(java.time.OffsetDateTime.now(CLOCK));
        record.setUpdatedAt(java.time.OffsetDateTime.now(CLOCK));
        record.setStartedAtEpochSecond(CLOCK.instant().getEpochSecond());
        return record;
    }

    private static void writeJsonl(Path path, Path cwd, String threadId) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "{\"thread_id\":\"" + threadId + "\",\"turn_context\":{\"cwd\":\"" + cwd + "\"}}\n");
    }

    private static final class FakeGateway implements CodexProcessGateway {
        boolean commandAvailable = true;
        boolean tmuxAlive = true;
        boolean ttydReady = true;
        int readyAfterStarts = 1;
        int startedTtydCount;
        int submittedPrompts;
        final List<String> unavailableCommands = new ArrayList<>();
        final List<String> startedTmux = new ArrayList<>();
        final List<String> startedPrompts = new ArrayList<>();
        final List<String> startedResumeTmux = new ArrayList<>();
        final List<Long> killedPids = new ArrayList<>();

        @Override
        public boolean commandAvailable(String command) {
            return commandAvailable && !unavailableCommands.contains(command);
        }

        @Override
        public void startTmux(String tmuxCommand, String tmuxName, Path cwd, String codexCommand, String initialPrompt) {
            startedTmux.add(tmuxName);
            startedPrompts.add(initialPrompt);
        }

        @Override
        public void startResumeTmux(String tmuxCommand, String tmuxName, Path cwd, String codexCommand, String threadId) {
            startedResumeTmux.add(tmuxName + ":" + threadId);
            tmuxAlive = true;
        }

        @Override
        public void resumeTmuxWithPrompt(String tmuxCommand, String tmuxName, Path cwd, String codexCommand, String threadId, Path promptFile) {
            submittedPrompts++;
            tmuxAlive = true;
        }

        @Override
        public TtydProcess startTtyd(String ttydCommand, String tmuxCommand, String tmuxName, int port) {
            startedTtydCount++;
            return new TtydProcess(1000L + startedTtydCount);
        }

        @Override
        public void submitPrompt(String tmuxCommand, String tmuxName, Path promptFile) {
            submittedPrompts++;
        }

        @Override
        public boolean hasTmuxSession(String tmuxCommand, String tmuxName) {
            return tmuxAlive;
        }

        @Override
        public boolean isTtydReady(long pid, int port, String tmuxName) {
            return ttydReady && startedTtydCount >= readyAfterStarts;
        }

        @Override
        public void killProcess(long pid) {
            killedPids.add(pid);
        }

        @Override
        public void killTmuxSession(String tmuxCommand, String tmuxName) {
        }

        @Override
        public int freePort() {
            return 49152;
        }
    }
}
