package com.crosscert.passkey.admin.web.shell

import com.crosscert.passkey.admin.audit.AuditChainMonitorController
import com.crosscert.passkey.admin.audit.AuditChainOverview
import com.crosscert.passkey.admin.auth.ActiveTenantController
import com.crosscert.passkey.admin.auth.AdminUserDetails
import com.crosscert.passkey.admin.license.LicenseController
import com.crosscert.passkey.admin.license.LicenseView
import com.crosscert.passkey.admin.web.AdminWebSliceTest
import com.crosscert.passkey.core.api.ApiResponse
import org.assertj.core.api.Assertions.assertThat
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.function.Function
import java.util.regex.Pattern
import java.util.stream.Collectors

/**
 * 공통 틀(레이아웃·사이드바·상단 바·브레드크럼·라이선스 배너·체인 카드·RP 전환) 렌더와 조각 엔드포인트.
 * 원본: shell/Sidebar.test.tsx, Header.test.tsx, useCrumbTenant.test.tsx, components/LicenseBanner.test.tsx.
 * 브라우저에서 도는 Alpine·htmx 동작(메뉴 열기, 폴링 타이머, 포커스)은 속성만 본다.
 *
 * (Kotlin 전환) 즉시 역참조하는 nullable jsoup 결과(`selectFirst`·`first`·`parent`)의 `!!` 는 원 Java 의 NPE 실패 지점과 같다.
 */
@AdminWebSliceTest(controllers = [ShellController::class, ShellControllerTest.DummyPage::class])
@Import(ShellControllerTest.DummyPage::class)
class ShellControllerTest {

    /** 레이아웃을 쓰는 더미 화면(규약 확인용). 템플릿은 src/test/resources/templates/admin/shell-test-page.html. */
    @Controller
    class DummyPage {
        @GetMapping("/admin/tenants", "/admin/activity", "/admin/audit-chain", "/admin/settings", "/admin/license")
        fun page(model: Model): String {
            model.addAttribute("breadcrumbs", listOf(Crumb.link("Tenants", "/admin/tenants"), Crumb.of("Acme")))
            return "admin/shell-test-page"
        }

        @GetMapping("/admin/tenants/{id}")
        fun tenant(@PathVariable id: String, model: Model): String {
            model.addAttribute("breadcrumbs", Crumbs.tenant(true, id, "Demo RP", "webauthn"))
            return "admin/shell-test-page"
        }

        @GetMapping("/admin/settings/crumbless")
        fun crumbless(): String = "admin/shell-test-page-nocrumbs"
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var clock: Clock
    @MockitoBean lateinit var license: LicenseController
    @MockitoBean lateinit var chain: AuditChainMonitorController
    @MockitoBean lateinit var activeTenants: ActiveTenantController

    companion object {
        val T1: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
        val T2: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000b2")

        fun platform(): Authentication = auth("PLATFORM_OPERATOR", "alice@example.com", setOf())

        fun rp(): Authentication = auth("RP_ADMIN", "bob@example.com", java.util.Set.of(T1, T2))

        private fun auth(role: String, email: String, tenants: Set<UUID>): Authentication {
            val p = AdminUserDetails(UUID.randomUUID(), email, "x", role, tenants, true, null, Clock.systemUTC())
            return UsernamePasswordAuthenticationToken(p, "x", p.authorities)
        }

        fun view(mode: String, state: String?, days: Long, graceHours: Long?): LicenseView =
            LicenseView(mode, state, "acme", "lic_1", Instant.parse("2026-11-10T00:00:00Z"), days, listOf(), null, graceHours, null)

        private fun navHrefs(d: Document): List<String> =
            d.select("aside.sidebar a.nav-item").stream().map { e -> e.attr("href") }.collect(Collectors.toList())

        private val LABEL = Function { c: Crumb -> c.label }
        private val HREF = Function { c: Crumb -> c.href }
    }

    @BeforeEach
    fun stubClock() {
        whenever(clock.instant()).thenReturn(Instant.parse("2026-10-11T00:00:00Z"))
        whenever(clock.zone).thenReturn(ZoneOffset.UTC)
        whenever(license.get()).thenReturn(ApiResponse.ok(view("onprem", "VALID", 30, null)))
    }

    private fun page(path: String, a: Authentication): Document {
        val r = mvc.perform(get(path).with(authentication(a))).andReturn()
        assertThat(r.response.status).`as`(path).isEqualTo(200)
        return Jsoup.parse(r.response.contentAsString)
    }

    // ── 사이드바 (Sidebar.test.tsx) ─────────────────────────

    @Test
    @DisplayName("PLATFORM: 플랫폼/관리 그룹, tenants·activity·audit-chain·license·settings 링크, 활성 항목 aria-current")
    fun platformSidebar() {
        val d = page("/admin/tenants", platform())
        assertThat(d.select("aside.sidebar").attr("aria-label")).isEqualTo("주 메뉴")
        assertThat(d.select(".sidebar__label").eachText()).containsExactly("플랫폼", "관리")
        assertThat(navHrefs(d)).containsExactly("/admin/tenants", "/admin/insights", "/admin/activity", "/admin/audit-chain", "/admin/license", "/admin/settings")
        assertThat(d.select("a.nav-item").eachText()).anyMatch { t -> t.contains("Tenants") }.anyMatch { t -> t.contains("활동") }
            .anyMatch { t -> t.contains("감사 체인") }.anyMatch { t -> t.contains("라이선스") && t.contains("온프렘") }.anyMatch { t -> t.contains("설정") }
        val current = d.select("a.nav-item[aria-current=page]")
        assertThat(current).hasSize(1)
        assertThat(current.attr("href")).isEqualTo("/admin/tenants")
        assertThat(current.hasClass("nav-item--active")).isTrue()
        // 환경 배지는 하드코딩하지 않는다.
        assertThat(d.select("aside.sidebar").text()).doesNotContain("prod")
        assertThat(d.select(".sidebar__env").text()).isEqualTo("Passkey Admin")
        assertThat(d.select(".sidebar__copy").text()).isEqualTo("© Crosscert")
    }

