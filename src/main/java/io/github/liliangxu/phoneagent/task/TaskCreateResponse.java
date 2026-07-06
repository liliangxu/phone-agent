package io.github.liliangxu.phoneagent.task;

import java.time.OffsetDateTime;

public record TaskCreateResponse(
        String taskId,
        Integer slot,
        TaskStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    static TaskCreateResponse from(TaskRecord task) {
        return new TaskCreateResponse(
                task.taskId(),
                task.slot(),
                task.status(),
                task.createdAt(),
                task.updatedAt());
    }
}
