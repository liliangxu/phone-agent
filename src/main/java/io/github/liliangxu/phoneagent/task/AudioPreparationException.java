package io.github.liliangxu.phoneagent.task;

public class AudioPreparationException extends RuntimeException {
    private final FailureStage stage;

    AudioPreparationException(FailureStage stage, String message) {
        super(message);
        this.stage = stage;
    }

    AudioPreparationException(FailureStage stage, String message, Throwable cause) {
        super(message, cause);
        this.stage = stage;
    }

    FailureStage stage() {
        return stage;
    }
}
