package com.crosscert.passkey.rpapp.web;

import com.crosscert.passkey.rpapp.config.CorsProperties;
import com.crosscert.passkey.rpapp.config.PasskeyProperties;
import com.crosscert.passkey.rpapp.config.WebSecurityConfig;
import com.crosscert.passkey.rpapp.user.InMemoryUserStore;
import com.crosscert.passkey.rpapp.user.RpAppUser;
import com.crosscert.passkey.rpapp.web.relay.RegRelayCodec;
import com.crosscert.passkey.sdk.PasskeyClient;
import com.crosscert.passkey.sdk.dto.AuthenticationFinishResponse;
import com.crosscert.passkey.sdk.dto.AuthenticationStartResponse;
import com.crosscert.passkey.sdk.dto.RegistrationFinishResponse;
import com.crosscert.passkey.sdk.dto.RegistrationStartResponse;
import com.crosscert.passkey.sdk.idtoken.IdTokenClaims;
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
import org.springframework.test.web.servlet.MvcResult;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * WebAuthnController 슬라이스 테스트. 무상태 전환의 핵심 계약을 검증한다:
 * <ul>
 *   <li>register/begin 이 단일 regRelayToken 을 응답에 싣고 세션·쿠키를 만들지 않는다.</li>
 *   <li>authenticate/finish 가 id-token/JWT 를 응답에 노출하지 않는다(회귀 가드).</li>
 *   <li>register/finish 가 클라이언트 userHandle 을 받지 않고 relay.decode 결과만 신뢰한다.</li>
 *   <li>relay/auth 토큰 누락 시 Bean Validation 으로 400 을 낸다.</li>
 * </ul>
 *
 * 실제 {@link WebSecurityConfig}(STATELESS + CSRF disable + CORS 화이트리스트)를 함께
 * 띄워 쿠키 부재를 충실히 검증한다. 컨트롤러 협력자 4개는 @MockBean 으로 stub.
 */
@WebMvcTest(WebAuthnController.class)
@Import(WebSecurityConfig.class)
class WebAuthnControllerTest {

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
    RegRelayCodec relay;

    @TestConfiguration
    static class FixtureConfig {
        /** WebSecurityConfig 가 요구하는 CORS 화이트리스트(정확 origin 1개). */
        @Bean
        CorsProperties corsProperties() {
            return new CorsProperties(List.of("https://app.example.com"));
        }
    }

    // ── 1. register/begin: regRelayToken 응답 + 세션/쿠키 미사용 ──────────

    @Test
    void registerBegin_returnsRegRelayToken_andCreationOptions_withoutSession() throws Exception {
        given(passkey.registrationStart(any())).willReturn(new RegistrationStartResponse(
                "reg-token-upstream",
                json.readTree("{\"challenge\":\"abc\"}")));
        given(users.createPending("alice", "Alice")).willReturn("handle-alice");
        given(relay.encode("reg-token-upstream", "handle-alice", "alice", "Alice"))
                .willReturn("opaque.relay.token");

        MvcResult result = mvc.perform(post("/passkey/register/begin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"displayName\":\"Alice\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.regRelayToken").value("opaque.relay.token"))
                .andExpect(jsonPath("$.data.publicKeyCredentialCreationOptions.challenge").value("abc"))
                // 무상태 단일-토큰 계약: 원시 registrationToken·userHandle 은 응답에 노출되면 안 된다
                // (오직 불투명 regRelayToken 만). 새 키가 실수로 추가되어도 잡히도록 명시 부재 검증.
                .andExpect(jsonPath("$.data.registrationToken").doesNotExist())
                .andExpect(jsonPath("$.data.userHandle").doesNotExist())
                // 무상태: 서버 세션 attribute 가 생기지 않는다.
                .andExpect(request().sessionAttributeDoesNotExist("registrationToken", "userHandle"))
                .andReturn();

        // 원시 upstream registrationToken("reg-token-upstream") 은 응답 어디에도 노출 금지
        // (regRelayToken 안에 HMAC 봉인되어 있을 뿐, 평문으로 새지 않는다).
        assertThat(result.getResponse().getContentAsString()).doesNotContain("reg-token-upstream");

        // 무상태: 클라이언트에게 Set-Cookie(JSESSIONID·XSRF-TOKEN) 가 전혀 가지 않아야 한다.
        // (서버측 MockHttpServletRequest 세션은 MockMvc 인프라 산물이라 신뢰 불가 →
        //  실제로 클라이언트에 도달하는 응답 쿠키/헤더만 검증한다.)
        assertThat(result.getResponse().getCookies()).isEmpty();
        assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
    }

    // ── 2. authenticate/finish: id-token/JWT 미노출 (회귀 가드) ────────────

