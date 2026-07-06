package io.github.liliangxu.phoneagent.codex;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.liliangxu.phoneagent.codex.CodexConsoleContractSupport.constructAny;
import static io.github.liliangxu.phoneagent.codex.CodexConsoleContractSupport.invokeAny;
import static io.github.liliangxu.phoneagent.codex.CodexConsoleContractSupport.property;

class CodexJsonlScannerContractTest {
    @TempDir
    Path tempDir;

    @Test
    void uniqueCandidateMatchesByMtimeAndExactCanonicalCwd() throws Exception {
        Path sessionsDir = tempDir.resolve("sessions");
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path jsonl = writeJsonl(sessionsDir.resolve("2026/06/thread-019e.jsonl"),
                "{\"thread_id\":\"thread-019e\",\"turn_context\":{\"cwd\":\"" + json(workspace) + "\"}}\n");
        Files.setLastModifiedTime(jsonl, java.nio.file.attribute.FileTime.from(Instant.ofEpochSecond(1_780_000_010L)));

        Object scanner = newScanner(sessionsDir);
        Object match = invokeFind(scanner, sessionsDir, workspace.toRealPath(), 1_780_000_015L);

        assertEquals("thread-019e", property(match, "threadId"));
        assertEquals(jsonl.toRealPath().toString(), Path.of(property(match, "jsonlPath").toString()).toRealPath().toString());
    }

    @Test
    void rolloutFilenameTimestampPreventsBindingOlderLiveJsonlFromSameCwd() throws Exception {
        Path sessionsDir = tempDir.resolve("sessions");
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path oldRollout = writeJsonl(sessionsDir.resolve("2026/06/09/rollout-2026-06-09T12-38-02-019eaaac-5984-7c80-9620-dee5a411d675.jsonl"),
                "{\"thread_id\":\"019eaaac-5984-7c80-9620-dee5a411d675\",\"turn_context\":{\"cwd\":\"" + json(workspace) + "\"}}\n");
        Files.setLastModifiedTime(oldRollout, java.nio.file.attribute.FileTime.from(Instant.parse("2026-06-11T05:40:00Z")));

        Object scanner = newScanner(sessionsDir);
        Object match = invokeFind(scanner, sessionsDir, workspace.toRealPath(), Instant.parse("2026-06-11T05:39:53Z").getEpochSecond());

        assertEquals(null, property(match, "threadId"),
                "Old rollout filenames must not match new console sessions even when the file mtime is fresh");
    }

    @Test
    void startupWindowPreventsOldUnboundSessionFromBindingMuchNewerJsonl() throws Exception {
        Path sessionsDir = tempDir.resolve("sessions");
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path jsonl = writeJsonl(sessionsDir.resolve("2026/06/11/rollout-2026-06-11T04-20-00-019eaaac-5984-7c80-9620-dee5a411d675.jsonl"),
                "{\"thread_id\":\"019eaaac-5984-7c80-9620-dee5a411d675\",\"turn_context\":{\"cwd\":\"" + json(workspace) + "\"}}\n");
        Files.setLastModifiedTime(jsonl, java.nio.file.attribute.FileTime.from(Instant.parse("2026-06-11T04:20:00Z")));

        Object scanner = newScanner(sessionsDir);
        Object match = invokeFind(scanner, sessionsDir, workspace.toRealPath(), Instant.parse("2026-06-11T04:00:00Z").getEpochSecond());

        assertEquals(null, property(match, "threadId"),
                "A stale unbound session must not bind a JSONL created well outside its startup window");
    }

