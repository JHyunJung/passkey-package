package com.crosscert.passkey.sdk

import com.crosscert.passkey.sdk.dto.AuthenticationFinishRequest
import com.crosscert.passkey.sdk.dto.AuthenticationFinishResponse
import com.crosscert.passkey.sdk.dto.AuthenticationStartRequest
import com.crosscert.passkey.sdk.dto.AuthenticationStartResponse
import com.crosscert.passkey.sdk.dto.RegistrationFinishRequest
import com.crosscert.passkey.sdk.dto.RegistrationFinishResponse
import com.crosscert.passkey.sdk.dto.RegistrationStartRequest
import com.crosscert.passkey.sdk.dto.RegistrationStartResponse
import com.crosscert.passkey.sdk.envelope.ApiResponseEnvelope
import com.crosscert.passkey.sdk.exception.PasskeyApiException
import com.crosscert.passkey.sdk.idtoken.IdTokenClaims
import com.crosscert.passkey.sdk.idtoken.IdTokenVerifier
import com.crosscert.passkey.sdk.idtoken.JwksCache
import com.crosscert.passkey.sdk.internal.PasskeyResponseErrorHandler
import com.crosscert.passkey.sdk.internal.RedactingRequestInterceptor
import com.crosscert.passkey.sdk.internal.TraceIdPropagationInterceptor
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration
import java.time.Instant

class PasskeyClient private constructor(config: PasskeyClientConfig) {

    private val http: RestClient
    private val idTokenVerifier: IdTokenVerifier
    private val objectMapper: ObjectMapper

    init {
        this.objectMapper = ObjectMapper()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

        // Use JdkClientHttpRequestFactory (Java 11 HttpClient) instead of
        // SimpleClientHttpRequestFactory: the latter sets chunked/fixed-length streaming
        // mode on HttpURLConnection which prevents getErrorStream() from returning the
        // 4xx/5xx response body, causing error-envelope parsing to always fail.
        val jdkClient = HttpClient.newBuilder()
            .connectTimeout(config.connectTimeout)
            .build()
        val rf = JdkClientHttpRequestFactory(jdkClient)
        rf.setReadTimeout(config.readTimeout)

        this.http = RestClient.builder()
            .baseUrl(config.baseUrl.toString())
            .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            // Order matters: TraceIdPropagation runs first so X-Trace-Id is
            // on the outgoing request when RedactingRequestInterceptor logs
            // its DEBUG line.
            .requestInterceptor(TraceIdPropagationInterceptor(config))
            .requestInterceptor(RedactingRequestInterceptor(config))
            .requestFactory(rf)
            .defaultStatusHandler(PasskeyResponseErrorHandler(objectMapper))
            .build()

        this.idTokenVerifier = IdTokenVerifier(
            JwksCache(config.baseUrl, config.jwksCacheTtl, config.clock),
            config.clock,
        )
    }

    fun registrationStart(req: RegistrationStartRequest): RegistrationStartResponse {
        return post(
            "/api/v1/rp/registration/start", req,
            object : TypeReference<ApiResponseEnvelope<RegistrationStartResponse>>() {},
        )
    }

    fun registrationFinish(req: RegistrationFinishRequest): RegistrationFinishResponse {
        return post(
            "/api/v1/rp/registration/finish", req,
            object : TypeReference<ApiResponseEnvelope<RegistrationFinishResponse>>() {},
        )
    }

    fun authenticationStart(req: AuthenticationStartRequest): AuthenticationStartResponse {
        return post(
            "/api/v1/rp/authentication/start", req,
            object : TypeReference<ApiResponseEnvelope<AuthenticationStartResponse>>() {},
        )
    }

    fun authenticationFinish(req: AuthenticationFinishRequest): AuthenticationFinishResponse {
        return post(
            "/api/v1/rp/authentication/finish", req,
            object : TypeReference<ApiResponseEnvelope<AuthenticationFinishResponse>>() {},
        )
    }

    fun verifyIdToken(compactJwt: String): IdTokenClaims {
        return idTokenVerifier.verify(compactJwt)
    }

    private fun <T> post(
        path: String,
        body: Any,
        typeRef: TypeReference<ApiResponseEnvelope<T>>,
    ): T {
        val started = Instant.now()
        val bytes: ByteArray?
        try {
            bytes = http.post()
                .uri(path)
                .body(body)
                .retrieve()
                .body(ByteArray::class.java)
        } catch (e: PasskeyApiException) {
            // PasskeyResponseErrorHandler converts 4xx/5xx → PasskeyApiException
            // with the server's envelope code. Logged here so SDK callers see
            // the failure even if they swallow the exception.
            val durMs = Duration.between(started, Instant.now()).toMillis()
            log.warn(
                "sdk call: POST {} status={} code={} durMs={}",
                path, e.httpStatus, e.code, durMs,
            )
            throw e
        }
        try {
            val env: ApiResponseEnvelope<T> = objectMapper.readValue(
                bytes,
                objectMapper.typeFactory.constructType(typeRef.type),
            )
            val durMs = Duration.between(started, Instant.now()).toMillis()
            if (!env.success) {
                // HTTP 200 with success=false (envelope-level failure).
                log.warn(
                    "sdk call: POST {} status=200 code={} durMs={}",
                    path, env.code, durMs,
                )
                throw PasskeyApiException(
                    200, env.code, env.message, env.traceId,
                    env.error?.fieldErrors,
                )
            }
            if (log.isDebugEnabled) {
                log.debug("sdk call: POST {} status=200 durMs={}", path, durMs)
            }
            @Suppress("UNCHECKED_CAST")
            return env.data as T
        } catch (e: PasskeyApiException) {
            throw e
        } catch (e: Exception) {
            val durMs = Duration.between(started, Instant.now()).toMillis()
            log.warn(
                "sdk call: POST {} envelope-parse-failure durMs={} cause={}",
                path, durMs, e.toString(),
            )
            throw PasskeyApiException(
                0, "C999", "Envelope parse failure: " + e.message,
                null, null,
            )
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(PasskeyClient::class.java)

        @JvmStatic
        fun of(config: PasskeyClientConfig): PasskeyClient {
            return PasskeyClient(config)
        }
    }
}
