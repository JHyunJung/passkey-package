package com.crosscert.passkey.rpapp.web;

import com.crosscert.passkey.rpapp.user.InMemoryUserStore;
import com.crosscert.passkey.sdk.PasskeyClient;
import com.crosscert.passkey.sdk.dto.RegistrationStartResponse;
import com.crosscert.passkey.sdk.relay.RegistrationRelayCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * TLS 를 종료하는 리버스 프록시(nginx) 뒤에서의 요청 복원을 **실제 Tomcat** 으로 고정한다.
 *
 * <p>브라우저는 같은 origin 이어도 POST fetch 에 {@code Origin} 헤더를 붙인다. 프록시가 TLS 를
 * 풀어 평문 http 로 넘기면 rp-app 은 요청을 {@code http://<host>:80} 으로 보고, Origin
 * ({@code https://<host>}) 과 달라 cross-origin 으로 판정한다. RP_CORS_ALLOWED_ORIGINS 가 빈
 * 기본 배포에서는 이것이 403 "Invalid CORS request" 로 끝난다(등록·로그인 begin 이 전부 막힘).
 * {@code server.forward-headers-strategy=native}(Tomcat RemoteIpValve)가 신뢰 프록시(사설·루프백 IP)에서
 * 온 X-Forwarded-Proto/-Port 를 반영해야 같은 origin 으로 판정된다.
 *
 * <p>MockMvc 가 아니라 RANDOM_PORT 다 — native 는 서블릿 필터가 아니라 Tomcat 밸브라 MockMvc 로는 보이지 않는다.
 * 테스트 클라이언트는 루프백(신뢰 프록시)에서 접속하므로 "nginx 가 보낸 요청" 을 그대로 흉내 낸다.
 *
 * <p>rp.relay.secret 은 {@link com.crosscert.passkey.rpapp.config.PasskeyClientWiringIT} 와 같은
 * 이유로 비-데모 값을 넣는다(test 프로필에서 RelayKeyGuard 가 데모 키를 거부).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "rp.relay.secret=test-relay-secret-not-demo-0123456789")
@ActiveProfiles("test")
class ProxiedSameOriginPostTest {

    private static final String BODY = "{\"username\":\"alice\",\"displayName\":\"Alice\"}";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    int port;
    @Autowired
    ObjectMapper json;

    @MockitoBean
    PasskeyClient passkey;
    @MockitoBean
    InMemoryUserStore users;
    @MockitoBean
    RegistrationRelayCodec relay;

    /** 테스트 전용: 서버가 복원한 클라이언트 IP·스킴을 그대로 돌려준다. */
    @TestConfiguration
    @RestController
    static class EchoController {
        @GetMapping("/__test/echo-remote")
        String echo(HttpServletRequest req) {
            return req.getRemoteAddr() + "|" + req.getScheme() + "|" + req.isSecure() + "|" + req.getServerPort();
        }
    }

    @BeforeEach
    void stubUpstream() throws Exception {
        given(passkey.registrationStart(any())).willReturn(new RegistrationStartResponse(
                "reg-token", json.readTree("{\"challenge\":\"c\"}")));
        given(users.createPending(any(), any())).willReturn("h1");
        given(relay.encode(any(), any(), any(), any())).willReturn("relay.tok");
    }

    /** 프록시가 평문 http 로 넘긴 register/begin — Host 는 브라우저가 본 그대로(localhost)다. */
    private int proxiedBegin(String origin, String forwardedPort) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/passkey/register/begin"))
                .header("Origin", origin)
                .header("X-Forwarded-Proto", "https")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(BODY));
        if (forwardedPort != null) b.header("X-Forwarded-Port", forwardedPort);
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private String echo(String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/__test/echo-remote"));
        for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    /** 443 에서 TLS 종료(번들 nginx 서브도메인 구성). */
    @Test
    void sameOriginPost_behindTlsProxy_isNotTreatedAsCors() throws Exception {
        assertThat(proxiedBegin("https://localhost", null)).isEqualTo(200);
    }

    /**
     * 비표준 포트에서 TLS 종료(호스트 nginx 가 같은 도메인의 :8443 으로 RP 를 노출하는 구성).
     * X-Forwarded-Proto 는 포트를 스킴 기본값으로 되돌리므로 X-Forwarded-Port 까지 있어야 한다.
     */
    @Test
    void sameOriginPost_behindTlsProxyOnNonDefaultPort_isNotTreatedAsCors() throws Exception {
        assertThat(proxiedBegin("https://localhost:8443", "8443")).isEqualTo(200);
    }

    /** 반대 방향 가드: 프록시 헤더를 신뢰한다고 진짜 다른 origin 이 열리면 안 된다. */
    @Test
    void crossOriginPost_behindTlsProxy_isStillRejected() throws Exception {
        assertThat(proxiedBegin("https://evil.example.com", null)).isEqualTo(403);
    }

    /**
     * 클라이언트가 X-Forwarded-For 를 미리 채워 보내고 프록시가 실제 IP 를 덧붙인 경우(nginx
     * {@code $proxy_add_x_forwarded_for}). 맨 왼쪽(위조 값)이 아니라 신뢰 프록시를 오른쪽부터 건너뛴
     * 첫 외부 IP 가 클라이언트여야 한다 — 그렇지 않으면 감사 IP 위조·IP 기반 rate-limit 우회가 된다.
     */
    @Test
    void spoofedLeftmostForwardedFor_isIgnored_rightmostUntrustedHopWins() throws Exception {
        assertThat(echo("X-Forwarded-For", "6.6.6.6, 203.0.113.7")).startsWith("203.0.113.7|");
    }

    @Test
    void forwardedProtoAndPort_restoreSchemeSecureAndPort() throws Exception {
        assertThat(echo("X-Forwarded-Proto", "https", "X-Forwarded-Port", "8443")).endsWith("|https|true|8443");
    }

    @Test
    void noProxyHeaders_isNoOp() throws Exception {
        assertThat(echo()).isEqualTo("127.0.0.1|http|false|" + port);
    }
}