    @Test
    void excludedJsonlPathsAreNotReusedByAnotherSession() throws Exception {
        Path sessionsDir = tempDir.resolve("sessions");
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path alreadyBound = writeJsonl(sessionsDir.resolve("2026/06/11/rollout-2026-06-11T04-00-01-019eaaac-5984-7c80-9620-dee5a411d675.jsonl"),
                "{\"thread_id\":\"019eaaac-5984-7c80-9620-dee5a411d675\",\"turn_context\":{\"cwd\":\"" + json(workspace) + "\"}}\n");
        Object scanner = newScanner(sessionsDir);

        Object match = invokeAny(scanner, new String[]{"match"},
                workspace.toRealPath().toString(),
                Instant.parse("2026-06-11T04:00:00Z").getEpochSecond(),
                java.util.Set.of(alreadyBound.toAbsolutePath().normalize().toString()));

        assertEquals(null, property(match, "threadId"),
                "A JSONL path that is already bound to one session must not be offered to another session");
    }

    @Test
    void rolloutFilenameReturnsFullUuidThreadId() throws Exception {
        Path sessionsDir = tempDir.resolve("sessions");
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path jsonl = writeJsonl(sessionsDir.resolve("2026/06/11/rollout-2026-06-11T13-45-00-019eaaac-5984-7c80-9620-dee5a411d675.jsonl"),
                "{\"turn_context\":{\"cwd\":\"" + json(workspace) + "\"}}\n");

        Object scanner = newScanner(sessionsDir);
        Object match = invokeFind(scanner, sessionsDir, workspace.toRealPath(), Instant.parse("2026-06-11T05:45:01Z").getEpochSecond());

        assertEquals("019eaaac-5984-7c80-9620-dee5a411d675", property(match, "threadId"));
        assertEquals(jsonl.toRealPath().toString(), Path.of(property(match, "jsonlPath").toString()).toRealPath().toString());
    }

    @Test
    void multipleMatchingCandidatesStayAmbiguousInsteadOfGuessing() throws Exception {
        Path sessionsDir = tempDir.resolve("sessions");
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        writeJsonl(sessionsDir.resolve("a/thread-a.jsonl"),
                "{\"thread_id\":\"thread-a\",\"turn_context\":{\"cwd\":\"" + json(workspace) + "\"}}\n");
        writeJsonl(sessionsDir.resolve("b/thread-b.jsonl"),
                "{\"thread_id\":\"thread-b\",\"turn_context\":{\"cwd\":\"" + json(workspace) + "\"}}\n");

        Object scanner = newScanner(sessionsDir);
        Object match = invokeFind(scanner, sessionsDir, workspace.toRealPath(), 1_780_000_000L);

        assertTrue(Boolean.TRUE.equals(property(match, "ambiguous")) || property(match, "threadId") == null,
                "Scanner must not bind a thread when multiple JSONL files match the same cwd and time window");
    }

    @Test
    void malformedJsonlKeepsPreviousWaitingState() throws Exception {
        Path jsonl = writeJsonl(tempDir.resolve("thread.jsonl"), """
                {"type":"event_msg","payload":{"type":"task_started"}}
                {"type":"event_msg","payload":{"type":"task_complete","last_agent_message":"Need your input"}}
                {"bad":
                """);
        Object scanner = newScanner(tempDir);

        Object waiting = invokeScan(scanner, jsonl, 0L, null, false);
        assertTrue(Boolean.FALSE.equals(property(waiting, "waitingMarker")),
                "Malformed JSONL must not enter waiting state from partially scanned events");
        assertEquals(null, property(waiting, "lastAssistantMessage"));
        assertEquals("JSONL parse error", property(waiting, "errorMessage"));

        Object stillWaiting = invokeScan(scanner, jsonl, 42L, "Previous assistant", true);
        assertTrue(Boolean.TRUE.equals(property(stillWaiting, "waitingMarker")),
                "Malformed JSONL must preserve the previous waiting marker");
        assertEquals("Previous assistant", property(stillWaiting, "lastAssistantMessage"));
    }

