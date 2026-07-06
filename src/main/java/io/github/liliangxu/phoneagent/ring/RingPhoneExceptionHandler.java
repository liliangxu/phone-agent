package io.github.liliangxu.phoneagent.ring;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class RingPhoneExceptionHandler {
    @ExceptionHandler(RingPhoneException.class)
    ResponseEntity<RingPhoneErrorResponse> ringPhone(RingPhoneException exception) {
        return ResponseEntity.status(exception.status())
                .body(new RingPhoneErrorResponse(exception.error(), exception.getMessage()));
    }
}