    @Test
    @DisplayName("라이선스 메뉴는 온프렘에서만(TSX mode === 'onprem')")
    fun licenseLinkOnlyOnPrem() {
        whenever(license.get()).thenReturn(ApiResponse.ok(view("saas", null, 0, null)))
        assertThat(navHrefs(page("/admin/tenants", platform()))).containsExactly("/admin/tenants", "/admin/insights", "/admin/activity", "/admin/audit-chain", "/admin/settings")
        whenever(license.get()).thenThrow(IllegalStateException("boom"))
        assertThat(navHrefs(page("/admin/tenants", platform()))).`as`("조회 실패는 saas 로 본다").doesNotContain("/admin/license")
    }

    @Test
    @DisplayName("RP_ADMIN: 설정만, 플랫폼 그룹·체인 카드·체인 조회 없음")
    fun rpAdminSidebar() {
        val d = page("/admin/settings", rp())
        assertThat(navHrefs(d)).containsExactly("/admin/settings")
        assertThat(d.select(".sidebar__label").eachText()).containsExactly("관리")
        assertThat(d.select("#chain-card")).isEmpty()
        assertThat(d.text()).doesNotContain("AUDIT CHAIN").doesNotContain("검증 중")
        verify(chain, never()).overview(org.mockito.ArgumentMatchers.anyInt())
        verify(license, never()).get() // 라이선스 메뉴는 PLATFORM 에게만 → 배너 조각이 불릴 때까지 조회하지 않는다
        assertThat(d.select("a.nav-item[aria-current=page]").attr("href")).isEqualTo("/admin/settings")
    }

    @Test
    @DisplayName("현재 경로 강조: 테넌트 상세는 Tenants, 각 화면은 자기 항목")
    fun activeNavFollowsPath() {
        assertThat(page("/admin/tenants/abc", platform()).select("a.nav-item[aria-current=page]").attr("href")).isEqualTo("/admin/tenants")
        assertThat(page("/admin/audit-chain", platform()).select("a.nav-item[aria-current=page]").attr("href")).isEqualTo("/admin/audit-chain")
        assertThat(page("/admin/activity", platform()).select("a.nav-item[aria-current=page]").attr("href")).isEqualTo("/admin/activity")
        assertThat(page("/admin/license", platform()).select("a.nav-item[aria-current=page]").attr("href")).isEqualTo("/admin/license")
    }

    @Test
    @DisplayName("PLATFORM 체인 카드 자리: 30초 폴링·120초 시간 제한·'검증 중…' 초기 상태, 다시 시도")
    fun chainCardHost() {
        val host = page("/admin/tenants", platform()).selectFirst("#chain-card")
        assertThat(host).isNotNull()
        host!!
        assertThat(host.attr("hx-get")).isEqualTo("/admin/_/shell/chain-card")
        assertThat(host.attr("hx-trigger")).isEqualTo("load, every 30s")
        assertThat(host.attr("hx-request")).contains("120000")
        assertThat(host.attr("x-data")).isEqualTo("chainCard")
        assertThat(host.text()).contains("검증 중…").contains("0초째 확인 중").contains("검증 상태 확인 불가").contains("다시 시도")
        assertThat(host.select("[x-text=\"secs + '초째 확인 중'\"]")).hasSize(1)
        assertThat(host.select("[role=status]").text()).isEqualTo("검증 상태 확인 불가")
        assertThat(host.select("#nav-chain-count")).isEmpty()
    }

    @Test
    @DisplayName("체인 카드 자리: hx-preserve(+id), 카드 안 링크가 target/swap/sync 를 상속하지 않음, 폴링·다시 시도가 같은 hx-sync(replace)")
    fun chainCardHostPreserveDisinheritSync() {
        val host = page("/admin/tenants", platform()).selectFirst("#chain-card")!!
        assertThat(host.hasAttr("hx-preserve")).isTrue()
        assertThat(Pattern.compile("\\s+").split(host.attr("hx-disinherit"))).contains("hx-target", "hx-swap", "hx-sync")
        assertThat(host.attr("hx-sync")).`as`("폴링은 진행 중 조회를 대체하지 않음").isEqualTo("#chain-card:drop")
        val retry = host.selectFirst("button[hx-get]")!!
        assertThat(retry.attr("hx-sync")).`as`("다시 시도만 대체").isEqualTo("#chain-card:replace")
        assertThat(retry.attr("hx-target")).isEqualTo("#chain-card-body")
        assertThat(retry.attr("hx-swap")).isEqualTo("innerHTML")
        assertThat(retry.hasAttr("data-quiet")).isTrue()
        assertThat(host.attr("hx-target")).isEqualTo("#chain-card-body")
        // 배지는 Alpine store 로 그린다(OOB 아님)
        val badge = page("/admin/tenants", platform()).selectFirst("a.nav-item[href=/admin/audit-chain] .nav-item__count")!!
        assertThat(badge.attr("x-show")).contains("\$store.chain.tampered")
    }

