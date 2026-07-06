package io.github.liliangxu.phoneagent.inbound;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.github.liliangxu.phoneagent.task.RecordingCallbackResult;

import java.util.List;

@RestController
public class InboundIntentController {
    private final InboundIntentService service;

    public InboundIntentController(InboundIntentService service) {
        this.service = service;
    }

    @GetMapping("/api/inbound-intents")
    public List<InboundIntentView> list() {
        return service.list();
    }

    @GetMapping("/api/inbound-intents/{intentId}")
    public InboundIntentView get(@PathVariable String intentId) {
        return service.get(intentId).orElseThrow(() -> new InboundIntentNotFoundException(intentId));
    }

    @PostMapping("/api/inbound-intents/text")
    public ResponseEntity<InboundIntentView> submitText(@RequestBody(required = false) CreateTextInboundIntentRequest request) {
        InboundIntentView created = service.submitText(InboundIntentSource.TEXT_API, request == null ? null : request.text());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping(value = "/internal/asterisk/inbound-intents/phone/start", produces = MediaType.TEXT_PLAIN_VALUE)
    public String startPhoneIntent() {
        return service.startAudioIntent(InboundIntentSource.PHONE_EXTENSION_0).intentId();
    }

    @PostMapping("/internal/asterisk/inbound-intents/phone/recordings")
    public RecordingCallbackResult completePhoneIntentRecording(@RequestParam String intentId) {
        return service.completeAudioRecording(intentId);
    }
}
