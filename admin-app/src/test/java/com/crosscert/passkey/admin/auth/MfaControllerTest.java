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
import java.time.OffsetDateTime;
import com.crosscert.passkey.core.config.KstTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.OptionalLong;

class MfaControllerTest {

    private final TotpService totp = mock(TotpService.class);
    private final AdminUserRepository users = mock(AdminUserRepository.class);
    private final RecoveryCodeService recoveryCodes = mock(RecoveryCodeService.class);
    private final MfaSecretCipher cipher = mock(MfaSecretCipher.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-11T00:00:00Z"), KstTime.ZONE);

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

    private AdminUser enrolledUser() {
        AdminUser u = AdminUser.create();
        u.setEmail("alice@example.com");
        u.setMfaEnabled(true);
        u.setMfaSecret("sealed-secret");
        return u;
    }

    @Test
    void verify_repeatedFailure_locksAccount() {
        AdminUser u = enrolledUser();
        when(users.findByEmail("alice@example.com")).thenReturn(Optional.of(u));
        when(cipher.open("sealed-secret")).thenReturn("PLAINSECRET");
        when(totp.verifyAt(any(), any(), anyLong())).thenReturn(false);   // always wrong
        when(recoveryCodes.consume(any(), any())).thenReturn(false);

        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpSession session = mock(HttpSession.class);
        when(req.getSession(false)).thenReturn(session);
        when(session.getAttribute(MfaPendingFilter.MFA_PENDING_ATTR)).thenReturn(Boolean.TRUE);

        for (int i = 0; i < 5; i++) {
            controller.verify(new MfaController.VerifyRequest("000000"), auth(), req);
        }
        assertThat(u.isLocked(OffsetDateTime.now(clock))).isTrue();
        verify(session).invalidate();   // session killed on lockout
    }

    @Test
    void verify_whenLocked_rejectsEvenCorrectCode() {
        AdminUser u = enrolledUser();
        u.recordFailedLogin(OffsetDateTime.now(clock), 1, Duration.ofMinutes(15)); // already locked
        when(users.findByEmail("alice@example.com")).thenReturn(Optional.of(u));
        when(cipher.open("sealed-secret")).thenReturn("PLAINSECRET");
        when(totp.verifyAt(any(), any(), anyLong())).thenReturn(true);   // correct code

        ResponseEntity<?> resp = controller.verify(
                new MfaController.VerifyRequest("123456"), auth(), pendingRequest(true));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void verify_success_resetsFailedCount() {
        AdminUser u = enrolledUser();
        u.recordFailedLogin(OffsetDateTime.now(clock), 5, Duration.ofMinutes(15)); // 1 fail, not locked
        when(users.findByEmail("alice@example.com")).thenReturn(Optional.of(u));
        when(cipher.open("sealed-secret")).thenReturn("PLAINSECRET");
        when(totp.verifyAt(any(), any(), anyLong())).thenReturn(true);
        when(totp.matchedCounter(any(), any(), anyLong())).thenReturn(OptionalLong.of(100L));

        ResponseEntity<?> resp = controller.verify(
                new MfaController.VerifyRequest("123456"), auth(), pendingRequest(true));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(u.isLocked(OffsetDateTime.now(clock))).isFalse();
    }

    // ---- F02: TOTP replay guard (RFC 6238 §5.2) --------------------------

    @Test
    void verify_success_persistsMatchedCounterAsLastTotpStep() {
        AdminUser u = enrolledUser();
        when(users.findByEmail("alice@example.com")).thenReturn(Optional.of(u));
        when(cipher.open("sealed-secret")).thenReturn("PLAINSECRET");
        when(totp.matchedCounter(any(), any(), anyLong())).thenReturn(OptionalLong.of(56789012345L));

        ResponseEntity<?> resp = controller.verify(
                new MfaController.VerifyRequest("123456"), auth(), pendingRequest(true));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(u.getMfaLastTotpStep()).isEqualTo(56789012345L);
    }

    @Test
    void verify_replayedStep_is401_andDoesNotClearMfaPending() {
        AdminUser u = enrolledUser();
        u.setMfaLastTotpStep(56789012345L); // already consumed by a prior verify
        when(users.findByEmail("alice@example.com")).thenReturn(Optional.of(u));
        when(cipher.open("sealed-secret")).thenReturn("PLAINSECRET");
        // Code still matches the current window (correct code, but reused).
        when(totp.matchedCounter(any(), any(), anyLong())).thenReturn(OptionalLong.of(56789012345L));
        when(recoveryCodes.consume(any(), any())).thenReturn(false);

        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpSession session = mock(HttpSession.class);
        when(req.getSession(false)).thenReturn(session);
        when(session.getAttribute(MfaPendingFilter.MFA_PENDING_ATTR)).thenReturn(Boolean.TRUE);

        ResponseEntity<?> resp = controller.verify(
                new MfaController.VerifyRequest("123456"), auth(), req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(session, never()).removeAttribute(MfaPendingFilter.MFA_PENDING_ATTR);
    }

    @Test
    void verify_stepOlderThanLast_is401() {
        AdminUser u = enrolledUser();
        u.setMfaLastTotpStep(56789012345L);
        when(users.findByEmail("alice@example.com")).thenReturn(Optional.of(u));
        when(cipher.open("sealed-secret")).thenReturn("PLAINSECRET");
        // Skew-window match on an older step than the stored high-water mark.
        when(totp.matchedCounter(any(), any(), anyLong())).thenReturn(OptionalLong.of(56789012344L));
        when(recoveryCodes.consume(any(), any())).thenReturn(false);

        ResponseEntity<?> resp = controller.verify(
                new MfaController.VerifyRequest("123456"), auth(), pendingRequest(true));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void verify_newerStepThanLast_isAccepted_andAdvancesHighWaterMark() {
        AdminUser u = enrolledUser();
        u.setMfaLastTotpStep(100L);
        when(users.findByEmail("alice@example.com")).thenReturn(Optional.of(u));
        when(cipher.open("sealed-secret")).thenReturn("PLAINSECRET");
        when(totp.matchedCounter(any(), any(), anyLong())).thenReturn(OptionalLong.of(101L));

        ResponseEntity<?> resp = controller.verify(
                new MfaController.VerifyRequest("123456"), auth(), pendingRequest(true));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(u.getMfaLastTotpStep()).isEqualTo(101L);
    }
}
