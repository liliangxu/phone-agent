package io.github.liliangxu.phoneagent.inbound;

import org.springframework.http.HttpStatus;
import io.github.liliangxu.phoneagent.codex.CodexSessionException;

public class InboundIntentNotFoundException extends CodexSessionException {
    public InboundIntentNotFoundException(String intentId) {
        super("INBOUND_INTENT_NOT_FOUND", "Inbound intent not found: " + intentId, HttpStatus.NOT_FOUND);
    }
}
