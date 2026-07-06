package io.github.liliangxu.phoneagent.task;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Persists phone task and BLF slot state while TaskService keeps the in-process
 * lock and scheduling algorithm. Every TaskService mutation writes through this
 * repository before external AMI or tmux side effects are attempted.
 */
@Repository
public class JdbcTaskStateRepository {
    private final JdbcTemplate jdbcTemplate;

    public JdbcTaskStateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    List<TaskRecord> loadTasks() {
        return jdbcTemplate.query("""
                SELECT task_id, bridge_id, text, status, failure_stage, error_message, slot,
                       task_audio_file, recording_file, reply_text, recording_callback_handled,
                       created_at, updated_at
                  FROM phone_tasks
                 ORDER BY created_at, task_id
                """, this::mapTask);
    }

    List<SlotRecord> loadSlots() {
        return jdbcTemplate.query("""
                SELECT slot, status, current_task_id, started_task_id
                  FROM phone_slots
                 ORDER BY slot
                """, this::mapSlot);
    }

    void saveTask(TaskRecord task) {
        jdbcTemplate.update("""
                INSERT INTO phone_tasks (
                    task_id, bridge_id, text, status, failure_stage, error_message, slot,
                    task_audio_file, recording_file, reply_text, recording_callback_handled,
                    replaced_task_id, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?)
                ON DUPLICATE KEY UPDATE
                    bridge_id = VALUES(bridge_id),
                    text = VALUES(text),
                    status = VALUES(status),
                    failure_stage = VALUES(failure_stage),
                    error_message = VALUES(error_message),
                    slot = VALUES(slot),
                    task_audio_file = VALUES(task_audio_file),
                    recording_file = VALUES(recording_file),
                    reply_text = VALUES(reply_text),
                    recording_callback_handled = VALUES(recording_callback_handled),
                    updated_at = VALUES(updated_at)
                """,
                task.taskId(),
                task.bridgeId(),
                task.text(),
                task.status().name(),
                task.failureStage() == null ? null : task.failureStage().name(),
                task.errorMessage(),
                task.slot(),
                path(task.taskAudioFile()),
                path(task.recordingFile()),
                task.replyText(),
                task.recordingCallbackHandled(),
                timestamp(task.createdAt()),
                timestamp(task.updatedAt()));
    }

    void saveSlot(SlotRecord slot, OffsetDateTime updatedAt) {
        jdbcTemplate.update("""
                UPDATE phone_slots
                   SET status = ?, current_task_id = ?, started_task_id = ?, updated_at = ?
                 WHERE slot = ?
                """,
                slot.status().name(),
                slot.taskId(),
                slot.startedTaskId(),
                timestamp(updatedAt),
                slot.slot());
    }

    private TaskRecord mapTask(ResultSet rs, int rowNum) throws SQLException {
        return TaskRecord.restore(
                rs.getString("task_id"),
                rs.getString("bridge_id"),
                rs.getString("text"),
                TaskStatus.valueOf(rs.getString("status")),
                nullableEnum(rs.getString("failure_stage"), FailureStage.class),
                rs.getString("error_message"),
                boxedInt(rs, "slot"),
                path(rs.getString("task_audio_file")),
                path(rs.getString("recording_file")),
                rs.getString("reply_text"),
                rs.getBoolean("recording_callback_handled"),
                offset(rs.getTimestamp("created_at")),
                offset(rs.getTimestamp("updated_at"))
        );
    }

    private SlotRecord mapSlot(ResultSet rs, int rowNum) throws SQLException {
        return SlotRecord.restore(
                rs.getInt("slot"),
                SlotStatus.valueOf(rs.getString("status")),
                rs.getString("current_task_id"),
                rs.getString("started_task_id"));
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

    private static Integer boxedInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static <E extends Enum<E>> E nullableEnum(String value, Class<E> type) {
        return value == null || value.isBlank() ? null : Enum.valueOf(type, value);
    }
}
