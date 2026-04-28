package com.humanitarian.platform.exception;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handles @Valid annotation errors — field level validation failures.
     * Returns a map of field -> error message.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationErrors(
            MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String field = ((FieldError) error).getField();
            String message = error.getDefaultMessage();
            fieldErrors.put(field, message);
        });
        return buildResponse(HttpStatus.BAD_REQUEST, "Validation failed", fieldErrors);
    }

    /**
     * Handles JSON parse errors — most importantly enum deserialization failures.
     * Instead of the ugly Jackson error, returns a clean user-friendly message.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleJsonParseError(
            HttpMessageNotReadableException ex) {

        String message = "Invalid request body";

        // Detect enum deserialization errors specifically
        Throwable cause = ex.getCause();
        if (cause instanceof InvalidFormatException invalidFormat) {
            Class<?> targetType = invalidFormat.getTargetType();

            if (targetType != null && targetType.isEnum()) {
                // Get accepted enum values
                String accepted = Arrays.stream(targetType.getEnumConstants())
                        .map(Object::toString)
                        .map(String::toLowerCase)
                        .collect(Collectors.joining(", "));

                String invalidValue = invalidFormat.getValue() != null
                        ? invalidFormat.getValue().toString()
                        : "unknown";

                message = String.format(
                        "Invalid value '%s' for field '%s'. Accepted values are: [%s]",
                        invalidValue,
                        invalidFormat.getPath().isEmpty() ? "unknown"
                                : invalidFormat.getPath().get(0).getFieldName(),
                        accepted
                );
            }
        }

        return buildResponse(HttpStatus.BAD_REQUEST, message, null);
    }

    /**
     * Handles IllegalArgumentException — thrown manually from @JsonCreator.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(
            IllegalArgumentException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage(), null);
    }

    /**
     * Handles wrong email/password at login.
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, Object>> handleBadCredentials(
            BadCredentialsException ex) {
        return buildResponse(HttpStatus.UNAUTHORIZED, "Invalid email or password", null);
    }

    /**
     * Handles access denied — user doesn't have the required role.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(
            AccessDeniedException ex) {
        return buildResponse(HttpStatus.FORBIDDEN,
                "Access denied. You don't have permission to perform this action.", null);
    }

    /**
     * Handles all other runtime exceptions. Picks a sensible status code from the
     * message text so legitimate "not found" responses come back as 404 and
     * unexpected database/JPA failures come back as 500 instead of being masked
     * as 400 (which used to make every backend error look like a validation bug).
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntimeException(
            RuntimeException ex) {
        String msg = ex.getMessage() == null ? "" : ex.getMessage();
        String lower = msg.toLowerCase();
        HttpStatus status;
        if (lower.contains("not found")) {
            status = HttpStatus.NOT_FOUND;
        } else if (lower.contains("no longer available") || lower.contains("already")
                || lower.contains("invalid") || lower.contains("must ")) {
            status = HttpStatus.BAD_REQUEST;
        } else {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return buildResponse(status, msg.isEmpty() ? status.getReasonPhrase() : msg, null);
    }

    /**
     * Catch-all handler for any unexpected exceptions.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception ex) {
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred: " + ex.getMessage(), null);
    }


    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ResourceNotFoundException ex) {
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage(), null);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(BusinessException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage(), null);
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Map<String, Object>> handleUnauthorized(UnauthorizedException ex) {
        return buildResponse(HttpStatus.FORBIDDEN, ex.getMessage(), null);
    }

    /**
     * Builds a consistent error response structure.
     */
    private ResponseEntity<Map<String, Object>> buildResponse(
            HttpStatus status, String message, Object details) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        if (details != null) body.put("details", details);
        return ResponseEntity.status(status).body(body);
    }
}