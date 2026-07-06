package io.github.liliangxu.phoneagent.codex;

import org.springframework.dao.DuplicateKeyException;
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
 * Persistence adapter for Codex phone bridge state. Methods that participate in
 * create/renotify/cancel flows can lock bridge rows with SELECT FOR UPDATE so a
 * single waiting event never owns two live phone tasks.
 */
@Repository
class JdbcCodexPhoneBridgeRepository {
    private final JdbcTemplate jdbcTemplate;

    JdbcCodexPhoneBridgeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    Optional<CodexPhoneBridgeRecord> findByWaitingEvent(String sessionId, String threadId, String waitingEventKey) {
        List<CodexPhoneBridgeRecord> rows = jdbcTemplate.query("""
                SELECT *
                  FROM codex_phone_bridges
                 WHERE codex_session_id = ? AND thread_id = ? AND waiting_event_key = ?
                """, this::map, sessionId, threadId, waitingEventKey);
        return rows.stream().findFirst();
    }

    Optional<CodexPhoneBridgeRecord> findByWaitingEventForUpdate(String sessionId, String threadId, String waitingEventKey) {
        List<CodexPhoneBridgeRecord> rows = jdbcTemplate.query("""
                SELECT *
                  FROM codex_phone_bridges
                 WHERE codex_session_id = ? AND thread_id = ? AND waiting_event_key = ?
                 FOR UPDATE
                """, this::map, sessionId, threadId, waitingEventKey);
        return rows.stream().findFirst();
    }

    Optional<CodexPhoneBridgeRecord> findById(String bridgeId) {
        List<CodexPhoneBridgeRecord> rows = jdbcTemplate.query("""
                SELECT *
                  FROM codex_phone_bridges
                 WHERE bridge_id = ?
                """, this::map, bridgeId);
        return rows.stream().findFirst();
    }

    Optional<CodexPhoneBridgeRecord> findByIdForUpdate(String bridgeId) {
        List<CodexPhoneBridgeRecord> rows = jdbcTemplate.query("""
                SELECT *
                  FROM codex_phone_bridges
                 WHERE bridge_id = ?
                 FOR UPDATE
                """, this::map, bridgeId);
        return rows.stream().findFirst();
    }

    Optional<CodexPhoneBridgeRecord> findByTaskId(String taskId) {
        List<CodexPhoneBridgeRecord> rows = jdbcTemplate.query("""
                SELECT *
                  FROM codex_phone_bridges
                 WHERE task_id = ?
                """, this::map, taskId);
        return rows.stream().findFirst();
    }

    Optional<CodexPhoneBridgeRecord> findByTaskIdForUpdate(String taskId) {
        List<CodexPhoneBridgeRecord> rows = jdbcTemplate.query("""
                SELECT *
                  FROM codex_phone_bridges
                 WHERE task_id = ?
                 FOR UPDATE
                """, this::map, taskId);
        return rows.stream().findFirst();
    }

    List<CodexPhoneBridgeRecord> findByStatuses(List<BridgeStatus> statuses) {
        if (statuses.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", statuses.stream().map(status -> "?").toList());
        Object[] args = statuses.stream().map(Enum::name).toArray();
        return jdbcTemplate.query("""
                SELECT *
                  FROM codex_phone_bridges
                 WHERE status IN (%s)
                 ORDER BY created_at, bridge_id
                """.formatted(placeholders), this::map, args);
    }

    List<CodexPhoneBridgeRecord> findLatestForSession(String sessionId, int limit) {
        return jdbcTemplate.query("""
                SELECT *
                  FROM codex_phone_bridges
                 WHERE codex_session_id = ?
                 ORDER BY updated_at DESC, bridge_id DESC
                 LIMIT ?
                """, this::map, sessionId, limit);
    }

    CodexPhoneBridgeRecord insert(CodexPhoneBridgeRecord bridge) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO codex_phone_bridges (
                        bridge_id, codex_session_id, thread_id, waiting_event_key,
                        last_assistant_message, status, task_id, replaced_task_id, slot,
                        reply_text, error_code, error_message, cancelled_at, created_at, updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    bridge.bridgeId(),
                    bridge.codexSessionId(),
                    bridge.threadId(),
                    bridge.waitingEventKey(),
                    bridge.lastAssistantMessage(),
                    bridge.status().name(),
                    bridge.taskId(),
                    bridge.replacedTaskId(),
                    bridge.slot(),
                    bridge.replyText(),
                    bridge.errorCode(),
                    bridge.errorMessage(),
                    timestampOrNull(bridge.cancelledAt()),
                    timestamp(bridge.createdAt()),
                    timestamp(bridge.updatedAt()));
            return bridge;
        } catch (DuplicateKeyException ignored) {
            return findByWaitingEvent(bridge.codexSessionId(), bridge.threadId(), bridge.waitingEventKey()).orElseThrow();
        }
    }

    void update(CodexPhoneBridgeRecord bridge) {
        jdbcTemplate.update("""
                UPDATE codex_phone_bridges
                   SET status = ?,
                       task_id = ?,
                       replaced_task_id = ?,
                       slot = ?,
                       reply_text = ?,
                       error_code = ?,
                       error_message = ?,
                       cancelled_at = ?,
                       updated_at = ?
                 WHERE bridge_id = ?
                """,
                bridge.status().name(),
                bridge.taskId(),
                bridge.replacedTaskId(),
                bridge.slot(),
                bridge.replyText(),
                bridge.errorCode(),
                bridge.errorMessage(),
                timestampOrNull(bridge.cancelledAt()),
                timestamp(bridge.updatedAt()),
                bridge.bridgeId());
    }

    private CodexPhoneBridgeRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new CodexPhoneBridgeRecord(
                rs.getString("bridge_id"),
                rs.getString("codex_session_id"),
                rs.getString("thread_id"),
                rs.getString("waiting_event_key"),
                rs.getString("last_assistant_message"),
                BridgeStatus.valueOf(rs.getString("status")),
                rs.getString("task_id"),
                rs.getString("replaced_task_id"),
                boxedInt(rs, "slot"),
                rs.getString("reply_text"),
                rs.getString("error_code"),
                rs.getString("error_message"),
                offsetOrNull(rs.getTimestamp("cancelled_at")),
                offset(rs.getTimestamp("created_at")),
                offset(rs.getTimestamp("updated_at"))
        );
    }

    private static Integer boxedInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Timestamp timestamp(OffsetDateTime value) {
        return Timestamp.from(value.toInstant());
    }

    private static Timestamp timestampOrNull(OffsetDateTime value) {
        return value == null ? null : timestamp(value);
    }

    private static OffsetDateTime offset(Timestamp timestamp) {
        return timestamp.toInstant().atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }

    private static OffsetDateTime offsetOrNull(Timestamp timestamp) {
        return timestamp == null ? null : offset(timestamp);
    }
}
