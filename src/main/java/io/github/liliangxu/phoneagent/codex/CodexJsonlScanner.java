package io.github.liliangxu.phoneagent.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import io.github.liliangxu.phoneagent.config.PhoneAgentProperties;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads Codex rollout JSONL files for Console-created sessions. The scanner is
 * intentionally conservative: it binds a session only when exactly one JSONL
 * candidate matches the session cwd and startup window.
 */
@Component
public class CodexJsonlScanner {
    private static final long STARTUP_SKEW_SECONDS = 5;
    private static final long STARTUP_MATCH_WINDOW_SECONDS = 300;
    private static final Pattern THREAD_ID_IN_ROLLOUT = Pattern.compile("rollout-\\d{4}-\\d{2}-\\d{2}T\\d{2}-\\d{2}-\\d{2}-([0-9a-fA-F-]{20,})\\.jsonl$");
    private static final Pattern ROLLOUT_TIMESTAMP = Pattern.compile("rollout-(\\d{4}-\\d{2}-\\d{2}T\\d{2}-\\d{2}-\\d{2})-[0-9a-fA-F-]{20,}\\.jsonl$");
    private static final DateTimeFormatter ROLLOUT_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss");

    private final Path sessionsDir;
    private final ObjectMapper objectMapper;

    @Autowired
    public CodexJsonlScanner(PhoneAgentProperties properties, ObjectMapper objectMapper) {
        this(properties.getCodex().getSessionsDir(), objectMapper);
    }

    CodexJsonlScanner(Path sessionsDir, ObjectMapper objectMapper) {
        this.sessionsDir = sessionsDir;
        this.objectMapper = objectMapper;
    }

    CodexJsonlScanner(Path sessionsDir) {
        this(sessionsDir, new ObjectMapper());
    }

    CodexJsonlScanner() {
        this(Path.of(System.getProperty("user.home"), ".codex", "sessions"), new ObjectMapper());
    }

    public MatchResult findUniqueMatch(Path sessionsDir, Path cwd, long startedAtEpochSecond) {
        return new CodexJsonlScanner(sessionsDir, objectMapper).match(cwd.toString(), startedAtEpochSecond);
    }

    public MatchResult findUniqueMatch(Path cwd, long startedAtEpochSecond) {
        return match(cwd.toString(), startedAtEpochSecond);
    }

