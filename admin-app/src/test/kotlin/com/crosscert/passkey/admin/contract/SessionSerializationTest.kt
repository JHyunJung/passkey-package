package com.crosscert.passkey.admin.contract

import com.crosscert.passkey.admin.auth.ActiveTenantController
import com.crosscert.passkey.admin.auth.ActiveTenantResolver
import com.crosscert.passkey.admin.auth.AdminUserDetails
import com.crosscert.passkey.admin.auth.AdminUserDetailsService
import com.crosscert.passkey.admin.auth.MfaPendingFilter
import com.crosscert.passkey.admin.policy.SecurityPolicyService
import com.crosscert.passkey.admin.web.AdminWebSliceTest
import com.crosscert.passkey.core.config.KstTime
import com.crosscert.passkey.core.entity.AdminUser
import com.crosscert.passkey.core.repository.AdminUserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.authentication.WebAuthenticationDetails
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.savedrequest.DefaultSavedRequest
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Collections
import java.util.Optional
import java.util.TreeSet
import java.util.UUID

/**
 * 세션 속성의 JDK 직렬화 왕복 (Kotlin 전환 안전망, Review Focus 2).
 *
 * Spring Session(Redis) 은 세션 속성을 JDK 직렬화로 저장한다(`JdkSerializationRedisSerializer` 기본).
 * 로그인 → 다음 요청 사이에 속성이 왕복되지 못하면 로그아웃·500 이 된다. 구·신버전 공존은 없으므로(스펙 §4A-3)
 * 같은 코드로 직렬화·역직렬화되는지만 본다.
 *
 * **세션에 들어가는 객체 전수**(`grep -rn "setAttribute\|@SessionAttributes\|HttpSession" admin-app/src/main`,
 * 2026-11-07 Java base 기준 — `@SessionAttributes`·Flash 속성·요청 캐시 직접 사용 없음):
 * 1. `SPRING_SECURITY_CONTEXT` — [SecurityContext](`SecurityContextImpl`) ⊃
 *    [UsernamePasswordAuthenticationToken] ⊃ [AdminUserDetails](id·email·hash·role·allowedTenantIds·
 *    enabled·lockedUntil·[Clock]) + `SimpleGrantedAuthority` + [WebAuthenticationDetails].
 *    폼 로그인이 Spring Security 저장소로 넣는다.
 * 2. `MFA_PENDING`([MfaPendingFilter.MFA_PENDING_ATTR]) — [Boolean]. MFA 사용 운영자 로그인 성공 처리기.
 * 3. `ACTIVE_TENANT_ID`([ActiveTenantResolver.ACTIVE_TENANT_ATTR]) — [UUID]. 활성 RP 결정·전환.
 * 4. `SPRING_SECURITY_SAVED_REQUEST` — [DefaultSavedRequest](프레임워크 타입). 미인증 화면 접근 시
 *    기본 요청 캐시가 넣는다.
 *
 * 아래 테스트는 실제 로그인·요청 흐름(슬라이스 보안 체인)으로 세션을 채운 뒤 관찰된 속성 이름 집합이 이 목록과 같은지
 * 단언하고, 각 속성을 왕복시켜 핵심 필드를 비교한다. 목록이 바뀌면(새 세션 속성) 이 테스트가 먼저 깨진다.
 */
@AdminWebSliceTest(controllers = [ActiveTenantController::class])
@Import(ActiveTenantResolver::class)
class SessionSerializationTest {

    companion object {
        val DOCUMENTED_ATTRIBUTES: Set<String> = setOf(
            HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
            MfaPendingFilter.MFA_PENDING_ATTR,
            ActiveTenantResolver.ACTIVE_TENANT_ATTR,
            "SPRING_SECURITY_SAVED_REQUEST",
        )

        val ADMIN_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000aa01")
        val T1: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
        val T2: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000c2")

        /** 지난 잠금 — 역직렬화된 Clock 으로 isAccountNonLocked 가 계산되는지 보기 위해 null 이 아닌 값을 둔다. */
        val PAST_LOCK: OffsetDateTime = OffsetDateTime.parse("2020-01-01T00:00:00+09:00")

        /** 운영과 같은 모양의 principal — AdminUserDetailsService 는 주입된 Clock(= Clock.system(KST)) 을 넘긴다. */
        private fun principal(email: String, role: String, tenants: Set<UUID>): AdminUserDetails =
            AdminUserDetails(ADMIN_ID, email, "\$2a\$12\$hash", role, tenants, true, PAST_LOCK, Clock.system(KstTime.ZONE))

        private fun attributes(s: MockHttpSession): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            for (n in Collections.list(s.attributeNames)) m[n] = s.getAttribute(n)
            return m
        }