    @Test
    @DisplayName("셸 조각 유지: 라이선스 배너 자리·RP 전환 자리에 hx-preserve + id")
    fun shellPartsPreserved() {
        assertThat(page("/admin/tenants", platform()).selectFirst("#license-banner")!!.hasAttr("hx-preserve")).isTrue()
        val sw = page("/admin/settings", rp()).selectFirst("#tenant-switcher")!!
        assertThat(sw.hasAttr("hx-preserve")).isTrue()
        assertThat(sw.attr("hx-swap")).`as`("자리(id·preserve)는 남기고 안쪽만 교체").isEqualTo("innerHTML")
    }

    @Test
    @DisplayName("조각 요청은 메뉴 강조·라이선스 조회를 하지 않는다")
    fun fragmentsSkipShellAdvice() {
        whenever(chain.overview(24)).thenReturn(overview(0, 1, Instant.parse("2026-10-11T00:00:00Z")))
        clearInvocations(license)
        mvc.perform(get("/admin/_/shell/chain-card").with(authentication(platform()))).andReturn()
        verify(license, never()).get()
    }

    // ── 레이아웃 규약 ───────────────────────────────────────

    @Test
    @DisplayName("<body> hx-boost=true + hx-headers 에 X-XSRF-TOKEN")
    fun bodyBoostAndCsrf() {
        val body = page("/admin/tenants", platform()).body()
        assertThat(body.attr("hx-boost")).isEqualTo("true")
        assertThat(body.attr("hx-headers")).matches("\\{\"X-XSRF-TOKEN\": \"[^\"]+\"}")
    }

    @Test
    @DisplayName("head: 제목·스타일 4개·스크립트 순서 htmx → alpine-focus → ui.js → app.js → alpine(defer)")
    fun headOrder() {
        val d = page("/admin/tenants", platform())
        assertThat(d.title()).isEqualTo("테스트 페이지")
        assertThat(d.select("head link[rel=stylesheet]").eachAttr("href")).containsExactly(
            "/admin/css/fonts.css", "/admin/css/tokens.css", "/admin/css/globals.css", "/admin/css/components.css",
        )
        val scripts = d.select("head script[src]")
        assertThat(scripts.eachAttr("src")).containsExactly(
            "/admin/js/vendor/htmx.min.js", "/admin/js/vendor/alpine-focus.min.js", "/admin/js/ui.js",
            "/admin/js/app.js", "/admin/js/vendor/alpine.min.js",
        )
        assertThat(scripts[0].hasAttr("defer")).isFalse()
        assertThat(scripts[1].hasAttr("defer")).isTrue()
        assertThat(scripts[4].hasAttr("defer")).isTrue()
        assertThat(d.select("script[src^=http], link[href^=http]")).isEmpty()
    }

    @Test
    @DisplayName("구조: .app > 사이드바 + .content(상단 바, main), 토스트 호스트·이동 확인(unsavedGuard) 한 번씩")
    fun structure() {
        val d = page("/admin/tenants", platform())
        assertThat(d.select("body > div.app > aside.sidebar")).hasSize(1)
        assertThat(d.select("body > div.app > div.content > header.topbar")).hasSize(1)
        assertThat(d.select("body > div.app > div.content > main #page-content").text()).isEqualTo("본문")
        assertThat(d.select("[x-data=toastHost]")).hasSize(1)
        assertThat(d.select("[x-data=unsavedGuard]")).hasSize(1)
        assertThat(d.select("#license-banner")).hasSize(1)
        // 라이선스 배너는 .app 위(App.tsx: LicenseBanner → .app)
        assertThat(d.body().children().first()!!.id()).isEqualTo("license-banner")
    }

    @Test
    @DisplayName("라이선스 배너 자리: load + 5분(300초) 폴링")
    fun licenseHost() {
        val host = page("/admin/tenants", platform()).selectFirst("#license-banner")!!
        assertThat(host.attr("hx-get")).isEqualTo("/admin/_/shell/license-banner")
        assertThat(host.attr("hx-trigger")).isEqualTo("load, every 300s")
    }

    @Test
    @DisplayName("crumbs 조각이 없는 페이지도 렌더된다(상단 바에 브레드크럼 없음)")
    fun pageWithoutCrumbs() {
        val d = page("/admin/settings/crumbless", platform())
        assertThat(d.select("header.topbar nav.crumbs")).isEmpty()
        assertThat(d.title()).isEqualTo("크럼 없음")
        assertThat(d.select("main #page-content")).hasSize(1)
    }

    // ── 브레드크럼 (Breadcrumb.tsx, App.tsx buildBreadcrumb, useCrumbTenant) ──

    @Test
    @DisplayName("브레드크럼: 마지막은 현재 위치(aria-current=page), 앞은 링크, 칸 사이 화살표")
    fun breadcrumb() {
        val nav = page("/admin/tenants", platform()).selectFirst("header.topbar nav.crumbs")!!
        assertThat(nav.attr("aria-label")).isEqualTo("현재 위치")
        assertThat(nav.select("a.crumbs__item").eachText()).containsExactly("Tenants")
        assertThat(nav.select("a.crumbs__item").attr("href")).isEqualTo("/admin/tenants")
        assertThat(nav.select("span.crumbs__current[aria-current=page]").text()).isEqualTo("Acme")
        assertThat(nav.select("svg")).hasSize(1)
    }

