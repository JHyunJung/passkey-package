package com.crosscert.passkey.admin.operator;

import com.crosscert.passkey.admin.policy.PasswordPolicyValidator;
import com.crosscert.passkey.core.entity.AdminPasswordResetToken;
import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.mail.MailSender;
import com.crosscert.passkey.core.repository.AdminPasswordResetTokenRepository;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock AdminPasswordResetTokenRepository tokens;
    @Mock AdminUserRepository users;
    @Mock MailSender mail;
    @Mock PasswordEncoder encoder;
    @Mock PasswordPolicyValidator policy;
    Clock clock = Clock.fixed(Instant.parse("2026-05-30T00:00:00Z"), ZoneOffset.UTC);
    PasswordResetService service;

    @BeforeEach
    void setUp() {
        service = new PasswordResetService(tokens, users, mail, encoder, policy, clock);
    }

    @Test
    void request_existing_user_creates_token_and_sends_mail() {
        AdminUser u = new AdminUser("op@crosscert.com", "h", "PLATFORM_OPERATOR");
        when(users.findByEmail("op@crosscert.com")).thenReturn(Optional.of(u));
        service.request("op@crosscert.com");
        verify(tokens).save(any(AdminPasswordResetToken.class));
        verify(mail).send(eq("op@crosscert.com"), anyString(), anyString());
    }

    @Test
    void request_unknown_user_is_silent_noop() {
        when(users.findByEmail("ghost@x.com")).thenReturn(Optional.empty());
        service.request("ghost@x.com");
        verify(tokens, never()).save(any());
        verify(mail, never()).send(any(), any(), any());
    }

    @Test
    void confirm_valid_token_resets_password_and_lockout() {
        AdminUser u = new AdminUser("op@crosscert.com", "old", "PLATFORM_OPERATOR");
        UUID uid = u.getId();
        // lockout 을 실제로 걸어 단언을 load-bearing 하게 (maxAttempts=1 → 첫 호출에 즉시 lock).
        u.recordFailedLogin(clock.instant(), 1, Duration.ofMinutes(15));
        assertThat(u.getLockedUntil()).isNotNull();  // 사전조건
        OffsetDateTime now = OffsetDateTime.now(clock);
        AdminPasswordResetToken tok = new AdminPasswordResetToken(
                uid, service.hashForTest("plain-token"), "plain-to",
                now.plusSeconds(3600), now);
        when(tokens.findByTokenHash(service.hashForTest("plain-token"))).thenReturn(Optional.of(tok));
        when(users.findById(uid)).thenReturn(Optional.of(u));
        when(encoder.encode("NewPassw0rd!")).thenReturn("newhash");

        service.confirm("plain-token", "NewPassw0rd!");

        verify(policy).validate("NewPassw0rd!");
        assertThat(u.getBcryptHash()).isEqualTo("newhash");
        assertThat(tok.isConsumed()).isTrue();
        assertThat(u.getLockedUntil()).isNull();     // 이제 reset 이 실제로 해제했음을 증명
    }

    @Test
    void confirm_expired_token_rejected() {
        UUID uid = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(clock);
        AdminPasswordResetToken tok = new AdminPasswordResetToken(
                uid, service.hashForTest("t"), "tt",
                now.minusSeconds(1), now.minusSeconds(3600));
        when(tokens.findByTokenHash(service.hashForTest("t"))).thenReturn(Optional.of(tok));
        assertThatThrownBy(() -> service.confirm("t", "NewPassw0rd!"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void confirm_consumed_token_rejected() {
        UUID uid = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(clock);
        AdminPasswordResetToken tok = new AdminPasswordResetToken(
                uid, service.hashForTest("t"), "tt",
                now.plusSeconds(3600), now);
        tok.consume(now);
        when(tokens.findByTokenHash(service.hashForTest("t"))).thenReturn(Optional.of(tok));
        assertThatThrownBy(() -> service.confirm("t", "NewPassw0rd!"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
