package com.crosscert.passkey.admin.auth;

import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.TestingAuthenticationToken;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MfaControllerTest {

    private final TotpService totp = mock(TotpService.class);
    private final AdminUserRepository users = mock(AdminUserRepository.class);
    private final RecoveryCodeService recoveryCodes = mock(RecoveryCodeService.class);
    private final MfaSecretCipher cipher = mock(MfaSecretCipher.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-11T00:00:00Z"), ZoneOffset.UTC);

    // max-attempts=5, duration=15m mirror AdminSecurityConfig defaults.
    private final MfaController controller =
            new MfaController(totp, users, clock, recoveryCodes, cipher, 5, Duration.ofMinutes(15));

    private HttpServletRequest pendingRequest(boolean pending) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpSession session = mock(HttpSession.class);
        when(req.getSession(false)).thenReturn(session);
        when(session.getAttribute(MfaPendingFilter.MFA_PENDING_ATTR))
                .thenReturn(pending ? Boolean.TRUE : null);
        return req;
    }

    private TestingAuthenticationToken auth() {
        return new TestingAuthenticationToken("alice@example.com", "x", "ROLE_PLATFORM_OPERATOR");
    }

    @Test
    void enroll_whilePending_is403_andSecretUntouched() {
        ResponseEntity<?> resp = controller.enroll(auth(), pendingRequest(true));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // critical: pending enroll must NOT overwrite the secret.
        verify(users, never()).save(any());
        verify(totp, never()).newSecretBase32();
    }

    @Test
    void disable_whilePending_is403() {
        ResponseEntity<?> resp = controller.disable(
                new MfaController.VerifyRequest("123456"), auth(), pendingRequest(true));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(users, never()).save(any());
    }

    @Test
    void confirm_whilePending_is403() {
        ResponseEntity<?> resp = controller.confirm(
                new MfaController.VerifyRequest("123456"), auth(), pendingRequest(true));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(users, never()).save(any());
    }
}