    @Test
    @DisplayName("Crumbs: Tenants › {이름} › {탭}(PLATFORM), 이름 조회 실패면 Tenants 만, RP 는 이름 › 탭")
    fun crumbsBuilder() {
        val p = Crumbs.tenant(true, "t1", "Demo RP", "apikeys")
        assertThat(p).extracting(LABEL).containsExactly("Tenants", "Demo RP", "API 키")
        assertThat(p).extracting(HREF).containsExactly("/admin/tenants", "/admin/tenants/t1?tab=overview", null)
        assertThat(Crumbs.tenant(true, "t1", null, "overview")).extracting(LABEL).containsExactly("Tenants")
        assertThat(Crumbs.tenant(false, "t1", "Demo RP", "audit")).extracting(LABEL).containsExactly("Demo RP", "감사 로그")
        assertThat(Crumbs.tenant(false, "t1", null, "overview")).isEmpty()
        assertThat(Crumbs.tenant(true, "t1", "X", "zzz")).extracting(LABEL).last().isEqualTo("zzz")
        for (kv in arrayOf(arrayOf("overview", "개요"), arrayOf("webauthn", "WebAuthn"), arrayOf("aaguid", "AAGUID 정책"), arrayOf("credentials", "Credentials"), arrayOf("funnel", "인증 현황"))) {
            assertThat(Crumbs.tenant(true, "t", "n", kv[0])).extracting(LABEL).last().isEqualTo(kv[1])
        }
        assertThat(Crumbs.tenants()).extracting(LABEL).containsExactly("Tenants")
        assertThat(Crumbs.activity()).extracting(LABEL).containsExactly("활동")
        assertThat(Crumbs.auditChain()).extracting(LABEL).containsExactly("감사 체인 모니터")
        assertThat(Crumbs.settings()).extracting(LABEL).containsExactly("설정")
        assertThat(Crumbs.license()).extracting(LABEL).containsExactly("라이선스")
        // 테넌트 상세 경로의 브레드크럼이 템플릿으로도 그려진다
        val nav = page("/admin/tenants/t1", platform()).selectFirst("nav.crumbs")!!
        assertThat(nav.select("a.crumbs__item").eachText()).containsExactly("Tenants", "Demo RP")
        assertThat(nav.select("a.crumbs__item").eachAttr("href")).containsExactly("/admin/tenants", "/admin/tenants/t1?tab=overview")
        assertThat(nav.select(".crumbs__current").text()).isEqualTo("WebAuthn")
    }

    @Test
    @DisplayName("navActive: 경로 → 사이드바 항목")
    fun navActiveMapping() {
        assertThat(ShellAdvice.navActive("/admin/tenants")).isEqualTo("tenants")
        assertThat(ShellAdvice.navActive("/admin/tenants/abc")).isEqualTo("tenants")
        assertThat(ShellAdvice.navActive("/admin/settings")).isEqualTo("settings")
        assertThat(ShellAdvice.navActive("/admin/insights")).isEqualTo("insights")
        assertThat(ShellAdvice.navActive("/admin/insights.csv")).isNull() // CSV 는 레이아웃이 없다
        assertThat(ShellAdvice.navActive("/admin/_/shell/chain-card")).isNull()
        assertThat(ShellAdvice.navActive("/admin/tenantsfoo")).isNull()
    }

    // ── 상단 바 (Header.test.tsx) ──────────────────────────

    @Test
    @DisplayName("상단 바: 검색 버튼은 실제 검색 범위를 말하고 팔레트 이벤트를 올린다, 단축키 표기는 클라이언트(Ctrl K 기본)")
    fun headerSearch() {
        val search = page("/admin/tenants", platform()).selectFirst("header.topbar button.topbar__search")!!
        assertThat(search.text()).contains("tenant 이름 · slug 검색").doesNotContain("credential")
        assertThat(search.attr("aria-label")).isEqualTo("tenant 이름 · slug 검색 (Ctrl K)")
        assertThat(search.attr("x-bind:aria-label")).contains("key")
        assertThat(search.attr("x-data")).isEqualTo("platformKey")
        assertThat(search.attr("x-on:click")).contains("open-palette")
        assertThat(search.selectFirst("kbd")!!.text()).isEqualTo("Ctrl K")
        assertThat(search.selectFirst("kbd")!!.attr("x-text")).isEqualTo("key")
    }

    @Test
    @DisplayName("사용자 메뉴는 로그아웃만(역할 전환 없음), 이름·이메일·역할 배지, 로그아웃은 CSRF 가 붙은 POST")
    fun userMenu() {
        val d = page("/admin/tenants", platform())
        val pill = d.selectFirst("button.user-pill")!!
        assertThat(pill.attr("aria-label")).isEqualTo("사용자 메뉴")
        assertThat(pill.attr("aria-haspopup")).isEqualTo("menu")
        assertThat(pill.selectFirst(".avatar")!!.text()).isEqualTo("A")
        assertThat(pill.select(".badge").text()).isEqualTo("PLATFORM")
        assertThat(pill.select(".badge").hasClass("badge--violet")).isTrue()
        assertThat(pill.text()).contains("alice@example.com")
        val menu = d.selectFirst("div.menu[aria-label=사용자 메뉴]")!!
        assertThat(menu.select("[role=menuitem]").eachText()).containsExactly("로그아웃")
        val form = menu.selectFirst("form")!!
        assertThat(form.attr("method")).isEqualToIgnoringCase("post")
        assertThat(form.attr("action")).isEqualTo("/admin/logout")
        assertThat(form.attr("hx-boost")).isEqualTo("false")
        assertThat(form.select("input[name=_csrf]").attr("value")).isNotBlank()
        assertThat(menu.select("button.menu__item--danger").text()).isEqualTo("로그아웃")
        assertThat(menu.text()).contains("alice@example.com")
    }

