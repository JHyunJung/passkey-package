package com.crosscert.passkey.admin.contract

import com.crosscert.passkey.admin.activity.ActivityController
import com.crosscert.passkey.admin.apikey.ApiKeyAdminController
import com.crosscert.passkey.admin.audit.AuditChainMonitorController
import com.crosscert.passkey.admin.audit.AuditLogController
import com.crosscert.passkey.admin.auth.ActiveTenantController
import com.crosscert.passkey.admin.auth.AdminUserDetails
import com.crosscert.passkey.admin.auth.MfaController
import com.crosscert.passkey.admin.credential.CredentialAdminController
import com.crosscert.passkey.admin.funnel.AdoptionController
import com.crosscert.passkey.admin.funnel.FunnelController
import com.crosscert.passkey.admin.insights.PlatformInsightsController
import com.crosscert.passkey.admin.license.LicenseController
import com.crosscert.passkey.admin.mds.MdsAdminController
import com.crosscert.passkey.admin.operator.AdminUserController
import com.crosscert.passkey.admin.operator.PasswordResetController
import com.crosscert.passkey.admin.operator.SignupRequestController
import com.crosscert.passkey.admin.policy.AaguidPolicyController
import com.crosscert.passkey.admin.policy.SecurityPolicyController
import com.crosscert.passkey.admin.system.SystemInfoController
import com.crosscert.passkey.admin.tenant.TenantAdminController
import com.crosscert.passkey.admin.web.AdminWebSliceTest
import com.crosscert.passkey.admin.web.HomeRedirectController
import com.crosscert.passkey.admin.web.activity.ActivityPageController
import com.crosscert.passkey.admin.web.audit.AuditChainPageController
import com.crosscert.passkey.admin.web.auth.LoginPageController
import com.crosscert.passkey.admin.web.auth.PasswordPagesController
import com.crosscert.passkey.admin.web.auth.SignupPageController
import com.crosscert.passkey.admin.web.insights.InsightsPageController
import com.crosscert.passkey.admin.web.license.LicensePageController
import com.crosscert.passkey.admin.web.settings.AccountTabController
import com.crosscert.passkey.admin.web.settings.AdminUsersTabController
import com.crosscert.passkey.admin.web.settings.MdsTabController
import com.crosscert.passkey.admin.web.settings.SecurityPolicyTabController
import com.crosscert.passkey.admin.web.settings.SettingsPageController
import com.crosscert.passkey.admin.web.settings.SystemInfoTabController
import com.crosscert.passkey.admin.web.shell.CommandPaletteController
import com.crosscert.passkey.admin.web.shell.ShellController
import com.crosscert.passkey.admin.web.support.ScreenErrorViewResolver
import com.crosscert.passkey.admin.web.tenant.AaguidTabController
import com.crosscert.passkey.admin.web.tenant.ApiKeysTabController
import com.crosscert.passkey.admin.web.tenant.AuditTabController
import com.crosscert.passkey.admin.web.tenant.CredentialsTabController
import com.crosscert.passkey.admin.web.tenant.FunnelTabController
import com.crosscert.passkey.admin.web.tenant.TenantDetailPageController
import com.crosscert.passkey.admin.web.tenant.TenantsPageController
import com.crosscert.passkey.admin.web.tenant.WebauthnTabController
import com.crosscert.passkey.core.api.ApiResponse
import com.crosscert.passkey.core.entity.AdminUser
import com.crosscert.passkey.core.repository.AdminUserRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.mockito.kotlin.whenever
import org.mockito.stubbing.Answer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.context.annotation.Import
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

/**
 * 렌더 HTML 스냅샷 테스트 공통 — 슬라이스 구성·결정적 스텁·요청 도우미.
 *
 * 모든 화면 컨트롤러를 한 슬라이스에 올리고([HtmlContractSlice] — 같은 구성이라 하위 테스트 클래스가 컨텍스트를 공유),
 * 화면 컨트롤러가 부르는 REST 컨트롤러는 [Fill.ANSWER] 기본 응답을 내는 mock 으로 둔다. mock 은 싱글턴으로 직접
 * 등록해(기존 `@MockitoBean` 과 같이) 메서드 보안 프록시를 거치지 않는다 — 화면 컨트롤러 자신의 `@PreAuthorize`
 * 와 보안 체인은 그대로 적용된다. 각 테스트는 화면에 의미 있는 값(상태·불리언 true/false 둘 다)을 [Stub] 으로 덧씌운다.
 * mock 의 스텁은 테스트마다 초기화된다(기본 Answer 는 유지).
 *
 * 시계: 슬라이스의 `Clock` mock 을 [NOW] 로 고정한다. 스텁의 시각은 [Fill.ODT](NOW 2일 전) 등 고정값.
 */
abstract class HtmlContractSupport {

