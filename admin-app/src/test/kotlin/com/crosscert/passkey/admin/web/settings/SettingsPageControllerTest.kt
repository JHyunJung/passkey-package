package com.crosscert.passkey.admin.web.settings

import com.crosscert.passkey.admin.audit.AuditChainMonitorController
import com.crosscert.passkey.admin.auth.ActiveTenantController
import com.crosscert.passkey.admin.auth.AdminUserDetails
import com.crosscert.passkey.admin.auth.MfaController
import com.crosscert.passkey.admin.license.LicenseController
import com.crosscert.passkey.admin.license.LicenseView
import com.crosscert.passkey.admin.operator.SignupRequestController
import com.crosscert.passkey.admin.operator.SignupRequestDto
import com.crosscert.passkey.admin.system.SystemInfoController
import com.crosscert.passkey.admin.system.SystemInfoView
import com.crosscert.passkey.admin.web.AdminWebSliceTest
import com.crosscert.passkey.core.api.ApiResponse
import org.assertj.core.api.Assertions.assertThat
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.select.Elements
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * 설정 화면 틀. 원본: SettingsPage.test.tsx(resolveTab, 역할별 탭, 가입 요청 배지, ?tab= 진입).
 * 탭 클릭 뒤 활성 표시는 ui.js(htmx:afterRequest) 몫이라 속성까지만 본다.
 *
 * (Kotlin 전환) 즉시 역참조하는 nullable jsoup 결과(`selectFirst`)의 `!!` 는 원 Java 의 NPE 실패 지점과 같다.
 */
