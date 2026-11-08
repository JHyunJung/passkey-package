package com.crosscert.passkey.admin.config

import com.crosscert.passkey.admin.audit.AuditLogService
import com.crosscert.passkey.admin.policy.SecurityPolicyDto
import com.crosscert.passkey.admin.policy.SecurityPolicyService
import com.crosscert.passkey.core.config.KstTime
import com.crosscert.passkey.core.entity.AdminUser
import com.crosscert.passkey.core.repository.AdminUserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Optional

class AdminLoginSuccessHandlerTest {

    private fun handlerWithPolicyMinutes(minutes: Int): AuthenticationSuccessHandler {
        val policy: SecurityPolicyService = mock()
        // @Min(1) is enforced only on UpdateRequest; the View record constructor
        // has no constraint, so a 0/negative value can be built here to exercise
        // the defensive clamp path.
        whenever(policy.get()).thenReturn(
            SecurityPolicyDto.View(minutes, false, listOf(), OffsetDateTime.now(), null),
        )
        return handlerWithPolicy(policy)
    }

    private fun handlerWithPolicy(policy: SecurityPolicyService): AuthenticationSuccessHandler {
        val audit: AuditLogService = mock()
        val users: AdminUserRepository = mock()
        val clock = Clock.fixed(Instant.parse("2026-06-04T00:00:00Z"), KstTime.ZONE)
        val mapper = ObjectMapper()

        val u: AdminUser = mock()
        whenever(u.role).thenReturn("PLATFORM_OPERATOR")
        whenever(u.isMfaEnabled).thenReturn(false)
        whenever(users.findByEmail("op@example.com")).thenReturn(Optional.of(u))

        return AdminSecurityConfig()
            .adminLoginSuccessHandler(audit, users, policy, clock, mapper)
    }

    @Test
    fun appliesPolicyIdleTimeoutToSessionInSeconds() {
        val handler = handlerWithPolicyMinutes(15)
        val req = MockHttpServletRequest()
        val res = MockHttpServletResponse()
        val auth: Authentication = UsernamePasswordAuthenticationToken("op@example.com", "x", listOf())

        handler.onAuthenticationSuccess(req, res, auth)

        assertThat(req.getSession(false)).isNotNull()
        assertThat(checkNotNull(req.getSession(false)).maxInactiveInterval).isEqualTo(15 * 60)
    }

    @Test
    fun defaultThirtyMinutesMapsTo1800Seconds() {
        val handler = handlerWithPolicyMinutes(30)
        val req = MockHttpServletRequest()
        val res = MockHttpServletResponse()
        val auth: Authentication = UsernamePasswordAuthenticationToken("op@example.com", "x", listOf())

        handler.onAuthenticationSuccess(req, res, auth)

        assertThat(checkNotNull(req.getSession(false)).maxInactiveInterval).isEqualTo(1800)
    }

    @Test
    fun fallsBackToThirtyMinutesWhenPolicyThrows() {
        val policy: SecurityPolicyService = mock()
        whenever(policy.get()).thenThrow(IllegalStateException("missing"))
        val handler = handlerWithPolicy(policy)
        val req = MockHttpServletRequest()
        val res = MockHttpServletResponse()
        val auth: Authentication = UsernamePasswordAuthenticationToken("op@example.com", "x", listOf())

        handler.onAuthenticationSuccess(req, res, auth)

        assertThat(req.getSession(false)).isNotNull()
        assertThat(checkNotNull(req.getSession(false)).maxInactiveInterval).isEqualTo(1800)
    }

    @Test
    fun clampsNonPositivePolicyToDefault() {
        val handler = handlerWithPolicyMinutes(0)
        val req = MockHttpServletRequest()
        val res = MockHttpServletResponse()
        val auth: Authentication = UsernamePasswordAuthenticationToken("op@example.com", "x", listOf())

        handler.onAuthenticationSuccess(req, res, auth)

        assertThat(checkNotNull(req.getSession(false)).maxInactiveInterval).isEqualTo(1800)
    }
}
