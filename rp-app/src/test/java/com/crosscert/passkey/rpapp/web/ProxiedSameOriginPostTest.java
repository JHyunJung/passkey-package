package com.crosscert.passkey.rpapp.web;

import com.crosscert.passkey.rpapp.user.InMemoryUserStore;
import com.crosscert.passkey.sdk.PasskeyClient;
import com.crosscert.passkey.sdk.dto.RegistrationStartResponse;
import com.crosscert.passkey.sdk.relay.RegistrationRelayCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TLS 를 종료하는 리버스 프록시(nginx) 뒤에서 같은-origin POST 가 CORS 로 막히지 않는지 고정한다.
 *
 * <p>브라우저는 같은 origin 이어도 POST fetch 에 {@code Origin} 헤더를 붙인다. 프록시가 TLS 를
 * 풀어 평문 http 로 넘기면 rp-app 은 요청을 {@code http://<host>:80} 으로 보고, Origin
 * ({@code https://<host>}) 과 달라 cross-origin 으로 판정한다. RP_CORS_ALLOWED_ORIGINS 가 빈
 * 기본 배포에서는 이것이 403 "Invalid CORS request" 로 끝난다(등록·로그인 begin 이 전부 막힘).
 * {@code server.forward-headers-strategy=framework} 가 X-Forwarded-Proto/-Port 를 반영해야
 * 같은 origin 으로 판정된다.
 *
 * <p>{@link WebAuthnCorsDisabledTest} 와 달리 {@code @WebMvcTest} 가 아니다 — 슬라이스는
 * forward-headers-strategy 가 등록하는 ForwardedHeaderFilter 를 포함하지 않아 이 회귀를 볼 수 없다.
 * 절대 URL({@code http://<host>/...})로 "프록시가 평문 http 로 Host 를 그대로 넘긴" 요청을 만든다.
 *
 * <p>rp.relay.secret 은 {@link com.crosscert.passkey.rpapp.config.PasskeyClientWiringIT} 와 같은
 * 이유로 비-데모 값을 넣는다(test 프로필에서 RelayKeyGuard 가 데모 키를 거부).
 */
@SpringBootTest(properties = "rp.relay.secret=test-relay-secret-not-demo-0123456789")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProxiedSameOriginPostTest {

    private static final String BODY = "{\"username\":\"alice\",\"displayName\":\"Alice\"}";

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    @MockBean
    PasskeyClient passkey;
    @MockBean
    InMemoryUserStore users;
    @MockBean
    RegistrationRelayCodec relay;

    @BeforeEach
    void stubUpstream() throws Exception {
        given(passkey.registrationStart(any())).willReturn(new RegistrationStartResponse(
                "reg-token", json.readTree("{\"challenge\":\"c\"}")));
        given(users.createPending(any(), any())).willReturn("h1");
        given(relay.encode(any(), any(), any(), any())).willReturn("relay.tok");
    }

    /** 프록시가 평문 http 로 넘긴 register/begin — Host 는 브라우저가 본 그대로다. */
    private static MockHttpServletRequestBuilder proxiedBegin(String host, String origin) {
        return post("http://" + host + "/passkey/register/begin")
                .header("Origin", origin)
                .header("X-Forwarded-Proto", "https")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY);
    }

    /** 443 에서 TLS 종료(번들 nginx 서브도메인 구성). */
    @Test
    void sameOriginPost_behindTlsProxy_isNotTreatedAsCors() throws Exception {
        mvc.perform(proxiedBegin("rp-qa.example.com", "https://rp-qa.example.com"))
                .andExpect(status().isOk());
    }

    /**
     * 비표준 포트에서 TLS 종료(호스트 nginx 가 같은 도메인의 :8443 으로 RP 를 노출하는 구성).
     * X-Forwarded-Proto 는 포트를 스킴 기본값으로 되돌리므로 X-Forwarded-Port 까지 있어야 한다 —
     * Host 에 포트를 실어 보내는 것만으로는 403 이 난다(실측).
     */
    @Test
    void sameOriginPost_behindTlsProxyOnNonDefaultPort_isNotTreatedAsCors() throws Exception {
        mvc.perform(proxiedBegin("passkey-qa.example.com", "https://passkey-qa.example.com:8443")
                        .header("X-Forwarded-Port", "8443"))
                .andExpect(status().isOk());
    }

    /** 반대 방향 가드: 프록시 헤더를 신뢰한다고 진짜 다른 origin 이 열리면 안 된다. */
    @Test
    void crossOriginPost_behindTlsProxy_isStillRejected() throws Exception {
        mvc.perform(proxiedBegin("rp-qa.example.com", "https://evil.example.com"))
                .andExpect(status().isForbidden());
    }
}
