package com.crosscert.passkey.sdk.internal;

import com.crosscert.passkey.sdk.PasskeyClientConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * SDK 의 DEBUG 로그가 ID Token / publicKeyCredential 평문을 남기지 않도록
 * 본문 일부를 마스킹해 출력. 운영 환경에선 logging.level 을 INFO 이하로.
 */
public class RedactingRequestInterceptor implements ClientHttpRequestInterceptor {
    private static final Logger log = LoggerFactory.getLogger(RedactingRequestInterceptor.class);

    private final String apiKey;

    public RedactingRequestInterceptor(PasskeyClientConfig config) {
        this.apiKey = config.apiKey();
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution exec)
            throws IOException {
        request.getHeaders().set("X-API-Key", apiKey);
        // X-Trace-Id is set upstream by TraceIdPropagationInterceptor so it
        // is already present on the outgoing request when this interceptor
        // runs — see PasskeyClient interceptor chain ordering.
        if (log.isDebugEnabled()) {
            log.debug("→ {} {} body={}",
                    request.getMethod(), request.getURI(),
                    redact(new String(body, StandardCharsets.UTF_8)));
        }
        ClientHttpResponse res = exec.execute(request, body);
        if (log.isDebugEnabled()) {
            log.debug("← {} {} status={}", request.getMethod(), request.getURI(), res.getStatusCode());
        }
        return res;
    }

    /** ID Token + publicKeyCredential.response.* 값은 길이만 노출. */
    static String redact(String json) {
        if (json == null || json.isEmpty()) return json;
        return json
                .replaceAll("(?s)\"idToken\"\\s*:\\s*\"([^\"]{0,16}).*?\"",
                            "\"idToken\":\"$1…<redacted>\"")
                .replaceAll("(?s)\"(clientDataJSON|attestationObject|authenticatorData|signature)\"\\s*:\\s*\"[^\"]*\"",
                            "\"$1\":\"<redacted>\"");
    }
}
