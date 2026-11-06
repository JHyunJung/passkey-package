package com.crosscert.passkey.sdk.internal;

import com.crosscert.passkey.sdk.envelope.ApiResponseEnvelope;
import com.crosscert.passkey.sdk.envelope.EnvelopeFieldError;
import com.crosscert.passkey.sdk.exception.PasskeyApiException;
import com.crosscert.passkey.sdk.exception.PasskeyAuthException;
import com.crosscert.passkey.sdk.exception.PasskeyRateLimitException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResponseErrorHandler;
import java.io.IOException;
import java.net.URI;
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

    /**
     * RestClient/RestTemplate 이 실제로 호출하는 진입점(Spring 6.x 의 {@code StatusHandler} 와
     * Spring 7 모두 3-인자 버전을 호출한다). Spring 7 에서는 1-인자 {@code handleError} 가
     * 인터페이스에서 제거되어 3-인자 기본 구현이 아무것도 하지 않으므로, 이 메서드를 직접
     * 구현해야 4xx/5xx → {@link PasskeyApiException} 매핑이 Spring 6·7 양쪽에서 동작한다.
     */
    @Override
    public void handleError(URI url, HttpMethod method, ClientHttpResponse response) throws IOException {
        mapAndThrow(response);
    }

    /**
     * Spring 6.0/6.1 에서는 이 1-인자 메서드가 추상 메서드라 구현이 필요하다(Spring 7 은 호출하지
     * 않는다). 프레임워크는 위 3-인자 메서드만 호출하고 3-인자 메서드는 이 메서드를 거치지
     * 않으므로 이중 처리는 없다.
     */
    @Override
    @SuppressWarnings("removal") // Spring 6.2 에서 deprecated-for-removal — 6.0/6.1 호환을 위해 의도적으로 유지.
    public void handleError(ClientHttpResponse response) throws IOException {
        mapAndThrow(response);
    }

    private void mapAndThrow(ClientHttpResponse response) throws IOException {
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
