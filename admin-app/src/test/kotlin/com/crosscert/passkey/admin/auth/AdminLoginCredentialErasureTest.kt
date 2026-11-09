package com.crosscert.passkey.admin.auth

import com.crosscert.passkey.admin.audit.AuditLogService
import com.crosscert.passkey.admin.config.AdminSecurityConfig
import com.crosscert.passkey.admin.config.MeController
import com.crosscert.passkey.admin.policy.DynamicCorsConfigurationSource
import com.crosscert.passkey.admin.policy.SecurityPolicyService
import com.crosscert.passkey.core.entity.AdminUser
import com.crosscert.passkey.core.repository.AdminUserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argThat
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.authentication.dao.DaoAuthenticationProvider
import org.springframework.security.core.Authentication
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.core.userdetails.cache.NullUserCache
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.util.Optional
import java.util.UUID

/**
 * S16 — 실제 formLogin(/admin/login) 체인을 통과시켜, 로그인 응답·세션 SecurityContext·
 * 이후 /admin/api/me 가 그대로인지(특성화)와 세션 principal 에 bcrypt 해시가 남지
 * 않는지(개선)를 검증한다.
 *
 * nested `@SpringBootConfiguration` 슬라이스 패턴. auth 패키지의 IT 는 모두
 * classes 를 명시하므로 하나 더 추가해도 자동탐색 충돌이 새로 생기지 않는다.
 * AdminSecurityConfig 가 요구하는 협력 빈(SecurityPolicyService, TenantBoundary 포함)을
 * 모두 mock 으로 등록하고, PasswordEncoder 는 실제 BCrypt(cost 4)를 쓴다.
 */
@WebMvcTest(controllers = [MeController::class])
@Import(AdminSecurityConfig::class)
class AdminLoginCredentialErasureTest {

