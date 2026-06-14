package com.crosscert.passkey.sdk.internal

import com.crosscert.passkey.sdk.PasskeyClientConfig
import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse
import java.io.IOException

/**
 * Propagates the caller's `traceId` (read via
 * [PasskeyClientConfig.traceIdProvider], default = MDC) onto the
 * outgoing SDK request as `X-Trace-Id`. The receiving passkey-app's
 * `TraceIdFilter` re-uses the inbound header so the two servers
 * share a single trace id for one logical user action.
 *
 * This interceptor must be registered BEFORE [RedactingRequestInterceptor]
 * so the header is present when redaction logging runs (the redactor
 * inspects request headers for its DEBUG output).
 */
class TraceIdPropagationInterceptor(
    private val config: PasskeyClientConfig,
) : ClientHttpRequestInterceptor {

    @Throws(IOException::class)
    override fun intercept(
        request: HttpRequest,
        body: ByteArray,
        exec: ClientHttpRequestExecution,
    ): ClientHttpResponse {
        // traceIdProvider 는 플랫폼 타입이라 런타임에 null 을 반환할 수 있으므로 nullable 로 받는다.
        val traceId: String? = config.traceIdProvider.get()
        if (traceId != null && !traceId.isBlank()) {
            request.headers.set(TRACE_HEADER, traceId)
        }
        return exec.execute(request, body)
    }

    companion object {
        private const val TRACE_HEADER = "X-Trace-Id"
    }
}
