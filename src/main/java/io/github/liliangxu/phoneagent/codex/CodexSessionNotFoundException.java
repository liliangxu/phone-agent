package io.github.liliangxu.phoneagent.codex;

import org.springframework.http.HttpStatus;

public class CodexSessionNotFoundException extends CodexSessionException {
    public CodexSessionNotFoundException(String id) {
        super("CODEX_SESSION_NOT_FOUND", "Codex session not found: " + id, HttpStatus.NOT_FOUND);
    }
}