    @Test
    void waitingDetectionUsesTaskCompleteMessageThenClearsOnUserMessage() throws Exception {
        Path jsonl = writeJsonl(tempDir.resolve("thread.jsonl"), """
                {"type":"event_msg","payload":{"type":"task_started"}}
                {"type":"event_msg","payload":{"type":"task_complete","last_agent_message":"Need your input"}}
                """);
        Object scanner = newScanner(tempDir);

        Object waiting = invokeScan(scanner, jsonl, 0L, null, false);
        assertTrue(Boolean.TRUE.equals(property(waiting, "waitingMarker")),
                "task_complete with last_agent_message must enter waiting state");
        assertEquals("Need your input", property(waiting, "lastAssistantMessage"));

        Files.writeString(jsonl, """
                {"type":"event_msg","payload":{"type":"task_started"}}
                {"type":"event_msg","payload":{"type":"task_complete","last_agent_message":"Need your input"}}
                {"type":"event_msg","payload":{"type":"user_message","message":"continue"}}
                """, StandardCharsets.UTF_8);

        Object running = invokeScan(scanner, jsonl, 0L, "Need your input", true);
        assertTrue(Boolean.FALSE.equals(property(running, "waitingMarker")),
                "user_message after task_complete must clear waiting state");
    }

    @Test
    void waitingDetectionFallsBackToLatestAgentMessage() throws Exception {
        Path jsonl = writeJsonl(tempDir.resolve("thread.jsonl"), """
                {"type":"event_msg","payload":{"type":"agent_message","message":"Fallback assistant text"}}
                {"type":"event_msg","payload":{"type":"task_complete","last_agent_message":""}}
                """);
        Object scanner = newScanner(tempDir);

        Object scan = invokeScan(scanner, jsonl, 0L, null, false);

        assertTrue(Boolean.TRUE.equals(property(scan, "waitingMarker")));
        assertEquals("Fallback assistant text", property(scan, "lastAssistantMessage"));
    }

    @Test
    void agentMessageDoesNotOverrideLaterStateDrivingEvent() throws Exception {
        Path jsonl = writeJsonl(tempDir.resolve("thread.jsonl"), """
                {"type":"event_msg","payload":{"type":"task_complete","last_agent_message":"Need your input"}}
                {"type":"event_msg","payload":{"type":"user_message","message":"continue"}}
                {"type":"event_msg","payload":{"type":"agent_message","message":"Working on it"}}
                """);
        Object scanner = newScanner(tempDir);

        Object scan = invokeScan(scanner, jsonl, 0L, "Need your input", true);

        assertTrue(Boolean.FALSE.equals(property(scan, "waitingMarker")),
                "agent_message must not mask a preceding user_message state transition in the same JSONL chunk");
        assertEquals(CodexJsonlEventType.USER_MESSAGE, property(scan, "lastEventType"));
    }

    private static Object newScanner(Path sessionsDir) {
        try {
            return constructAny("CodexJsonlScanner", sessionsDir);
        } catch (AssertionError ignored) {
            return constructAny("CodexJsonlScanner");
        }
    }

    private static Object invokeFind(Object scanner, Path sessionsDir, Path cwd, long startedAtEpochSecond) {
        try {
            return invokeAny(scanner, new String[]{"findUniqueMatch", "findThread", "matchThread"},
                    sessionsDir, cwd, startedAtEpochSecond);
        } catch (AssertionError ignored) {
            return invokeAny(scanner, new String[]{"findUniqueMatch", "findThread", "matchThread"},
                    cwd, startedAtEpochSecond);
        }
    }

    private static Object invokeScan(Object scanner, Path jsonl, long lastProcessedSize,
                                     String lastAssistantMessage, boolean waitingMarker) {
        try {
            return invokeAny(scanner, new String[]{"scanWaitingState", "scan", "scanIncremental"},
                    jsonl, lastProcessedSize, lastAssistantMessage, waitingMarker);
        } catch (AssertionError ignored) {
            return invokeAny(scanner, new String[]{"scanWaitingState", "scan", "scanIncremental"},
                    jsonl, lastProcessedSize, Map.of(
                            "lastAssistantMessage", lastAssistantMessage == null ? "" : lastAssistantMessage,
                            "waitingMarker", waitingMarker
                    ));
        }
    }

    private static Path writeJsonl(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    private static String json(Path path) {
        return path.toAbsolutePath().normalize().toString().replace("\\", "\\\\");
    }
}
