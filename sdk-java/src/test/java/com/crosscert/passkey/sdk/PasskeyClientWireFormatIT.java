package com.crosscert.passkey.sdk;

import com.crosscert.passkey.sdk.dto.AuthenticationFinishRequest;
import com.crosscert.passkey.sdk.dto.RegistrationFinishRequest;
import com.crosscert.passkey.sdk.dto.RegistrationStartRequest;
import com.crosscert.passkey.sdk.exception.PasskeyApiException;
import com.crosscert.passkey.sdk.exception.PasskeyAuthException;
import com.crosscert.passkey.sdk.exception.PasskeyRateLimitException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 와이어 형식·에러 매핑이 HTTP 메시지 컨버터 선택이나 Spring 버전에 좌우되지 않음을 고정한다.
 *
 * <p>요청 바디는 SDK 자체 Jackson 2 매퍼로 직렬화되어야 한다 — JsonNode 필드는 JSON 객체
 * 그대로 실려야 하며, bean 속성({"nodeType":"OBJECT",...})으로 새면 안 된다(Spring 7 + Jackson 3
 * 클래스패스에서 실제로 발생하던 회귀). 에러 매핑은 RestClient 가 호출하는 3-인자
 * {@code handleError} 경로를 통해 동작해야 한다.
 */
class PasskeyClientWireFormatIT {

    private static final String REG_FINISH = "/api/v1/rp/registration/finish";
    private static final String AUTH_FINISH = "/api/v1/rp/authentication/finish";
    private static final String REG_START = "/api/v1/rp/registration/start";

    private static final String OK_ENVELOPE =
            "{\"success\":true,\"code\":\"S000\",\"message\":\"OK\",\"data\":{}}";

    static WireMockServer wm;
    static PasskeyClient client;

    @BeforeAll
    static void start() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        client = PasskeyClient.of(PasskeyClientConfig.defaults(
                URI.create("http://localhost:" + wm.port()), () -> "ck_test_apikey"));
    }

    @AfterAll
    static void stop() {
        if (wm != null) wm.stop();
    }

    @BeforeEach
    void reset() {
        wm.resetAll();
    }

    private static ObjectNode credential() {
        ObjectNode n = new ObjectMapper().createObjectNode();
        n.put("id", "credId");
        n.put("type", "public-key");
        n.putObject("response").put("clientDataJSON", "Y2RK").put("attestationObject", "YXRP");
        return n;
    }

    private static final String CREDENTIAL_JSON =
            "{\"id\":\"credId\",\"type\":\"public-key\","
                    + "\"response\":{\"clientDataJSON\":\"Y2RK\",\"attestationObject\":\"YXRP\"}}";

    // ── 요청 와이어 형식 ────────────────────────────────────────────

    @Test
    void registrationFinish_sendsJsonNodeAsExactJsonObject() {
        wm.stubFor(post(urlEqualTo(REG_FINISH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(OK_ENVELOPE)));

        client.registrationFinish(new RegistrationFinishRequest("rt_1", credential()));

        wm.verify(postRequestedFor(urlEqualTo(REG_FINISH))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson(
                        "{\"registrationToken\":\"rt_1\",\"publicKeyCredential\":" + CREDENTIAL_JSON + "}")));
    }

    @Test
    void authenticationFinish_sendsJsonNodeAsExactJsonObject() {
        wm.stubFor(post(urlEqualTo(AUTH_FINISH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(OK_ENVELOPE)));

        client.authenticationFinish(new AuthenticationFinishRequest("at_1", credential()));

        wm.verify(postRequestedFor(urlEqualTo(AUTH_FINISH))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson(
                        "{\"authenticationToken\":\"at_1\",\"publicKeyCredential\":" + CREDENTIAL_JSON + "}")));
    }

    // ── 에러 매핑 ──────────────────────────────────────────────────

    private static void stubError(int status, String body, String retryAfter) {
        var resp = aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body);
        if (retryAfter != null) resp = resp.withHeader("Retry-After", retryAfter);
        wm.stubFor(post(urlEqualTo(REG_START)).willReturn(resp));
    }

    private static Throwable callRegistrationStart() {
        return org.assertj.core.api.Assertions.catchThrowable(() ->
                client.registrationStart(new RegistrationStartRequest("u", "A", "a@b.c")));
    }

    private static String errorEnvelope(String code, String message) {
        return "{\"success\":false,\"code\":\"" + code + "\",\"message\":\"" + message + "\","
                + "\"error\":{\"errorCode\":\"" + code + "\",\"fieldErrors\":"
                + "[{\"field\":\"userHandle\",\"rejectedValue\":\"u\",\"reason\":\"invalid\"}]},"
                + "\"traceId\":\"trace-1\"}";
    }

    @Test
    void on400WithEnvelope_throwsPasskeyApiException_withEnvelopeFields() {
        stubError(400, errorEnvelope("C001", "invalid input"), null);

        Throwable t = callRegistrationStart();

        assertThat(t).isExactlyInstanceOf(PasskeyApiException.class).hasMessage("invalid input");
        PasskeyApiException e = (PasskeyApiException) t;
        assertThat(e.getHttpStatus()).isEqualTo(400);
        assertThat(e.getCode()).isEqualTo("C001");
        assertThat(e.getTraceId()).isEqualTo("trace-1");
        assertThat(e.getFieldErrors()).singleElement()
                .satisfies(fe -> assertThat(fe.field()).isEqualTo("userHandle"));
    }

    @Test
    void on401WithEnvelope_throwsPasskeyAuthException() {
        stubError(401, errorEnvelope("A001", "bad api key"), null);

        assertThatThrownBy(PasskeyClientWireFormatIT::registrationStartOrThrow)
                .isInstanceOf(PasskeyAuthException.class)
                .satisfies(t -> {
                    PasskeyAuthException e = (PasskeyAuthException) t;
                    assertThat(e.getHttpStatus()).isEqualTo(401);
                    assertThat(e.getCode()).isEqualTo("A001");
                });
    }

    @Test
    void on429WithRetryAfter_throwsPasskeyRateLimitException() {
        stubError(429, errorEnvelope("R001", "slow down"), "7");

        assertThatThrownBy(PasskeyClientWireFormatIT::registrationStartOrThrow)
                .isInstanceOf(PasskeyRateLimitException.class)
                .satisfies(t -> {
                    PasskeyRateLimitException e = (PasskeyRateLimitException) t;
                    assertThat(e.getHttpStatus()).isEqualTo(429);
                    assertThat(e.getCode()).isEqualTo("R001");
                    assertThat(e.getRetryAfterSeconds()).isEqualTo(7L);
                });
    }

    @Test
    void on500WithoutEnvelope_throwsPasskeyApiException_withFallbackCode() {
        stubError(500, "Internal Server Error", null);

        Throwable t = callRegistrationStart();

        assertThat(t).isExactlyInstanceOf(PasskeyApiException.class);
        PasskeyApiException e = (PasskeyApiException) t;
        assertThat(e.getHttpStatus()).isEqualTo(500);
        assertThat(e.getCode()).isEqualTo("C999");
    }

    private static void registrationStartOrThrow() {
        client.registrationStart(new RegistrationStartRequest("u", "A", "a@b.c"));
    }
}
