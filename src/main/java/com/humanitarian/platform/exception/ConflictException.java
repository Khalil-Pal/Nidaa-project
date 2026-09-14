package com.humanitarian.platform.exception;

/**
 * The request was valid when it was checked but the row had moved on by the
 * time the guarded UPDATE ran: another caller changed the status first (B-4).
 * Mapped to 409 so the client knows to reload rather than retry blindly.
 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