    @Test
    @DisplayName("RP_ADMIN 배지는 RP_ADMIN(info)")
    fun rpBadge() {
        val pill = page("/admin/settings", rp()).selectFirst("button.user-pill")!!
        assertThat(pill.select(".badge").text()).isEqualTo("RP_ADMIN")
        assertThat(pill.select(".badge").hasClass("badge--info")).isTrue()
        assertThat(pill.selectFirst(".avatar")!!.text()).isEqualTo("B")
    }

    @Test
    @DisplayName("도움말: 주소가 비어 있으면 문서 항목을 숨기고 키보드 단축키만, 단축키 시트 이벤트")
    fun helpMenuDefault() {
        val d = page("/admin/tenants", platform())
        val trigger = d.selectFirst("header.topbar button[aria-haspopup=menu]:not(.user-pill)")!!
        assertThat(trigger.text()).isEqualTo("도움말")
        val menu = d.selectFirst("div.menu[role=menu][aria-label=도움말]")!!
        assertThat(menu.select("[role=menuitem]")).hasSize(1)
        val item = menu.selectFirst("[role=menuitem]")!!
        assertThat(item.text()).isEqualTo("키보드 단축키?")
        assertThat(item.attr("aria-keyshortcuts")).isEqualTo("?")
        assertThat(item.attr("x-on:click")).contains("open-shortcuts")
        assertThat(menu.select(".menu__sep")).isEmpty()
        assertThat(menu.parent()!!.attr("x-data")).isEqualTo("shellMenu(true)")
    }

    @Test
    @DisplayName("RP_ADMIN 은 RP 전환 자리를 load 로 채운다, PLATFORM 은 없다")
    fun switcherHostOnlyForRp() {
        val host = page("/admin/settings", rp()).selectFirst("header.topbar div[hx-get]")!!
        assertThat(host.attr("hx-get")).isEqualTo("/admin/_/shell/tenant-switcher")
        assertThat(host.attr("hx-trigger")).isEqualTo("load")
        assertThat(page("/admin/tenants", platform()).select("header.topbar [hx-get]")).isEmpty()
    }

    // ── 도움말 링크 설정은 ShellHelpLinksTest ──

    // ── RP 전환 (Header.tsx RpSwitcher) ────────────────────

    private fun twoTenants(): ActiveTenantController.ActiveTenantView =
        ActiveTenantController.ActiveTenantView(
            T1, listOf(T1, T2),
            listOf(ActiveTenantController.TenantRef(T1, "Acme"), ActiveTenantController.TenantRef(T2, "Beta")),
        )

    @Test
    @DisplayName("전환 목록: 허용 RP 2개 이상이면 현재 RP 이름 버튼 + 라디오 메뉴(활성 체크), 고르면 POST")
    fun switcherFragment() {
        whenever(activeTenants.current()).thenReturn(twoTenants())
        val html = mvc.perform(get("/admin/_/shell/tenant-switcher").header("HX-Request", "true").with(authentication(rp())))
            .andReturn().response.contentAsString
        val root = Jsoup.parseBodyFragment(html).body()
        assertThat(root.selectFirst("button.btn--ghost")!!.text()).contains("Acme")
        val menu = root.selectFirst("div.menu[role=menu][aria-label=RP 전환]")!!
        assertThat(menu.selectFirst(".t-over")!!.text()).isEqualTo("RP 전환")
        val items = menu.select("[role=menuitemradio]")
        assertThat(items.eachText()).containsExactly("Acme", "Beta")
        assertThat(items[0].attr("aria-checked")).isEqualTo("true")
        assertThat(items[0].select("svg")).hasSize(1)
        assertThat(items[1].attr("aria-checked")).isEqualTo("false")
        assertThat(items[1].attr("hx-post")).isEqualTo("/admin/_/active-tenant")
        assertThat(items[1].attr("hx-vals")).isEqualTo("{\"tenantId\": \"" + T2 + "\"}")
        assertThat(items[1].attr("hx-swap")).isEqualTo("none")
        assertThat(items[0].hasAttr("hx-post")).`as`("현재 RP 를 고르면 닫기만").isFalse()
        assertThat(root.select("span[x-show=switching]").text()).isEqualTo("전환 중…")
    }

    @Test
    @DisplayName("전환 목록: 이름이 없으면 id 끝 8자, 허용 RP 가 1개이거나 조회 실패면 빈 응답")
    fun switcherFallbacksAndEmpty() {
        whenever(activeTenants.current()).thenReturn(ActiveTenantController.ActiveTenantView(T1, listOf(T1, T2), listOf()))
        val html = mvc.perform(get("/admin/_/shell/tenant-switcher").with(authentication(rp()))).andReturn().response.contentAsString
        assertThat(Jsoup.parseBodyFragment(html).select("[role=menuitemradio]").eachText()).containsExactly("000000a1", "000000b2")
        assertThat(Jsoup.parseBodyFragment(html).selectFirst("button.btn--ghost")!!.text()).contains("000000a1")

        whenever(activeTenants.current()).thenReturn(ActiveTenantController.ActiveTenantView(T1, listOf(T1), listOf()))
        val one = mvc.perform(get("/admin/_/shell/tenant-switcher").with(authentication(rp()))).andReturn()
        assertThat(one.response.status).isEqualTo(200)
        assertThat(one.response.contentAsString.trim { it <= ' ' }).isEmpty()

        whenever(activeTenants.current()).thenThrow(IllegalStateException("boom"))
        val failed = mvc.perform(get("/admin/_/shell/tenant-switcher").with(authentication(rp()))).andReturn()
        assertThat(failed.response.status).isEqualTo(200)
        assertThat(failed.response.contentAsString.trim { it <= ' ' }).isEmpty()
    }

