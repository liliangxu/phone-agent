package io.github.liliangxu.phoneagent.codex;

import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import io.github.liliangxu.phoneagent.config.PhoneAgentProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Coordinates Console-owned Codex sessions. The service owns validation,
 * process startup ordering, conservative Codex JSONL binding, and liveness
 * state transitions while keeping the existing phone task lifecycle untouched.
 */
@Service
public class CodexSessionService {
    private static final int MAX_TITLE_CHARS = 120;
    private static final int MAX_PROMPT_CHARS = 8000;
    private final PhoneAgentProperties properties;
    private final CodexSessionStore store;
    private final CodexProcessGateway processGateway;
    private final CodexJsonlScanner jsonlScanner;
    private final CodexPhoneBridgeService bridgeService;
    private final Clock clock;
    private final int ttydAttempts;
    private final long ttydReadyTimeoutMillis;
    private final long ttydReadySleepMillis;
    private final AtomicInteger sequence = new AtomicInteger();

    @Autowired
    public CodexSessionService(
            PhoneAgentProperties properties,
            CodexSessionStore store,
            CodexProcessGateway processGateway,
            CodexJsonlScanner jsonlScanner,
            @Lazy CodexPhoneBridgeService bridgeService,
            Clock clock
    ) {
        this(properties, store, processGateway, jsonlScanner, bridgeService, clock, 3, 5000, 100);
    }

    CodexSessionService(
            PhoneAgentProperties properties,
            CodexSessionStore store,
            CodexProcessGateway processGateway,
            CodexJsonlScanner jsonlScanner,
            Clock clock,
            int ttydAttempts,
            long ttydReadyTimeoutMillis,
            long ttydReadySleepMillis
    ) {
        this(properties, store, processGateway, jsonlScanner, null, clock, ttydAttempts, ttydReadyTimeoutMillis, ttydReadySleepMillis);
    }

    CodexSessionService(
            PhoneAgentProperties properties,
            CodexSessionStore store,
            CodexProcessGateway processGateway,
            CodexJsonlScanner jsonlScanner,
            CodexPhoneBridgeService bridgeService,
            Clock clock,
            int ttydAttempts,
            long ttydReadyTimeoutMillis,
            long ttydReadySleepMillis
    ) {
        this.properties = properties;
        this.store = store;
        this.processGateway = processGateway;
        this.jsonlScanner = jsonlScanner;
        this.bridgeService = bridgeService;
        this.clock = clock;
        this.ttydAttempts = ttydAttempts;
        this.ttydReadyTimeoutMillis = ttydReadyTimeoutMillis;
        this.ttydReadySleepMillis = ttydReadySleepMillis;
    }

    public List<CodexSessionView> list() {
        return store.list().stream().map(this::view).toList();
    }

    public Optional<CodexSessionView> get(String id) {
        return store.get(id).map(this::view);
    }

    /**
     * Ensures that a saved session has a live browser terminal before the UI
     * mounts an iframe. Terminal failures are reported to the caller and never
     * rewritten into CodexSession.status.
     */
    public CodexSessionView ensureTerminal(String id) {
        requireCommand(properties.getCodex().getTmuxCommand(), "tmux");
        requireCommand(properties.getCodex().getTtydCommand(), "ttyd");
        CodexSessionRecord snapshot = store.get(id).orElseThrow(() -> new CodexSessionNotFoundException(id));
        if (snapshot.getStatus() == CodexSessionStatus.CREATE_FAILED) {
            throw new CodexSessionException("CODEX_SESSION_CREATE_FAILED", "Session creation failed; terminal cannot be restored", HttpStatus.CONFLICT);
        }
        String tmuxName = snapshot.getTmuxName();
        boolean tmuxAlive = processGateway.hasTmuxSession(properties.getCodex().getTmuxCommand(), tmuxName);
        if (tmuxAlive
                && snapshot.getTtydPid() != null
                && snapshot.getTtydPort() != null
                && processGateway.isTtydReady(snapshot.getTtydPid(), snapshot.getTtydPort(), tmuxName)) {
            return view(snapshot);
        }
        if (snapshot.getTtydPid() != null) {
            processGateway.killProcess(snapshot.getTtydPid());
        }
        if (!tmuxAlive) {
            requireCommand(properties.getCodex().getCodexCommand(), "codex");
            String threadId = resumeThreadId(snapshot).orElseThrow(() -> new CodexSessionException(
                    "CODEX_SESSION_NOT_RESUMABLE",
                    "Session terminal is gone and no Codex thread id is available for resume",
                    HttpStatus.CONFLICT));
            processGateway.startResumeTmux(
                    properties.getCodex().getTmuxCommand(),
                    tmuxName,
                    Path.of(snapshot.getCwd()),
                    properties.getCodex().getCodexCommand(),
                    threadId
            );
            snapshot.setThreadId(threadId);
        }
        TtydStarted ttyd = startReadyTtyd(tmuxName);
        store.update(id, current -> {
            current.setTtydPort(ttyd.port());
            current.setTtydPid(ttyd.pid());
            current.setTtydUrl("http://127.0.0.1:" + ttyd.port() + "/");
            if (snapshot.getThreadId() != null && !snapshot.getThreadId().isBlank()) {
                current.setThreadId(snapshot.getThreadId());
            }
            current.setUpdatedAt(OffsetDateTime.now(clock));
            return current;
        });
        return store.get(id).map(this::view).orElseThrow(() -> new CodexSessionNotFoundException(id));
    }

