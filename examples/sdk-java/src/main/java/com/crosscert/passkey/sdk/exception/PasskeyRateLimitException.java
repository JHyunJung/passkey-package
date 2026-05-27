package com.crosscert.passkey.sdk.exception;

import com.crosscert.passkey.sdk.envelope.EnvelopeFieldError;

import java.util.List;

public class PasskeyRateLimitException extends PasskeyApiException {
    private final long retryAfterSeconds;

    public PasskeyRateLimitException(String code, String message, String traceId,
                                     List<EnvelopeFieldError> fieldErrors,
                                     long retryAfterSeconds) {
        super(429, code, message, traceId, fieldErrors);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() { return retryAfterSeconds; }
}
