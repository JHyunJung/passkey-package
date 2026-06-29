package com.crosscert.passkey.sdk.internal;

import com.crosscert.passkey.sdk.PasskeyClientConfig;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import java.io.IOException;

/**
 * 호출자의 traceId(기본=MDC)를 X-Trace-Id 로 전파. RedactingRequestInterceptor 보다
 * 먼저 등록되어야 redaction DEBUG 로그 시점에 헤더가 존재한다.
 */
public class TraceIdPropagationInterceptor implements ClientHttpRequestInterceptor {

    private static final String TRACE_HEADER = "X-Trace-Id";

    private final PasskeyClientConfig config;

    public TraceIdPropagationInterceptor(PasskeyClientConfig config) {
        this.config = config;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution exec) throws IOException {
        String traceId = config.traceIdProvider().get();
        if (traceId != null && !traceId.isBlank()) {
            request.getHeaders().set(TRACE_HEADER, traceId);
        }
        return exec.execute(request, body);
    }
}