    public CodexSessionView create(CreateCodexSessionRequest request) {
        CreateSpec spec = validate(request);
        requireCommand(properties.getCodex().getTmuxCommand(), "tmux");
        requireCommand(properties.getCodex().getTtydCommand(), "ttyd");
        requireCommand(properties.getCodex().getCodexCommand(), "codex");

        OffsetDateTime now = OffsetDateTime.now(clock);
        String id = nextId(now);
        String tmuxName = "phone-agent-codex-" + id;
        CodexSessionRecord record = new CodexSessionRecord();
        record.setId(id);
        record.setTitle(spec.title());
        record.setCwd(spec.cwd().toString());
        record.setStatus(CodexSessionStatus.CREATING);
        record.setTmuxName(tmuxName);
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        record.setStartedAtEpochSecond(now.toEpochSecond());
        store.put(record);

        try {
            processGateway.startTmux(properties.getCodex().getTmuxCommand(), tmuxName, spec.cwd(), properties.getCodex().getCodexCommand(), spec.initialPrompt());
            TtydStarted ttyd = startReadyTtyd(tmuxName);
            boolean promptSubmitted = spec.initialPrompt() != null;
            store.update(id, current -> {
                current.setStatus(CodexSessionStatus.IDLE);
                current.setTtydPort(ttyd.port());
                current.setTtydPid(ttyd.pid());
                current.setTtydUrl("http://127.0.0.1:" + ttyd.port() + "/");
                current.setInitialPromptSubmitted(promptSubmitted);
                current.setUpdatedAt(OffsetDateTime.now(clock));
                return current;
            });
        } catch (RuntimeException e) {
            processGateway.killTmuxSession(properties.getCodex().getTmuxCommand(), tmuxName);
            store.update(id, current -> {
                current.setStatus(CodexSessionStatus.CREATE_FAILED);
                current.setErrorMessage(e.getMessage());
                current.setUpdatedAt(OffsetDateTime.now(clock));
                return current;
            });
            throw new CodexSessionException("CODEX_PROCESS_START_FAILED", e.getMessage(), HttpStatus.SERVICE_UNAVAILABLE);
        }
        poll(id);
        return store.get(id).map(this::view).orElseThrow(() -> new CodexSessionNotFoundException(id));
    }

    public void pollAll() {
        for (CodexSessionRecord record : store.list().stream()
                // New empty sessions can remain unbound until their first Codex
                // JSONL appears. Poll newest first so an older idle session in
                // the same cwd cannot claim a JSONL created by a newer session.
                .sorted((left, right) -> right.getCreatedAt().compareTo(left.getCreatedAt()))
                .toList()) {
            poll(record.getId());
        }
    }

