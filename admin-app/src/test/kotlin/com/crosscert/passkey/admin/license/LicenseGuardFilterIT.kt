package com.crosscert.passkey.admin.license

import com.crosscert.passkey.admin.tenant.TenantAdminController
import com.crosscert.passkey.admin.tenant.TenantAdminService
import com.crosscert.passkey.admin.tenant.TenantLifecycleService
import com.crosscert.passkey.admin.tenant.WebauthnDiffService
import com.crosscert.passkey.admin.web.AdminWebSliceTest
import com.crosscert.passkey.core.license.LicenseGuardFilter
import com.crosscert.passkey.core.license.LicenseLimits
import com.crosscert.passkey.core.license.LicenseProperties
import com.crosscert.passkey.core.license.LicenseState
import com.crosscert.passkey.core.license.LicenseStateMachine
import com.crosscert.passkey.core.license.LicenseToken
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * L3.4 — LicenseGuardFilterIT: DEAD 상태에서 API 차단 / exempt 경로 통과 통합 테스트.
 *
 * `@WebMvcTest` 슬라이스로 운영. LicenseGuardFilter + AdminSecurityConfig 를
 * 함께 로드하고, DeadStateConfig 가 만료된 토큰으로 LicenseStateMachine 을 제공하여
 * DEAD 상태를 인위적으로 만든다.
 *
 * 3 assertions:
 *  1. DEAD 상태에서 /admin/api/tenants → 503 (차단)
 *  2. DEAD 상태에서 /admin/api/license → 200 (exempt, L4.1 에서 enable)
 *  3. DEAD 상태에서 /actuator/health → 200 (exempt)
 */
@AdminWebSliceTest(controllers = [TenantAdminController::class, LicenseController::class])
@TestPropertySource(
    properties = [
        "passkey.deployment.mode=onprem",
        "passkey.license.path=/dev/null",
        "passkey.license.heartbeat-url=http://localhost:9",
    ],
)
@Import(
    LicenseGuardFilter::class,
    LicenseGuardFilterIT.DeadStateConfig::class,
)
class LicenseGuardFilterIT {

    /**
     * Provides a LicenseStateMachine in DEAD state (token expired yesterday).
     * @Primary ensures this beats any other LicenseStateMachine candidate.
     */
    @TestConfiguration
    class DeadStateConfig {

        @Bean
        fun licenseProperties(): LicenseProperties = LicenseProperties(null, null, null, null, null, null)

        @Bean
        @Primary
        fun deadStateMachine(): LicenseStateMachine {
            val past = Instant.now().minus(Duration.ofDays(1))
            val expiredToken = LicenseToken(
                "test-customer",
                "lic-test-dead",
                past.minus(Duration.ofDays(30)),
                past.minus(Duration.ofDays(30)),
                past, // expiresAt = yesterday
                UUID.nameUUIDFromBytes("test".toByteArray()).toString(),
                setOf("mds"),
                LicenseLimits.DEFAULTS,
            )
            val sm = LicenseStateMachine(
                expiredToken, past, Instant::now,
            )
            assert(sm.state() == LicenseState.DEAD) { "Precondition: state must be DEAD, got " + sm.state() }
            return sm
        }
    }

    @Autowired
    lateinit var mvc: MockMvc

    @BeforeEach
    fun setUpClock() {
        whenever(clock.instant()).thenReturn(Instant.now())
    }

    // ----------------------------------------------------------------
    // Mocked beans required by AdminSecurityConfig + TenantAdminController
    // ----------------------------------------------------------------

    // AdminSecurityConfig·리포지토리 mock 은 AdminWebSliceTest 묶음이 제공한다(clock 은 묶음의 mock 을 받아 스텁).
    @Autowired
    lateinit var clock: Clock

    @MockitoBean
    lateinit var tenantAdminService: TenantAdminService

    @MockitoBean
    lateinit var webauthnDiffService: WebauthnDiffService

    @MockitoBean
    lateinit var tenantLifecycleService: TenantLifecycleService

    // ----------------------------------------------------------------
    // Tests
    // ----------------------------------------------------------------

    /**
     * DEAD 상태에서 일반 admin API 는 503 으로 차단되어야 한다.
     * @WithMockUser 로 인증을 우회 — 필터는 인증 전에 실행된다.
     */
    @Test
    @WithMockUser(roles = ["PLATFORM_OPERATOR"])
    fun deadState_blocksAdminApi_with503() {
        mvc.perform(get("/admin/api/tenants"))
            .andExpect(status().isServiceUnavailable())
    }

    /**
     * DEAD 상태에서 /admin/api/license 는 통과해야 한다 (LicenseController L4.1 에서 추가).
     * LicenseController 가 없어서 지금은 404 가 반환되므로, exempt 경로 통과 여부만 확인.
     * L4.1 에서 enable.
     */
    @Test
    fun deadState_allowsLicenseEndpoint() {
        mvc.perform(get("/admin/api/license"))
            .andExpect(status().isOk())
    }

    /**
     * DEAD 상태에서 /actuator/health 는 통과해야 한다.
     * actuator 는 @WebMvcTest 슬라이스에서 포함되지 않으므로 404 로 exempt 통과를 확인.
     * 503 이 아니라면 필터가 exempt 경로를 올바르게 통과시키고 있는 것이다.
     */
    @Test
    fun deadState_allowsHealthEndpoint_notBlocked() {
        // @WebMvcTest does not wire actuator endpoints, so 404 is expected.
        // The important assertion is: NOT 503 (filter passes the request through).
        val status = mvc.perform(get("/actuator/health"))
            .andReturn()
            .response
            .status
        assertThat(status)
            .`as`("DEAD state must not block exempt /actuator/health path with 503")
            .isNotEqualTo(503)
    }
}