    @Test
    @DisplayName("POST /admin/_/active-tenant: switchTenant 호출 후 현재 화면 새로고침(HX-Refresh, TSX reload)")
    fun switchTenantRedirects() {
        whenever(activeTenants.switchTenant(anyOrNull())).thenReturn(twoTenants())
        val r = mvc.perform(
            post("/admin/_/active-tenant").param("tenantId", T2.toString())
                .header("HX-Request", "true").with(authentication(rp())).with(csrf()),
        )
            .andReturn()
        assertThat(r.response.status).isEqualTo(200)
        assertThat(r.response.getHeader("HX-Refresh")).isEqualTo("true")
        assertThat(r.response.getHeader("HX-Redirect")).isNull()
        verify(activeTenants).switchTenant(ActiveTenantController.SwitchRequest(T2))
    }

    @Test
    @DisplayName("htmx 가 아닌 전환 POST: 같은 출처 /admin Referer 로(쿼리 유지), 아니면 선택한 RP 상세로 302")
    fun switchTenantPlainRedirect() {
        val fallback = "/admin/tenants/" + T2
        val ok = mvc.perform(
            post("/admin/_/active-tenant").param("tenantId", T2.toString())
                .header("Referer", "http://localhost/admin/settings?tab=security").with(authentication(rp())).with(csrf()),
        )
        assertThat(ok.andReturn().response.redirectedUrl).isEqualTo("/admin/settings?tab=security")
        for (ref in arrayOf<String?>(null, "", "http://evil.example/admin/settings", "https://localhost/admin/settings", "http://localhost/other", "http://localhost:9999/admin/x", "::bad")) {
            val req = post("/admin/_/active-tenant").param("tenantId", T2.toString()).with(authentication(rp())).with(csrf())
            if (ref != null) req.header("Referer", ref)
            val r = mvc.perform(req).andReturn()
            assertThat(r.response.status).`as`("referer " + ref).isEqualTo(302)
            assertThat(r.response.redirectedUrl).`as`("referer " + ref).isEqualTo(fallback)
        }
    }

    @Test
    @DisplayName("전환 실패: 실제 오류 상태 + 오류 토스트(제목·쉬운 문구·현재 RP 유지), 리다이렉트 없음, 본문 없음")
    fun switchTenantFailureToasts() {
        whenever(activeTenants.current()).thenReturn(twoTenants())
        whenever(activeTenants.switchTenant(anyOrNull())).thenThrow(
            org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "HTTP 503",
            ),
        )
        val r = mvc.perform(
            post("/admin/_/active-tenant").param("tenantId", T2.toString())
                .header("HX-Request", "true").with(authentication(rp())).with(csrf()),
        )
            .andReturn()
        assertThat(r.response.status).isEqualTo(503)
        assertThat(r.response.getHeader("HX-Redirect")).isNull()
        assertThat(r.response.contentAsString).isEmpty()
        val trigger = r.response.getHeader("HX-Trigger")
        assertThat(trigger).contains("\"kind\":\"error\"")
        val toast = tools.jackson.databind.ObjectMapper().readTree(trigger).get("toast")
        assertThat(toast.get("title").asString()).isEqualTo("RP를 전환하지 못했습니다")
        assertThat(toast.get("message").asString()).isEqualTo("서버가 잠시 응답하지 않습니다. 지금 보고 있는 RP(Acme)는 그대로입니다.")
        assertThat(toast.get("detail").asString()).startsWith("HTTP 503")
    }

    @Test
    @DisplayName("전환 거부(허용 범위 밖)는 403 문구")
    fun switchTenantDenied() {
        whenever(activeTenants.current()).thenReturn(twoTenants())
        whenever(activeTenants.switchTenant(anyOrNull())).thenThrow(org.springframework.security.access.AccessDeniedException("denied"))
        val r = mvc.perform(
            post("/admin/_/active-tenant").param("tenantId", T2.toString())
                .header("HX-Request", "true").with(authentication(rp())).with(csrf()),
        ).andReturn()
        assertThat(r.response.status).isEqualTo(403)
        val toast = tools.jackson.databind.ObjectMapper().readTree(r.response.getHeader("HX-Trigger")).get("toast")
        assertThat(toast.get("message").asString()).isEqualTo("권한이 없습니다. 지금 보고 있는 RP(Acme)는 그대로입니다.")
    }

    // ── 라이선스 배너 (LicenseBanner.test.tsx) ─────────────

    private fun banner(a: Authentication, currentUrl: String?): String {
        val req = get("/admin/_/shell/license-banner").header("HX-Request", "true").with(authentication(a))
        if (currentUrl != null) req.header("HX-Current-URL", currentUrl)
        return mvc.perform(req).andReturn().response.contentAsString
    }

    @Test
    @DisplayName("WARNING: 남은 일수 문구 + '라이선스 보기' 링크(/license), role=status")
    fun bannerWarning() {
        whenever(license.get()).thenReturn(ApiResponse.ok(view("onprem", "WARNING", 30, null)))
        val b = Jsoup.parseBodyFragment(banner(platform(), "http://localhost/admin/tenants")).selectFirst(".topbanner")!!
        assertThat(b.hasClass("topbanner--warning")).isTrue()
        assertThat(b.attr("role")).isEqualTo("status")
        assertThat(b.selectFirst("strong")!!.text()).isEqualTo("라이선스가 30일 후 만료됩니다. 영업 담당자에게 갱신을 요청하세요.")
        assertThat(b.selectFirst("a.btn--sm")!!.text()).isEqualTo("라이선스 보기")
        assertThat(b.selectFirst("a.btn--sm")!!.attr("href")).isEqualTo("/admin/license")
        assertThat(b.select("svg")).hasSize(1)
    }

