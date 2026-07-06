package io.github.liliangxu.phoneagent.inbound;

public enum InboundIntentFailureStage {
    VALIDATION,
    RECORDING,
    ASR,
    CODEX_CREATE,
    INTERNAL
}
