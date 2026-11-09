package com.crosscert.passkey.admin.operator

import com.crosscert.passkey.core.config.KstTime
import com.crosscert.passkey.core.entity.AdminPasswordResetToken
import com.crosscert.passkey.core.entity.AdminUser
import com.crosscert.passkey.core.mail.MailSender
import com.crosscert.passkey.core.repository.AdminPasswordResetTokenRepository
import com.crosscert.passkey.core.repository.AdminUserRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.core.env.Environment
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class PasswordResetServiceTest {

    @Mock
    lateinit var tokens: AdminPasswordResetTokenRepository

    @Mock
    lateinit var users: AdminUserRepository

    @Mock
    lateinit var mail: MailSender

    @Mock
    lateinit var encoder: PasswordEncoder

    @Mock
    lateinit var env: Environment

    var clock: Clock = Clock.fixed(Instant.parse("2026-05-30T00:00:00Z"), KstTime.ZONE)
    lateinit var service: PasswordResetService

    @BeforeEach
    fun setUp() {
        service = PasswordResetService(tokens, users, mail, encoder, clock, env)
    }

    @Test
    fun request_existing_user_creates_token_and_sends_mail() {
        val u = AdminUser("op@crosscert.com", "h", "PLATFORM_OPERATOR")
        whenever(users.findByEmail("op@crosscert.com")).thenReturn(Optional.of(u))
        service.request("op@crosscert.com")
        verify(tokens).save(any<AdminPasswordResetToken>())
        verify(mail).send(eq("op@crosscert.com"), anyString(), anyString())
    }

    @Test
    fun request_unknown_user_is_silent_noop() {
        whenever(users.findByEmail("ghost@x.com")).thenReturn(Optional.empty())
        service.request("ghost@x.com")
        verify(tokens, never()).save(anyOrNull<AdminPasswordResetToken>())
        verify(mail, never()).send(anyOrNull(), anyOrNull(), anyOrNull())
    }

    @Test
    fun confirm_valid_token_resets_password_and_lockout() {
        val u = AdminUser("op@crosscert.com", "old", "PLATFORM_OPERATOR")
        val uid = u.id
        // lockout 을 실제로 걸어 단언을 load-bearing 하게 (maxAttempts=1 → 첫 호출에 즉시 lock).
        u.recordFailedLogin(OffsetDateTime.now(clock), 1, Duration.ofMinutes(15))
        assertThat(u.lockedUntil).isNotNull() // 사전조건
        val now = OffsetDateTime.now(clock)
        val tok = AdminPasswordResetToken(
            uid, service.hashForTest("plain-token"), "plain-to",
            now.plusSeconds(3600), now,
        )
        whenever(tokens.findByTokenHash(service.hashForTest("plain-token"))).thenReturn(Optional.of(tok))
        whenever(users.findById(uid)).thenReturn(Optional.of(u))
        whenever(encoder.encode("NewPassw0rd!")).thenReturn("newhash")

        service.confirm("plain-token", "NewPassw0rd!")

        assertThat(u.bcryptHash).isEqualTo("newhash")
        assertThat(tok.isConsumed).isTrue()
        assertThat(u.lockedUntil).isNull() // 이제 reset 이 실제로 해제했음을 증명
    }

    @Test
    fun confirm_expired_token_rejected() {
        val uid = UUID.randomUUID()
        val now = OffsetDateTime.now(clock)
        val tok = AdminPasswordResetToken(
            uid, service.hashForTest("t"), "tt",
            now.minusSeconds(1), now.minusSeconds(3600),
        )
        whenever(tokens.findByTokenHash(service.hashForTest("t"))).thenReturn(Optional.of(tok))
        assertThatThrownBy { service.confirm("t", "NewPassw0rd!") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun confirm_consumed_token_rejected() {
        val uid = UUID.randomUUID()
        val now = OffsetDateTime.now(clock)
        val tok = AdminPasswordResetToken(
            uid, service.hashForTest("t"), "tt",
            now.plusSeconds(3600), now,
        )
        tok.consume(now)
        whenever(tokens.findByTokenHash(service.hashForTest("t"))).thenReturn(Optional.of(tok))
        assertThatThrownBy { service.confirm("t", "NewPassw0rd!") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
