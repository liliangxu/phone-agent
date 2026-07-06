package io.github.liliangxu.phoneagent.codex;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CodexPhoneBridgeController {
    private final CodexPhoneBridgeService bridgeService;

    public CodexPhoneBridgeController(CodexPhoneBridgeService bridgeService) {
        this.bridgeService = bridgeService;
    }

    @PostMapping("/api/codex-phone-bridges/{bridgeId}/cancel")
    public BridgeSummary cancel(@PathVariable String bridgeId) {
        return bridgeService.cancel(bridgeId);
    }

    @PostMapping("/api/codex-phone-bridges/{bridgeId}/renotify")
    public BridgeSummary renotify(@PathVariable String bridgeId) {
        return bridgeService.renotify(bridgeId);
    }
}
