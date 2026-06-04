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
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminLoginSuccessHandlerTest {

    private AuthenticationSuccessHandler handlerWithPolicyMinutes(int minutes) {
        AuditLogService audit = mock(AuditLogService.class);
        AdminUserRepository users = mock(AdminUserRepository.class);
        SecurityPolicyService policy = mock(SecurityPolicyService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-06-04T00:00:00Z"), ZoneOffset.UTC);
        ObjectMapper mapper = new ObjectMapper();

        AdminUser u = mock(AdminUser.class);
        when(u.getRole()).thenReturn("PLATFORM_OPERATOR");
        when(u.isMfaEnabled()).thenReturn(false);
        when(users.findByEmail("op@example.com")).thenReturn(Optional.of(u));

        when(policy.get()).thenReturn(new SecurityPolicyDto.View(
                minutes, 12, false, List.of(), Instant.now(), null));

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
}
