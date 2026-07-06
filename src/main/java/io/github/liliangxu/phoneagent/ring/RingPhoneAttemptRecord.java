package io.github.liliangxu.phoneagent.ring;

import java.time.OffsetDateTime;

public record RingPhoneAttemptRecord(
        String attemptId,
        RingPhoneStatus status,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        String errorCode,
        String errorMessage,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