    @Test
    @DisplayName("NETWORK_GRACE: 남은 시간(없으면 '곧') + '조치 방법 보기'")
    fun bannerNetworkGrace() {
        whenever(license.get()).thenReturn(ApiResponse.ok(view("onprem", "NETWORK_GRACE", 30, 12L)))
        val b = Jsoup.parseBodyFragment(banner(platform(), null)).selectFirst(".topbanner")!!
        assertThat(b.hasClass("topbanner--warning")).isTrue()
        assertThat(b.attr("role")).isEqualTo("status")
        assertThat(b.selectFirst("strong")!!.text()).isEqualTo("라이선스 서버에 연결할 수 없습니다. 12시간 안에 연결되지 않으면 서비스가 중단됩니다.")
        assertThat(b.selectFirst("a.btn--sm")!!.text()).isEqualTo("조치 방법 보기")

        whenever(license.get()).thenReturn(ApiResponse.ok(view("onprem", "NETWORK_GRACE", 30, null)))
        assertThat(Jsoup.parseBodyFragment(banner(platform(), null)).selectFirst("strong")!!.text())
            .isEqualTo("라이선스 서버에 연결할 수 없습니다. 곧 연결되지 않으면 서비스가 중단됩니다.")
    }

    @Test
    @DisplayName("DEAD: role=alert + 쉬운 영향 문구 + '갱신 방법 보기'")
    fun bannerDead() {
        whenever(license.get()).thenReturn(ApiResponse.ok(view("onprem", "DEAD", 0, null)))
        val b = Jsoup.parseBodyFragment(banner(platform(), null)).selectFirst(".topbanner")!!
        assertThat(b.hasClass("topbanner--danger")).isTrue()
        assertThat(b.attr("role")).isEqualTo("alert")
        assertThat(b.selectFirst("strong")!!.text()).isEqualTo("라이선스가 만료되어 패스키 등록·인증이 중단되었습니다. 관리 콘솔은 계속 쓸 수 있습니다.")
        assertThat(b.selectFirst("a.btn--sm")!!.text()).isEqualTo("갱신 방법 보기")
    }

