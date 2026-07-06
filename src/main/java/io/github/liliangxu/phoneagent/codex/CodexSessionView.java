package io.github.liliangxu.phoneagent.codex;

import java.time.OffsetDateTime;
import java.util.List;

public record CodexSessionView(
        String id,
        String title,
        String cwd,
        CodexSessionStatus status,
        String tmuxName,
        String ttydUrl,
        String threadId,
        String threadShortId,
        String jsonlPath,
        String lastAssistantMessage,
        String lastMessageSummary,
        String errorMessage,
        BridgeSummary activeBridge,
        List<BridgeSummary> bridgeHistory,
        String phoneBridgeErrorCode,
        String phoneBridgeErrorMessage,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    static CodexSessionView from(CodexSessionRecord record) {
        return from(record, null, List.of());
    }

    static CodexSessionView from(CodexSessionRecord record, BridgeSummary activeBridge, List<BridgeSummary> bridgeHistory) {
        String threadId = record.getThreadId();
        String shortId = threadId == null || threadId.isBlank()
                ? null
                : threadId.substring(0, Math.min(4, threadId.length()));
        List<BridgeSummary> history = bridgeHistory == null ? List.of() : List.copyOf(bridgeHistory);
        return new CodexSessionView(
                record.getId(),
                record.getTitle(),
                record.getCwd(),
                record.getStatus(),
                record.getTmuxName(),
                record.getTtydUrl(),
                threadId,
                shortId,
                record.getJsonlPath(),
                record.getLastAssistantMessage(),
                lastMessageSummary(activeBridge, history, record.getLastAssistantMessage()),
                record.getErrorMessage(),
                activeBridge,
                history,
                record.getPhoneBridgeErrorCode(),
                record.getPhoneBridgeErrorMessage(),
                record.getCreatedAt(),
                record.getUpdatedAt()
        );
    }

    private static String lastMessageSummary(BridgeSummary activeBridge, List<BridgeSummary> history, String assistant) {
        if (activeBridge != null && activeBridge.replyText() != null && !activeBridge.replyText().isBlank()) {
            return activeBridge.replyText();
        }
        for (BridgeSummary bridge : history) {
            if (bridge.replyText() != null && !bridge.replyText().isBlank()) {
                return bridge.replyText();
            }
        }
        return assistant;
    }
}