@AdminWebSliceTest(controllers = [SettingsPageController::class, AccountTabController::class, SystemInfoTabController::class])
class SettingsPageControllerTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var clock: Clock
    @MockitoBean lateinit var mfa: MfaController
    @MockitoBean lateinit var signups: SignupRequestController
    @MockitoBean lateinit var systemInfo: SystemInfoController
    @MockitoBean lateinit var license: LicenseController
    @MockitoBean lateinit var chain: AuditChainMonitorController
    @MockitoBean lateinit var activeTenants: ActiveTenantController

    companion object {
        val NOW: Instant = Instant.parse("2026-10-11T00:00:00Z")
        val T1: UUID = UUID.fromString("00000000-0000-0000-0000-000000000123")

        fun platform(): Authentication = auth("PLATFORM_OPERATOR", setOf())
        fun rp(): Authentication = auth("RP_ADMIN", setOf(T1))

        private fun auth(role: String, tenants: Set<UUID>): Authentication {
            val p = AdminUserDetails(UUID.randomUUID(), "a@x.com", "x", role, tenants, true, null, Clock.systemUTC())
            return UsernamePasswordAuthenticationToken(p, "x", p.authorities)
        }

        private fun tabs(d: Document): Elements = d.select("main [role=tablist] [role=tab]")
    }

    @BeforeEach
    fun setUp() {
        whenever(clock.instant()).thenReturn(NOW)
        whenever(clock.zone).thenReturn(ZoneOffset.UTC)
        whenever(license.get()).thenReturn(ApiResponse.ok(LicenseView("saas", null, null, null, null, 0, listOf(), null, null, null)))
        whenever(signups.list()).thenReturn(listOf(mock<SignupRequestDto.View>(), mock<SignupRequestDto.View>()))
        whenever(systemInfo.get()).thenReturn(
            ApiResponse.ok(
                SystemInfoView(
                    "1.2.3", null, 4L, SystemInfoView.Host("prod"),
                    listOf(SystemInfoView.Component("admin-app", "1.2.3", "OK", "Spring Boot")),
                ),
            ),
        )
    }

    private fun page(url: String, a: Authentication): Document {
        val r = mvc.perform(get(url).with(authentication(a))).andReturn()
        assertThat(r.response.status).isEqualTo(200)
        return Jsoup.parse(r.response.getContentAsString(StandardCharsets.UTF_8))
    }

    private fun frag(url: String, a: Authentication): MvcResult = frag(get(url), a)

    private fun frag(b: MockHttpServletRequestBuilder, a: Authentication): MvcResult =
        mvc.perform(b.with(authentication(a)).header("HX-Request", "true")).andReturn()

    @Test
    @DisplayName("settingsTabResolveMatchesOriginal: PLATFORM 전용 탭은 PLATFORM 일 때만, 그 외(RP·모르는 값·없음)는 account")
    fun settingsTabResolveMatchesOriginal() {
        for (t in listOf("admins", "mds", "system", "security")) {
            assertThat(SettingsPageController.resolveTab(t, true)).isEqualTo(t)
            assertThat(SettingsPageController.resolveTab(t, false)).isEqualTo("account")
        }
        assertThat(SettingsPageController.resolveTab(null, true)).isEqualTo("account")
        assertThat(SettingsPageController.resolveTab("", true)).isEqualTo("account")
        assertThat(SettingsPageController.resolveTab("account", true)).isEqualTo("account")
        assertThat(SettingsPageController.resolveTab("nope", true)).isEqualTo("account")
        assertThat(SettingsPageController.resolveTab("System", true)).isEqualTo("account") // 대소문자 구분(includes)
        assertThat(SettingsPageController.resolveTab("account", false)).isEqualTo("account")
    }

    @Test
    @DisplayName("PLATFORM: 제목·설명, '설정 탭' 탭 줄 5개(순서), 내 계정 선택 + 인라인, 브레드크럼 '설정'")
    fun platformSeesAllTabs() {
        val d = page("/admin/settings", platform())
        val pageRoot = d.selectFirst("main > .page")
        assertThat(pageRoot).isNotNull()
        assertThat(d.selectFirst("main h1")!!.text()).isEqualTo("설정")
        assertThat(d.selectFirst("main .page__sub")!!.text()).isEqualTo("콘솔 운영자, MDS 신뢰 anchor, 시스템 상태.")
        assertThat(d.selectFirst("main [role=tablist]")!!.attr("aria-label")).isEqualTo("설정 탭")
        assertThat(tabs(d).stream().map { t -> t.ownText() }).containsExactly("내 계정", "Admin 사용자", "MDS Status", "시스템", "보안 정책")
        assertThat(tabs(d)[0].attr("aria-selected")).isEqualTo("true")
        assertThat(d.selectFirst("#settings-tab #mfa-account")).isNotNull()
        assertThat(d.selectFirst(".crumbs__current")!!.text()).isEqualTo("설정")
    }

    @Test
    @DisplayName("rpAdminSeesOnlyAccountTab: RP 는 내 계정 탭만, 가입 요청 조회 안 함, ?tab=system 도 내 계정")
    fun rpAdminSeesOnlyAccountTab() {
        val d = page("/admin/settings", rp())
        assertThat(tabs(d).stream().map { t -> t.ownText() }).containsExactly("내 계정")
        assertThat(d.selectFirst("main")!!.text()).doesNotContain("Admin 사용자").doesNotContain("MDS Status").doesNotContain("보안 정책")
        verify(signups, never()).list()

        val sys = page("/admin/settings?tab=system", rp())
        assertThat(sys.selectFirst("#settings-tab #mfa-account")).isNotNull()
        assertThat(sys.selectFirst("#settings-tab")!!.hasAttr("hx-get")).isFalse()
        verify(systemInfo, never()).get()
    }

    @Test
    @DisplayName("Admin 사용자 탭에 대기 중 가입 요청 건수 배지(tabs__count), 조회 실패면 숨김")
    fun pendingBadge() {
        val admins = tabs(page("/admin/settings", platform()))[1]
        assertThat(admins.selectFirst(".tabs__count")!!.text()).isEqualTo("2")

        whenever(signups.list()).thenThrow(IllegalStateException("db"))
        val again = tabs(page("/admin/settings", platform()))[1]
        assertThat(again.selectFirst(".tabs__count")).isNull()
    }

    @Test
    @DisplayName("?tab=security(PLATFORM): 그 탭이 선택되고 탭 영역이 load 로 조각을 부른다(내 계정 내용 없음)")
    fun platformTabQueryLoadsLazily() {
        val d = page("/admin/settings?tab=security", platform())
        assertThat(tabs(d)[4].attr("aria-selected")).isEqualTo("true")
        val box = d.selectFirst("#settings-tab")!!
        assertThat(box.attr("hx-get")).isEqualTo("/admin/_/settings/tab/security")
        assertThat(box.attr("hx-trigger")).isEqualTo("load")
        assertThat(box.attr("hx-sync")).isEqualTo("#settings-tab:replace")
        assertThat(box.selectFirst("[role=status]")).isNotNull()
        assertThat(d.selectFirst("#mfa-account")).isNull()
    }

    @Test
    @DisplayName("탭 링크: 화면 주소 href·push-url(내 계정은 ?tab 없이), 조각 hx-get, 같은 sync 그룹, 이동 가드, 브레드크럼은 그대로(data-label 없음)")
    fun tabLinksAreFragmentRequests() {
        val d = page("/admin/settings", platform())
        val account = tabs(d)[0]
        assertThat(account.attr("href")).isEqualTo("/admin/settings")
        assertThat(account.attr("hx-push-url")).isEqualTo("/admin/settings")
        val sys = tabs(d)[3]
        assertThat(sys.attr("href")).isEqualTo("/admin/settings?tab=system")
        assertThat(sys.attr("hx-get")).isEqualTo("/admin/_/settings/tab/system")
        assertThat(sys.attr("hx-target")).isEqualTo("#settings-tab")
        assertThat(sys.attr("hx-push-url")).isEqualTo("/admin/settings?tab=system")
        assertThat(sys.attr("hx-sync")).isEqualTo("#settings-tab:replace")
        assertThat(sys.hasAttr("data-guard-dirty")).isTrue()
        assertThat(sys.attr("data-tab")).isEqualTo("system")
        assertThat(sys.hasAttr("data-label")).isFalse()
        assertThat(d.selectFirst("#settings-tab")!!.attr("hx-disinherit")).isEqualTo("hx-sync hx-swap hx-target")
    }

    @Test
    @DisplayName("일반 탭 조각: 레이아웃 없음, RP·모르는 탭 → 내 계정, 리터럴 매핑이 없는 PLATFORM 탭 → 404")
    fun genericTabFragment() {
        val rpAdmins = frag("/admin/_/settings/tab/admins", rp())
        assertThat(rpAdmins.response.status).isEqualTo(200)
        val d = Jsoup.parse(rpAdmins.response.getContentAsString(StandardCharsets.UTF_8))
        assertThat(d.selectFirst("#mfa-account")).isNotNull()
        assertThat(d.select("aside, [role=tablist]")).isEmpty()

        assertThat(frag("/admin/_/settings/tab/whatever", platform()).response.status).isEqualTo(200)
        assertThat(frag("/admin/_/settings/tab/admins", platform()).response.status).isEqualTo(404)
        verify(signups, never()).list()
    }

    @Test
    @DisplayName("리터럴 탭 매핑(…/tab/system)이 일반 매핑보다 우선, RP 가 직접 부르면 403")
    fun literalTabMappingWinsOverGeneric() {
        val r = frag("/admin/_/settings/tab/system", platform())
        assertThat(r.response.status).isEqualTo(200)
        assertThat(r.response.getContentAsString(StandardCharsets.UTF_8)).contains("백엔드 컴포넌트")
        verify(systemInfo).get()

        assertThat(frag("/admin/_/settings/tab/system", rp()).response.status).isEqualTo(403)
    }
}
