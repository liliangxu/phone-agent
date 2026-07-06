package io.github.liliangxu.phoneagent.codex;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class CodexSessionController {
    private final CodexSessionService service;

    public CodexSessionController(CodexSessionService service) {
        this.service = service;
    }

    @GetMapping("/api/codex-sessions")
    public List<CodexSessionView> list() {
        return service.list();
    }

    @GetMapping("/api/codex-sessions/{id}")
    public CodexSessionView get(@PathVariable String id) {
        return service.get(id).orElseThrow(() -> new CodexSessionNotFoundException(id));
    }

    @PostMapping("/api/codex-sessions")
    public ResponseEntity<CodexSessionView> create(@RequestBody(required = false) CreateCodexSessionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PostMapping("/api/codex-sessions/{id}/terminal/ensure")
    public CodexSessionView ensureTerminal(@PathVariable String id) {
        return service.ensureTerminal(id);
    }
}

@Controller
class CodexConsoleController {
    @GetMapping({"/", "/console", "/console/"})
    String console() {
        return "forward:/console/index.html";
    }
}
