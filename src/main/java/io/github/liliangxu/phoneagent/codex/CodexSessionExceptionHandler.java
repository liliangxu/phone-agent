package io.github.liliangxu.phoneagent.codex;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class CodexSessionExceptionHandler {
    @ExceptionHandler(CodexSessionException.class)
    ResponseEntity<CodexSessionErrorResponse> codexSession(CodexSessionException exception) {
        return ResponseEntity
                .status(exception.status())
                .body(new CodexSessionErrorResponse(exception.error(), exception.getMessage()));
    }
}
