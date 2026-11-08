package com.crosscert.passkey.admin.operator

import com.crosscert.passkey.admin.audit.AuditLogService
import com.crosscert.passkey.admin.auth.AdminUserDetailsService
import com.crosscert.passkey.admin.auth.TenantBoundary
import com.crosscert.passkey.admin.config.AdminSecurityConfig
import com.crosscert.passkey.admin.policy.DynamicCorsConfigurationSource
import com.crosscert.passkey.admin.policy.SecurityPolicyService
import com.crosscert.passkey.core.api.GlobalExceptionHandler
import com.crosscert.passkey.core.config.KstTime
import com.crosscert.passkey.core.repository.AdminUserRepository
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Clock
import java.time.Instant

/**
 * @WebMvcTest slice for [PasswordResetController], mirroring the
 * `*ControllerSecurityTest` pattern (nested `@SpringBootConfiguration`
 * to short-circuit `@WebMvcTest`'s upward search for `AdminApplication`
 * and its `@EnableJpaRepositories`; `AdminSecurityConfig` +
 * `GlobalExceptionHandler` imported so permitAll + CSRF-ignore for
 * `/admin/api/password-reset/\*\*` are actually exercised; collaborators mocked).
 */
@WebMvcTest(controllers = [PasswordResetController::class])
@Import(
    AdminSecurityConfig::class,
    GlobalExceptionHandler::class,
)
class PasswordResetControllerTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(PasswordResetController::class)
    class SliceConfig {
        // AdminSecurityConfig's adminLoginSuccessHandler bean needs a Clock.
        @Bean
        fun clock(): Clock = Clock.fixed(Instant.parse("2026-05-30T00:00:00Z"), KstTime.ZONE)
    }

    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var service: PasswordResetService

    // AdminSecurityConfig collaborators that are not otherwise supplied by the slice.
    @MockitoBean
    lateinit var audit: AuditLogService

    @MockitoBean
    lateinit var uds: AdminUserDetailsService

    @MockitoBean
    lateinit var encoder: PasswordEncoder

    @MockitoBean
    lateinit var corsSource: DynamicCorsConfigurationSource

    @MockitoBean
    lateinit var users: AdminUserRepository

    // AdminSecurityConfig 협력 빈(adminLoginSuccessHandler 의 SecurityPolicyService, adminFilterChain 의 TenantBoundary).
    // 이 클래스는 자체 SliceConfig(실제 고정 Clock)를 쓰므로 AdminWebSliceTest 묶음(Clock mock 포함) 대신 개별로 둔다.
    @MockitoBean
    lateinit var securityPolicyService: SecurityPolicyService

    @MockitoBean
    lateinit var tenantBoundary: TenantBoundary

    @Test
    fun request_always_200_even_for_unknown() {
        mvc.perform(
            post("/admin/api/password-reset/request").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"ghost@x.com\"}"),
        )
            .andExpect(status().isOk())
        verify(service).request(eq("ghost@x.com"))
    }

    @Test
    fun confirm_delegates_to_service() {
        mvc.perform(
            post("/admin/api/password-reset/confirm").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"t\",\"newPassword\":\"NewPassw0rd!\"}"),
        )
            .andExpect(status().isOk())
        verify(service).confirm(eq("t"), eq("NewPassw0rd!"))
    }

    @Test
    fun confirm_invalid_token_returns_400() {
        doThrow(IllegalArgumentException("invalid token"))
            .whenever(service).confirm(eq("bad"), anyString())
        mvc.perform(
            post("/admin/api/password-reset/confirm").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"bad\",\"newPassword\":\"NewPassw0rd!\"}"),
        )
            .andExpect(status().isBadRequest())
    }
}
