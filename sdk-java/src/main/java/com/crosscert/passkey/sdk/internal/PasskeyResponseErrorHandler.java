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
            String retryAfter = response.getHeaders().getFirst("Retry-After");
            long retry = (retryAfter == null) ? 0 : Long.parseLong(retryAfter);
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
}