    @Test
    void authenticateFinish_doesNotExposeIdTokenOrJwt() throws Exception {
        String jwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJoYW5kbGUtYWxpY2UifQ.SIGPART";
        given(passkey.authenticationFinish(any())).willReturn(
                new AuthenticationFinishResponse(jwt, "Bearer", 300L));
        given(passkey.verifyIdToken(jwt)).willReturn(new IdTokenClaims(
                "https://issuer.example.com/7f00dead-0000-0000-0000-000000000001",
                "handle-alice",
                "7f00dead-0000-0000-0000-000000000001",
                Instant.now(),
                Instant.now().plusSeconds(300),
                List.of("user_verified"),
                "cred-1",
                "aaguid-1"));
        given(props.tenantId()).willReturn("7f00dead-0000-0000-0000-000000000001");
        given(props.issuerBase()).willReturn(URI.create("https://issuer.example.com"));
        given(users.findByUserHandle("handle-alice")).willReturn(Optional.of(
                new RpAppUser("handle-alice", "alice", "Alice", Instant.now(), "cred-1")));

        MvcResult result = mvc.perform(post("/passkey/authenticate/finish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publicKeyCredential\":{\"id\":\"x\"},\"authenticationToken\":\"auth-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authenticated").value(true))
                .andExpect(jsonPath("$.data.userHandle").value("handle-alice"))
                .andExpect(jsonPath("$.data.displayName").value("Alice"))
                // id-token 관련 키는 응답 data 에 어떤 표기로도 존재하면 안 된다.
                .andExpect(jsonPath("$.data.idToken").doesNotExist())
                .andExpect(jsonPath("$.data.id_token").doesNotExist())
                .andExpect(jsonPath("$.data.token").doesNotExist())
                .andReturn();

        // 회귀 가드: 응답 body 어디에도 JWT 자체나 토큰 키(camel/snake)가 들어가면 안 된다.
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("eyJhbGciOi");   // JWT header prefix
        assertThat(body).doesNotContain(jwt);
        assertThat(body).doesNotContain("idToken");
        assertThat(body).doesNotContain("id_token");
        assertThat(body).doesNotContain("Bearer");

        // 헤더 경로로도 누출 금지: Authorization·Set-Cookie 에 JWT 가 실리면 안 된다.
        assertThat(result.getResponse().getHeader("Authorization")).isNull();
        assertThat(result.getResponse().getHeaders("Set-Cookie"))
                .noneMatch(h -> h.contains("eyJhbGciOi"));
    }

    // ── 3. CSRF/세션 쿠키 부재 (register/begin Set-Cookie 없음) ────────────

    @Test
    void registerBegin_setsNoSessionOrCsrfCookie() throws Exception {
        given(passkey.registrationStart(any())).willReturn(new RegistrationStartResponse(
                "reg-token", json.readTree("{\"challenge\":\"c\"}")));
        given(users.createPending(any(), any())).willReturn("h1");
        given(relay.encode(any(), any(), any(), any())).willReturn("relay.tok");

        MvcResult result = mvc.perform(post("/passkey/register/begin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bob\",\"displayName\":\"Bob\"}"))
                .andExpect(status().isOk())
                .andReturn();

        List<String> setCookies = result.getResponse().getHeaders("Set-Cookie");
        assertThat(setCookies)
                .noneMatch(c -> c.contains("JSESSIONID"))
                .noneMatch(c -> c.contains("XSRF-TOKEN"));
        assertThat(result.getResponse().getCookies()).isEmpty();
    }

    // ── 4. finish 토큰 누락 → 400 (Bean Validation @NotBlank) ──────────────

    @Test
    void authenticateFinish_missingAuthenticationToken_returns400() throws Exception {
        mvc.perform(post("/passkey/authenticate/finish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publicKeyCredential\":{\"id\":\"x\"}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void registerFinish_missingRegRelayToken_returns400() throws Exception {
        mvc.perform(post("/passkey/register/finish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publicKeyCredential\":{\"id\":\"x\"}}"))
                .andExpect(status().isBadRequest());
    }

    // ── 5. register/finish: 클라이언트 userHandle 미수신 — relay.decode 만 신뢰 ──

    @Test
    void registerFinish_usesRelayUserHandle_notClientSupplied() throws Exception {
        // relay 토큰만이 userHandle 의 출처. body 에 클라이언트가 임의 userHandle 을 끼워넣어도
        // RegisterCompleteReq 에 해당 필드가 없어 무시되고, confirmRegistration 은 relay 의 값으로 호출된다.
        given(relay.decode("opaque.relay")).willReturn(
                new RegRelayCodec.RegRelay("reg-token-upstream", "relay-handle", "relay-user", "Relay User"));
        given(passkey.registrationFinish(any())).willReturn(new RegistrationFinishResponse(
                "cred-abc", "aaguid", "packed", "2026-01-01T00:00:00Z"));

        mvc.perform(post("/passkey/register/finish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publicKeyCredential\":{\"id\":\"x\"},"
                                + "\"regRelayToken\":\"opaque.relay\","
                                + "\"userHandle\":\"ATTACKER-CONTROLLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.credentialId").value("cred-abc"));

        // 확정은 relay 가 복원한 userHandle/username/displayName 로만 일어난다(클라이언트 값 무시).
        verify(users).confirmRegistration(
                eq("relay-handle"), eq("relay-user"), eq("Relay User"), eq("cred-abc"));
    }

    // ── 6. register/finish: username 점유 선검사 — upstream finish 전에 거부 ──

    @Test
    void registerFinish_usernameTakenByOther_rejectsBeforeUpstreamFinish() throws Exception {
        // relay 의 username 이 이미 다른 handle 로 점유됨 → upstream finish 호출 전 USERNAME_TAKEN.
        given(relay.decode("opaque.relay")).willReturn(
                new RegRelayCodec.RegRelay("reg-token-upstream", "relay-handle", "taken-user", "Taken User"));
        given(users.isUsernameTakenByOther("taken-user", "relay-handle")).willReturn(true);

        mvc.perform(post("/passkey/register/finish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publicKeyCredential\":{\"id\":\"x\"},"
                                + "\"regRelayToken\":\"opaque.relay\"}"))
                .andExpect(status().isConflict())   // USERNAME_TAKEN → 409
                .andExpect(jsonPath("$.code").value("W001"))
                .andExpect(jsonPath("$.error.errorCode").value("W001"));

        // 핵심: upstream 에 credential 을 만들지 않고(불일치 방지), 확정도 일어나지 않는다.
        verify(passkey, never()).registrationFinish(any());
        verify(users, never()).confirmRegistration(any(), any(), any(), any());
    }
}