        @Suppress("UNCHECKED_CAST")
        fun <T> roundTrip(value: T): T {
            assertThat(value).`as`("세션 속성은 Serializable").isInstanceOf(Serializable::class.java)
            val bytes = ByteArrayOutputStream()
            ObjectOutputStream(bytes).use { out -> out.writeObject(value) }
            ObjectInputStream(ByteArrayInputStream(bytes.toByteArray())).use { input ->
                return input.readObject() as T
            }
        }

        private fun assertSecurityContextRoundTrip(ctx: SecurityContext, email: String, role: String, tenants: Set<UUID>) {
            assertThat(ctx.authentication).isInstanceOf(UsernamePasswordAuthenticationToken::class.java)
            val back = roundTrip(ctx)
            val token = back.authentication as UsernamePasswordAuthenticationToken
            assertThat(token.isAuthenticated).isTrue()
            assertThat(token.name).isEqualTo(email)
            assertThat(token.credentials).isNull() // 인증 후 자격 증명 삭제(eraseCredentials) 그대로
            // 토큰 권한 = principal 의 ROLE_* + Security 7 이 인증 방식으로 더하는 FACTOR_PASSWORD.
            assertThat(token.authorities.map { it.authority })
                .containsExactly("ROLE_$role", "FACTOR_PASSWORD")
            assertThat(token.details).isInstanceOf(WebAuthenticationDetails::class.java)
            assertThat(token.details).isEqualTo(ctx.authentication?.details)
            assertPrincipal(token.principal as AdminUserDetails, email, role, tenants)
        }

        private fun assertPrincipal(p: AdminUserDetails, email: String, role: String, tenants: Set<UUID>) {
            assertThat(p.id).isEqualTo(ADMIN_ID)
            assertThat(p.username).isEqualTo(email)
            assertThat(p.role).isEqualTo(role)
            assertThat(p.allowedTenantIds).isEqualTo(tenants)
            assertThat(p.isEnabled).isTrue()
            assertThat(p.isAccountNonLocked).isTrue() // 역직렬화된 Clock 으로 PAST_LOCK 이후임을 계산
            assertThat(p.isAccountNonExpired).isTrue()
            assertThat(p.isCredentialsNonExpired).isTrue()
            assertThat(p.isPlatformOperator()).isEqualTo("PLATFORM_OPERATOR" == role)
            assertThat(p.isRpAdmin()).isEqualTo("RP_ADMIN" == role)
            assertThat(p.authorities.map { it.authority }).containsExactly("ROLE_$role")
        }
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var clock: Clock
    @Autowired lateinit var uds: AdminUserDetailsService
    @Autowired lateinit var encoder: PasswordEncoder
    @Autowired lateinit var users: AdminUserRepository
    @Autowired lateinit var policy: SecurityPolicyService

    @BeforeEach
    fun setUp() {
        whenever(clock.instant()).thenReturn(Fill.INSTANT)
        whenever(clock.zone).thenReturn(ZoneOffset.UTC)
        whenever(encoder.matches(anyString(), anyString())).thenReturn(true)
        whenever(policy.get()).thenAnswer(Fill.ANSWER)
    }

    private fun account(email: String, role: String, tenants: Set<UUID>, mfa: Boolean) {
        whenever(uds.loadUserByUsername(email)).thenReturn(principal(email, role, tenants))
        val u = AdminUser(email, "\$2a\$12\$hash", role)
        u.setMfaEnabled(mfa)
        whenever(users.findByEmail(email)).thenReturn(Optional.of(u))
    }

    private fun login(email: String): MockHttpSession {
        val r = mvc.perform(
            post("/admin/login").with(csrf().asHeader())
                .param("email", email).param("password", "pw"),
        ).andReturn()
        assertThat(r.response.status).`as`("login $email").isEqualTo(200)
        return r.request.getSession(false) as MockHttpSession
    }

