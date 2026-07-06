package io.github.liliangxu.phoneagent.codex;

import java.time.OffsetDateTime;

public record BridgeSummary(
        String bridgeId,
        BridgePhase phase,
        BridgeStatus status,
        boolean cancellable,
        boolean renotifyAllowed,
        String taskId,
        Integer slot,
        String assistantMessage,
        String replyText,
        String errorMessage,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    static BridgeSummary from(CodexPhoneBridgeRecord bridge) {
        return new BridgeSummary(
                bridge.bridgeId(),
                phase(bridge.status()),
                bridge.status(),
                bridge.cancellable(),
                bridge.renotifyAllowed(),
                bridge.taskId(),
                bridge.slot(),
                bridge.lastAssistantMessage(),
                bridge.replyText(),
                bridge.errorMessage(),
                bridge.createdAt(),
                bridge.updatedAt()
        );
    }

    /**
     * Groups fine-grained bridge states into the small set needed by the
     * Console session-card indicator.
     */
    static BridgePhase phase(BridgeStatus status) {
        return switch (status) {
            case WAITING_DETECTED, TASK_CREATED, QUEUED, NOTIFIED, PICKED_UP, RECORDING,
                    TRANSCRIBING, ASR_DONE, REPLYING_TO_CODEX -> BridgePhase.IN_PROGRESS;
            case REPLIED_TO_CODEX, NO_REPLY -> BridgePhase.DONE;
            case FAILED_TASK_CREATE, FAILED_BLF_NOTIFY, FAILED_RECORDING, FAILED_ASR,
                    FAILED_CODEX_SESSION_STOPPED, FAILED_REPLY_TO_CODEX -> BridgePhase.FAILED;
            case CANCELLED -> BridgePhase.CANCELLED;
        };
    }
}
