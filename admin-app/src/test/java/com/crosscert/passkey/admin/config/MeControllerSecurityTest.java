package com.crosscert.passkey.admin.config;

import com.crosscert.passkey.admin.auth.AdminUserDetails;
import com.crosscert.passkey.admin.auth.MfaPendingFilter;
import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.crosscert.passkey.admin.web.AdminWebSliceTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AdminWebSliceTest
class MeControllerSecurityTest {

    @Autowired MockMvc mvc;
    @Autowired AdminUserRepository users;

    private Authentication operator() {
        AdminUserDetails principal = new AdminUserDetails(
                UUID.randomUUID(), "alice@example.com", "x",
                "PLATFORM_OPERATOR", java.util.Set.of(), true, null, java.time.Clock.systemUTC());
        return new UsernamePasswordAuthenticationToken(
                principal, "x", principal.getAuthorities());
    }

    private static AdminUser adminUser(boolean mfaEnabled) {
        var u = new AdminUser("alice@example.com", "x", "ADMIN");
        u.setMfaEnabled(mfaEnabled);
        return u;
    }

    @Test
    void anonymousIsUnauthorized() throws Exception {
        mvc.perform(get("/admin/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void me_returnsMfaEnabledAndMfaRequired_whenPending() throws Exception {
        when(users.findByEmail(anyString())).thenReturn(Optional.of(adminUser(true)));

        mvc.perform(get("/admin/api/me")
                .with(authentication(operator()))
                .sessionAttr(MfaPendingFilter.MFA_PENDING_ATTR, Boolean.TRUE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.email").value("alice@example.com"))
            .andExpect(jsonPath("$.data.role").value("PLATFORM_OPERATOR"))
            .andExpect(jsonPath("$.data.mfaEnabled").value(true))
            .andExpect(jsonPath("$.data.mfaRequired").value(true));
    }

    @Test
    void me_mfaRequiredFalse_whenNoPendingAttr() throws Exception {
        when(users.findByEmail(anyString())).thenReturn(Optional.of(adminUser(false)));

        mvc.perform(get("/admin/api/me")
                .with(authentication(operator())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.mfaEnabled").value(false))
            .andExpect(jsonPath("$.data.mfaRequired").value(false));
    }

    @Test
    void me_exposesSessionIdleTimeoutMinutes_fromMaxInactiveInterval() throws Exception {
        when(users.findByEmail(anyString())).thenReturn(Optional.of(adminUser(false)));

        var session = new org.springframework.mock.web.MockHttpSession();
        session.setMaxInactiveInterval(900); // 15분

        mvc.perform(get("/admin/api/me")
                .with(authentication(operator()))
                .session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.sessionIdleTimeoutMinutes").value(15));
    }

    @Test
    void me_fallsBackToThirtyMinutes_whenSessionAbsent() throws Exception {
        when(users.findByEmail(anyString())).thenReturn(Optional.of(adminUser(false)));

        // 세션을 첨부하지 않으면 컨트롤러의 req.getSession(false) 가 null →
        // AdminSecurityConfig.DEFAULT_IDLE_MINUTES(30) 폴백.
        mvc.perform(get("/admin/api/me")
                .with(authentication(operator())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.sessionIdleTimeoutMinutes").value(30));
    }

    // --- actuator 노출 회귀 가드 -------------------------------------------
    // AdminSecurityConfig 는 /actuator/health·/actuator/info 만 permitAll 이고
    // 나머지는 anyRequest().denyAll() 이 잡는다. 이 슬라이스에는 actuator 핸들러가
    // 없지만 보안 체인이 핸들러보다 먼저 돌므로, 인가 결정은 그대로 검증된다.

    @Test
    void anonymousActuatorPrometheus_isDenied() throws Exception {
        // permitAll 목록에 없으므로 denyAll → 401. 200 이 되면 노출 회귀다.
        mvc.perform(get("/actuator/prometheus").accept("application/json"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousActuatorHealth_passesSecurityButHasNoHandlerHere() throws Exception {
        // permitAll 이라 보안 체인은 통과하고, 슬라이스에 핸들러가 없어 404 가 된다.
        // 즉 "보안이 막아서 못 온 것이 아님" 을 보이는 대조군이다.
        mvc.perform(get("/actuator/health").accept("application/json"))
            .andExpect(status().isNotFound());
    }
}
