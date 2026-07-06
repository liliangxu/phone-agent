package io.github.liliangxu.phoneagent.inbound;

import java.time.OffsetDateTime;

public record InboundIntentView(
        String intentId,
        InboundIntentSource source,
        InboundIntentInputType inputType,
        InboundIntentStatus status,
        InboundIntentInputStatus inputStatus,
        InboundIntentFailureStage failureStage,
        String errorMessage,
        String recordingFile,
        String transcript,
        String codexSessionId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    static InboundIntentView from(InboundIntentRecord record) {
        return new InboundIntentView(
                record.intentId(),
                record.source(),
                record.inputType(),
                record.status(),
                record.inputStatus(),
                record.failureStage(),
                record.errorMessage(),
                record.recordingFile() == null ? null : record.recordingFile().toString(),
                record.transcript(),
                record.codexSessionId(),
                record.createdAt(),
                record.updatedAt()
        );
    }
}
