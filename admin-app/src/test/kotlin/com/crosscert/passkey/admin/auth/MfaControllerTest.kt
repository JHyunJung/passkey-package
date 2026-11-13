package com.crosscert.passkey.admin.auth

import com.crosscert.passkey.admin.audit.AuditLogService
import com.crosscert.passkey.core.config.KstTime
import com.crosscert.passkey.core.entity.AdminUser
import com.crosscert.passkey.core.repository.AdminUserRepository
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.TestingAuthenticationToken
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Optional
import java.util.OptionalLong

class MfaControllerTest {

    private val totp: TotpService = mock()
    private val users: AdminUserRepository = mock()
    private val recoveryCodes: RecoveryCodeService = mock()
    private val cipher: MfaSecretCipher = mock()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-06-11T00:00:00Z"), KstTime.ZONE)

    // max-attempts=5, duration=15m mirror AdminSecurityConfig defaults.
    private val controller = MfaController(totp, users, clock, recoveryCodes, cipher, mock<AuditLogService>(), 5, Duration.ofMinutes(15))

    private fun pendingRequest(pending: Boolean): HttpServletRequest {
        val req: HttpServletRequest = mock()
        val session: HttpSession = mock()
        whenever(req.getSession(false)).thenReturn(session)
        whenever(session.getAttribute(MfaPendingFilter.MFA_PENDING_ATTR))
            .thenReturn(if (pending) java.lang.Boolean.TRUE else null)
        return req
    }

    private fun auth(): TestingAuthenticationToken =
        TestingAuthenticationToken("alice@example.com", "x", "ROLE_PLATFORM_OPERATOR")