    void poll(String id) {
        CodexSessionRecord snapshot = store.get(id).orElse(null);
        if (snapshot == null
                || snapshot.getStatus() == CodexSessionStatus.CREATING
                || snapshot.getStatus() == CodexSessionStatus.CREATE_FAILED) {
            return;
        }
        PollResult result = computePoll(snapshot);
        store.update(id, current -> applyPollResult(current, result)).ifPresent(updated -> {
            if (bridgeService != null) {
                if (snapshot.getStatus() == CodexSessionStatus.WAITING_USER && updated.getStatus() == CodexSessionStatus.RUNNING) {
                    bridgeService.cancelCurrentWaitingBridge(snapshot);
                }
                bridgeService.reconcileWaitingSession(updated);
            }
        });
    }

    private CodexSessionView view(CodexSessionRecord record) {
        if (bridgeService == null) {
            return CodexSessionView.from(record);
        }
        return CodexSessionView.from(record, bridgeService.activeBridge(record.getId()).orElse(null), bridgeService.latestSummaries(record.getId()));
    }

    private PollResult computePoll(CodexSessionRecord record) {
        String threadId = record.getThreadId();
        String jsonlPath = record.getJsonlPath();
        String errorMessage = record.getErrorMessage();
        if (jsonlPath == null || jsonlPath.isBlank()) {
            CodexJsonlScanner.MatchResult match = jsonlScanner.match(record.getCwd(), record.getStartedAtEpochSecond(), boundJsonlPathsExcept(record.getId()));
            if (match.matched()) {
                threadId = match.file().threadId();
                jsonlPath = match.file().path().toString();
                errorMessage = null;
            } else if (match.ambiguousMatch()) {
                errorMessage = "Multiple Codex session candidates matched; waiting for a unique match";
            } else {
                return PollResult.scanned(statusWhenNoWaitingEvent(record), record.isWaitingMarker(), record.getLastAssistantMessage(),
                        record.getLastProcessedJsonlSize(), record.getLastRelevantEventTimestamp(), errorMessage, threadId, jsonlPath);
            }
        }

        CodexSessionRecord scanRecord = record.copy();
        scanRecord.setThreadId(threadId);
        scanRecord.setJsonlPath(jsonlPath);
        scanRecord.setErrorMessage(errorMessage);
        CodexJsonlScanner.WaitingScanResult scan = jsonlScanner.scanWaiting(scanRecord);
        String nextError = scan.errorMessage() == null ? errorMessage : scan.errorMessage();
        if (scan.errorMessage() != null) {
            return PollResult.scanned(record.getStatus(), record.isWaitingMarker(), record.getLastAssistantMessage(),
                    record.getLastProcessedJsonlSize(), record.getLastRelevantEventTimestamp(),
                    nextError, threadId, jsonlPath);
        }
        String nextTimestamp = scan.lastRelevantEventTimestamp();
        long nextSize = scan.lastProcessedJsonlSize();
        CodexSessionStatus status = statusFromScan(record, scan, threadId, jsonlPath, nextTimestamp, nextSize);
        boolean waitingMarker = (status == CodexSessionStatus.WAITING_USER || status == CodexSessionStatus.COMPLETED) && scan.waiting();
        return PollResult.scanned(status, waitingMarker, scan.lastAssistantMessage(),
                scan.lastProcessedJsonlSize(), scan.lastRelevantEventTimestamp(), nextError, threadId, jsonlPath);
    }

    private CodexSessionRecord applyPollResult(CodexSessionRecord record, PollResult result) {
        if (record.getStatus() == CodexSessionStatus.CREATING || record.getStatus() == CodexSessionStatus.CREATE_FAILED) {
            return record;
        }
        boolean changed = record.getStatus() != result.status();
        record.setStatus(result.status());
        if (result.threadId() != null) {
            changed |= !Objects.equals(record.getThreadId(), result.threadId());
            record.setThreadId(result.threadId());
        }
        if (result.jsonlPath() != null) {
            changed |= !Objects.equals(record.getJsonlPath(), result.jsonlPath());
            record.setJsonlPath(result.jsonlPath());
        }
        changed |= record.isWaitingMarker() != result.waitingMarker();
        record.setWaitingMarker(result.waitingMarker());
        changed |= !Objects.equals(record.getLastAssistantMessage(), result.lastAssistantMessage());
        record.setLastAssistantMessage(result.lastAssistantMessage());
        changed |= record.getLastProcessedJsonlSize() != result.lastProcessedJsonlSize();
        record.setLastProcessedJsonlSize(result.lastProcessedJsonlSize());
        if (result.lastRelevantEventTimestamp() != null) {
            changed |= !Objects.equals(record.getLastRelevantEventTimestamp(), result.lastRelevantEventTimestamp());
            record.setLastRelevantEventTimestamp(result.lastRelevantEventTimestamp());
        }
        changed |= !Objects.equals(record.getErrorMessage(), result.errorMessage());
        record.setErrorMessage(result.errorMessage());
        if (changed) {
            record.setUpdatedAt(OffsetDateTime.now(clock));
        }
        return record;
    }