    /** 화면 컨트롤러 전부 + 오류 화면 해석기 + REST 컨트롤러 mock. */
    @Target(AnnotationTarget.CLASS)
    @Retention(AnnotationRetention.RUNTIME)
    @MustBeDocumented
    @AdminWebSliceTest(
        controllers = [
            TenantsPageController::class, TenantDetailPageController::class, ApiKeysTabController::class,
            AaguidTabController::class, WebauthnTabController::class, AuditTabController::class,
            CredentialsTabController::class, FunnelTabController::class,
            SettingsPageController::class, AccountTabController::class, AdminUsersTabController::class,
            MdsTabController::class, SecurityPolicyTabController::class, SystemInfoTabController::class,
            LicensePageController::class, InsightsPageController::class, ActivityPageController::class,
            AuditChainPageController::class, ShellController::class, CommandPaletteController::class,
            LoginPageController::class, PasswordPagesController::class, SignupPageController::class,
            HomeRedirectController::class,
        ],
    )
    @Import(ScreenErrorViewResolver::class, ControllerMocks::class)
    annotation class HtmlContractSlice

    /** REST 컨트롤러 mock 을 싱글턴으로 등록한다(프록시·후처리기 없음 — `@MockitoBean` 과 같은 성질). */
    class ControllerMocks : BeanFactoryPostProcessor {
        override fun postProcessBeanFactory(bf: ConfigurableListableBeanFactory) {
            ALL.clear() // 컨텍스트를 다시 만들면 이전 컨텍스트의 mock 이 쌓이지 않게
            for (t in TYPES) {
                val m: Any = Mockito.mock(t, ANSWER)
                ALL.add(m)
                val n = t.simpleName
                bf.registerSingleton(Character.toLowerCase(n[0]) + n.substring(1), m)
            }
        }

        companion object {
            val TYPES: List<Class<*>> = listOf(
                TenantAdminController::class.java, ApiKeyAdminController::class.java, FunnelController::class.java,
                ActivityController::class.java, AuditLogController::class.java, AuditChainMonitorController::class.java,
                AaguidPolicyController::class.java, CredentialAdminController::class.java, AdoptionController::class.java,
                PlatformInsightsController::class.java, SignupRequestController::class.java,
                SecurityPolicyController::class.java,
                MfaController::class.java, SystemInfoController::class.java, AdminUserController::class.java,
                MdsAdminController::class.java, LicenseController::class.java, PasswordResetController::class.java,
                ActiveTenantController::class.java,
            )
            val ALL: MutableList<Any> = ArrayList()
        }
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var clock: Clock
    @Autowired lateinit var admins: AdminUserRepository

    @Autowired lateinit var tenants: TenantAdminController
    @Autowired lateinit var apiKeys: ApiKeyAdminController
    @Autowired lateinit var funnel: FunnelController
    @Autowired lateinit var activity: ActivityController
    @Autowired lateinit var auditLog: AuditLogController
    @Autowired lateinit var chain: AuditChainMonitorController
    @Autowired lateinit var aaguid: AaguidPolicyController
    @Autowired lateinit var credentials: CredentialAdminController
    @Autowired lateinit var adoption: AdoptionController
    @Autowired lateinit var insights: PlatformInsightsController
    @Autowired lateinit var signups: SignupRequestController
    @Autowired lateinit var securityPolicy: SecurityPolicyController
    @Autowired lateinit var mfa: MfaController
    @Autowired lateinit var systemInfo: SystemInfoController
    @Autowired lateinit var adminUsers: AdminUserController
    @Autowired lateinit var mds: MdsAdminController
    @Autowired lateinit var license: LicenseController
    @Autowired lateinit var passwordReset: PasswordResetController
    @Autowired lateinit var activeTenants: ActiveTenantController

    @BeforeEach
    fun baseStubs() {
        whenever(clock.instant()).thenReturn(NOW)
        whenever(clock.millis()).thenReturn(NOW.toEpochMilli())
        whenever(clock.zone).thenReturn(ZoneOffset.UTC)
        whenever(admins.findByEmail(anyString())).thenAnswer { inv ->
            Optional.of(AdminUser(inv.getArgument<String>(0), "x", "PLATFORM_OPERATOR"))
        }
    }

    @AfterEach
    fun resetControllerMocks() {
        ControllerMocks.ALL.forEach { Mockito.reset(it) }
    }

    /** 요청을 보내고 `contract/html/<path>` 스냅샷과 비교한다. */
    fun snap(path: String, req: MockHttpServletRequestBuilder): MvcResult {
        val r = mvc.perform(req).andReturn()
        HtmlSnapshot.assertMatches(path, r)
        return r
    }

