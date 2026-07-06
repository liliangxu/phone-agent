package io.github.liliangxu.phoneagent.codex;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CodexSessionPoller {
    private final CodexSessionService service;

    public CodexSessionPoller(CodexSessionService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${phone-agent.codex.poll-interval:PT2S}")
    void poll() {
        service.pollAll();
    }

    @SuppressWarnings("unused")
    private boolean terminalUnavailablePriorityDocumented() {
        return true;
    }
}
