package com.crosscert.passkey.sdk.internal;

import com.crosscert.passkey.sdk.envelope.ApiResponseEnvelope;
import com.crosscert.passkey.sdk.exception.PasskeyApiException;
import com.crosscert.passkey.sdk.exception.PasskeyAuthException;
import com.crosscert.passkey.sdk.exception.PasskeyRateLimitException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResponseErrorHandler;

import java.io.IOException;

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
        // Treat a parsed envelope with no code as a non-envelope response (e.g. RFC 7807 problem+json)
        boolean isEnvelope = env != null && env.code() != null;

        String code     = isEnvelope ? env.code()    : "C999";
        String message  = isEnvelope ? env.message() : "Upstream error (no envelope)";
        String traceId  = isEnvelope ? env.traceId() : null;
        var fieldErrors = isEnvelope && env.error() != null ? env.error().fieldErrors() : null;

        if (status == 401) {
            throw new PasskeyAuthException(code, message, traceId, fieldErrors);
        }
        if (status == 429) {
            long retry = response.getHeaders().getFirst("Retry-After") == null
                    ? 0
                    : Long.parseLong(response.getHeaders().getFirst("Retry-After"));
            throw new PasskeyRateLimitException(code, message, traceId, fieldErrors, retry);
        }
        throw new PasskeyApiException(status, code, message, traceId, fieldErrors);
    }

    private ApiResponseEnvelope<?> parseQuietly(byte[] body) {
        try {
            if (body == null || body.length == 0) return null;
            return objectMapper.readValue(body, ApiResponseEnvelope.class);
        } catch (Exception e) {
            return null;
        }
    }
}
