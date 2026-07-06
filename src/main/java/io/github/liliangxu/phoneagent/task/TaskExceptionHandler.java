package io.github.liliangxu.phoneagent.task;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class TaskExceptionHandler {
    @ExceptionHandler(TaskValidationException.class)
    ResponseEntity<ErrorResponse> validation(TaskValidationException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(exception.getMessage()));
    }

    @ExceptionHandler(InvalidSlotException.class)
    ResponseEntity<ErrorResponse> invalidSlot(InvalidSlotException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(exception.getMessage()));
    }

    @ExceptionHandler(TaskNotFoundException.class)
    ResponseEntity<ErrorResponse> notFound(TaskNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(exception.getMessage()));
    }

    @ExceptionHandler(TaskConflictException.class)
    ResponseEntity<ErrorResponse> conflict(TaskConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(exception.getMessage()));
    }

    @ExceptionHandler(BlfSyncInProgressException.class)
    ResponseEntity<BlfSyncResponse> blfSyncInProgress(BlfSyncInProgressException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(BlfSyncResponse.inProgress(exception.reason()));
    }

    @ExceptionHandler(TaskCreationException.class)
    ResponseEntity<TaskView> creation(TaskCreationException exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(exception.task());
    }

    record ErrorResponse(String errorMessage) {
    }
}