    companion object {
        private const val EMAIL = "rp@example.com"
        private const val PASSWORD = "correct-horse-battery-staple"
        private val HASH: String = BCryptPasswordEncoder(4).encode(PASSWORD)!!
        private val ADMIN_ID: UUID = UUID.fromString("0f000000-0000-0000-0000-000000000001")
        private val TENANT_ID: UUID = UUID.fromString("0a000000-0000-0000-0000-000000000001")

        /** AdminUserDetailsService 처럼 호출마다 새 인스턴스를 돌려준다(AdminUserDetailsServiceTest 가 고정). */
        private fun freshDetails(): AdminUserDetails =
            AdminUserDetails(
                ADMIN_ID, EMAIL, HASH, "RP_ADMIN",
                setOf(TENANT_ID), true, null, Clock.systemUTC(),
            )

        private fun adminUser(mfaEnabled: Boolean): AdminUser {
            val u = AdminUser(EMAIL, HASH, "RP_ADMIN")
            u.isMfaEnabled = mfaEnabled
            return u
        }

        private fun session(r: MvcResult): MockHttpSession {
            val s = r.request.getSession(false) as MockHttpSession?
            assertThat(s).isNotNull()
            return s!!
        }

        private fun sessionAuthentication(r: MvcResult): Authentication {
            val ctx = session(r)
                .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) as SecurityContext?
            assertThat(ctx).isNotNull()
            return ctx!!.authentication!!
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(MeController::class)
    class SliceConfig {
        // 실제 BCrypt — DaoAuthenticationProvider 가 진짜 해시 비교를 하게 한다.
        @Bean
        fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder(4)

        @Bean
        fun clock(): Clock = Clock.systemUTC()
    }

    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var adminAuthProvider: DaoAuthenticationProvider

    @MockitoBean
    lateinit var uds: AdminUserDetailsService

    @MockitoBean
    lateinit var users: AdminUserRepository

    @MockitoBean
    lateinit var audit: AuditLogService

    @MockitoBean
    lateinit var corsSource: DynamicCorsConfigurationSource

    @MockitoBean
    lateinit var securityPolicyService: SecurityPolicyService

    @MockitoBean
    lateinit var tenantBoundary: TenantBoundary

    @BeforeEach
    fun stubs() {
        whenever(uds.loadUserByUsername(EMAIL)).thenAnswer { freshDetails() }
        whenever(users.findByEmail(EMAIL)).thenAnswer { Optional.of(adminUser(false)) }
    }

    private fun login(password: String): MvcResult =
        mvc.perform(
            post("/admin/login")
                .param("email", EMAIL)
                .param("password", password)
                .with(csrf().asHeader()),
        ).andReturn()

    // ---- 특성화: 변경 전후 모두 통과해야 한다 ------------------------------------

    @Test
    fun login_success_responseSessionPrincipalAndMeUnchanged() {
        val r = mvc.perform(
            post("/admin/login")
                .param("email", EMAIL)
                .param("password", PASSWORD)
                .with(csrf().asHeader()),
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value(EMAIL))
            .andExpect(jsonPath("$.role").value("RP_ADMIN"))
            .andExpect(jsonPath("$.mfaRequired").value(false))
            .andReturn()

        verify(audit).append(argThat { "ADMIN_LOGIN" == action && EMAIL == actorEmail })

        val auth = sessionAuthentication(r)
        assertThat(auth.isAuthenticated).isTrue()
        assertThat(auth.name).isEqualTo(EMAIL)
        // 제출 비밀번호는 지금도 ProviderManager 가 지운다.
        assertThat(auth.credentials).isNull()
        // Spring Security 7 은 비밀번호 인증 성공 시 FACTOR_PASSWORD 권한을 Authentication 에 덧붙인다(Boot 4 이식에 따른 차이).
        assertThat(auth.authorities.map(GrantedAuthority::getAuthority))
            .containsExactlyInAnyOrder("ROLE_RP_ADMIN", "FACTOR_PASSWORD")
        val p = auth.principal as AdminUserDetails
        assertThat(p.authorities.map(GrantedAuthority::getAuthority)).containsExactly("ROLE_RP_ADMIN")
        assertThat(p.id).isEqualTo(ADMIN_ID)
        assertThat(p.username).isEqualTo(EMAIL)
        assertThat(p.role).isEqualTo("RP_ADMIN")
        assertThat(p.allowedTenantIds).containsExactly(TENANT_ID)
        assertThat(p.isEnabled).isTrue()
        assertThat(p.isAccountNonLocked).isTrue()

        val session = session(r)
        // SecurityPolicyService mock(get()=null) → 성공 핸들러의 기본 30분 폴백.
        assertThat(session.maxInactiveInterval).isEqualTo(30 * 60)
        assertThat(session.getAttribute(MfaPendingFilter.MFA_PENDING_ATTR)).isNull()

        mvc.perform(get("/admin/api/me").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.email").value(EMAIL))
            .andExpect(jsonPath("$.data.role").value("RP_ADMIN"))
            .andExpect(jsonPath("$.data.tenantIds[0]").value(TENANT_ID.toString()))
            .andExpect(jsonPath("$.data.mfaEnabled").value(false))
            .andExpect(jsonPath("$.data.mfaRequired").value(false))
            .andExpect(jsonPath("$.data.sessionIdleTimeoutMinutes").value(30))
    }

    @Test
    fun login_mfaEnabled_marksSessionPending_andMeReportsIt() {
        whenever(users.findByEmail(EMAIL)).thenAnswer { Optional.of(adminUser(true)) }

        val r = mvc.perform(
            post("/admin/login")
                .param("email", EMAIL)
                .param("password", PASSWORD)
                .with(csrf().asHeader()),
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mfaRequired").value(true))
            .andReturn()

        val session = session(r)
        assertThat(session.getAttribute(MfaPendingFilter.MFA_PENDING_ATTR)).isEqualTo(java.lang.Boolean.TRUE)
        assertThat((sessionAuthentication(r).principal as AdminUserDetails).id).isEqualTo(ADMIN_ID)

        // /admin/api/me 는 MFA pending 중에도 허용 경로.
        mvc.perform(get("/admin/api/me").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.mfaEnabled").value(true))
            .andExpect(jsonPath("$.data.mfaRequired").value(true))
    }

    @Test
    fun login_badPassword_is401_andNoContextStored() {
        val r = mvc.perform(
            post("/admin/login")
                .param("email", EMAIL)
                .param("password", "wrong-password")
                .with(csrf().asHeader()),
        )
            .andExpect(status().isUnauthorized())
            .andExpect(content().json("{\"error\":\"unauthorized\"}"))
            .andReturn()

        verify(audit).append(argThat { "ADMIN_LOGIN_FAILED" == action })
        val s = r.request.getSession(false) as MockHttpSession?
        assertThat(
            s == null ||
                s.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) == null,
        ).isTrue()
    }

    @Test
    fun repeatedLogin_eachSucceeds() {
        // 같은 계정의 연속 로그인이 실제 체인에서 모두 BCrypt 검증을 통과해야 한다.
        // (인스턴스 재사용이 없다는 전제 자체는 AdminUserDetailsServiceTest 와
        //  adminAuthProvider_hasNoUserCache 가 고정한다.)
        assertThat(login(PASSWORD).response.status).isEqualTo(200)
        assertThat(login(PASSWORD).response.status).isEqualTo(200)
    }

    @Test
    fun adminAuthProvider_hasNoUserCache() {
        // UserCache 가 있으면 소거된 principal 이 다음 로그인에 재사용돼 BCrypt 비교가 깨진다.
        assertThat(adminAuthProvider.userCache).isInstanceOf(NullUserCache::class.java)
    }

    // ---- 개선: 세션(=Redis 직렬화 대상)에 해시가 남지 않는다 ------------------------

    @Test
    fun login_success_sessionHoldsNoPasswordHash() {
        val r = login(PASSWORD)
        assertThat(r.response.status).isEqualTo(200)

        val p = sessionAuthentication(r).principal as AdminUserDetails
        assertThat(p.password).isNull()

        // Spring Session Redis 기본 직렬화(JdkSerializationRedisSerializer)와 같은 JDK 직렬화로
        // 세션에 저장되는 SecurityContext 를 직렬화해 해시 문자열이 없는지 본다.
        val ctx = session(r).getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)
        val bos = ByteArrayOutputStream()
        ObjectOutputStream(bos).use { it.writeObject(ctx) }
        assertThat(String(bos.toByteArray(), StandardCharsets.ISO_8859_1)).doesNotContain(HASH)
    }
}
