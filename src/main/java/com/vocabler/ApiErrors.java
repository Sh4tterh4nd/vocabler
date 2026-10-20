package com.vocabler;

import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> status(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", e.getReason() == null ? "Request failed" : e.getReason()));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> validation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField() + ": " + error.getDefaultMessage()).findFirst().orElse("Invalid input");
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }
    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<?> duplicate() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "This item already exists"));
    }
}
