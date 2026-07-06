package io.github.liliangxu.phoneagent.codex;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum CodexSessionStatus {
    CREATING,
    CREATE_FAILED,
    IDLE,
    RUNNING,
    WAITING_USER,
    COMPLETED;

    /**
     * Maps persisted status names from older local development builds into the
     * current session-state model. Terminal health is intentionally collapsed to
     * IDLE because pane availability is no longer a CodexSession status.
     */
    @JsonCreator
    public static CodexSessionStatus fromPersisted(String value) {
        if (value == null || value.isBlank()) {
            return IDLE;
        }
        return switch (value) {
            case "STARTING" -> CREATING;
            case "FAILED" -> CREATE_FAILED;
            case "TERMINAL_UNAVAILABLE", "STOPPED" -> IDLE;
            case "HANDLED" -> COMPLETED;
            default -> {
                try {
                    yield CodexSessionStatus.valueOf(value);
                } catch (IllegalArgumentException ignored) {
                    yield IDLE;
                }
            }
        };
    }
}
