package com.crosscert.passkey.admin.web.tenant

import com.crosscert.passkey.admin.activity.ActivityController
import com.crosscert.passkey.admin.activity.ActivityView
import com.crosscert.passkey.admin.apikey.ApiKeyAdminController
import com.crosscert.passkey.admin.apikey.ApiKeyAdminDto
import com.crosscert.passkey.admin.audit.AuditChainMonitorController
import com.crosscert.passkey.admin.audit.AuditLogController
import com.crosscert.passkey.admin.auth.ActiveTenantController
import com.crosscert.passkey.admin.auth.AdminUserDetails
import com.crosscert.passkey.admin.funnel.FunnelController
import com.crosscert.passkey.admin.funnel.FunnelDto
import com.crosscert.passkey.admin.license.LicenseController
import com.crosscert.passkey.admin.license.LicenseView
import com.crosscert.passkey.admin.policy.AaguidPolicyController
import com.crosscert.passkey.admin.policy.AaguidPolicyDto
import com.crosscert.passkey.admin.tenant.TenantAdminController
import com.crosscert.passkey.admin.tenant.TenantAdminDto
import com.crosscert.passkey.admin.web.AdminWebSliceTest
import com.crosscert.passkey.core.api.ApiResponse
import com.crosscert.passkey.core.api.BusinessException
import com.crosscert.passkey.core.api.ErrorCode
import com.crosscert.passkey.core.entity.TenantAaguidPolicy
import org.assertj.core.api.Assertions.assertThat
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.stereotype.Controller
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ResponseBody
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

/**
 * Tenant 상세 틀과 개요 탭. 원본: pages/TenantDetailPage.test.tsx, pages/tenant/TenantOverview.test.tsx, tenantSignals.test.ts, tabFormat.test.ts.
 * 브라우저에서 도는 부분(Alpine·htmx 의 실제 교체·포커스·메뉴·다이얼로그 열기)은 속성·소스 수준까지만 본다.
 *
 * (Kotlin 전환) nullable jsoup 결과(`selectFirst`·`first`)와 응답 헤더의 `!!` 는 원 Java 의 NPE 실패 지점과 같다.
 * `trim { it <= ' ' }`·`lowercase(Locale.getDefault())` 는 Java `String.trim()`·`toLowerCase()` 와 같은 판정을 유지한다.
 */
@AdminWebSliceTest(controllers = [TenantDetailPageController::class])
@Import(TenantDetailPageControllerTest.LiteralTabController::class)
class TenantDetailPageControllerTest {

    /** 이후 탭 task 가 하게 될 리터럴 탭 매핑의 대역(우선순위 검증용). */
    @Controller
    class LiteralTabController {
        @GetMapping("/admin/_/tenants/{id}/tab/webauthn")
        @ResponseBody
        fun webauthn(): String = "LITERAL-WEBAUTHN"
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var clock: Clock
    @MockitoBean lateinit var tenants: TenantAdminController
    @MockitoBean lateinit var funnel: FunnelController
    @MockitoBean lateinit var activity: ActivityController
    @MockitoBean lateinit var audit: AuditLogController
    @MockitoBean lateinit var chain: AuditChainMonitorController
    @MockitoBean lateinit var apiKeys: ApiKeyAdminController
    @MockitoBean lateinit var aaguid: AaguidPolicyController
    @MockitoBean lateinit var license: LicenseController
    @MockitoBean lateinit var activeTenants: ActiveTenantController

    companion object {
        val NOW: Instant = Instant.parse("2026-10-11T00:00:00Z")
        val T1: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val OTHER: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000ff")

        // ── fixtures ────────────────────────────────────────────

        fun platform(): Authentication = auth("PLATFORM_OPERATOR", setOf())
        fun rp(vararg ids: UUID): Authentication = auth("RP_ADMIN", java.util.Set.of(*ids))

        private fun auth(role: String, tenants: Set<UUID>): Authentication {
            val p = AdminUserDetails(UUID.randomUUID(), "alice@example.com", "x", role, tenants, true, null, Clock.systemUTC())
            return UsernamePasswordAuthenticationToken(p, "x", p.authorities)
        }

        fun view(status: String): TenantAdminDto.TenantView =
            TenantAdminDto.TenantView(
                T1, "acme", "Acme", status, "passkey.acme.com", "Acme",
                listOf("http://localhost:9090", "android:apk-key-hash:abc"), setOf("none"), false, false, "NONE", 60000,
                1234, 3, null, OffsetDateTime.parse("2026-03-01T16:00:00Z"), OffsetDateTime.parse("2026-03-01T16:00:00Z"),
            )

        fun funnelView(): FunnelDto.View =
            FunnelDto.View(7, FunnelDto.Stage(10, 9, 0.9), FunnelDto.Stage(200, 190, 0.95), 0.0, listOf(), listOf())

        fun policy(mode: TenantAaguidPolicy.Mode, vararg entries: AaguidPolicyDto.Entry): AaguidPolicyDto.View =
            AaguidPolicyDto.View(T1, mode, false, listOf(*entries), null, null)

        fun key(expiresAt: OffsetDateTime?, revokedAt: OffsetDateTime?): ApiKeyAdminDto.ApiKeyView =
            ApiKeyAdminDto.ApiKeyView(UUID.randomUUID(), T1, "prod", "pk_a", setOf(), NOW.atOffset(ZoneOffset.UTC), expiresAt, revokedAt, null)

        private fun doc(r: MvcResult): Document = Jsoup.parse(r.response.getContentAsString(StandardCharsets.UTF_8))

        private fun tabLabels(d: Document): List<String> = d.select("[role=tab]").map { e -> e.ownText().trim { it <= ' ' } }
    }

