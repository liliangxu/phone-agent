package io.github.liliangxu.phoneagent.ring;

import org.springframework.http.HttpStatus;

public class RingPhoneException extends RuntimeException {
    private final String error;
    private final HttpStatus status;

    public RingPhoneException(String error, String message, HttpStatus status) {
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
