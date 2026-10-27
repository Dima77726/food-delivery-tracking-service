package com.dima.fooddelivery.common.exception;

/**
 * Клиент шлёт чаще, чем разрешено. Отдаётся как 429 с {@code Retry-After}.
 */
public class TooManyRequestsException extends RuntimeException {

    private final long retryAfterSeconds;

    public TooManyRequestsException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
