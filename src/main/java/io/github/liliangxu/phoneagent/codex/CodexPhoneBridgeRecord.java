package io.github.liliangxu.phoneagent.codex;

import java.time.OffsetDateTime;
import java.util.EnumSet;

public record CodexPhoneBridgeRecord(
        String bridgeId,
        String codexSessionId,
        String threadId,
        String waitingEventKey,
        String lastAssistantMessage,
        BridgeStatus status,
        String taskId,
        String replacedTaskId,
        Integer slot,
        String replyText,
        String errorCode,
        String errorMessage,
        OffsetDateTime cancelledAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    private static final EnumSet<BridgeStatus> CANCELLABLE = EnumSet.of(
            BridgeStatus.WAITING_DETECTED,
            BridgeStatus.TASK_CREATED,
            BridgeStatus.QUEUED,
            BridgeStatus.NOTIFIED,
            BridgeStatus.FAILED_TASK_CREATE,
            BridgeStatus.FAILED_BLF_NOTIFY
    );
    private static final EnumSet<BridgeStatus> RENOTIFY_ALLOWED = EnumSet.of(
            BridgeStatus.FAILED_TASK_CREATE,
            BridgeStatus.FAILED_BLF_NOTIFY,
            BridgeStatus.FAILED_RECORDING,
            BridgeStatus.FAILED_ASR,
            BridgeStatus.FAILED_CODEX_SESSION_STOPPED,
            BridgeStatus.FAILED_REPLY_TO_CODEX,
            BridgeStatus.CANCELLED,
            BridgeStatus.NO_REPLY,
            BridgeStatus.REPLIED_TO_CODEX
    );
    private static final EnumSet<BridgeStatus> TERMINAL = EnumSet.of(
            BridgeStatus.REPLIED_TO_CODEX,
            BridgeStatus.NO_REPLY,
            BridgeStatus.CANCELLED,
            BridgeStatus.FAILED_RECORDING,
            BridgeStatus.FAILED_ASR,
            BridgeStatus.FAILED_CODEX_SESSION_STOPPED,
            BridgeStatus.FAILED_REPLY_TO_CODEX
    );

    boolean cancellable() {
        return CANCELLABLE.contains(status);
    }

    boolean renotifyAllowed() {
        return RENOTIFY_ALLOWED.contains(status);
    }

    boolean terminal() {
        return TERMINAL.contains(status);
    }
}
