package com.crosscert.passkey.sdk.internal;

import com.crosscert.passkey.sdk.PasskeyClientConfig;
import com.crosscert.passkey.sdk.exception.PasskeyConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * SDK 의 DEBUG 로그가 ID Token / publicKeyCredential 평문을 남기지 않도록
 * 본문 일부를 마스킹해 출력. 운영 환경에선 logging.level 을 INFO 이하로.
 */
public class RedactingRequestInterceptor implements ClientHttpRequestInterceptor {
    private static final Logger log = LoggerFactory.getLogger(RedactingRequestInterceptor.class);

    private final Supplier<String> apiKeySupplier;

    public RedactingRequestInterceptor(PasskeyClientConfig config) {
        this.apiKeySupplier = config.apiKeySupplier();
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution exec)
            throws IOException {
        // 매 요청마다 현재 유효 키를 다시 묻는다 — "재기동 없는 교체"의 심장.
        // 부팅 시 1회 캡처가 아니라 호출 시점 조회라, Supplier 뒤편(파일/시크릿
        // 매니저 등)에서 키가 바뀌면 다음 요청부터 자동 반영된다.
        String currentKey = apiKeySupplier.get();
        if (currentKey == null || currentKey.isBlank()) {
            // fail-fast: null/blank 키로 호출하면 서버가 401 을 주고 그 원인이
            // SDK 설정 문제임이 드러나지 않는다. 여기서 명확히 끊는다.
            throw new PasskeyConfigurationException(
                    "API key supplier returned null/blank — check api key source");
        }
        request.getHeaders().set("X-API-Key", currentKey);
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

    /**
     * Mask the API-key secret in any string that contains an
     * {@code X-API-Key: pk_<prefix><secret>} header rendering. Keeps the
     * 11-char prefix ({@code pk_} + 8 base64url chars — same shape used
     * by passkey-app's ApiKeyAuthFilter) for log correlation and replaces
     * the secret tail with {@code <redacted>}. Case-insensitive on the
     * header name so accidental {@code x-api-key} renderings are also
     * caught.
     *
     * <p>Character class is base64url ({@code A-Z a-z 0-9 _ -}) because
     * admin-app's ApiKeyAdminService generates secrets via
     * {@code Base64.getUrlEncoder().withoutPadding()}.
     */
    private static final Pattern API_KEY_HEADER =
            Pattern.compile("(?i)(X-API-Key\\s*[:=]\\s*\"?pk_[A-Za-z0-9_-]{8})[A-Za-z0-9_-]+");

    /** ID Token + publicKeyCredential.response.* 값은 길이만 노출. */
    static String redact(String json) {
        if (json == null || json.isEmpty()) return json;
        String out = json
                .replaceAll("(?s)\"idToken\"\\s*:\\s*\"([^\"]{0,16}).*?\"",
                            "\"idToken\":\"$1…<redacted>\"")
                .replaceAll("(?s)\"(clientDataJSON|attestationObject|authenticatorData|signature)\"\\s*:\\s*\"[^\"]*\"",
                            "\"$1\":\"<redacted>\"");
        out = API_KEY_HEADER.matcher(out).replaceAll("$1<redacted>");
        return out;
    }
}
