package io.github.liliangxu.phoneagent.codex;

import org.springframework.http.HttpStatus;

public class CodexSessionException extends RuntimeException {
    private final String error;
    private final HttpStatus status;

    public CodexSessionException(String error, String message, HttpStatus status) {
        super(message);
        this.error = error;
        this.status = status;
    }

    public String error() {
        return error;
    }

    public HttpStatus status() {
        return status;
    }
}
