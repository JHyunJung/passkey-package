package com.crosscert.passkey.rpapp.web;

import com.crosscert.passkey.rpapp.config.CorsProperties;
import com.crosscert.passkey.rpapp.config.PasskeyProperties;
import com.crosscert.passkey.rpapp.config.WebSecurityConfig;
import com.crosscert.passkey.rpapp.user.InMemoryUserStore;
import com.crosscert.passkey.sdk.PasskeyClient;
import com.crosscert.passkey.sdk.dto.RegistrationStartResponse;
import com.crosscert.passkey.sdk.relay.RegistrationRelayCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code RP_CORS_ALLOWED_ORIGINS} 를 비운 배포(= cross-origin 을 쓰지 않는 기본 배포)에서
 * CORS 가 실제로 비활성인지 HTTP 레벨에서 고정한다.
 *
 * <p>"비활성" 은 별도의 분기 코드가 아니라 {@link WebSecurityConfig} 가
 * {@code setAllowedOrigins(빈 목록)} 을 넘긴 결과다 — 어떤 Origin 도 매칭되지 않아
 * Spring Security 의 CorsFilter 가 preflight 를 거부한다. 암묵적 동작이라 회귀해도
 * 조용히 넘어갈 수 있어(예: 나중에 누가 반사 허용으로 "고치는" 경우) 테스트로 못박는다.
 *
 * <p>{@link WebAuthnControllerTest} 와 같은 슬라이스·같은 @MockBean 구성이며
 * 차이는 FixtureConfig 의 CorsProperties 가 <b>빈 목록</b>이라는 점 하나다.
 */
@WebMvcTest(WebAuthnController.class)
@Import(WebSecurityConfig.class)
class WebAuthnCorsDisabledTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    @MockBean
    PasskeyClient passkey;
    @MockBean
    InMemoryUserStore users;
    @MockBean
    PasskeyProperties props;
    @MockBean
    RegistrationRelayCodec relay;

    @TestConfiguration
    static class FixtureConfig {
        /** 허용 origin 없음 — RP_CORS_ALLOWED_ORIGINS 를 비운 배포와 같은 상태. */
        @Bean
        CorsProperties corsProperties() {
            return new CorsProperties(List.of());
        }
    }

    /**
     * cross-origin preflight 는 거부되고, 무엇보다 ACAO 헤더가 <b>붙지 않아야</b> 한다
     * (헤더가 있으면 브라우저가 cross-origin 을 허용해 버린다 — 상태 코드보다 이쪽이 핵심 단언).
     */
    @Test
    void preflight_isRejected_whenNoOriginsConfigured() throws Exception {
        mvc.perform(options("/passkey/register/begin")
                        .header("Origin", "https://app.example.com")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    /**
     * 반대 방향 가드: CORS 를 끈 것이 같은-origin 데모 자체를 막으면 안 된다.
     * Origin 헤더 없는 평범한 POST 는 정상 처리된다(CorsFilter 는 관여하지 않는다).
     */
    @Test
    void sameOriginPost_stillWorks_withoutCors() throws Exception {
        given(passkey.registrationStart(any())).willReturn(new RegistrationStartResponse(
                "reg-token", json.readTree("{\"challenge\":\"c\"}")));
        given(users.createPending(any(), any())).willReturn("h1");
        given(relay.encode(any(), any(), any(), any())).willReturn("relay.tok");

        mvc.perform(post("/passkey/register/begin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"displayName\":\"Alice\"}"))
                .andExpect(status().isOk());
    }
}
