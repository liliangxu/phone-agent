package io.github.liliangxu.phoneagent.inbound;

import java.nio.file.Path;
import java.time.OffsetDateTime;

/**
 * Mutable persistence model for user-initiated intents. It is intentionally
 * separate from phone tasks because inbound requests do not own BLF slots or
 * Codex waiting-event bridges.
 */
public class InboundIntentRecord {
    private final String intentId;
    private final InboundIntentSource source;
    private final InboundIntentInputType inputType;
    private InboundIntentStatus status;
    private InboundIntentInputStatus inputStatus;
    private InboundIntentFailureStage failureStage;
    private String errorMessage;
    private Path recordingFile;
    private String transcript;
    private String codexSessionId;
    private boolean recordingCallbackHandled;
    private final OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public InboundIntentRecord(String intentId, InboundIntentSource source, InboundIntentInputType inputType,
                               InboundIntentStatus status, InboundIntentInputStatus inputStatus,
                               OffsetDateTime createdAt) {
        this.intentId = intentId;
        this.source = source;
        this.inputType = inputType;
        this.status = status;
        this.inputStatus = inputStatus;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public static InboundIntentRecord restore(
            String intentId,
            InboundIntentSource source,
            InboundIntentInputType inputType,
            InboundIntentStatus status,
            InboundIntentInputStatus inputStatus,
            InboundIntentFailureStage failureStage,
            String errorMessage,
            Path recordingFile,
            String transcript,
            String codexSessionId,
            boolean recordingCallbackHandled,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
        InboundIntentRecord record = new InboundIntentRecord(intentId, source, inputType, status, inputStatus, createdAt);
        record.failureStage = failureStage;
        record.errorMessage = errorMessage;
        record.recordingFile = recordingFile;
        record.transcript = transcript;
        record.codexSessionId = codexSessionId;
        record.recordingCallbackHandled = recordingCallbackHandled;
        record.updatedAt = updatedAt;
        return record;
    }

    public String intentId() {
        return intentId;
    }

    public InboundIntentSource source() {
        return source;
    }

    public InboundIntentInputType inputType() {
        return inputType;
    }

    public InboundIntentStatus status() {
        return status;
    }

    public InboundIntentInputStatus inputStatus() {
        return inputStatus;
    }

    public InboundIntentFailureStage failureStage() {
        return failureStage;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public Path recordingFile() {
        return recordingFile;
    }

    public String transcript() {
        return transcript;
    }

    public String codexSessionId() {
        return codexSessionId;
    }

    public boolean recordingCallbackHandled() {
        return recordingCallbackHandled;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public OffsetDateTime updatedAt() {
        return updatedAt;
    }

    void inputStatus(InboundIntentInputStatus inputStatus, OffsetDateTime now) {
        this.inputStatus = inputStatus;
        this.updatedAt = now;
    }

    void status(InboundIntentStatus status, OffsetDateTime now) {
        this.status = status;
        this.updatedAt = now;
        if (status != InboundIntentStatus.FAILED) {
            this.failureStage = null;
            this.errorMessage = null;
        }
    }

    void recordingFile(Path recordingFile, OffsetDateTime now) {
        this.recordingFile = recordingFile;
        this.updatedAt = now;
    }

    void transcript(String transcript, OffsetDateTime now) {
        this.transcript = transcript;
        this.updatedAt = now;
    }

    void codexSessionId(String codexSessionId, OffsetDateTime now) {
        this.codexSessionId = codexSessionId;
        this.updatedAt = now;
    }

    void markRecordingCallbackHandled(OffsetDateTime now) {
        this.recordingCallbackHandled = true;
        this.updatedAt = now;
    }

    void fail(InboundIntentFailureStage failureStage, String errorMessage, OffsetDateTime now) {
        this.status = InboundIntentStatus.FAILED;
        this.inputStatus = failureStage == InboundIntentFailureStage.ASR || failureStage == InboundIntentFailureStage.RECORDING
                ? InboundIntentInputStatus.FAILED_INPUT
                : this.inputStatus;
        this.failureStage = failureStage;
        this.errorMessage = errorMessage;
        this.updatedAt = now;
    }
}
