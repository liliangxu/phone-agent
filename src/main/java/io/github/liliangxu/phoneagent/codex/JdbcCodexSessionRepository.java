package io.github.liliangxu.phoneagent.codex;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * MySQL-backed storage adapter for Console-owned Codex sessions. It deliberately
 * mirrors CodexSessionRecord fields so the service layer can keep its existing
 * update semantics while persistence moves away from JSON files.
 */
@Repository
class JdbcCodexSessionRepository {
    private final JdbcTemplate jdbcTemplate;

    JdbcCodexSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    List<CodexSessionRecord> list() {
        return jdbcTemplate.query("""
                SELECT *
                  FROM codex_sessions
                 ORDER BY created_at, id
                """, this::map);
    }

    Optional<CodexSessionRecord> get(String id) {
        List<CodexSessionRecord> records = jdbcTemplate.query("""
                SELECT *
                  FROM codex_sessions
                 WHERE id = ?
                """, this::map, id);
        return records.stream().findFirst();
    }

    boolean exists(String id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM codex_sessions WHERE id = ?",
                Integer.class,
                id);
        return count != null && count > 0;
    }

    void save(CodexSessionRecord record) {
        jdbcTemplate.update("""
                INSERT INTO codex_sessions (
                    id, title, cwd, status, tmux_name, ttyd_port, ttyd_pid, ttyd_url,
                    thread_id, jsonl_path, waiting_marker, last_assistant_message,
                    last_processed_jsonl_size, last_relevant_event_timestamp,
                    initial_prompt_submitted, started_at_epoch_second, error_message,
                    phone_bridge_error_code, phone_bridge_error_message, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    title = VALUES(title),
                    cwd = VALUES(cwd),
                    status = VALUES(status),
                    tmux_name = VALUES(tmux_name),
                    ttyd_port = VALUES(ttyd_port),
                    ttyd_pid = VALUES(ttyd_pid),
                    ttyd_url = VALUES(ttyd_url),
                    thread_id = VALUES(thread_id),
                    jsonl_path = VALUES(jsonl_path),
                    waiting_marker = VALUES(waiting_marker),
                    last_assistant_message = VALUES(last_assistant_message),
                    last_processed_jsonl_size = VALUES(last_processed_jsonl_size),
                    last_relevant_event_timestamp = VALUES(last_relevant_event_timestamp),
                    initial_prompt_submitted = VALUES(initial_prompt_submitted),
                    started_at_epoch_second = VALUES(started_at_epoch_second),
                    error_message = VALUES(error_message),
                    phone_bridge_error_code = VALUES(phone_bridge_error_code),
                    phone_bridge_error_message = VALUES(phone_bridge_error_message),
                    updated_at = VALUES(updated_at)
                """,
                record.getId(),
                record.getTitle(),
                record.getCwd(),
                record.getStatus().name(),
                record.getTmuxName(),
                record.getTtydPort(),
                record.getTtydPid(),
                record.getTtydUrl(),
                record.getThreadId(),
                record.getJsonlPath(),
                record.isWaitingMarker(),
                record.getLastAssistantMessage(),
                record.getLastProcessedJsonlSize(),
                record.getLastRelevantEventTimestamp(),
                record.isInitialPromptSubmitted(),
                record.getStartedAtEpochSecond(),
                record.getErrorMessage(),
                record.getPhoneBridgeErrorCode(),
                record.getPhoneBridgeErrorMessage(),
                timestamp(record.getCreatedAt()),
                timestamp(record.getUpdatedAt()));
    }

    private CodexSessionRecord map(ResultSet rs, int rowNum) throws SQLException {
        CodexSessionRecord record = new CodexSessionRecord();
        record.setId(rs.getString("id"));
        record.setTitle(rs.getString("title"));
        record.setCwd(rs.getString("cwd"));
        record.setStatus(CodexSessionStatus.fromPersisted(rs.getString("status")));
        record.setTmuxName(rs.getString("tmux_name"));
        record.setTtydPort(boxedInt(rs, "ttyd_port"));
        record.setTtydPid(boxedLong(rs, "ttyd_pid"));
        record.setTtydUrl(rs.getString("ttyd_url"));
        record.setThreadId(rs.getString("thread_id"));
        record.setJsonlPath(rs.getString("jsonl_path"));
        record.setWaitingMarker(rs.getBoolean("waiting_marker"));
        record.setLastAssistantMessage(rs.getString("last_assistant_message"));
        record.setLastProcessedJsonlSize(rs.getLong("last_processed_jsonl_size"));
        record.setLastRelevantEventTimestamp(rs.getString("last_relevant_event_timestamp"));
        record.setInitialPromptSubmitted(rs.getBoolean("initial_prompt_submitted"));
        record.setStartedAtEpochSecond(rs.getLong("started_at_epoch_second"));
        record.setErrorMessage(rs.getString("error_message"));
        record.setPhoneBridgeErrorCode(rs.getString("phone_bridge_error_code"));
        record.setPhoneBridgeErrorMessage(rs.getString("phone_bridge_error_message"));
        record.setCreatedAt(offset(rs.getTimestamp("created_at")));
        record.setUpdatedAt(offset(rs.getTimestamp("updated_at")));
        return record;
    }

    private static Timestamp timestamp(OffsetDateTime value) {
        return Timestamp.from(value.toInstant());
    }

    private static OffsetDateTime offset(Timestamp timestamp) {
        return timestamp.toInstant().atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }

    private static Integer boxedInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Long boxedLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