    /**
     * Keeps already-observed stable states when no new JSONL event exists. New
     * sessions without Codex activity stay IDLE; RUNNING only persists after a
     * real task start, user message, or submitted prompt marked it running.
     */
    private CodexSessionStatus statusWhenNoWaitingEvent(CodexSessionRecord record) {
        return switch (record.getStatus()) {
            case RUNNING, WAITING_USER, COMPLETED -> record.getStatus();
            default -> CodexSessionStatus.IDLE;
        };
    }

    /**
     * Derives the session-facing state from Codex JSONL while honoring completed
     * waiting events. COMPLETED is a Phone Agent projection over the current
     * Codex waiting event, not a native Codex JSONL state.
     */
    private CodexSessionStatus statusFromScan(
            CodexSessionRecord record,
            CodexJsonlScanner.WaitingScanResult scan,
            String threadId,
            String jsonlPath,
            String eventTimestamp,
            long processedSize
    ) {
        if (scan.lastEventType() == CodexJsonlEventType.TASK_STARTED
                || scan.lastEventType() == CodexJsonlEventType.USER_MESSAGE) {
            return CodexSessionStatus.RUNNING;
        }
        if (scan.lastEventType() != CodexJsonlEventType.TASK_COMPLETE) {
            return statusWhenNoWaitingEvent(record);
        }
        if (waitingEventReplyInProgressOrDone(record.getId(), threadId, jsonlPath, eventTimestamp, processedSize)) {
            return statusWhenNoWaitingEvent(record);
        }
        return CodexSessionStatus.COMPLETED;
    }

    private Optional<String> resumeThreadId(CodexSessionRecord record) {
        if (record.getThreadId() != null && !record.getThreadId().isBlank()) {
            return Optional.of(record.getThreadId());
        }
        if (record.getJsonlPath() == null || record.getJsonlPath().isBlank()) {
            return Optional.empty();
        }
        return jsonlScanner.resolveThreadId(Path.of(record.getJsonlPath()));
    }

