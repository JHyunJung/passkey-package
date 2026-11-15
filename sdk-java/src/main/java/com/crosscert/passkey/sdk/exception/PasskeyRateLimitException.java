package com.crosscert.passkey.sdk.exception;

import com.crosscert.passkey.sdk.envelope.EnvelopeFieldError;
import java.util.List;

/**
 * 서버가 429 를 돌려줬을 때. {@link #getRetryAfterSeconds()} 는 Retry-After 헤더(초 또는 HTTP-date)를
 * 초로 바꾼 값이고, 헤더가 없거나 해석하지 못하면 0 이다.
 */
public class PasskeyRateLimitException extends PasskeyApiException {
    private final long retryAfterSeconds;

    public PasskeyRateLimitException(String code, String message, String traceId,
                                    List<EnvelopeFieldError> fieldErrors, long retryAfterSeconds) {
        super(429, code, message, traceId, fieldErrors);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() { return retryAfterSeconds; }
}
