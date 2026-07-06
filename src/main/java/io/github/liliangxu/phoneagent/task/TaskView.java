package io.github.liliangxu.phoneagent.task;

import java.time.OffsetDateTime;

public record TaskView(
        String taskId,
        String bridgeId,
        String text,
        TaskStatus status,
        FailureStage failureStage,
        String errorMessage,
        Integer slot,
        String recordingFile,
        String replyText,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    static TaskView from(TaskRecord task) {
        return new TaskView(
                task.taskId(),
                task.bridgeId(),
                task.text(),
                task.status(),
                task.failureStage(),
                task.errorMessage(),
                task.slot(),
                task.recordingFile() == null ? null : task.recordingFile().toString(),
                task.replyText(),
                task.createdAt(),
                task.updatedAt());
    }
}