    @Test
    @DisplayName("링크 없음: RP 관리자. PLATFORM 링크는 /license 에서 숨길 표식(data-hide-on-path)")
    fun bannerLinkHidden() {
        whenever(license.get()).thenReturn(ApiResponse.ok(view("onprem", "WARNING", 30, null)))
        assertThat(Jsoup.parseBodyFragment(banner(rp(), null)).select("a")).isEmpty()
        assertThat(Jsoup.parseBodyFragment(banner(rp(), null)).select("strong").text()).contains("30일 후 만료됩니다")
        // /license 화면에서 숨기는 일은 app.js 가 현재 주소로 한다(배너가 이동 사이에 유지되므로) — 링크에 표식이 있다.
        val link = Jsoup.parseBodyFragment(banner(platform(), "http://localhost:8080/admin/license")).selectFirst("a[data-license-link]")!!
        assertThat(link.attr("data-hide-on-path")).isEqualTo("/admin/license")
        val css = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/static/admin/css/components.css"))
        assertThat(css).`as`("hidden 이 .btn 의 display 에 지지 않도록").contains("[hidden] { display: none !important; }")
        assertThat(java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/static/admin/js/app.js"))).contains("replace(/\\/+\$/, '')")
    }

    @Test
    @DisplayName("VALID·SaaS 는 아무것도 그리지 않는다(WARNING·DEAD 라도 SaaS 는 숨김)")
    fun bannerHidden() {
        whenever(license.get()).thenReturn(ApiResponse.ok(view("onprem", "VALID", 30, null)))
        assertThat(banner(platform(), null).trim { it <= ' ' }).isEmpty()
        for (state in arrayOf<String?>("WARNING", "DEAD", null)) {
            whenever(license.get()).thenReturn(ApiResponse.ok(view("saas", state, 30, null)))
            assertThat(banner(platform(), null).trim { it <= ' ' }).`as`("saas " + state).isEmpty()
        }
    }

    @Test
    @DisplayName("조회 실패는 알리지 않는다: 503·본문 없음·토스트 없음(htmx 가 스왑하지 않아 마지막 배너 유지)")
    fun bannerFailureIsQuiet() {
        whenever(license.get()).thenThrow(IllegalStateException("boom"))
        val r = mvc.perform(get("/admin/_/shell/license-banner").header("HX-Request", "true").with(authentication(platform()))).andReturn()
        assertThat(r.response.status).isEqualTo(503)
        assertThat(r.response.contentAsString.trim { it <= ' ' }).isEmpty()
        assertThat(r.response.getHeader("HX-Trigger")).isNull()
    }

    // ── 감사 체인 카드 (Sidebar.test.tsx) ──────────────────

    private fun overview(tampered: Int, rows: Long, verifiedAt: Instant): AuditChainOverview =
        AuditChainOverview(
            verifiedAt, 24, 60,
            AuditChainOverview.Totals(5 - tampered, 5, tampered, rows, 10), listOf(),
        )

    @Test
    @DisplayName("정상: AUDIT CHAIN OK + '마지막 검증 · 방금 · 1,234 행', 메뉴 배지 비움(OOB)")
    fun chainCardOk() {
        whenever(chain.overview(24)).thenReturn(overview(0, 1234, Instant.parse("2026-10-11T00:00:00Z")))
        val html = mvc.perform(get("/admin/_/shell/chain-card").header("HX-Request", "true").with(authentication(platform())))
            .andReturn().response.contentAsString
        val root = Jsoup.parseBodyFragment(html).body()
        val card = root.selectFirst("a.chain-card")!!
        assertThat(card.hasClass("chain-card--bad")).isFalse()
        assertThat(card.attr("href")).isEqualTo("/admin/audit-chain")
        assertThat(card.selectFirst(".chain-card__title")!!.text()).isEqualTo("AUDIT CHAIN OK")
        assertThat(card.selectFirst(".chain-card__sub")!!.text()).isEqualTo("마지막 검증 · 방금 · 1,234 행")
        assertThat(root.selectFirst("[data-chain-tampered]")!!.attr("data-tampered")).isEqualTo("0")
        assertThat(html).doesNotContain("hx-swap-oob")
    }

    @Test
    @DisplayName("위변조: 빨간 카드 '위변조 의심 · N tenant' + 감사 체인 항목에 '위변조 N' 배지, 카드는 감사 체인 링크")
    fun chainCardTampered() {
        whenever(chain.overview(24)).thenReturn(overview(1, 1234, Instant.parse("2026-10-10T22:00:00Z")))
        val html = mvc.perform(get("/admin/_/shell/chain-card").with(authentication(platform()))).andReturn().response.contentAsString
        val root = Jsoup.parseBodyFragment(html).body()
        val card = root.selectFirst("a.chain-card")!!
        assertThat(card.hasClass("chain-card--bad")).isTrue()
        assertThat(card.attr("href")).isEqualTo("/admin/audit-chain")
        assertThat(card.selectFirst(".chain-card__title")!!.text()).isEqualTo("위변조 의심 · 1 tenant")
        assertThat(card.selectFirst(".chain-card__sub")!!.text()).isEqualTo("즉시 확인이 필요합니다")
        assertThat(root.selectFirst("[data-chain-tampered]")!!.attr("data-tampered")).isEqualTo("1")
        assertThat(html).doesNotContain("hx-swap-oob")
    }

    @Test
    @DisplayName("경과 시간 표기: 5분 전·2시간 전")
    fun chainCardTimeAgo() {
        whenever(chain.overview(24)).thenReturn(overview(0, 10, Instant.parse("2026-10-10T23:55:00Z")))
        assertThat(mvc.perform(get("/admin/_/shell/chain-card").with(authentication(platform()))).andReturn().response.contentAsString)
            .contains("마지막 검증 · 5분 전 · 10 행")
        whenever(chain.overview(24)).thenReturn(overview(0, 10, Instant.parse("2026-10-10T22:00:00Z")))
        assertThat(mvc.perform(get("/admin/_/shell/chain-card").with(authentication(platform()))).andReturn().response.contentAsString)
            .contains("마지막 검증 · 2시간 전")
        assertThat(ShellController.timeAgo(Instant.parse("2026-10-09T20:00:00Z"), Instant.parse("2026-10-11T00:00:00Z"))).`as`("일 단위 없음").isEqualTo("28시간 전")
        assertThat(ShellController.timeAgo(Instant.parse("2026-10-11T00:00:30Z"), Instant.parse("2026-10-11T00:00:00Z"))).`as`("미래는 방금").isEqualTo("방금")
    }

    @Test
    @DisplayName("조회 실패·빈 응답은 503(본문·토스트 없음) — 사이드바가 '검증 상태 확인 불가'를 보인다")
    fun chainCardFailure() {
        whenever(chain.overview(24)).thenThrow(IllegalStateException("503"))
        val r = mvc.perform(get("/admin/_/shell/chain-card").header("HX-Request", "true").with(authentication(platform()))).andReturn()
        assertThat(r.response.status).isEqualTo(503)
        assertThat(r.response.contentAsString.trim { it <= ' ' }).isEmpty()
        assertThat(r.response.getHeader("HX-Trigger")).isNull()

        doReturn(null).whenever(chain).overview(24)
        assertThat(mvc.perform(get("/admin/_/shell/chain-card").with(authentication(platform()))).andReturn().response.status).isEqualTo(503)
        doReturn(AuditChainOverview(Instant.now(), 24, 60, null, listOf())).whenever(chain).overview(24)
        assertThat(mvc.perform(get("/admin/_/shell/chain-card").with(authentication(platform()))).andReturn().response.status).isEqualTo(503)
    }

    @Test
    @DisplayName("체인 카드 권한 거부는 삼키지 않고 403")
    fun chainCardAccessDenied() {
        whenever(chain.overview(24)).thenThrow(org.springframework.security.access.AccessDeniedException("no"))
        assertThat(mvc.perform(get("/admin/_/shell/chain-card").header("HX-Request", "true").with(authentication(rp()))).andReturn().response.status)
            .isEqualTo(403)
    }

    // ── 조각 경로는 인증 필요 ──────────────────────────────

    @Test
    @DisplayName("조각 엔드포인트는 미인증이면 HX-Redirect(로그인)")
    fun fragmentsRequireLogin() {
        for (p in listOf("/admin/_/shell/license-banner", "/admin/_/shell/chain-card", "/admin/_/shell/tenant-switcher")) {
            val r = mvc.perform(get(p).header("HX-Request", "true")).andReturn()
            assertThat(r.response.status).`as`(p).isEqualTo(401)
            assertThat(r.response.getHeader("HX-Redirect")).`as`(p).isEqualTo("/admin/login")
        }
    }
}