    /**
     * Resolves a Codex thread id from a known JSONL path. This is used when a
     * saved console session lost its tmux process but still has enough rollout
     * metadata to run `codex resume`.
     */
    public Optional<String> resolveThreadId(Path jsonl) {
        String threadId = threadId(jsonl);
        if (threadId != null) {
            return Optional.of(threadId);
        }
        if (!Files.isRegularFile(jsonl)) {
            return Optional.empty();
        }
        try (Stream<String> lines = Files.lines(jsonl).limit(80)) {
            for (String line : lines.toList()) {
                try {
                    threadId = findThreadId(objectMapper.readTree(line));
                    if (threadId != null) {
                        return Optional.of(threadId);
                    }
                } catch (IOException ignored) {
                    // Bad early lines are ignored; later metadata may still identify the thread.
                }
            }
        } catch (IOException ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    public MatchResult match(String cwd, long startedAtEpochSecond) {
        return match(cwd, startedAtEpochSecond, Set.of());
    }

    /**
     * Binds a managed Codex session to the rollout JSONL created by the Codex
     * TUI that was started for that session. The match is bounded to a short
     * startup window and excludes already-claimed JSONL paths so stale unbound
     * sessions cannot steal a newer Codex thread from another session.
     */
    public MatchResult match(String cwd, long startedAtEpochSecond, Set<String> excludedJsonlPaths) {
        if (!Files.isDirectory(sessionsDir)) {
            return MatchResult.none();
        }
        long lowerBoundMillis = Instant.ofEpochSecond(startedAtEpochSecond).minusSeconds(STARTUP_SKEW_SECONDS).toEpochMilli();
        long upperBoundMillis = Instant.ofEpochSecond(startedAtEpochSecond).plusSeconds(STARTUP_MATCH_WINDOW_SECONDS).toEpochMilli();
        long startedAtMillis = Instant.ofEpochSecond(startedAtEpochSecond).toEpochMilli();
        List<CodexSessionFile> matches = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(sessionsDir)) {
            paths
                    .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .filter(path -> timestampWithinWindow(path, lowerBoundMillis, upperBoundMillis))
                    .filter(path -> !excluded(path, excludedJsonlPaths))
                    .forEach(path -> candidate(path, cwd).ifPresent(matches::add));
        } catch (IOException ignored) {
            return MatchResult.none();
        }
        if (matches.size() == 1) {
            return MatchResult.single(matches.getFirst());
        }
        if (matches.isEmpty()) {
            return MatchResult.none();
        }
        matches.sort(Comparator.comparingLong(file -> Math.abs(safeTimestampMillis(file.path()) - startedAtMillis)));
        long bestDistance = Math.abs(safeTimestampMillis(matches.getFirst().path()) - startedAtMillis);
        long tiedBest = matches.stream()
                .filter(file -> Math.abs(safeTimestampMillis(file.path()) - startedAtMillis) == bestDistance)
                .count();
        return tiedBest == 1 ? MatchResult.single(matches.getFirst()) : MatchResult.ambiguousResult();
    }

    public WaitingScanResult scanWaiting(CodexSessionRecord record) {
        if (record.getJsonlPath() == null || record.getJsonlPath().isBlank()) {
            return WaitingScanResult.noChange(record, record.getLastProcessedJsonlSize(), null);
        }
        Path path = Path.of(record.getJsonlPath());
        if (!Files.isRegularFile(path)) {
            return WaitingScanResult.noChange(record, record.getLastProcessedJsonlSize(), "JSONL file not found");
        }
        long size;
        try {
            size = Files.size(path);
        } catch (IOException e) {
            return WaitingScanResult.noChange(record, record.getLastProcessedJsonlSize(), "Failed to read JSONL size");
        }
        long offset = Math.min(record.getLastProcessedJsonlSize(), size);
        if (offset == size) {
            return WaitingScanResult.noChange(record, size, null);
        }

        boolean waiting = record.isWaitingMarker();
        String assistant = record.getLastAssistantMessage();
        String fallbackAssistant = assistant;
        String eventTimestamp = record.getLastRelevantEventTimestamp();
        String parseError = null;
        CodexJsonlEventType lastEventType = CodexJsonlEventType.NONE;
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            file.seek(offset);
            String line;
            while ((line = file.readLine()) != null) {
                String utf8 = new String(line.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
                try {
                    JsonNode root = objectMapper.readTree(utf8);
                    if (!"event_msg".equals(root.path("type").asText())) {
                        continue;
                    }
                    JsonNode payload = root.path("payload");
                    String type = payload.path("type").asText();
                    if ("agent_message".equals(type)) {
                        String message = text(payload.path("message"));
                        if (message != null) {
                            fallbackAssistant = message;
                        }
                    } else if ("task_complete".equals(type)) {
                        String message = text(payload.path("last_agent_message"));
                        if (message == null) {
                            message = fallbackAssistant;
                        }
                        if (message != null) {
                            waiting = true;
                            assistant = message;
                            eventTimestamp = root.path("timestamp").asText(null);
                            lastEventType = CodexJsonlEventType.TASK_COMPLETE;
                        }
                    } else if ("task_started".equals(type)) {
                        waiting = false;
                        eventTimestamp = root.path("timestamp").asText(null);
                        lastEventType = CodexJsonlEventType.TASK_STARTED;
                    } else if ("user_message".equals(type)) {
                        waiting = false;
                        eventTimestamp = root.path("timestamp").asText(null);
                        lastEventType = CodexJsonlEventType.USER_MESSAGE;
                    }
                } catch (IOException e) {
                    parseError = "JSONL parse error";
                }
            }
        } catch (IOException e) {
            return WaitingScanResult.noChange(record, offset, "Failed to scan JSONL");
        }
        if (parseError != null) {
            return WaitingScanResult.noChange(record, record.getLastProcessedJsonlSize(), parseError);
        }
        return new WaitingScanResult(waiting, assistant, size, eventTimestamp, parseError, lastEventType);
    }

    public WaitingScanResult scanWaitingState(Path jsonl, Long lastProcessedSize, String lastAssistantMessage, Boolean waitingMarker) {
        CodexSessionRecord record = new CodexSessionRecord();
        record.setJsonlPath(jsonl.toString());
        record.setLastProcessedJsonlSize(lastProcessedSize == null ? 0L : lastProcessedSize);
        record.setLastAssistantMessage(lastAssistantMessage);
        record.setWaitingMarker(Boolean.TRUE.equals(waitingMarker));
        return scanWaiting(record);
    }

    private Optional<CodexSessionFile> candidate(Path path, String cwd) {
        String threadId = threadId(path);
        boolean cwdMatched = false;
        try (Stream<String> lines = Files.lines(path).limit(80)) {
            for (String line : lines.toList()) {
                try {
                    JsonNode root = objectMapper.readTree(line);
                    if (samePath(cwd, findCwd(root))) {
                        cwdMatched = true;
                    }
                    if (threadId == null) {
                        threadId = findThreadId(root);
                    }
                    if (cwdMatched && threadId != null) {
                        return Optional.of(new CodexSessionFile(threadId, path));
                    }
                } catch (IOException ignored) {
                    // Bad early lines disqualify only that line, not the file.
                }
            }
        } catch (IOException ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static boolean timestampWithinWindow(Path path, long lowerBoundMillis, long upperBoundMillis) {
        try {
            long timestampMillis = timestampMillis(path);
            return timestampMillis >= lowerBoundMillis && timestampMillis <= upperBoundMillis;
        } catch (IOException e) {
            return false;
        }
    }

    private static long timestampMillis(Path path) throws IOException {
        Long rolloutTimestampMillis = rolloutTimestampMillis(path);
        return rolloutTimestampMillis == null ? Files.getLastModifiedTime(path).toMillis() : rolloutTimestampMillis;
    }

    private static long safeTimestampMillis(Path path) {
        try {
            return timestampMillis(path);
        } catch (IOException e) {
            return Long.MAX_VALUE;
        }
    }

    private static boolean excluded(Path path, Set<String> excludedJsonlPaths) {
        if (excludedJsonlPaths == null || excludedJsonlPaths.isEmpty()) {
            return false;
        }
        String normalized = path.toAbsolutePath().normalize().toString();
        return excludedJsonlPaths.contains(normalized) || excludedJsonlPaths.contains(path.toString());
    }

    private static Long rolloutTimestampMillis(Path path) {
        Matcher matcher = ROLLOUT_TIMESTAMP.matcher(path.getFileName().toString());
        if (!matcher.matches()) {
            return null;
        }
        try {
            return LocalDateTime.parse(matcher.group(1), ROLLOUT_TIMESTAMP_FORMAT)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String threadId(Path path) {
        Matcher matcher = THREAD_ID_IN_ROLLOUT.matcher(path.getFileName().toString());
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String findCwd(JsonNode root) {
        String direct = text(root.path("cwd"));
        if (direct != null) {
            return direct;
        }
        String topLevelTurnContext = text(root.path("turn_context").path("cwd"));
        if (topLevelTurnContext != null) {
            return topLevelTurnContext;
        }
        JsonNode payload = root.path("payload");
        String payloadCwd = text(payload.path("cwd"));
        if (payloadCwd != null) {
            return payloadCwd;
        }
        return text(payload.path("turn_context").path("cwd"));
    }

    private static String findThreadId(JsonNode root) {
        String id = text(root.path("id"));
        if (id != null) {
            return id;
        }
        id = text(root.path("thread_id"));
        if (id != null) {
            return id;
        }
        JsonNode payload = root.path("payload");
        id = text(payload.path("id"));
        return id == null ? text(payload.path("thread_id")) : id;
    }

    private static boolean samePath(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        if (expected.equals(actual)) {
            return true;
        }
        try {
            return Path.of(expected).toRealPath().equals(Path.of(actual).toRealPath());
        } catch (IOException e) {
            return Path.of(expected).toAbsolutePath().normalize().equals(Path.of(actual).toAbsolutePath().normalize());
        }
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value;
    }

    public record CodexSessionFile(String threadId, Path path) {
    }

    public record MatchResult(boolean matched, boolean ambiguousMatch, CodexSessionFile file) {
        static MatchResult single(CodexSessionFile file) {
            return new MatchResult(true, false, file);
        }

        static MatchResult none() {
            return new MatchResult(false, false, null);
        }

        static MatchResult ambiguousResult() {
            return new MatchResult(false, true, null);
        }

        public boolean ambiguous() {
            return ambiguousMatch;
        }

        public String threadId() {
            return file == null ? null : file.threadId();
        }

        public String jsonlPath() {
            return file == null ? null : file.path().toString();
        }
    }

    public record WaitingScanResult(
            boolean waiting,
            String lastAssistantMessage,
            long lastProcessedJsonlSize,
            String lastRelevantEventTimestamp,
            String errorMessage,
            CodexJsonlEventType lastEventType
    ) {
        static WaitingScanResult noChange(CodexSessionRecord record, long size, String errorMessage) {
            return new WaitingScanResult(record.isWaitingMarker(), record.getLastAssistantMessage(),
                    size, record.getLastRelevantEventTimestamp(), errorMessage, CodexJsonlEventType.NONE);
        }

        public boolean waitingMarker() {
            return waiting;
        }
    }
}
