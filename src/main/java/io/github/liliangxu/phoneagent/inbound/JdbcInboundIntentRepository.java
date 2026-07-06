package io.github.liliangxu.phoneagent.inbound;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

@Repository
class JdbcInboundIntentRepository {
    private final JdbcTemplate jdbcTemplate;

    JdbcInboundIntentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    List<InboundIntentRecord> list() {
        return jdbcTemplate.query("""
                SELECT intent_id, source, input_type, status, input_status, failure_stage,
                       error_message, recording_file, transcript, codex_session_id,
                       recording_callback_handled, created_at, updated_at
                  FROM inbound_intents
                 ORDER BY created_at DESC, intent_id DESC
                """, this::map);
    }

    Optional<InboundIntentRecord> get(String intentId) {
        List<InboundIntentRecord> records = jdbcTemplate.query("""
                SELECT intent_id, source, input_type, status, input_status, failure_stage,
                       error_message, recording_file, transcript, codex_session_id,
                       recording_callback_handled, created_at, updated_at
                  FROM inbound_intents
                 WHERE intent_id = ?
                """, this::map, intentId);
        return records.stream().findFirst();
    }

    void save(InboundIntentRecord record) {
        jdbcTemplate.update("""
                INSERT INTO inbound_intents (
                    intent_id, source, input_type, status, input_status, failure_stage,
                    error_message, recording_file, transcript, codex_session_id,
                    recording_callback_handled, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    source = VALUES(source),
                    input_type = VALUES(input_type),
                    status = VALUES(status),
                    input_status = VALUES(input_status),
                    failure_stage = VALUES(failure_stage),
                    error_message = VALUES(error_message),
                    recording_file = VALUES(recording_file),
                    transcript = VALUES(transcript),
                    codex_session_id = VALUES(codex_session_id),
                    recording_callback_handled = VALUES(recording_callback_handled),
                    updated_at = VALUES(updated_at)
                """,
                record.intentId(),
                record.source().name(),
                record.inputType().name(),
                record.status().name(),
                record.inputStatus().name(),
                record.failureStage() == null ? null : record.failureStage().name(),
                record.errorMessage(),
                path(record.recordingFile()),
                record.transcript(),
                record.codexSessionId(),
                record.recordingCallbackHandled(),
                timestamp(record.createdAt()),
                timestamp(record.updatedAt()));
    }

    boolean claimRecordingCallback(String intentId, Path recordingFile, OffsetDateTime updatedAt) {
        int updated = jdbcTemplate.update("""
                UPDATE inbound_intents
                   SET recording_callback_handled = true,
                       recording_file = ?,
                       updated_at = ?
                 WHERE intent_id = ?
                   AND recording_callback_handled = false
                """,
                path(recordingFile),
                timestamp(updatedAt),
                intentId);
        return updated == 1;
    }

    private InboundIntentRecord map(ResultSet rs, int rowNum) throws SQLException {
        return InboundIntentRecord.restore(
                rs.getString("intent_id"),
                InboundIntentSource.valueOf(rs.getString("source")),
                InboundIntentInputType.valueOf(rs.getString("input_type")),
                InboundIntentStatus.valueOf(rs.getString("status")),
                InboundIntentInputStatus.valueOf(rs.getString("input_status")),
                nullableEnum(rs.getString("failure_stage"), InboundIntentFailureStage.class),
                rs.getString("error_message"),
                path(rs.getString("recording_file")),
                rs.getString("transcript"),
                rs.getString("codex_session_id"),
                rs.getBoolean("recording_callback_handled"),
                offset(rs.getTimestamp("created_at")),
                offset(rs.getTimestamp("updated_at"))
        );
    }

    private static String path(Path path) {
        return path == null ? null : path.toString();
    }

    private static Path path(String path) {
        return path == null || path.isBlank() ? null : Path.of(path);
    }

    private static Timestamp timestamp(OffsetDateTime value) {
        return Timestamp.from(value.toInstant());
    }

    private static OffsetDateTime offset(Timestamp timestamp) {
        return timestamp.toInstant().atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }

    private static <E extends Enum<E>> E nullableEnum(String value, Class<E> type) {
        return value == null || value.isBlank() ? null : Enum.valueOf(type, value);
    }
}
