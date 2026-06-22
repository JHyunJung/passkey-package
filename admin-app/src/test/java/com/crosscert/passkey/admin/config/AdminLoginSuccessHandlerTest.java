package com.crosscert.passkey.admin.config;

import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.admin.policy.SecurityPolicyDto;
import com.crosscert.passkey.admin.policy.SecurityPolicyService;
import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import com.crosscert.passkey.core.config.KstTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminLoginSuccessHandlerTest {

    private AuthenticationSuccessHandler handlerWithPolicyMinutes(int minutes) {
        SecurityPolicyService policy = mock(SecurityPolicyService.class);
        // @Min(1) is enforced only on UpdateRequest; the View record constructor
        // has no constraint, so a 0/negative value can be built here to exercise
        // the defensive clamp path.
        when(policy.get()).thenReturn(new SecurityPolicyDto.View(
                minutes, false, List.of(), OffsetDateTime.now(), null));
        return handlerWithPolicy(policy);
    }

    private AuthenticationSuccessHandler handlerWithPolicy(SecurityPolicyService policy) {
        AuditLogService audit = mock(AuditLogService.class);
        AdminUserRepository users = mock(AdminUserRepository.class);
        Clock clock = Clock.fixed(Instant.parse("2026-06-04T00:00:00Z"), KstTime.ZONE);
        ObjectMapper mapper = new ObjectMapper();

        AdminUser u = mock(AdminUser.class);
        when(u.getRole()).thenReturn("PLATFORM_OPERATOR");
        when(u.isMfaEnabled()).thenReturn(false);
        when(users.findByEmail("op@example.com")).thenReturn(Optional.of(u));

        return new AdminSecurityConfig()
                .adminLoginSuccessHandler(audit, users, policy, clock, mapper);
    }

    @Test
    void appliesPolicyIdleTimeoutToSessionInSeconds() throws Exception {
        AuthenticationSuccessHandler handler = handlerWithPolicyMinutes(15);
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        Authentication auth =
                new UsernamePasswordAuthenticationToken("op@example.com", "x", List.of());

        handler.onAuthenticationSuccess(req, res, auth);

        assertThat(req.getSession(false)).isNotNull();
        assertThat(req.getSession(false).getMaxInactiveInterval()).isEqualTo(15 * 60);
    }

    @Test
    void defaultThirtyMinutesMapsTo1800Seconds() throws Exception {
        AuthenticationSuccessHandler handler = handlerWithPolicyMinutes(30);
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        Authentication auth =
                new UsernamePasswordAuthenticationToken("op@example.com", "x", List.of());

        handler.onAuthenticationSuccess(req, res, auth);

        assertThat(req.getSession(false).getMaxInactiveInterval()).isEqualTo(1800);
    }

    @Test
    void fallsBackToThirtyMinutesWhenPolicyThrows() throws Exception {
        SecurityPolicyService policy = mock(SecurityPolicyService.class);
        when(policy.get()).thenThrow(new IllegalStateException("missing"));
        AuthenticationSuccessHandler handler = handlerWithPolicy(policy);
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        Authentication auth =
                new UsernamePasswordAuthenticationToken("op@example.com", "x", List.of());

        handler.onAuthenticationSuccess(req, res, auth);

        assertThat(req.getSession(false)).isNotNull();
        assertThat(req.getSession(false).getMaxInactiveInterval()).isEqualTo(1800);
    }

    @Test
    void clampsNonPositivePolicyToDefault() throws Exception {
        AuthenticationSuccessHandler handler = handlerWithPolicyMinutes(0);
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        Authentication auth =
                new UsernamePasswordAuthenticationToken("op@example.com", "x", List.of());

        handler.onAuthenticationSuccess(req, res, auth);

        assertThat(req.getSession(false).getMaxInactiveInterval()).isEqualTo(1800);
    }
}
