package com.crosscert.passkey.sdk.internal;

import com.crosscert.passkey.sdk.envelope.ApiResponseEnvelope;
import com.crosscert.passkey.sdk.envelope.EnvelopeFieldError;
import com.crosscert.passkey.sdk.exception.PasskeyApiException;
import com.crosscert.passkey.sdk.exception.PasskeyAuthException;
import com.crosscert.passkey.sdk.exception.PasskeyRateLimitException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResponseErrorHandler;
import java.io.IOException;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

public class PasskeyResponseErrorHandler implements ResponseErrorHandler {

    private final ObjectMapper objectMapper;

    public PasskeyResponseErrorHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean hasError(ClientHttpResponse response) throws IOException {
        return response.getStatusCode().isError();
    }

    @Override
    public void handleError(ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        byte[] bodyBytes = response.getBody().readAllBytes();
        ApiResponseEnvelope<?> env = parseQuietly(bodyBytes);

        String code;
        String message;
        String traceId;
        List<EnvelopeFieldError> fieldErrors;
        // code 가 없는 envelope 은 비-envelope 응답(예: RFC 7807 problem+json)으로 취급.
        if (env != null && env.code() != null) {
            code = env.code();
            message = env.message();
            traceId = env.traceId();
            fieldErrors = (env.error() == null) ? null : env.error().fieldErrors();
        } else {
            code = "C999";
            message = "Upstream error (no envelope)";
            traceId = null;
            fieldErrors = null;
        }

        if (status == 401) {
            throw new PasskeyAuthException(code, message, traceId, fieldErrors);
        }
        if (status == 429) {
            long retry = parseRetryAfterSeconds(response.getHeaders().getFirst("Retry-After"));
            throw new PasskeyRateLimitException(code, message, traceId, fieldErrors, retry);
        }
        throw new PasskeyApiException(status, code, message, traceId, fieldErrors);
    }

    private ApiResponseEnvelope<?> parseQuietly(byte[] body) {
        try {
            if (body == null || body.length == 0) {
                return null;
            }
            return objectMapper.readValue(body, ApiResponseEnvelope.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * RFC 7231 Section 7.1.3 은 Retry-After 값으로 delay-seconds(정수 초) 뿐 아니라
     * HTTP-date(예: "Wed, 21 Oct 2025 07:28:00 GMT")도 허용한다. Long.parseLong 을 그대로
     * 쓰면 HTTP-date 형식에서 NumberFormatException 이 발생해 handleError 를 탈출하고
     * 429 의미가 소실된 채 상위(rp-app)에서 500 으로 관측된다 — 어떤 경우에도 예외 없이
     * 안전한 정수(초)를 반환한다.
     */
    private long parseRetryAfterSeconds(String retryAfter) {
        if (retryAfter == null || retryAfter.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(retryAfter.trim());
        } catch (NumberFormatException ignoredNotDelaySeconds) {
            try {
                ZonedDateTime target = ZonedDateTime.parse(retryAfter.trim(), DateTimeFormatter.RFC_1123_DATE_TIME);
                long seconds = Duration.between(ZonedDateTime.now(ZoneOffset.UTC), target).getSeconds();
                return Math.max(seconds, 0L);
            } catch (DateTimeParseException ignoredNotHttpDate) {
                // 알 수 없는 형식 — 429 신호를 잃지 않도록 안전 폴백.
                return 0L;
            }
        }
    }
}