    private TtydStarted startReadyTtyd(String tmuxName) {
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < ttydAttempts; attempt++) {
            int port = processGateway.freePort();
            CodexProcessGateway.TtydProcess process = processGateway.startTtyd(
                    properties.getCodex().getTtydCommand(),
                    properties.getCodex().getTmuxCommand(),
                    tmuxName,
                    port
            );
            long deadline = System.currentTimeMillis() + ttydReadyTimeoutMillis;
            while (System.currentTimeMillis() < deadline) {
                if (processGateway.isTtydReady(process.pid(), port, tmuxName)) {
                    return new TtydStarted(port, process.pid());
                }
                sleep(ttydReadySleepMillis);
            }
            processGateway.killProcess(process.pid());
            lastFailure = new IllegalStateException("ttyd readiness timed out");
        }
        throw lastFailure == null ? new IllegalStateException("Failed to start ttyd") : lastFailure;
    }

    private CreateSpec validate(CreateCodexSessionRequest request) {
        boolean useDefaultWorkspace = request == null || request.cwd() == null || request.cwd().isBlank();
        Path rawCwd = useDefaultWorkspace ? defaultWorkspace() : Path.of(request.cwd());
        Path cwd = useDefaultWorkspace ? createAndCanonicalizeDefaultWorkspace(rawCwd) : canonical(rawCwd);
        if (!Files.isDirectory(cwd)) {
            throw validation("cwd must be an existing directory");
        }
        if (!isAllowed(cwd)) {
            throw validation("cwd must be under allowed workspace roots");
        }
        String title = request == null || request.title() == null ? "" : request.title().trim();
        if (title.isBlank()) {
            title = "Codex " + DateTimeFormatter.ofPattern("HH:mm:ss").format(OffsetDateTime.now(clock));
        }
        if (title.codePointCount(0, title.length()) > MAX_TITLE_CHARS) {
            throw validation("title must not exceed 120 code points");
        }
        String prompt = request == null || request.initialPrompt() == null || request.initialPrompt().isBlank()
                ? null
                : request.initialPrompt();
        if (prompt != null && prompt.codePointCount(0, prompt.length()) > MAX_PROMPT_CHARS) {
            throw validation("initialPrompt must not exceed 8000 code points");
        }
        return new CreateSpec(title, cwd, prompt);
    }

    private boolean isAllowed(Path cwd) {
        List<Path> roots = properties.getCodex().getAllowedWorkspaceRoots();
        if (roots == null || roots.isEmpty()) {
            return false;
        }
        for (Path root : roots) {
            Path canonicalRoot = canonical(root);
            if (cwd.equals(canonicalRoot) || cwd.startsWith(canonicalRoot)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Collects JSONL paths already claimed by other managed sessions. Codex
     * does not expose a clean TUI startup session id, so binding remains a
     * filesystem match; this exclusion prevents old unbound records from
     * claiming a rollout that was already attached to a newer session.
     */
    private Set<String> boundJsonlPathsExcept(String sessionId) {
        return store.list().stream()
                .filter(record -> !Objects.equals(record.getId(), sessionId))
                .map(CodexSessionRecord::getJsonlPath)
                .filter(path -> path != null && !path.isBlank())
                .map(path -> Path.of(path).toAbsolutePath().normalize().toString())
                .collect(Collectors.toSet());
    }

    private boolean waitingEventReplyInProgressOrDone(String sessionId, String threadId, String jsonlPath, String eventTimestamp, long processedSize) {
        if (bridgeService == null || threadId == null || threadId.isBlank()) {
            return false;
        }
        String key = waitingEventKey(jsonlPath, eventTimestamp, processedSize);
        return key != null && bridgeService.waitingEventReplyInProgressOrDone(sessionId, threadId, key);
    }

    private static String waitingEventKey(String jsonlPath, String eventTimestamp, long processedSize) {
        if (eventTimestamp != null && !eventTimestamp.isBlank()) {
            return eventTimestamp;
        }
        if (jsonlPath != null && !jsonlPath.isBlank() && processedSize > 0) {
            return jsonlPath + ":" + processedSize;
        }
        return null;
    }

    private Path defaultWorkspace() {
        return Path.of(System.getProperty("user.dir")).resolve("workspace");
    }

    private Path createAndCanonicalizeDefaultWorkspace(Path path) {
        try {
            Files.createDirectories(path);
            return canonical(path);
        } catch (IOException e) {
            throw validation("default workspace directory could not be created");
        }
    }

    private static Path canonical(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            throw validation("cwd must be an existing directory");
        }
    }

    private void requireCommand(String command, String name) {
        if (!processGateway.commandAvailable(command)) {
            throw new CodexSessionException("CODEX_COMMAND_NOT_FOUND", name + " command not found", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private String nextId(OffsetDateTime now) {
        return "cs-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(now) + "-" + "%04d".formatted(sequence.incrementAndGet());
    }

    private static CodexSessionException validation(String message) {
        return new CodexSessionException("CODEX_SESSION_VALIDATION_FAILED", message, HttpStatus.BAD_REQUEST);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for ttyd readiness", e);
        }
    }

    private record CreateSpec(String title, Path cwd, String initialPrompt) {
    }

    private record TtydStarted(int port, long pid) {
    }

    private record PollResult(
            CodexSessionStatus status,
            boolean waitingMarker,
            String lastAssistantMessage,
            long lastProcessedJsonlSize,
            String lastRelevantEventTimestamp,
            String errorMessage,
            String threadId,
            String jsonlPath
    ) {
        static PollResult scanned(
                CodexSessionStatus status,
                boolean waitingMarker,
                String lastAssistantMessage,
                long lastProcessedJsonlSize,
                String lastRelevantEventTimestamp,
                String errorMessage,
                String threadId,
                String jsonlPath
        ) {
            return new PollResult(status, waitingMarker, lastAssistantMessage, lastProcessedJsonlSize,
                    lastRelevantEventTimestamp, errorMessage, threadId, jsonlPath);
        }
    }
}
