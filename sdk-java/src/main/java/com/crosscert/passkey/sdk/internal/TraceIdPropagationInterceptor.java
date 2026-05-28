package com.crosscert.passkey.sdk.internal;

import com.crosscert.passkey.sdk.PasskeyClientConfig;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * Propagates the caller's {@code traceId} (read via
 * {@link PasskeyClientConfig#traceIdProvider()}, default = MDC) onto the
 * outgoing SDK request as {@code X-Trace-Id}. The receiving passkey-app's
 * {@code TraceIdFilter} re-uses the inbound header so the two servers
 * share a single trace id for one logical user action.
 *
 * <p>This interceptor must be registered BEFORE {@link RedactingRequestInterceptor}
 * so the header is present when redaction logging runs (the redactor
 * inspects request headers for its DEBUG output).
 */
public final class TraceIdPropagationInterceptor implements ClientHttpRequestInterceptor {

    private static final String TRACE_HEADER = "X-Trace-Id";

    private final PasskeyClientConfig config;

    public TraceIdPropagationInterceptor(PasskeyClientConfig config) {
        this.config = config;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution exec)
            throws IOException {
        String traceId = config.traceIdProvider().get();
        if (traceId != null && !traceId.isBlank()) {
            request.getHeaders().set(TRACE_HEADER, traceId);
        }
        return exec.execute(request, body);
    }
}
