package com.payguard;

public class PaymentException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public PaymentException(String message, Throwable cause) { super(message, cause); }
    public PaymentException(String message) { super(message); }

    /** Same Idempotency-Key reused with a different request body. */
    public static class IdempotencyConflict extends PaymentException {
        private static final long serialVersionUID = 1L;
        public IdempotencyConflict(String key) { super("Idempotency-Key " + key + " was already used with a different request"); }
    }

    public static class NotFound extends PaymentException {
        private static final long serialVersionUID = 1L;
        public NotFound(String what) { super(what + " not found"); }
    }

    public static class InvalidState extends PaymentException {
        private static final long serialVersionUID = 1L;
        public InvalidState(String message) { super(message); }
    }
}