    @Test
    fun enroll_whilePending_is403_andSecretUntouched() {
        val resp = controller.enroll(auth(), pendingRequest(true))

        assertThat(resp.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        // critical: pending enroll must NOT overwrite the secret.
        verify(users, never()).save(anyOrNull())
        verify(totp, never()).newSecretBase32()
    }

    @Test
    fun disable_whilePending_is403() {
        val resp = controller.disable(
            MfaController.VerifyRequest("123456"), auth(), pendingRequest(true),
        )

        assertThat(resp.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        verify(users, never()).save(anyOrNull())
    }

    @Test
    fun regenerateRecoveryCodes_whilePending_is403() {
        // MfaPendingFilter 가 우회돼도 핸들러가 비밀번호만 통과한 세션의 재발급을 거부한다(심층 방어).
        val resp = controller.regenerateRecoveryCodes(
            MfaController.VerifyRequest("123456"), auth(), pendingRequest(true),
        )

        assertThat(resp.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        verify(users, never()).save(anyOrNull())
        verify(recoveryCodes, never()).generate(anyOrNull())
    }

    @Test
    fun confirm_whilePending_is403() {
        val resp = controller.confirm(
            MfaController.VerifyRequest("123456"), auth(), pendingRequest(true),
        )

        assertThat(resp.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        verify(users, never()).save(anyOrNull())
    }

    private fun enrolledUser(): AdminUser {
        val u = AdminUser.create()
        u.email = "alice@example.com"
        u.isMfaEnabled = true
        u.mfaSecret = "sealed-secret"
        return u
    }

    @Test
    fun verify_repeatedFailure_locksAccount() {
        val u = enrolledUser()
        whenever(users.findByEmail("alice@example.com")).thenReturn(Optional.of(u))
        whenever(cipher.open("sealed-secret")).thenReturn("PLAINSECRET")
        whenever(totp.verifyAt(anyOrNull(), anyOrNull(), anyLong())).thenReturn(false) // always wrong
        whenever(recoveryCodes.consume(anyOrNull(), anyOrNull())).thenReturn(false)

        val req: HttpServletRequest = mock()
        val session: HttpSession = mock()
        whenever(req.getSession(false)).thenReturn(session)
        whenever(session.getAttribute(MfaPendingFilter.MFA_PENDING_ATTR)).thenReturn(java.lang.Boolean.TRUE)

        for (i in 0 until 5) {
            controller.verify(MfaController.VerifyRequest("000000"), auth(), req)
        }
        assertThat(u.isLocked(OffsetDateTime.now(clock))).isTrue()
        verify(session).invalidate() // session killed on lockout
    }

    @Test
    fun verify_whenLocked_rejectsEvenCorrectCode() {
        val u = enrolledUser()
        u.recordFailedMfa(OffsetDateTime.now(clock), 1, Duration.ofMinutes(15)) // already locked
        whenever(users.findByEmail("alice@example.com")).thenReturn(Optional.of(u))
        whenever(cipher.open("sealed-secret")).thenReturn("PLAINSECRET")
        whenever(totp.verifyAt(anyOrNull(), anyOrNull(), anyLong())).thenReturn(true) // correct code

        val resp = controller.verify(
            MfaController.VerifyRequest("123456"), auth(), pendingRequest(true),
        )

        assertThat(resp.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun verify_success_resetsMfaFailedCount() {
        val u = enrolledUser()
        u.recordFailedMfa(OffsetDateTime.now(clock), 5, Duration.ofMinutes(15)) // 1 fail, not locked
        whenever(users.findByEmail("alice@example.com")).thenReturn(Optional.of(u))
        whenever(cipher.open("sealed-secret")).thenReturn("PLAINSECRET")
        whenever(totp.verifyAt(anyOrNull(), anyOrNull(), anyLong())).thenReturn(true)
        whenever(totp.matchedCounter(anyOrNull(), anyOrNull(), anyLong())).thenReturn(OptionalLong.of(100L))

        val resp = controller.verify(
            MfaController.VerifyRequest("123456"), auth(), pendingRequest(true),
        )

        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(u.mfaFailedCount).isEqualTo(0)
        assertThat(u.isLocked(OffsetDateTime.now(clock))).isFalse()
    }

    // ---- G05: MFA lockout counter independent of password login success ---

    @Test
    fun verify_repeatedFailure_incrementsMfaFailedCount_notPasswordFailedLoginCount() {
        val u = enrolledUser()
        whenever(users.findByEmail("alice@example.com")).thenReturn(Optional.of(u))
        whenever(cipher.open("sealed-secret")).thenReturn("PLAINSECRET")
        whenever(totp.matchedCounter(anyOrNull(), anyOrNull(), anyLong())).thenReturn(OptionalLong.empty())
        whenever(recoveryCodes.consume(anyOrNull(), anyOrNull())).thenReturn(false)

        controller.verify(MfaController.VerifyRequest("000000"), auth(), pendingRequest(true))

        assertThat(u.mfaFailedCount).isEqualTo(1)
        assertThat(u.failedLoginCount).isEqualTo(0)
    }

    @Test
    fun passwordLoginSuccess_doesNotResetMfaBruteForceProgress() {
        // G05 repro: 4 sub-threshold MFA failures accrue, then a password-only
        // re-login (recordSuccessfulLogin) must NOT wipe that progress — that
        // was the bypass that let an attacker cycle indefinitely without ever
        // tripping the MFA lock.
        val u = enrolledUser()
        for (i in 0 until 4) {
            u.recordFailedMfa(OffsetDateTime.now(clock), 5, Duration.ofMinutes(15))
        }
        assertThat(u.mfaFailedCount).isEqualTo(4)

        u.recordSuccessfulLogin() // simulates AdminSecurityConfig's password-login success handler

        assertThat(u.mfaFailedCount).isEqualTo(4) // unchanged — the fix
    }

    // ---- F02: TOTP replay guard (RFC 6238 §5.2) --------------------------

    @Test
    fun verify_success_persistsMatchedCounterAsLastTotpStep() {
        val u = enrolledUser()
        whenever(users.findByEmail("alice@example.com")).thenReturn(Optional.of(u))
        whenever(cipher.open("sealed-secret")).thenReturn("PLAINSECRET")
        whenever(totp.matchedCounter(anyOrNull(), anyOrNull(), anyLong())).thenReturn(OptionalLong.of(56789012345L))

        val resp = controller.verify(
            MfaController.VerifyRequest("123456"), auth(), pendingRequest(true),
        )

        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(u.mfaLastTotpStep).isEqualTo(56789012345L)
    }

    @Test
    fun verify_replayedStep_is401_andDoesNotClearMfaPending() {
        val u = enrolledUser()
        u.mfaLastTotpStep = 56789012345L // already consumed by a prior verify
        whenever(users.findByEmail("alice@example.com")).thenReturn(Optional.of(u))
        whenever(cipher.open("sealed-secret")).thenReturn("PLAINSECRET")
        // Code still matches the current window (correct code, but reused).
        whenever(totp.matchedCounter(anyOrNull(), anyOrNull(), anyLong())).thenReturn(OptionalLong.of(56789012345L))
        whenever(recoveryCodes.consume(anyOrNull(), anyOrNull())).thenReturn(false)

        val req: HttpServletRequest = mock()
        val session: HttpSession = mock()
        whenever(req.getSession(false)).thenReturn(session)
        whenever(session.getAttribute(MfaPendingFilter.MFA_PENDING_ATTR)).thenReturn(java.lang.Boolean.TRUE)

        val resp = controller.verify(
            MfaController.VerifyRequest("123456"), auth(), req,
        )

        assertThat(resp.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        verify(session, never()).removeAttribute(MfaPendingFilter.MFA_PENDING_ATTR)
    }

    @Test
    fun verify_stepOlderThanLast_is401() {
        val u = enrolledUser()
        u.mfaLastTotpStep = 56789012345L
        whenever(users.findByEmail("alice@example.com")).thenReturn(Optional.of(u))
        whenever(cipher.open("sealed-secret")).thenReturn("PLAINSECRET")
        // Skew-window match on an older step than the stored high-water mark.
        whenever(totp.matchedCounter(anyOrNull(), anyOrNull(), anyLong())).thenReturn(OptionalLong.of(56789012344L))
        whenever(recoveryCodes.consume(anyOrNull(), anyOrNull())).thenReturn(false)

        val resp = controller.verify(
            MfaController.VerifyRequest("123456"), auth(), pendingRequest(true),
        )

        assertThat(resp.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun verify_newerStepThanLast_isAccepted_andAdvancesHighWaterMark() {
        val u = enrolledUser()
        u.mfaLastTotpStep = 100L
        whenever(users.findByEmail("alice@example.com")).thenReturn(Optional.of(u))
        whenever(cipher.open("sealed-secret")).thenReturn("PLAINSECRET")
        whenever(totp.matchedCounter(anyOrNull(), anyOrNull(), anyLong())).thenReturn(OptionalLong.of(101L))

        val resp = controller.verify(
            MfaController.VerifyRequest("123456"), auth(), pendingRequest(true),
        )

        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(u.mfaLastTotpStep).isEqualTo(101L)
    }
}
