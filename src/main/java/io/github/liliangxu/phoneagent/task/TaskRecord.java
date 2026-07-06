package io.github.liliangxu.phoneagent.task;

import java.nio.file.Path;
import java.time.OffsetDateTime;

/**
 * Mutable task state guarded by TaskService's lock and written through to
 * MySQL after every state transition. The in-process object keeps slot
 * ownership, queue order, and callback idempotency coherent while the database
 * remains the recovery source after restart.
 */
final class TaskRecord {
    private final String taskId;
    private final String text;
    private String bridgeId;
    private TaskStatus status = TaskStatus.CREATED;
    private FailureStage failureStage;
    private String errorMessage;
    private Integer slot;
    private Path taskAudioFile;
    private Path recordingFile;
    private String replyText;
    private boolean recordingCallbackHandled;
    private final OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    TaskRecord(String taskId, String text, OffsetDateTime now) {
        this.taskId = taskId;
        this.text = text;
        this.createdAt = now;
        this.updatedAt = now;
    }

    static TaskRecord restore(
            String taskId,
            String bridgeId,
            String text,
            TaskStatus status,
            FailureStage failureStage,
            String errorMessage,
            Integer slot,
            Path taskAudioFile,
            Path recordingFile,
            String replyText,
            boolean recordingCallbackHandled,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
        TaskRecord task = new TaskRecord(taskId, text, createdAt);
        task.bridgeId = bridgeId;
        task.status = status;
        task.failureStage = failureStage;
        task.errorMessage = errorMessage;
        task.slot = slot;
        task.taskAudioFile = taskAudioFile;
        task.recordingFile = recordingFile;
        task.replyText = replyText;
        task.recordingCallbackHandled = recordingCallbackHandled;
        task.updatedAt = updatedAt;
        return task;
    }

    String taskId() {
        return taskId;
    }

    String text() {
        return text;
    }

    String bridgeId() {
        return bridgeId;
    }

    void bridgeId(String bridgeId, OffsetDateTime now) {
        this.bridgeId = bridgeId;
        markUpdated(now);
    }

    TaskStatus status() {
        return status;
    }

    FailureStage failureStage() {
        return failureStage;
    }

    String errorMessage() {
        return errorMessage;
    }

    Integer slot() {
        return slot;
    }

    Path taskAudioFile() {
        return taskAudioFile;
    }

    Path recordingFile() {
        return recordingFile;
    }

    String replyText() {
        return replyText;
    }

    boolean recordingCallbackHandled() {
        return recordingCallbackHandled;
    }

    OffsetDateTime createdAt() {
        return createdAt;
    }

    OffsetDateTime updatedAt() {
        return updatedAt;
    }

    void markUpdated(OffsetDateTime now) {
        this.updatedAt = now;
    }

    void status(TaskStatus status, OffsetDateTime now) {
        this.status = status;
        markUpdated(now);
    }

    void fail(TaskStatus failedStatus, FailureStage stage, String message, OffsetDateTime now) {
        this.status = failedStatus;
        this.failureStage = stage;
        this.errorMessage = message;
        markUpdated(now);
    }

    void clearFailure(OffsetDateTime now) {
        this.failureStage = null;
        this.errorMessage = null;
        markUpdated(now);
    }

    void slot(Integer slot, OffsetDateTime now) {
        this.slot = slot;
        markUpdated(now);
    }

    void taskAudioFile(Path taskAudioFile, OffsetDateTime now) {
        this.taskAudioFile = taskAudioFile;
        markUpdated(now);
    }

    void recordingFile(Path recordingFile, OffsetDateTime now) {
        this.recordingFile = recordingFile;
        markUpdated(now);
    }

    void replyText(String replyText, OffsetDateTime now) {
        this.replyText = replyText;
        markUpdated(now);
    }

    void markRecordingCallbackHandled(OffsetDateTime now) {
        this.recordingCallbackHandled = true;
        markUpdated(now);
    }
}