    companion object {
        val NOW: Instant = Instant.parse("2026-10-13T03:34:56Z")
        val T0: OffsetDateTime = Fill.ODT // NOW - 2일
        val TID: UUID = Fill.ID
        val TID2: UUID = UUID.fromString("00000000-0000-0000-0000-00000000f222")
        val OP_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
        val RP_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a2")
        const val OP_EMAIL = "op@example.com"
        const val RP_EMAIL = "rp@example.com"

        /**
         * [Fill.ANSWER] + `ApiResponse<Void>` 은 `ApiResponse.ok()`, `ResponseEntity<X>` 는 200 + X 값.
         * (`ResponseEntity<?>` 를 돌려주는 메서드는 테스트에서 직접 스텁한다.)
         */
        val ANSWER: Answer<Any?> = Answer { inv ->
            val raw = inv.method.returnType
            if (raw == java.lang.Void.TYPE || raw == java.lang.Void::class.java) null else value(inv.method.genericReturnType)
        }

        private fun value(t: Type): Any? {
            if (t is ParameterizedType) {
                val arg = t.actualTypeArguments[0]
                if (t.rawType == ApiResponse::class.java && arg == java.lang.Void::class.java) return ApiResponse.ok()
                if (t.rawType == ResponseEntity::class.java && arg == java.lang.Void::class.java) {
                    return ResponseEntity.ok().build<Any>()
                }
                if (t.rawType == ResponseEntity::class.java) {
                    return ResponseEntity.ok(value(arg))
                }
            }
            return Fill.value(t)
        }

        // ── principals ─────────────────────────────────────────

        fun operator(): Authentication = auth(OP_ID, OP_EMAIL, "PLATFORM_OPERATOR", emptySet())

        fun rpAdmin(): Authentication = auth(RP_ID, RP_EMAIL, "RP_ADMIN", RP_TENANTS)

        /**
         * RP_ADMIN 의 허용 tenant(최소값은 항상 f111 = [TID]). 화면에서 이 순서가 드러나는 곳은 RP 의 홈 경로
         * (`HomeRedirectController.homePath` = `tenantIds.get(0)`)로 가는 리다이렉트 `Location` 이며, 스냅샷에는
         * 정렬 뒤 첫 값 f111 이 고정된다.
         *
         * `AdminUserDetails` 는 `Set.copyOf` 로 담고, 그 순회 순서는 JVM 마다 다르지만(`ImmutableCollections.SALT`)
         * **한 JVM 안에서는 고정**이다. 그래서 클래스 초기화 때 후보 집합을 차례로 만들어 `Set.copyOf(후보)` 의 첫 원소가
         * 최소값이 **아닌** 첫 후보를 고른다 — `MeController` 의 `tenantIds.sort` 가 빠지면 첫 값이 f111 이 아니게 되어
         * RP 홈 리다이렉트 스냅샷이 반드시 깨진다. 전제는 `RpTenantOrderPreconditionTest` 가 단언한다.
         */
        val RP_TENANTS: Set<UUID> = rpTenants()

        fun rpTenants(): Set<UUID> {
            val base = ArrayList(listOf(TID, TID2))
            for (d in listOf("3", "4", "5", "6", "7", "8", "9", "a")) {
                base.add(UUID.fromString("00000000-0000-0000-0000-00000000f$d$d$d")) // f333 … faaa, 모두 f111 보다 큼
            }
            for (k in 0 until 256) {
                val candidate = ArrayList(base)
                // 후보마다 f111 보다 큰 ID 를 k 개 더해 해시 배치를 바꾼다(최소값은 그대로 f111).
                for (j in 1..k) candidate.add(UUID.fromString(String.format("00000000-0000-0000-0000-0001%08x", j)))
                // AdminUserDetails 와 같은 복사(이미 불변 Set 이면 그대로 쓰인다)
                val copy: Set<UUID> = java.util.Set.copyOf(candidate)
                if (copy.iterator().next() != TID) return copy
            }
            throw IllegalStateException("RP_ADMIN tenant 집합: 첫 순회 원소가 최소값이 아닌 후보를 찾지 못했다")
        }

        fun auth(id: UUID, email: String, role: String, tenants: Set<UUID>): Authentication {
            val p = AdminUserDetails(id, email, "x", role, tenants, true, null, Clock.systemUTC())
            return UsernamePasswordAuthenticationToken(p, "x", p.authorities)
        }

        // ── 요청 도우미 ─────────────────────────────────────────

        fun page(url: String, a: Authentication): MockHttpServletRequestBuilder =
            get(URI.create(url)).with(authentication(a))

        fun frag(url: String, a: Authentication): MockHttpServletRequestBuilder =
            get(URI.create(url)).with(authentication(a)).header("HX-Request", "true")

        /** htmx 쓰기 요청(POST/PUT/DELETE) — CSRF 포함. */
        fun write(b: MockHttpServletRequestBuilder, a: Authentication): MockHttpServletRequestBuilder =
            b.with(authentication(a)).with(csrf()).header("HX-Request", "true")
    }
}
