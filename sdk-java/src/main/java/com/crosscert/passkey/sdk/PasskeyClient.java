package com.crosscert.passkey.sdk;

import com.crosscert.passkey.sdk.dto.*;
import com.crosscert.passkey.sdk.envelope.ApiResponseEnvelope;
import com.crosscert.passkey.sdk.exception.PasskeyApiException;
import com.crosscert.passkey.sdk.idtoken.IdTokenClaims;
import com.crosscert.passkey.sdk.idtoken.IdTokenVerifier;
import com.crosscert.passkey.sdk.idtoken.JwksCache;
import com.crosscert.passkey.sdk.internal.PasskeyResponseErrorHandler;
import com.crosscert.passkey.sdk.internal.RedactingRequestInterceptor;
import com.crosscert.passkey.sdk.internal.TraceIdPropagationInterceptor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;

public final class PasskeyClient {
    private static final Logger log = LoggerFactory.getLogger(PasskeyClient.class);

    private final RestClient http;
    private final IdTokenVerifier idTokenVerifier;
    private final ObjectMapper objectMapper;

    private PasskeyClient(PasskeyClientConfig config) {
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // Use JdkClientHttpRequestFactory (Java 11 HttpClient) instead of
        // SimpleClientHttpRequestFactory: the latter sets chunked/fixed-length streaming
        // mode on HttpURLConnection which prevents getErrorStream() from returning the
        // 4xx/5xx response body, causing error-envelope parsing to always fail.
        HttpClient jdkClient = HttpClient.newBuilder()
                .connectTimeout(config.connectTimeout())
                .build();
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(jdkClient);
        rf.setReadTimeout(config.readTimeout());

        this.http = RestClient.builder()
                .baseUrl(config.baseUrl().toString())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                // Order matters: TraceIdPropagation runs first so X-Trace-Id is
                // on the outgoing request when RedactingRequestInterceptor logs
                // its DEBUG line.
                .requestInterceptor(new TraceIdPropagationInterceptor(config))
                .requestInterceptor(new RedactingRequestInterceptor(config))
                .requestFactory(rf)
                .defaultStatusHandler(new PasskeyResponseErrorHandler(objectMapper))
                .build();

        this.idTokenVerifier = new IdTokenVerifier(
                new JwksCache(config.baseUrl(), config.jwksCacheTtl(), config.clock()),
                config.clock());
    }

    public static PasskeyClient of(PasskeyClientConfig config) {
        return new PasskeyClient(config);
    }

    public RegistrationStartResponse registrationStart(RegistrationStartRequest req) {
        return post("/api/v1/rp/registration/start", req,
                new TypeReference<ApiResponseEnvelope<RegistrationStartResponse>>(){});
    }

    public RegistrationFinishResponse registrationFinish(RegistrationFinishRequest req) {
        return post("/api/v1/rp/registration/finish", req,
                new TypeReference<ApiResponseEnvelope<RegistrationFinishResponse>>(){});
    }

    public AuthenticationStartResponse authenticationStart(AuthenticationStartRequest req) {
        return post("/api/v1/rp/authentication/start", req,
                new TypeReference<ApiResponseEnvelope<AuthenticationStartResponse>>(){});
    }

    public AuthenticationFinishResponse authenticationFinish(AuthenticationFinishRequest req) {
        return post("/api/v1/rp/authentication/finish", req,
                new TypeReference<ApiResponseEnvelope<AuthenticationFinishResponse>>(){});
    }

    public IdTokenClaims verifyIdToken(String compactJwt) {
        return idTokenVerifier.verify(compactJwt);
    }

    private <T> T post(String path, Object body, TypeReference<ApiResponseEnvelope<T>> typeRef) {
        Instant started = Instant.now();
        byte[] bytes;
        try {
            bytes = http.post()
                    .uri(path)
                    .body(body)
                    .retrieve()
                    .body(byte[].class);
        } catch (PasskeyApiException e) {
            // PasskeyResponseErrorHandler converts 4xx/5xx → PasskeyApiException
            // with the server's envelope code. Logged here so SDK callers see
            // the failure even if they swallow the exception.
            long durMs = Duration.between(started, Instant.now()).toMillis();
            log.warn("sdk call: POST {} status={} code={} durMs={}",
                    path, e.getHttpStatus(), e.getCode(), durMs);
            throw e;
        }
        try {
            ApiResponseEnvelope<T> env = objectMapper.readValue(bytes, objectMapper.getTypeFactory()
                    .constructType(typeRef.getType()));
            long durMs = Duration.between(started, Instant.now()).toMillis();
            if (!env.success()) {
                // HTTP 200 with success=false (envelope-level failure).
                log.warn("sdk call: POST {} status=200 code={} durMs={}",
                        path, env.code(), durMs);
                throw new PasskeyApiException(200, env.code(), env.message(), env.traceId(),
                        env.error() == null ? null : env.error().fieldErrors());
            }
            if (log.isDebugEnabled()) {
                log.debug("sdk call: POST {} status=200 durMs={}", path, durMs);
            }
            return env.data();
        } catch (PasskeyApiException e) {
            throw e;
        } catch (Exception e) {
            long durMs = Duration.between(started, Instant.now()).toMillis();
            log.warn("sdk call: POST {} envelope-parse-failure durMs={} cause={}",
                    path, durMs, e.toString());
            throw new PasskeyApiException(0, "C999", "Envelope parse failure: " + e.getMessage(),
                    null, null);
        }
    }
}
