package com.crosscert.passkey.admin.operator

import com.crosscert.passkey.core.config.KstTime
import com.crosscert.passkey.core.entity.AdminPasswordResetToken
import com.crosscert.passkey.core.entity.AdminUser
import com.crosscert.passkey.core.mail.MailSender
import com.crosscert.passkey.core.repository.AdminPasswordResetTokenRepository
import com.crosscert.passkey.core.repository.AdminUserRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.core.env.Environment
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Optional
import java.util.UUID
import java.util.concurrent.Executor
import java.util.regex.Pattern

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
        // S9: 발송은 AfterCommitMailDispatcher 경유. 단위 테스트는 동일 스레드 executor 로
        // 실행해 mail.send 를 동기적으로 검증한다(운영은 커밋 후 pwreset-mail- 스레드).
        service = PasswordResetService(
            tokens, users, AfterCommitMailDispatcher(mail, Executor { it.run() }), encoder, clock, env,
        )
    }

    @AfterEach
    fun clearTxSync() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization()
        }
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

    /**
     * S9 특성화 — 메일 수신자(요청 원문 email), 제목, 본문 전체 문자열, 저장 토큰을 고정한다.
     * 메일을 비동기로 바꾼 뒤에도 그대로 통과해야 한다.
     */
    @Test
    fun request_mail_recipient_subject_and_body_are_pinned() {
        ReflectionTestUtils.setField(service, "baseUrl", "https://admin.example.com")
        val u = AdminUser("op@crosscert.com", "h", "PLATFORM_OPERATOR")
        whenever(users.findByEmail("Op@CrossCert.com")).thenReturn(Optional.of(u))

        service.request("Op@CrossCert.com")

        val tokCap = argumentCaptor<AdminPasswordResetToken>()
        verify(tokens).save(tokCap.capture())
        val bodyCap = argumentCaptor<String>()
        verify(mail, times(1)).send(eq("Op@CrossCert.com"), eq("비밀번호 재설정 — Passkey2"), bodyCap.capture())

        val body = bodyCap.firstValue
        val m = Pattern.compile("token=(rst_[0-9a-f]{64})").matcher(body)
        assertThat(m.find()).isTrue()
        val plaintext = m.group(1)
        val url = "https://admin.example.com/reset-password?token=$plaintext"
        assertThat(body).isEqualTo(
            "재설정 URL: <a href=\"$url\">$url</a><br>만료: 2026-05-30T10:00+09:00",
        )

        val tok = tokCap.firstValue
        assertThat(tok.tokenHash).isEqualTo(service.hashForTest(plaintext))
        assertThat(tok.tokenPrefix).isEqualTo(plaintext.substring(0, 8))
        assertThat(tok.expiresAt).isEqualTo(OffsetDateTime.now(clock).plusHours(1))
        assertThat(tok.isConsumed).isFalse()
    }

    /** S9 특성화 — 메일 발송이 실패해도 request 는 예외 없이 끝나고 토큰은 저장된다. */
    @Test
    fun request_mail_failure_is_swallowed_and_token_kept() {
        val u = AdminUser("op@crosscert.com", "h", "PLATFORM_OPERATOR")
        whenever(users.findByEmail("op@crosscert.com")).thenReturn(Optional.of(u))
        doThrow(RuntimeException("mail send failed"))
            .whenever(mail).send(anyString(), anyString(), anyString())

        assertThatCode { service.request("op@crosscert.com") }.doesNotThrowAnyException()

        verify(tokens).save(any<AdminPasswordResetToken>())
        verify(mail, times(1)).send(eq("op@crosscert.com"), anyString(), anyString())
    }

    /**
     * S9 — 트랜잭션 안에서는 메일을 보내지 않고, 커밋 이후에만 정확히 1회 보낸다.
     * 응답 시간이 SMTP 왕복에 좌우되지 않게 해서 계정 존재 여부가 드러나지 않게 한다.
     */
    @Test
    fun request_defers_mail_until_after_commit() {
        val u = AdminUser("op@crosscert.com", "h", "PLATFORM_OPERATOR")
        whenever(users.findByEmail("op@crosscert.com")).thenReturn(Optional.of(u))
        TransactionSynchronizationManager.initSynchronization()
        try {
            service.request("op@crosscert.com")

            verify(tokens).save(any<AdminPasswordResetToken>())
            verify(mail, never()).send(anyOrNull(), anyOrNull(), anyOrNull())

            val syncs = TransactionSynchronizationManager.getSynchronizations()
            assertThat(syncs).hasSize(1)
            syncs.forEach { it.afterCommit() }

            verify(mail, times(1)).send(eq("op@crosscert.com"), eq("비밀번호 재설정 — Passkey2"), anyString())
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    /** S9 — 롤백되면(토큰 미저장) 죽은 링크가 담긴 메일을 보내지 않는다. */
    @Test
    fun request_rolled_back_sends_no_mail() {
        val u = AdminUser("op@crosscert.com", "h", "PLATFORM_OPERATOR")
        whenever(users.findByEmail("op@crosscert.com")).thenReturn(Optional.of(u))
        TransactionSynchronizationManager.initSynchronization()
        try {
            service.request("op@crosscert.com")
            TransactionSynchronizationManager.getSynchronizations()
                .forEach { it.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK) }
            verify(mail, never()).send(anyOrNull(), anyOrNull(), anyOrNull())
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = [0, 1, 11, 73])
    fun confirm_rejects_password_outside_policy_before_touching_token(len: Int) {
        // 가입(SignupRequestDto)과 같은 정책(12자 이상·72바이트 이하). REST 경로는 화면 검사를 거치지 않으므로 서비스가 최종 방어선이다.
        assertThatThrownBy { service.confirm("t", "a".repeat(len)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageNotContaining("token") // 화면이 'token' 단어로 만료 카드를 고르므로 섞이면 안 된다
        org.mockito.kotlin.verifyNoInteractions(tokens, users, encoder) // 토큰 상태를 드러내지 않는다
    }

    @Test
    fun confirm_rejects_null_password() {
        assertThatThrownBy { service.confirm("t", null) }
            .isInstanceOf(IllegalArgumentException::class.java)
        org.mockito.kotlin.verifyNoInteractions(tokens)
    }

    @Test
    fun confirm_rejects_multibyte_password_over_72_bytes_with_korean_message() {
        // 25자(12–128자 규칙은 통과)지만 75바이트 — BCrypt 의 영어 오류 대신 정책 메시지로 거른다.
        assertThatThrownBy { service.confirm("t", "가".repeat(25)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("한글 24자")
        org.mockito.kotlin.verifyNoInteractions(tokens, users, encoder)
    }
}
