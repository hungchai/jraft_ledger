package com.ibank.ledger.rest.advice;

import com.ibank.ledger.domain.exception.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleAccountNotFound(AccountNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(errorBody(HttpStatus.NOT_FOUND, e.getMessage()));
    }

    @ExceptionHandler(BalanceTypeNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleBalanceTypeNotFound(BalanceTypeNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(errorBody(HttpStatus.NOT_FOUND, e.getMessage()));
    }

    @ExceptionHandler({
            AccountAlreadyExistsException.class,
            DuplicateBalanceTypeException.class,
            InsufficientBalanceException.class,
            AccountHasNonZeroBalanceException.class,
            MissingOwnerIdException.class,
            DraftExpiredException.class,
            DraftNotPendingException.class,
            MakerCheckerSamePersonException.class,
            AccountClosedException.class,
            BalanceTypeInactiveException.class
    })
    public ResponseEntity<Map<String, Object>> handleBadRequest(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(errorBody(HttpStatus.BAD_REQUEST, e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(errorBody(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage()));
    }

    private Map<String, Object> errorBody(HttpStatus status, String message) {
        return Map.of(
                "timestamp", Instant.now().toString(),
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", message
        );
    }
}