    @Test
    fun observedSessionAttributesMatchDocumentedListAndRoundTrip() {
        val observed = LinkedHashMap<String, Any?>()

        // A. RP_ADMIN(MFA 없음) 로그인 → 같은 세션으로 활성 RP 조회(세션 SecurityContext 로 인증, ACTIVE_TENANT_ID 생성).
        account("rp@example.com", "RP_ADMIN", java.util.Set.of(T2, T1), false)
        val rp = login("rp@example.com")
        val active = mvc.perform(get("/admin/api/active-tenant").session(rp)).andReturn()
        assertThat(active.response.status).isEqualTo(200)
        assertThat(rp.getAttribute(ActiveTenantResolver.ACTIVE_TENANT_ATTR)).isEqualTo(T1) // TreeSet.first
        observed.putAll(attributes(rp))

        // B. MFA 사용 운영자 로그인 → MFA_PENDING.
        account("mfa@example.com", "PLATFORM_OPERATOR", emptySet(), true)
        val mfa = login("mfa@example.com")
        assertThat(mfa.getAttribute(MfaPendingFilter.MFA_PENDING_ATTR)).isEqualTo(java.lang.Boolean.TRUE)
        observed.putAll(attributes(mfa))

        // C. 미인증 화면 접근 → 요청 캐시(SPRING_SECURITY_SAVED_REQUEST).
        val anon = mvc.perform(get("/admin/tenants")).andReturn()
        val anonSession = anon.request.getSession(false) as MockHttpSession?
        if (anonSession != null) observed.putAll(attributes(anonSession))

        // 테스트 도구 산물: spring-security-test 의 csrf() 후처리기가 HttpSessionCsrfTokenRepository 로 토큰을 세션에 둔다.
        // 운영 저장소는 CookieCsrfTokenRepository(AdminSecurityConfig.buildCsrfTokenRepository)라 세션 속성이 아니다.
        assertThat(observed.remove("org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository.CSRF_TOKEN"))
            .`as`("csrf() 후처리기 산물").isNotNull()

        assertThat(TreeSet(observed.keys)).isEqualTo(TreeSet(DOCUMENTED_ATTRIBUTES))

        // 각 속성 왕복.
        for ((key, value) in observed) {
            val back = roundTrip(value)
            assertThat(back).`as`(key).isNotNull().hasSameClassAs(value)
        }

        val ctx = rp.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) as SecurityContext
        assertSecurityContextRoundTrip(ctx, "rp@example.com", "RP_ADMIN", setOf(T1, T2))
        val mfaCtx = mfa.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) as SecurityContext
        assertSecurityContextRoundTrip(mfaCtx, "mfa@example.com", "PLATFORM_OPERATOR", emptySet())

        assertThat(roundTrip(rp.getAttribute(ActiveTenantResolver.ACTIVE_TENANT_ATTR))).isEqualTo(T1)
        assertThat(roundTrip(mfa.getAttribute(MfaPendingFilter.MFA_PENDING_ATTR))).isEqualTo(java.lang.Boolean.TRUE)
        val saved = observed["SPRING_SECURITY_SAVED_REQUEST"] as DefaultSavedRequest
        val savedBack = roundTrip(saved)
        assertThat(savedBack.requestURI).isEqualTo(saved.requestURI).isEqualTo("/admin/tenants")
        assertThat(savedBack.method).isEqualTo("GET")
    }

    @Test
    fun adminUserDetailsRoundTripsStandalone() {
        // 잠금 중(미래 lockedUntil)·잠금 없음(null) 두 경우 모두 Clock 과 함께 복원된다.
        val locked = AdminUserDetails(
            ADMIN_ID, "a@example.com", "h", "PLATFORM_OPERATOR", emptySet(),
            false, OffsetDateTime.parse("2999-01-01T00:00:00+09:00"), Clock.system(KstTime.ZONE),
        )
        val back = roundTrip(locked)
        assertThat(back.isAccountNonLocked).isFalse()
        assertThat(back.isEnabled).isFalse()
        assertThat(back.password).isEqualTo("h")

        val open = AdminUserDetails(
            ADMIN_ID, "b@example.com", "h", "RP_ADMIN", setOf(T1),
            true, null, Clock.systemUTC(),
        )
        val openBack = roundTrip(open)
        assertPrincipal(openBack, "b@example.com", "RP_ADMIN", setOf(T1))
    }
}
