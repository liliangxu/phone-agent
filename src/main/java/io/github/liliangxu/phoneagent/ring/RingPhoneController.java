package io.github.liliangxu.phoneagent.ring;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RingPhoneController {
    private final RingPhoneService service;

    public RingPhoneController(RingPhoneService service) {
        this.service = service;
    }

    @PostMapping("/api/ring-phone")
    public RingPhoneResponse ring() {
        return service.ring();
    }
}