    @BeforeEach
    fun setUp() {
        whenever(clock.instant()).thenReturn(NOW)
        whenever(clock.zone).thenReturn(ZoneOffset.UTC)
        whenever(license.get()).thenReturn(ApiResponse.ok(LicenseView("saas", "VALID", "acme", "lic_1", NOW.plusSeconds(86400L * 30), 30, listOf(), null, null, null)))
        whenever(tenants.get(T1.toString())).thenReturn(ApiResponse.ok(view("ACTIVE")))
        whenever(funnel.get(eq(T1), anyInt())).thenReturn(funnelView())
        whenever(apiKeys.list(T1.toString())).thenReturn(ApiResponse.ok(listOf()))
        whenever(aaguid.get(T1)).thenReturn(policy(TenantAaguidPolicy.Mode.ANY))
        whenever(activity.activity(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(ApiResponse.ok(ActivityView(null, listOf(), listOf())))
        whenever(audit.list(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyInt(), anyInt())).thenReturn(ApiResponse.ok(listOf()))
        whenever(chain.verifyTenant(T1.toString())).thenReturn(AuditChainMonitorController.TenantVerifyResponse(T1, true, null, NOW))
    }

    private fun `as`(b: MockHttpServletRequestBuilder, a: Authentication, htmx: Boolean): MockHttpServletRequestBuilder {
        b.with(authentication(a))
        if (htmx) b.header("HX-Request", "true")
        return b
    }

    private fun getPage(url: String, a: Authentication): MvcResult =
        mvc.perform(`as`(get(URI.create(url)), a, false)).andReturn()

    private fun getFragment(url: String, a: Authentication): MvcResult =
        mvc.perform(`as`(get(URI.create(url)), a, true)).andReturn()

    private fun page(url: String, a: Authentication): Document {
        val r = getPage(url, a)
        assertThat(r.response.status).`as`(url).isEqualTo(200)
        return doc(r)
    }

    private fun doPost(url: String, a: Authentication, htmx: Boolean, currentUrl: String?): MvcResult {
        val b = post(URI.create(url)).with(authentication(a)).with(csrf())
        if (htmx) b.header("HX-Request", "true")
        if (currentUrl != null) b.header("HX-Current-URL", currentUrl)
        return mvc.perform(b).andReturn()
    }

    // ── 헤더·탭 줄 ──────────────────────────────────────────

    @Test
    @DisplayName("이름·상태 배지·RP 사이트 링크·속성 칩·탭 7개, 개요가 인라인으로 그려진다")
    fun headerAndTabs() {
        val d = page("/admin/tenants/$T1", platform())
        assertThat(d.select("aside.sidebar")).hasSize(1)
        assertThat(d.select("h1.page__title").text()).isEqualTo("Acme")
        assertThat(d.select(".page__title ~ .badge, .badge").any { b -> b.text() == "활성" && b.hasClass("badge--success") }).isTrue()
        val rp = d.selectFirst("a.btn[target=_blank]")!!
        assertThat(rp.attr("href")).isEqualTo("https://passkey.acme.com")
        assertThat(rp.attr("rel")).contains("noopener").contains("noreferrer")
        assertThat(rp.text()).contains("RP 사이트 열기")
        assertThat(d.select("button[aria-label='ID 복사'], button[aria-label='RP ID 복사']")).hasSize(2)
        assertThat(d.select(".propchip__v").eachText()).contains("acme", "2026-03-02")
        assertThat(tabLabels(d)).containsExactly("개요", "WebAuthn", "AAGUID 정책", "API 키", "Credentials", "감사 로그", "인증 현황")
        assertThat(d.select("[role=tab][aria-selected=true]").text()).isEqualTo("개요")
        assertThat(d.select("[role=tablist]").attr("aria-label")).isEqualTo("Tenant 탭")
        // 개요는 서버가 그린다(R3')
        assertThat(d.select("#tenant-tab .kpi__label").eachText()).containsExactly("등록 Credential", "유효 API Key", "등록 성공률 (7d)", "인증 성공률 (7d)")
        assertThat(d.select("#tenant-tab h2.card__title").eachText()).containsExactly("WebAuthn 요약", "최근 활동", "감사 체인")
        assertThat(d.select("#tenant-tab[hx-get]")).isEmpty()
    }

    @Test
    @DisplayName("탭 줄의 *-badge / *-count 자리 id 는 각각 한 번만(중첩·중복 없음)")
    fun tabSlotIdsAreUnique() {
        val d = page("/admin/tenants/$T1", platform())
        for (suffix in arrayOf("-badge", "-count")) {
            val seen = java.util.HashMap<String, Int>()
            d.select("[role=tablist] [id$=$suffix]").forEach { e -> seen.merge(e.id(), 1) { a, b -> a + b } }
            assertThat(seen).isNotEmpty()
            assertThat(seen.values).allMatch { n -> n == 1 }
            assertThat(d.select("[role=tablist] [id$=$suffix] [id$=$suffix]")).isEmpty()
        }
    }

    @Test
    @DisplayName("탭 링크는 hx-get(조각) + hx-push-url(화면 주소), 대상은 #tenant-tab, 일반 링크 href 도 같은 화면 주소")
    fun tabLinkAttributes() {
        val d = page("/admin/tenants/$T1", platform())
        val wa = d.selectFirst("[role=tab][data-tab=webauthn]")!!
        assertThat(wa.attr("href")).isEqualTo("/admin/tenants/$T1?tab=webauthn")
        assertThat(wa.attr("hx-get")).isEqualTo("/admin/_/tenants/$T1/tab/webauthn")
        assertThat(wa.attr("hx-push-url")).isEqualTo("/admin/tenants/$T1?tab=webauthn")
        assertThat(wa.attr("hx-target")).isEqualTo("#tenant-tab")
        assertThat(wa.attr("hx-swap")).isEqualTo("innerHTML")
        assertThat(wa.attr("data-label")).isEqualTo("WebAuthn")
        assertThat(d.select("[role=tab][data-tab]")).hasSize(7)
    }

    @Test
    @DisplayName("다른 탭이 골라지면 탭 컨테이너가 hx-trigger=load 로 조각을 불러오고 스켈레톤을 보인다(개요를 미리 읽지 않는다)")
    fun nonOverviewTabLoadsLazily() {
        val d = page("/admin/tenants/$T1?tab=webauthn", platform())
        val box = d.selectFirst("#tenant-tab")!!
        assertThat(box.attr("hx-get")).isEqualTo("/admin/_/tenants/$T1/tab/webauthn")
        assertThat(box.attr("hx-trigger")).isEqualTo("load")
        assertThat(box.select("[role=status][aria-busy=true]")).hasSize(1)
        assertThat(d.select("[role=tab][aria-selected=true]").text()).isEqualTo("WebAuthn")
        assertThat(d.select(".kpi")).isEmpty()
        verify(funnel, never()).get(anyOrNull(), anyInt())
        assertThat(d.select("nav.crumbs").text()).contains("WebAuthn")
    }

    @Test
    @DisplayName("모든 탭 키(TSX TENANT_TABS)가 선택될 수 있다")
    fun everyTabKeyIsSelectable() {
        for (k in listOf("overview", "webauthn", "aaguid", "apikeys", "credentials", "audit", "funnel")) {
            val d = page("/admin/tenants/$T1?tab=$k", platform())
            assertThat(d.select("[role=tab][aria-selected=true]").attr("data-tab")).isEqualTo(k)
        }
    }

    @Test
    @DisplayName("unknownTabFallsBackToOverview: 모르는 탭 키는 전체 페이지·조각 모두 개요")
    fun unknownTabFallsBackToOverview() {
        val d = page("/admin/tenants/$T1?tab=bogus", platform())
        assertThat(d.select("[role=tab][aria-selected=true]").text()).isEqualTo("개요")
        assertThat(d.select("#tenant-tab .kpi")).hasSize(4)
        assertThat(d.select("#tenant-tab[hx-get]")).isEmpty()

        val r = getFragment("/admin/_/tenants/$T1/tab/bogus", platform())
        assertThat(r.response.status).isEqualTo(200)
        assertThat(doc(r).select(".kpi")).hasSize(4)
    }

    @Test
    @DisplayName("탭 조각은 레이아웃 없이 개요만 돌려준다(일반 매핑은 개요·모르는 키)")
    fun tabFragmentHasNoLayout() {
        for (k in listOf("overview", "audit", "zzz")) {
            val r = getFragment("/admin/_/tenants/$T1/tab/$k", platform())
            assertThat(r.response.status).isEqualTo(200)
            val d = doc(r)
            assertThat(d.select("aside.sidebar, header.topbar, body > .app")).isEmpty()
            assertThat(d.select(".kpi")).hasSize(4)
            assertThat(d.select("h1")).isEmpty()
        }
    }

    @Test
    @DisplayName("literalTabMappingWinsOverGeneric: 이후 탭 task 의 리터럴 경로가 일반 {tab} 매핑보다 우선한다")
    fun literalTabMappingWinsOverGeneric() {
        val lit = getFragment("/admin/_/tenants/$T1/tab/webauthn", platform())
        assertThat(lit.response.getContentAsString(StandardCharsets.UTF_8)).isEqualTo("LITERAL-WEBAUTHN")
        // 다른 키는 일반 매핑(개요)
        val gen = getFragment("/admin/_/tenants/$T1/tab/aaguid", platform())
        assertThat(doc(gen).select(".kpi")).hasSize(4)
    }

    // ── 브레드크럼 ──────────────────────────────────────────

    @Test
    @DisplayName("브레드크럼 이름은 서버 조회 결과: PLATFORM 은 Tenants › 이름 › 탭, RP_ADMIN 은 이름 › 탭")
    fun breadcrumbTenantName() {
        val p = page("/admin/tenants/$T1?tab=audit", platform()).selectFirst("nav.crumbs")!!
        assertThat(p.select("a.crumbs__item").eachText()).containsExactly("Tenants", "Acme")
        assertThat(p.select("a.crumbs__item")[1].attr("href")).isEqualTo("/admin/tenants/$T1?tab=overview")
        assertThat(p.select(".crumbs__current").text()).isEqualTo("감사 로그")

        val r = page("/admin/tenants/$T1", rp(T1)).selectFirst("nav.crumbs")!!
        assertThat(r.select("a.crumbs__item").eachText()).containsExactly("Acme")
        assertThat(r.select(".crumbs__current").text()).isEqualTo("개요")
    }

    // ── 개요: KPI·요약·활동 ────────────────────────────────

    @Test
    @DisplayName("KPI: 천 단위, 만료 임박은 경고 톤, 성공률 %와 'n / m 시도', 7일 증감은 서버에 없어 그리지 않음")
    fun kpis() {
        val in10 = NOW.atOffset(ZoneOffset.UTC).plusDays(10)
        whenever(apiKeys.list(T1.toString())).thenReturn(
            ApiResponse.ok(
                listOf(
                    key(in10, null), key(NOW.atOffset(ZoneOffset.UTC).plusDays(5), NOW.atOffset(ZoneOffset.UTC)), // 회수됨 제외
                    key(NOW.atOffset(ZoneOffset.UTC).plusDays(31), null), key(NOW.atOffset(ZoneOffset.UTC).minusDays(1), null), key(null, null),
                ),
            ),
        )
        val d = page("/admin/tenants/$T1", platform())
        assertThat(d.select(".kpi__value").eachText()).containsExactly("1,234", "3", "90.0%", "95.0%")
        assertThat(d.select(".kpi__hint").eachText()).containsExactly("만료 임박 1", "9 / 10 시도", "190 / 200 시도")
        assertThat(d.selectFirst(".kpi__hint--warn")!!.text()).isEqualTo("만료 임박 1")
        assertThat(d.text()).doesNotContain("7일 +")
    }

    @Test
    @DisplayName("만료 임박 조회가 실패하면 그 줄이 없다")
    fun noExpiringHintWhenLookupFails() {
        whenever(apiKeys.list(T1.toString())).thenThrow(IllegalStateException("boom"))
        val d = page("/admin/tenants/$T1", platform())
        assertThat(d.text()).doesNotContain("만료 임박")
        assertThat(d.select("[role=tab]").any { t -> t.text().contains("확인 필요") }).isFalse()
    }

    @Test
    @DisplayName("퍼널 실패: 성공률 KPI 는 '—' + '불러오지 못했습니다'(시도 없음 아님) + 다시 시도, 시도 0 이면 '시도 없음'")
    fun funnelFailureAndNoAttempts() {
        whenever(funnel.get(eq(T1), anyInt())).thenThrow(IllegalStateException("down"))
        val d = page("/admin/tenants/$T1", platform())
        assertThat(d.select(".kpi__value").eachText().subList(2, 4)).containsExactly("—", "—")
        assertThat(d.select(".kpi__hint").eachText()).filteredOn { s -> s == "불러오지 못했습니다" }.hasSize(2)
        assertThat(d.text()).doesNotContain("시도 없음")
        val err = d.selectFirst("#tenant-tab [role=alert].empty")!!
        assertThat(err.text()).contains("인증 현황 지표")
        assertThat(err.selectFirst("button[hx-get]")!!.attr("hx-get")).isEqualTo("/admin/_/tenants/$T1/tab/overview")

        // (Kotlin 전환) 원 Java 의 when(funnel.get(eq(T1), anyInt())) 는 eq() 가 null 을 넘겨 위 thenThrow 스텁에 걸리지 않았다.
        // mockito-kotlin eq() 는 실제 값(T1)을 넘겨 재스텁 호출이 thenThrow 를 터뜨리므로, 호출 없이 스텁하는 doReturn 으로 같은 스텁을 건다.
        doReturn(FunnelDto.View(7, FunnelDto.Stage(0, 0, 0.0), FunnelDto.Stage(0, 0, 0.0), 0.0, listOf(), listOf())).whenever(funnel).get(eq(T1), anyInt())
        val none =page("/admin/tenants/$T1", platform())
        assertThat(none.select(".kpi__hint").eachText()).contains("최근 7일 등록 시도 없음", "최근 7일 인증 시도 없음")
        assertThat(none.select("#tenant-tab [role=alert]")).isEmpty()
    }

    @Test
    @DisplayName("WebAuthn 요약: rpId·rpName·origins(실제 값, 없으면 행 없음)·userVerification·attestation·timeout, 편집은 webauthn 탭")
    fun webauthnSummary() {
        val d = page("/admin/tenants/$T1", platform())
        assertThat(d.select("dl.kv dt").eachText()).containsExactly("rpId", "rpName", "origins", "userVerification", "attestation", "timeout")
        assertThat(d.select("dl.kv .tag").eachText()).containsExactly("http://localhost:9090", "android:apk-key-hash:abc", "PREFERRED", "NONE")
        assertThat(d.select("dl.kv dd.mono").eachText()).containsExactly("passkey.acme.com", "60000 ms")
        assertThat(d.text()).doesNotContain("https://passkey.acme.com 은").doesNotContain("[\"https://")
        val edit = d.select("a.btn--ghost").first { a -> a.text().contains("편집") }
        assertThat(edit.attr("href")).isEqualTo("/admin/tenants/$T1?tab=webauthn")
    }

    @Test
    @DisplayName("PLATFORM: 최근 활동은 이 tenant 의 활동 피드 5건(다른 tenant 제외), 전체 보기는 활동 화면(tenantId)")
    fun platformRecentActivity() {
        val now = NOW.atOffset(ZoneOffset.UTC)
        whenever(activity.activity(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), eq(T1))).thenReturn(
            ApiResponse.ok(
                ActivityView(
                    null, listOf(),
                    listOf(
                        ActivityView.Event(UUID.randomUUID(), "CREDENTIAL_AUTHENTICATED", "", null, "cred-0123456789abcdef-xyz", T1, "acme", now, "ops"),
                        ActivityView.Event(UUID.randomUUID(), "API_KEY_ISSUED", "op@x.com", null, "k1", OTHER, "other", now, "ops"),
                        ActivityView.Event(UUID.randomUUID(), "AUTH_FAIL", null, null, null, T1, "acme", now.minusMinutes(5), "security"),
                        ActivityView.Event(UUID.randomUUID(), "WEBAUTHN_CONFIG_UPDATED", null, null, "t", T1, "acme", now.minusHours(3), "ops"),
                    ),
                ),
            ),
        )
        val d = page("/admin/tenants/$T1", platform())
        val card = d.select("section.card").first { c -> c.select("h2").text() == "최근 활동" }
        assertThat(card.select("li .tag").eachText()).containsExactly("CREDENTIAL_AUTHENTICATED", "AUTH_FAIL", "WEBAUTHN_CONFIG_UPDATED")
        assertThat(card.select("li .mono").eachText()[0]).isEqualTo("…456789abcdef-xyz")
        assertThat(card.select("li .mono").eachText()[1]).isEqualTo("…—")
        assertThat(card.select("li span.hint").eachText().filter { x -> !x.startsWith("…") }).containsExactly("방금 전", "5분 전", "3시간 전")
        assertThat(card.select("li .dot").eachAttr("style")).containsExactly("background: var(--brand-700)", "background: var(--bad)", "background: var(--warn-dot)")
        val all = card.select("a").first { a -> a.text().contains("전체 보기") }
        assertThat(all.attr("href")).isEqualTo("/admin/activity?tenantId=$T1")
        verify(audit, never()).list(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyInt(), anyInt())
    }

    @Test
    @DisplayName("활동이 없거나 조회가 실패하면 '최근 활동 없음'")
    fun noRecentActivity() {
        assertThat(page("/admin/tenants/$T1", platform()).text()).contains("최근 활동 없음")
        whenever(activity.activity(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())).thenThrow(IllegalStateException("x"))
        assertThat(page("/admin/tenants/$T1", platform()).text()).contains("최근 활동 없음")
    }

    // ── RP_ADMIN ───────────────────────────────────────────

    @Test
    @DisplayName("RP_ADMIN(자기 tenant): PLATFORM 전용 API(체인 검증·활동 피드)를 부르지 않고 감사 체인 카드가 없다, 최근 활동은 감사 로그 5건, 전체 보기는 audit 탭")
    fun rpAdminOwnTenant() {
        whenever(audit.list(anyOrNull(), anyOrNull(), eq(T1), anyOrNull(), anyOrNull(), eq(0), eq(5))).thenReturn(
            ApiResponse.ok(
                listOf(
                    AuditLogController.AuditLogView(UUID.randomUUID(), null, "rp@x.com", "WEBAUTHN_CONFIG_UPDATED", "TENANT", "tenant-0123456789abcdef", T1, null, NOW.atOffset(ZoneOffset.UTC)),
                ),
            ),
        )
        val d = page("/admin/tenants/$T1", rp(T1))
        assertThat(d.select("li .tag").eachText()).containsExactly("WEBAUTHN_CONFIG_UPDATED")
        verify(audit).list(null, null, T1, null, null, 0, 5)
        verify(activity, never()).activity(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
        verify(chain, never()).verifyTenant(anyOrNull())
        assertThat(d.select("#tenant-tab h2.card__title").eachText()).containsExactly("WebAuthn 요약", "최근 활동")
        assertThat(d.select("#tenant-chain")).isEmpty()
        assertThat(d.select("#tenant-tab .grid-2")).hasSize(1)
        assertThat(d.select("#tenant-tab .grid-3")).isEmpty()
        val all = d.select("a").first { a -> a.text().contains("전체 보기") }
        assertThat(all.attr("href")).isEqualTo("/admin/tenants/$T1?tab=audit")
    }

    @Test
    @DisplayName("rpAdminOtherTenantDenied: RP_ADMIN 이 다른 tenant 를 열면 전체 페이지·조각 모두 403, 어떤 tenant 데이터도 읽거나 그리지 않는다")
    fun rpAdminOtherTenantDenied() {
        whenever(tenants.get(OTHER.toString())).thenThrow(BusinessException(ErrorCode.ACCESS_DENIED, "RP_ADMIN cannot access tenant $OTHER"))
        val secret = "Acme"
        for (url in listOf("/admin/tenants/$OTHER?tab=api-keys", "/admin/tenants/$OTHER?tab=apikeys", "/admin/tenants/$OTHER")) {
            // 직접 접근: 403 + 인라인 오류(TSX 의 LoadError), 테넌트 내용 없음
            val full = getPage(url, rp(T1))
            assertThat(full.response.status).`as`(url).isEqualTo(403)
            val fd = doc(full)
            assertThat(fd.select("main [role=alert]").text()).contains("권한이 없습니다")
            assertThat(fd.select("main").text()).doesNotContain(secret).doesNotContain("passkey.acme.com")
            assertThat(fd.select("main .kpi, main [role=tab], main h1")).isEmpty()
            // boost 이동(HX-Request): 4xx 는 스왑되지 않으므로 200 + 같은 인라인 오류, 데이터 없음
            val boosted = getFragment(url, rp(T1))
            assertThat(boosted.response.status).`as`(url).isEqualTo(200)
            val bd = doc(boosted)
            assertThat(bd.select("main [role=alert]").text()).contains("권한이 없습니다")
            assertThat(bd.select("main").text()).doesNotContain(secret).doesNotContain("passkey.acme.com")
        }
        for (tab in listOf("overview", "apikeys", "api-keys")) {
            val url = "/admin/_/tenants/$OTHER/tab/$tab"
            val frag = getFragment(url, rp(T1))
            assertThat(frag.response.status).`as`(url).isEqualTo(403)
            assertThat(frag.response.contentAsString).`as`(url).doesNotContain("Acme").doesNotContain("kpi")
            assertThat(getPage(url, rp(T1)).response.status).`as`("$url (non-htmx)").isEqualTo(403)
        }
        val chainFrag = getFragment("/admin/_/tenants/$OTHER/overview/chain", rp(T1))
        assertThat(chainFrag.response.status).isEqualTo(403)
        // 경계 확인 전에 tenant 데이터를 읽는 호출이 하나도 없다
        verify(funnel, never()).get(anyOrNull(), anyInt())
        verify(apiKeys, never()).list(anyOrNull())
        verify(aaguid, never()).get(anyOrNull())
        verify(activity, never()).activity(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
        verify(audit, never()).list(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyInt(), anyInt())
        verify(chain, never()).verifyTenant(anyOrNull())
    }

    // ── 정지·정지 해제 ──────────────────────────────────────

    @Test
    @DisplayName("PLATFORM: 더보기(테두리 버튼) 메뉴에 '테넌트 정지'(위험 구획), 정지 L3 다이얼로그: 제목 조사, 영향 범위, slug 입력, 취소 기본 포커스")
    fun suspendDialogForPlatform() {
        val d = page("/admin/tenants/$T1", platform())
        val trigger = d.selectFirst("button[aria-label=더보기]")!!
        assertThat(trigger.hasClass("btn")).isTrue()
        assertThat(d.select("main [role=menuitem]").eachText()).containsExactly("테넌트 정지")
        assertThat(d.selectFirst("main [role=menuitem].menu__item--danger")).isNotNull()
        assertThat(d.selectFirst("main [role=menuitem]")!!.attr("x-on:click.capture")).isEqualTo("\$dispatch('dialog:open', { id: 'suspend-tenant' })")
        val html = d.html()
        assertThat(html).contains("Acme을 정지할까요?").contains("정지하면 이 tenant의 모든 RP 서버 요청이 거부됩니다.")
            .contains("영향 범위").contains("· 유효한 API 키 3개를 즉시 회수").contains("· 이 tenant의 패스키 등록·인증 모두 거부")
            .contains("· 정지를 해제해도 회수된 키는 복구되지 않음").contains("확인을 위해 slug acme 를 입력하세요")
            .contains("일치해야 정지 버튼이 활성화됩니다.").doesNotContain("ceremony")
        val dlg = d.selectFirst("template[x-if]")
        assertThat(dlg).isNotNull()
        assertThat(d.select("[x-data*='suspend-tenant']")).hasSize(1)
        val root = d.selectFirst("[x-data*='suspend-tenant']")!!
        assertThat(root.attr("x-data")).contains("\"level\":\"L3\"").contains("\"verb\":\"정지\"").contains("\"typing\":true")
        val confirm = d.selectFirst("template button[data-confirm]")!!
        assertThat(confirm.attr("hx-post")).isEqualTo("/admin/_/tenants/$T1/suspend")
        assertThat(confirm.attr("hx-swap")).isEqualTo("none")
        assertThat(confirm.hasClass("btn--danger-solid")).isTrue()
        assertThat(d.select("main template button[autofocus]").text()).isEqualTo("취소")
        assertThat(d.select("[x-data*='activate-tenant']")).isEmpty()
    }

    @Test
    @DisplayName("정지 영향의 API 키 수는 템플릿 밖 #tenant-apikey-count(data-count)를 열 때 읽는다 — API 키 탭 변경 응답이 OOB 로 갱신")
    fun suspendKeyCountReadsLiveCountElement() {
        val d = page("/admin/tenants/$T1", platform())
        val count = d.selectFirst("#tenant-apikey-count")
        assertThat(count).isNotNull()
        assertThat(count!!.attr("data-count")).isEqualTo("3")
        assertThat(count.hasAttr("hidden")).isTrue()
        assertThat(count.parents().`is`("template")).isFalse()
        val li = d.select("template li").first { e -> e.text().contains("유효한 API 키") }
        assertThat(li.attr("x-text")).contains("tenant-apikey-count").contains("dataset.count")
        // RP_ADMIN 화면에도 자리가 있어 OOB 대상이 없다는 오류가 나지 않는다
        assertThat(page("/admin/tenants/$T1", rp(T1)).select("#tenant-apikey-count")).hasSize(1)
    }

    @Test
    @DisplayName("SUSPENDED: 배지 '정지'(danger), 메뉴에 '정지 해제'(정지 메뉴 없음), L1 다이얼로그 문구")
    fun activateDialogForSuspended() {
        whenever(tenants.get(T1.toString())).thenReturn(ApiResponse.ok(view("SUSPENDED")))
        val d = page("/admin/tenants/$T1", platform())
        assertThat(d.select(".badge").any { b -> b.text() == "정지" && b.hasClass("badge--danger") }).isTrue()
        assertThat(d.select("main [role=menuitem]").eachText()).containsExactly("정지 해제")
        assertThat(d.select("main [role=menuitem].menu__item--danger")).isEmpty()
        assertThat(d.select("[x-data*='suspend-tenant']")).isEmpty()
        val html = d.html()
        assertThat(html).contains("Acme 의 정지를 해제합니다.").contains("이 테넌트의 정지를 해제하시겠습니까?").contains("패스키 등록·인증이 다시 허용됩니다")
            .contains("정지 때 회수된 API 키는 복구되지 않으므로 필요하면 새로 발급하세요.")
        val root = d.selectFirst("[x-data*='activate-tenant']")!!
        assertThat(root.attr("x-data")).contains("\"level\":\"L1\"").contains("\"verb\":\"정지 해제\"")
        val confirm = d.selectFirst("template button[data-confirm]")!!
        assertThat(confirm.attr("hx-post")).isEqualTo("/admin/_/tenants/$T1/activate")
        assertThat(confirm.hasClass("btn--primary")).isTrue()
    }

    @Test
    @DisplayName("suspendHiddenForRpAdmin: RP_ADMIN 에게는 더보기 메뉴·정지 다이얼로그가 없고, 직접 POST 도 403(REST 미호출)")
    fun suspendHiddenForRpAdmin() {
        val d = page("/admin/tenants/$T1", rp(T1))
        assertThat(d.select("button[aria-label=더보기]")).isEmpty()
        assertThat(d.select("main [role=menuitem]")).isEmpty()
        assertThat(d.html()).doesNotContain("정지할까요?").doesNotContain("정지 해제").doesNotContain("suspend-tenant").doesNotContain("activate-tenant")
        // RP 사이트 열기는 역할과 무관하게 보인다
        assertThat(d.select("a.btn[target=_blank]")).hasSize(1)

        for (htmx in booleanArrayOf(true, false)) {
            for (act in listOf("suspend", "activate")) {
                val r = doPost("/admin/_/tenants/$T1/$act", rp(T1), htmx, null)
                assertThat(r.response.status).`as`("$act htmx=$htmx").isEqualTo(403)
                assertThat(r.response.getHeader("HX-Redirect")).isNull()
            }
        }
        verify(tenants, never()).suspend(anyOrNull(), anyOrNull())
        verify(tenants, never()).activate(anyOrNull(), anyOrNull())
    }

    @Test
    @DisplayName("정지 성공: REST suspend(id, auth) 호출, 경고 토스트(slug · 모든 API 키 회수됨), 같은 탭으로 HX-Redirect")
    fun suspendSuccess() {
        whenever(tenants.suspend(eq(T1.toString()), anyOrNull())).thenReturn(ApiResponse.ok())
        val r = doPost("/admin/_/tenants/$T1/suspend", platform(), true, "http://localhost/admin/tenants/$T1?tab=audit")
        val res = r.response
        assertThat(res.status).isEqualTo(200)
        assertThat(res.getHeader("HX-Redirect")).isEqualTo("/admin/tenants/$T1?tab=audit")
        assertThat(res.getHeader("HX-Trigger")!!.lowercase(Locale.getDefault())).contains("\"kind\":\"warn\"").contains("\\ud14c\\ub10c\\ud2b8")
        verify(tenants).suspend(eq(T1.toString()), any<Authentication>())
        verify(tenants, never()).activate(anyOrNull(), anyOrNull())
    }

    @Test
    @DisplayName("정지 해제 성공: REST activate 호출, 성공 토스트 '정지 해제됨'(메시지 없음), 알 수 없는 탭은 개요로")
    fun activateSuccess() {
        whenever(tenants.activate(eq(T1.toString()), anyOrNull())).thenReturn(ApiResponse.ok())
        val r = doPost("/admin/_/tenants/$T1/activate", platform(), true, "http://localhost/admin/tenants/$T1?tab=hacked%22")
        assertThat(r.response.getHeader("HX-Redirect")).isEqualTo("/admin/tenants/$T1?tab=overview")
        val trigger = r.response.getHeader("HX-Trigger")
        assertThat(trigger).contains("\"kind\":\"success\"").contains("\"message\":\"\"")
        verify(tenants).activate(eq(T1.toString()), any<Authentication>())
        // JS 없는 폼 제출은 302
        val plain = doPost("/admin/_/tenants/$T1/activate", platform(), false, null)
        assertThat(plain.response.status).isEqualTo(302)
        assertThat(plain.response.redirectedUrl).isEqualTo("/admin/tenants/$T1?tab=overview")
    }

    @Test
    @DisplayName("정지 실패는 실제 오류 상태 + 오류 토스트(본문 없음, 리다이렉트 없음) — 다이얼로그가 열린 채 인라인 오류를 보인다")
    fun suspendFailure() {
        whenever(tenants.suspend(eq(T1.toString()), anyOrNull())).thenThrow(IllegalStateException("boom"))
        val r = doPost("/admin/_/tenants/$T1/suspend", platform(), true, null)
        assertThat(r.response.status).isEqualTo(500)
        assertThat(r.response.getHeader("HX-Redirect")).isNull()
        assertThat(r.response.getHeader("HX-Trigger")).contains("toast")
        assertThat(r.response.contentAsString).isEmpty()
    }

    // ── 탭 신호 배지 ────────────────────────────────────────

    @Test
    @DisplayName("탭 배지: AAGUID(mode≠ANY, mdsName 없는 항목 수)와 API 키(30일 안 만료 수) '확인 필요 N' + 사유 툴팁, 조건 없으면 없음")
    fun tabBadges() {
        whenever(aaguid.get(T1)).thenReturn(
            policy(
                TenantAaguidPolicy.Mode.ALLOWLIST,
                AaguidPolicyDto.Entry(UUID.randomUUID(), null, null), AaguidPolicyDto.Entry(UUID.randomUUID(), null, null),
                AaguidPolicyDto.Entry(UUID.randomUUID(), null, "YubiKey"), AaguidPolicyDto.Entry(null, null, null),
            ),
        )
        whenever(apiKeys.list(T1.toString())).thenReturn(ApiResponse.ok(listOf(key(NOW.atOffset(ZoneOffset.UTC).plusDays(10), null))))
        val d = page("/admin/tenants/$T1", platform())
        val aaguidTab = d.selectFirst("[role=tab][data-tab=aaguid]")!!
        assertThat(aaguidTab.select(".tabs__badge").text()).isEqualTo("확인 필요 2")
        assertThat(aaguidTab.select(".tabs__badge").attr("title")).isEqualTo("MDS 미확인 AAGUID 2건")
        val keyTab = d.selectFirst("[role=tab][data-tab=apikeys]")!!
        assertThat(keyTab.select(".tabs__badge").text()).isEqualTo("확인 필요 1")
        assertThat(keyTab.select(".tabs__badge").attr("title")).isEqualTo("30일 안에 만료되는 키 1개")
        assertThat(d.select("[role=tab][data-tab=overview] .tabs__badge")).isEmpty()

        // mode=ANY 면 미확인 항목이 있어도 0, 조회 실패는 조용히 무시
        whenever(aaguid.get(T1)).thenReturn(policy(TenantAaguidPolicy.Mode.ANY, AaguidPolicyDto.Entry(UUID.randomUUID(), null, null)))
        whenever(apiKeys.list(T1.toString())).thenThrow(IllegalStateException("403"))
        assertThat(page("/admin/tenants/$T1", platform()).select(".tabs__badge")).isEmpty()
        whenever(aaguid.get(T1)).thenThrow(IllegalStateException("403"))
        assertThat(page("/admin/tenants/$T1", platform()).select(".tabs__badge")).isEmpty()
    }

    // ── 감사 체인 카드 ──────────────────────────────────────

    @Test
    @DisplayName("PLATFORM 첫 화면의 체인 카드는 '확인 중' + hx-trigger=load(15초 제한, 조용히), 서버가 아직 검증하지 않는다")
    fun chainCardInitialChecking() {
        val d = page("/admin/tenants/$T1", platform())
        val card = d.selectFirst("#tenant-chain")!!
        assertThat(card.attr("hx-get")).isEqualTo("/admin/_/tenants/$T1/overview/chain")
        assertThat(card.attr("hx-trigger")).isEqualTo("load")
        assertThat(card.attr("hx-swap")).isEqualTo("outerHTML")
        assertThat(card.attr("hx-request")).contains("15000")
        assertThat(card.hasAttr("data-quiet")).isTrue()
        assertThat(card.text()).contains("확인 중").contains("체인 무결성 상태를 확인하고 있습니다.")
        assertThat(card.select("button[data-manual]").first()!!.hasAttr("disabled")).isTrue()
        verify(chain, never()).verifyTenant(anyOrNull())
        assertThat(card.select("h2").text()).isEqualTo("감사 체인")
        assertThat(card.select("a").first { a -> a.text().contains("자세히") }.attr("href"))
            .isEqualTo("/admin/tenants/$T1?tab=audit")
        assertThat(d.select("#tenant-tab .grid-3")).hasSize(1)
    }

    @Test
    @DisplayName("체인 카드 조각: 무결 → '무결 · 위변조 0건' + 마지막 검증, 호출 인자는 tenant id, 수동 검증·월간 보고서 버튼, 토스트 없음")
    fun chainCardIntact() {
        val r = getFragment("/admin/_/tenants/$T1/overview/chain", platform())
        assertThat(r.response.status).isEqualTo(200)
        assertThat(r.response.getHeader("HX-Trigger")).isNull()
        val d = doc(r)
        assertThat(d.select("aside.sidebar")).isEmpty()
        val card = d.selectFirst("#tenant-chain")!!
        assertThat(card.hasAttr("hx-get")).isFalse() // 결과 카드는 다시 load 하지 않는다
        assertThat(card.text()).contains("무결 · 위변조 0건").contains("마지막 검증 방금 전")
        val manual = card.selectFirst("button[data-manual]")!!
        assertThat(manual.attr("hx-get")).isEqualTo("/admin/_/tenants/$T1/overview/chain?manual=1")
        assertThat(manual.attr("hx-target")).isEqualTo("#tenant-chain")
        assertThat(manual.hasAttr("disabled")).isFalse()
        assertThat(manual.attr("x-bind:disabled")).contains("verifying")
        assertThat(card.select("a").first { a -> a.text() == "월간 보고서" }.attr("href")).isEqualTo("/admin/audit-chain")
        assertThat(card.text()).contains("검증 중…") // 수동 검증 중 라벨(x-show)
        verify(chain).verifyTenant(T1.toString())
    }

    @Test
    @DisplayName("위변조: bad 배너 '위변조 감지' + 마지막 검증 + 항목 id 끝 8자")
    fun chainCardTampered() {
        val entry = UUID.fromString("00000000-0000-0000-0000-0123456789ab")
        whenever(chain.verifyTenant(T1.toString())).thenReturn(AuditChainMonitorController.TenantVerifyResponse(T1, false, entry, NOW.minusSeconds(120)))
        val d = doc(getFragment("/admin/_/tenants/$T1/overview/chain", platform()))
        val alert = d.selectFirst("#tenant-chain [role=alert]")!!
        assertThat(alert.text()).contains("위변조 감지").contains("마지막 검증 2분 전").contains("위변조 항목 …456789ab")
        assertThat(d.text()).doesNotContain("무결 · 위변조 0건")
    }

    @Test
    @DisplayName("자동 검증 실패는 200 + '확인 불가'(원인) + 다시 시도·감사 로그로, 토스트 없음 — '확인 중'에 멈추지 않는다")
    fun chainAutoFailureIsUnavailable() {
        whenever(chain.verifyTenant(T1.toString())).thenThrow(IllegalStateException("verifier down"))
        val r = getFragment("/admin/_/tenants/$T1/overview/chain", platform())
        assertThat(r.response.status).isEqualTo(200)
        assertThat(r.response.getHeader("HX-Trigger")).isNull()
        val card = doc(r).selectFirst("#tenant-chain")!!
        assertThat(card.attr("data-view")).isEqualTo("unavailable")
        assertThat(card.text()).contains("확인 불가").contains("검증 결과를 받지 못했습니다").contains("다시 시도하거나 감사 로그에서 확인하세요.")
        val retry = card.selectFirst("button[data-quiet]")!!
        assertThat(retry.text()).isEqualTo("다시 시도")
        assertThat(retry.attr("hx-get")).isEqualTo("/admin/_/tenants/$T1/overview/chain")
        assertThat(card.select("a").any { a -> a.text() == "감사 로그로" && a.attr("href") == "/admin/tenants/$T1?tab=audit" }).isTrue()
        assertThat(card.text()).doesNotContain("verifier down")
    }

    @Test
    @DisplayName("수동 검증: 성공 토스트(무결/위변조), 실패는 오류 상태 + 오류 토스트(본문 없음) → 교체되지 않아 이전 결과 유지")
    fun chainManual() {
        val ok = getFragment("/admin/_/tenants/$T1/overview/chain?manual=1", platform())
        assertThat(ok.response.status).isEqualTo(200)
        assertThat(ok.response.getHeader("HX-Trigger")!!.lowercase(Locale.getDefault())).contains("\"kind\":\"success\"").contains("\\uc218\\ub3d9 \\uac80\\uc99d \\uc644\\ub8cc")
        assertThat(doc(ok).select("#tenant-chain")).hasSize(1)

        whenever(chain.verifyTenant(T1.toString())).thenReturn(AuditChainMonitorController.TenantVerifyResponse(T1, false, UUID.randomUUID(), NOW))
        val bad = getFragment("/admin/_/tenants/$T1/overview/chain?manual=1", platform())
        assertThat(bad.response.getHeader("HX-Trigger")).contains("\"kind\":\"warn\"")

        whenever(chain.verifyTenant(T1.toString())).thenThrow(IllegalStateException("boom"))
        val fail = getFragment("/admin/_/tenants/$T1/overview/chain?manual=1", platform())
        assertThat(fail.response.status).isEqualTo(500)
        assertThat(fail.response.contentAsString).isEmpty()
        assertThat(fail.response.getHeader("HX-Trigger")!!.lowercase(Locale.getDefault())).contains("\"kind\":\"error\"").contains("\\uc218\\ub3d9 \\uac80\\uc99d \\uc2e4\\ud328")
    }

    @Test
    @DisplayName("체인 카드의 클라이언트 상태는 템플릿 상수 x-data + data-* 로만 서버 값을 받는다(JS 평가 속성에 서버 값 없음)")
    fun chainCardAlpineIsConstant() {
        val r = getFragment("/admin/_/tenants/$T1/overview/chain", platform())
        val card = doc(r).selectFirst("#tenant-chain")!!
        assertThat(card.attr("x-data")).isEqualTo("{ view: \$el.dataset.view, reason: \$el.dataset.reason || '', verifying: false }")
        assertThat(card.attr("x-on:htmx:timeout")).contains("\$el.dataset.timeoutText").contains("data-manual")
        assertThat(card.attr("data-timeout-text")).isEqualTo("15초 안에 응답이 없었습니다.")
    }

    @Test
    @DisplayName("체인 검증은 PLATFORM 전용: RP_ADMIN 은 403 이고 verifyTenant 를 부르지 않는다")
    fun chainRequiresPlatform() {
        for (q in listOf("", "?manual=1")) {
            val r = getFragment("/admin/_/tenants/$T1/overview/chain$q", rp(T1))
            assertThat(r.response.status).isEqualTo(403)
        }
        verifyNoInteractions(chain)
    }

    // ── 오류 ───────────────────────────────────────────────

    @Test
    @DisplayName("404: '찾을 수 없습니다' + Tenants로 돌아가기(다시 시도 없음). 직접 접근은 404, boost 이동은 보이도록 200")
    fun notFound() {
        whenever(tenants.get("nope")).thenThrow(BusinessException(ErrorCode.TENANT_NOT_FOUND))
        val direct = getPage("/admin/tenants/nope", platform())
        assertThat(direct.response.status).isEqualTo(404)
        val d = doc(direct)
        assertThat(d.select("[role=alert] .empty__title").text()).isEqualTo("Tenant를 찾을 수 없습니다")
        assertThat(d.select(".empty__sub").text()).isEqualTo("삭제됐거나 주소가 잘못된 tenant입니다.")
        assertThat(d.select("a.btn--primary").any { a -> a.text() == "Tenants로 돌아가기" && a.attr("href") == "/admin/tenants" }).isTrue()
        assertThat(d.select("main").text()).doesNotContain("다시 시도")
        assertThat(d.select("[role=tab]")).isEmpty()

        val boosted = getFragment("/admin/tenants/nope", platform())
        assertThat(boosted.response.status).isEqualTo(200)
        assertThat(doc(boosted).select(".empty__title").text()).isEqualTo("Tenant를 찾을 수 없습니다")
    }

    @Test
    @DisplayName("알 수 없는 서버 예외의 원문(SQL 등)은 어떤 화면에도 싣지 않는다(퍼널 카드·상세 LoadError·체인 카드)")
    fun unknownExceptionMessageNeverRendered() {
        whenever(funnel.get(eq(T1), anyInt())).thenThrow(IllegalStateException("SELECT secret FROM x"))
        val r = getPage("/admin/tenants/$T1", platform())
        assertThat(r.response.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("secret")
        assertThat(doc(r).select("#tenant-tab [role=alert]").text()).contains("서버가 잠시 응답하지 않습니다").contains("HTTP 500")

        whenever(tenants.get(T1.toString())).thenThrow(IllegalStateException("SELECT secret FROM y"))
        assertThat(getPage("/admin/tenants/$T1", platform()).response.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("secret")
        doReturn(ApiResponse.ok(view("ACTIVE"))).whenever(tenants).get(T1.toString())

        whenever(chain.verifyTenant(T1.toString())).thenThrow(IllegalStateException("SELECT secret FROM z"))
        assertThat(getFragment("/admin/_/tenants/$T1/overview/chain", platform()).response.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("secret")
    }

    @Test
    @DisplayName("탭 영역을 바꾸는 모든 요청은 같은 hx-sync 그룹(#tenant-tab:replace): 탭 링크·퍼널 다시 시도·load 컨테이너, 탭 링크는 data-guard-dirty")
    fun everyTabRequesterSharesSyncGroup() {
        val d = page("/admin/tenants/$T1", platform())
        for (t in d.select("[role=tab][data-tab]")) {
            assertThat(t.attr("hx-sync")).isEqualTo("#tenant-tab:replace")
            assertThat(t.hasAttr("data-guard-dirty")).isTrue()
        }
        whenever(funnel.get(eq(T1), anyInt())).thenThrow(IllegalStateException("x"))
        val retry = page("/admin/tenants/$T1", platform()).selectFirst("#tenant-tab [role=alert] button[hx-get]")!!
        assertThat(retry.attr("hx-sync")).isEqualTo("#tenant-tab:replace")
        assertThat(retry.attr("hx-target")).isEqualTo("#tenant-tab")
        assertThat(page("/admin/tenants/$T1?tab=audit", platform()).selectFirst("#tenant-tab")!!.attr("hx-sync")).isEqualTo("#tenant-tab:replace")
    }

    @Test
    @DisplayName("탭 컨테이너(인라인·lazy 모두)는 hx-sync·hx-swap·hx-target 상속을 막아 탭 안 요청이 서로·탭 전환과 취소하지 않는다")
    fun tabContainerDisinheritsRequestAttrs() {
        for (url in listOf("/admin/tenants/$T1", "/admin/tenants/$T1?tab=audit")) {
            val box = page(url, platform()).selectFirst("#tenant-tab")!!
            assertThat(box.attr("hx-disinherit")).isEqualTo("hx-sync hx-swap hx-target")
        }
    }

    @Test
    @DisplayName("JDK 가 던진 IllegalArgumentException 의 원문은 400 이어도 토스트·화면에 싣지 않는다")
    fun illegalArgumentMessageNotSurfaced() {
        whenever(tenants.suspend(eq(T1.toString()), anyOrNull())).thenThrow(IllegalArgumentException("Invalid UUID string: secret"))
        val res = doPost("/admin/_/tenants/$T1/suspend", platform(), true, null).response
        assertThat(res.status).isEqualTo(400)
        assertThat(res.getHeader("HX-Trigger")).contains("HTTP 400").doesNotContain("secret")
        val full = mvc.perform(post(URI.create("/admin/_/tenants/$T1/suspend")).with(authentication(platform())).with(csrf())).andReturn().response
        assertThat(full.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("secret")
    }

    @Test
    @DisplayName("조회 실패 화면의 다시 시도 주소는 idOrSlug 를 경로 조각으로 인코딩한다")
    fun retryUrlEncodesPathSegment() {
        whenever(tenants.get("a b?c")).thenThrow(IllegalStateException("x"))
        val retry = doc(getPage(URI.create("/admin/tenants/a%20b%3Fc").toString(), platform())).selectFirst("main button[hx-get]")!!
        assertThat(retry.attr("hx-get")).isEqualTo("/admin/tenants/a%20b%3Fc")
    }

    @Test
    @DisplayName("확인 요청(X-Confirm)의 실패는 토스트 없이 HX-Trigger confirmError 로만, 일반 htmx 요청은 토스트. 확인 버튼은 X-Confirm 헤더를 싣는다")
    fun confirmFailureIsInlineOnly() {
        whenever(tenants.suspend(eq(T1.toString()), anyOrNull())).thenThrow(IllegalStateException("SELECT secret"))
        val b = post(URI.create("/admin/_/tenants/$T1/suspend")).with(authentication(platform())).with(csrf())
            .header("HX-Request", "true").header("X-Confirm", "1")
        val res = mvc.perform(b).andReturn().response
        assertThat(res.status).isEqualTo(500)
        val h = res.getHeader("HX-Trigger")
        assertThat(h).contains("confirmError").doesNotContain("\"toast\"").doesNotContain("secret")
        assertThat(res.contentAsString).isEmpty()
        val plain = doPost("/admin/_/tenants/$T1/suspend", platform(), true, null)
        assertThat(plain.response.getHeader("HX-Trigger")).contains("\"toast\"").doesNotContain("confirmError")

        val confirm = page("/admin/tenants/$T1", platform()).selectFirst("main template button[data-confirm]")!!
        assertThat(confirm.attr("hx-headers").replace(" ", "")).isEqualTo("{\"X-Confirm\":\"1\"}")
        assertThat(confirm.hasAttr("data-quiet")).isTrue() // 전송 실패는 전역 토스트 없이 대화상자 안에
    }

    @Test
    @DisplayName("5xx: LoadError '다시 시도' + '‹ Tenant 목록으로', 인라인 하나(토스트 없음), 상세는 그리지 않는다")
    fun loadFailure() {
        whenever(tenants.get(T1.toString())).thenThrow(IllegalStateException("db down"))
        val r = getPage("/admin/tenants/$T1?tab=audit", platform())
        assertThat(r.response.status).isEqualTo(200)
        assertThat(r.response.getHeader("HX-Trigger")).isNull()
        val d = doc(r)
        assertThat(d.select("main [role=alert]")).hasSize(1)
        assertThat(d.select("main [role=alert]").text()).contains("Tenant 정보를 불러오지 못했습니다")
        val retry = d.selectFirst("main button[hx-get]")!!
        assertThat(retry.attr("hx-get")).isEqualTo("/admin/tenants/$T1?tab=audit")
        assertThat(d.select("a.btn--ghost").any { a -> a.text() == "‹ Tenant 목록으로" && a.attr("href") == "/admin/tenants" }).isTrue()
        assertThat(d.select("main [role=tab], main h1")).isEmpty()
    }

    @Test
    @DisplayName("slug 로 열어도 화면의 링크·조각 주소는 canonical id 를 쓴다")
    fun slugUsesCanonicalId() {
        whenever(tenants.get("acme")).thenReturn(ApiResponse.ok(view("ACTIVE")))
        val d = page("/admin/tenants/acme?tab=webauthn", platform())
        assertThat(d.selectFirst("#tenant-tab")!!.attr("hx-get")).isEqualTo("/admin/_/tenants/$T1/tab/webauthn")
        assertThat(d.selectFirst("[role=tab][data-tab=audit]")!!.attr("href")).isEqualTo("/admin/tenants/$T1?tab=audit")
    }

    @Test
    @DisplayName("미인증 요청은 로그인으로(조각·전체 모두) — tenant 를 읽지 않는다")
    fun unauthenticated() {
        val r = mvc.perform(get(URI.create("/admin/tenants/$T1"))).andReturn()
        assertThat(r.response.status).isIn(302, 401)
        val f = mvc.perform(get(URI.create("/admin/_/tenants/$T1/tab/overview")).header("HX-Request", "true")).andReturn()
        assertThat(f.response.status).isIn(302, 401)
        verifyNoInteractions(tenants)
    }

    // ── 순수 규칙 ───────────────────────────────────────────

    @Test
    @DisplayName("이벤트 점 색(eventColor): 실패·폐기·위변조는 bad, API_KEY*·*_UPDATED 는 warn, 그 밖은 brand")
    fun eventColor() {
        assertThat(TenantDetailPageController.eventColor("AUTH_FAIL")).isEqualTo("var(--bad)")
        assertThat(TenantDetailPageController.eventColor("API_KEY_REVOKED")).isEqualTo("var(--bad)")
        assertThat(TenantDetailPageController.eventColor("CHAIN_TAMPER_DETECTED")).isEqualTo("var(--bad)")
        assertThat(TenantDetailPageController.eventColor("API_KEY_ISSUED")).isEqualTo("var(--warn-dot)")
        assertThat(TenantDetailPageController.eventColor("TENANT_UPDATED")).isEqualTo("var(--warn-dot)")
        assertThat(TenantDetailPageController.eventColor("CREDENTIAL_REGISTERED")).isEqualTo("var(--brand-700)")
    }

    @Test
    @DisplayName("tabFrom: 알려진 탭만 되돌리고, 모르는 값·깨진 주소는 개요")
    fun tabFrom() {
        assertThat(TenantDetailPageController.tabFrom("http://h/admin/tenants/x?tab=audit")).isEqualTo("audit")
        assertThat(TenantDetailPageController.tabFrom("http://h/admin/tenants/x?tab=bogus")).isEqualTo("overview")
        assertThat(TenantDetailPageController.tabFrom("http://h/admin/tenants/x")).isEqualTo("overview")
        assertThat(TenantDetailPageController.tabFrom(null)).isEqualTo("overview")
        assertThat(TenantDetailPageController.tabFrom("::::")).isEqualTo("overview")
    }
}
