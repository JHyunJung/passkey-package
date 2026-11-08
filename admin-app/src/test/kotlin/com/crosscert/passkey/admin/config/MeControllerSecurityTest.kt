package com.crosscert.passkey.admin.config

import com.crosscert.passkey.admin.auth.AdminUserDetails
import com.crosscert.passkey.admin.auth.MfaPendingFilter
import com.crosscert.passkey.admin.web.AdminWebSliceTest
import com.crosscert.passkey.core.entity.AdminUser
import com.crosscert.passkey.core.repository.AdminUserRepository
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Clock
import java.util.Optional
import java.util.UUID

@AdminWebSliceTest
class MeControllerSecurityTest {

    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var users: AdminUserRepository

    private fun operator(): Authentication {
        val principal = AdminUserDetails(
            UUID.randomUUID(), "alice@example.com", "x",
            "PLATFORM_OPERATOR", setOf(), true, null, Clock.systemUTC(),
        )
        return UsernamePasswordAuthenticationToken(principal, "x", principal.authorities)
    }

    @Test
    fun anonymousIsUnauthorized() {
        mvc.perform(get("/admin/api/me")).andExpect(status().isUnauthorized())
    }

    @Test
    fun me_returnsMfaEnabledAndMfaRequired_whenPending() {
        whenever(users.findByEmail(anyString())).thenReturn(Optional.of(adminUser(true)))

        mvc.perform(
            get("/admin/api/me")
                .with(authentication(operator()))
                .sessionAttr(MfaPendingFilter.MFA_PENDING_ATTR, java.lang.Boolean.TRUE),
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.email").value("alice@example.com"))
            .andExpect(jsonPath("$.data.role").value("PLATFORM_OPERATOR"))
            .andExpect(jsonPath("$.data.mfaEnabled").value(true))
            .andExpect(jsonPath("$.data.mfaRequired").value(true))
    }

    @Test
    fun me_mfaRequiredFalse_whenNoPendingAttr() {
        whenever(users.findByEmail(anyString())).thenReturn(Optional.of(adminUser(false)))

        mvc.perform(
            get("/admin/api/me")
                .with(authentication(operator())),
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.mfaEnabled").value(false))
            .andExpect(jsonPath("$.data.mfaRequired").value(false))
    }

    @Test
    fun me_exposesSessionIdleTimeoutMinutes_fromMaxInactiveInterval() {
        whenever(users.findByEmail(anyString())).thenReturn(Optional.of(adminUser(false)))

        val session = MockHttpSession()
        session.maxInactiveInterval = 900 // 15분

        mvc.perform(
            get("/admin/api/me")
                .with(authentication(operator()))
                .session(session),
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.sessionIdleTimeoutMinutes").value(15))
    }

    @Test
    fun me_fallsBackToThirtyMinutes_whenSessionAbsent() {
        whenever(users.findByEmail(anyString())).thenReturn(Optional.of(adminUser(false)))

        // 세션을 첨부하지 않으면 컨트롤러의 req.getSession(false) 가 null →
        // AdminSecurityConfig.DEFAULT_IDLE_MINUTES(30) 폴백.
        mvc.perform(
            get("/admin/api/me")
                .with(authentication(operator())),
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.sessionIdleTimeoutMinutes").value(30))
    }

    // --- actuator 노출 회귀 가드 -------------------------------------------
    // AdminSecurityConfig 는 /actuator/health·/actuator/info 만 permitAll 이고
    // 나머지는 anyRequest().denyAll() 이 잡는다. 이 슬라이스에는 actuator 핸들러가
    // 없지만 보안 체인이 핸들러보다 먼저 돌므로, 인가 결정은 그대로 검증된다.

    @Test
    fun anonymousActuatorPrometheus_isDenied() {
        // permitAll 목록에 없으므로 denyAll → 401. 200 이 되면 노출 회귀다.
        mvc.perform(get("/actuator/prometheus").accept("application/json"))
            .andExpect(status().isUnauthorized())
    }

    @Test
    fun anonymousActuatorHealth_passesSecurityButHasNoHandlerHere() {
        // permitAll 이라 보안 체인은 통과하고, 슬라이스에 핸들러가 없어 404 가 된다.
        // 즉 "보안이 막아서 못 온 것이 아님" 을 보이는 대조군이다.
        mvc.perform(get("/actuator/health").accept("application/json"))
            .andExpect(status().isNotFound())
    }

    private companion object {
        fun adminUser(mfaEnabled: Boolean): AdminUser {
            val u = AdminUser("alice@example.com", "x", "ADMIN")
            u.isMfaEnabled = mfaEnabled
            return u
        }
    }
}
