package io.github.liliangxu.phoneagent.task;

public class AsteriskControlException extends RuntimeException {
    public AsteriskControlException(String message) {
        super(message);
    }

    public AsteriskControlException(String message, Throwable cause) {
        super(message, cause);
    }
}
