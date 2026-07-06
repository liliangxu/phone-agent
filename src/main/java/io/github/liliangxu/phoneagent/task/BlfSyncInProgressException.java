package io.github.liliangxu.phoneagent.task;

public class BlfSyncInProgressException extends RuntimeException {
    private final String reason;

    public BlfSyncInProgressException(String reason) {
        super("BLF sync is already in progress: " + reason);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
