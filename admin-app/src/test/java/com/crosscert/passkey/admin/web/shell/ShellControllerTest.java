package com.crosscert.passkey.admin.web.shell;

import com.crosscert.passkey.admin.audit.AuditChainMonitorController;
import com.crosscert.passkey.admin.audit.AuditChainOverview;
import com.crosscert.passkey.admin.auth.ActiveTenantController;
import com.crosscert.passkey.admin.auth.AdminUserDetails;
import com.crosscert.passkey.admin.license.LicenseController;
import com.crosscert.passkey.admin.license.LicenseView;
import com.crosscert.passkey.admin.web.AdminWebSliceTest;
import com.crosscert.passkey.core.api.ApiResponse;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 공통 틀(레이아웃·사이드바·상단 바·브레드크럼·라이선스 배너·체인 카드·RP 전환) 렌더와 조각 엔드포인트.
 * 원본: shell/Sidebar.test.tsx, Header.test.tsx, useCrumbTenant.test.tsx, components/LicenseBanner.test.tsx.
 * 브라우저에서 도는 Alpine·htmx 동작(메뉴 열기, 폴링 타이머, 포커스)은 속성만 본다.
 */
@AdminWebSliceTest(controllers = {ShellController.class, ShellControllerTest.DummyPage.class})
@Import(ShellControllerTest.DummyPage.class)
class ShellControllerTest {

    /** 레이아웃을 쓰는 더미 화면(규약 확인용). 템플릿은 src/test/resources/templates/admin/shell-test-page.html. */
    @Controller
    public static class DummyPage {
        @GetMapping({"/admin/tenants", "/admin/activity", "/admin/audit-chain", "/admin/settings", "/admin/license"})
        String page(Model model) {
            model.addAttribute("breadcrumbs", List.of(Crumb.link("Tenants", "/admin/tenants"), Crumb.of("Acme")));
            return "admin/shell-test-page";
        }

        @GetMapping("/admin/tenants/{id}")
        String tenant(@PathVariable String id, Model model) {
            model.addAttribute("breadcrumbs", Crumbs.tenant(true, id, "Demo RP", "webauthn"));
            return "admin/shell-test-page";
        }

        @GetMapping("/admin/settings/crumbless")
        String crumbless() {
            return "admin/shell-test-page-nocrumbs";
        }
    }

    @Autowired MockMvc mvc;
    @Autowired Clock clock;
    @MockBean LicenseController license;
    @MockBean AuditChainMonitorController chain;
    @MockBean ActiveTenantController activeTenants;

    static final UUID T1 = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final UUID T2 = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    @BeforeEach
    void stubClock() {
        when(clock.instant()).thenReturn(Instant.parse("2026-10-11T00:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(license.get()).thenReturn(ApiResponse.ok(view("onprem", "VALID", 30, null)));
    }

    static Authentication platform() {
        return auth("PLATFORM_OPERATOR", "alice@example.com", Set.of());
    }

    static Authentication rp() {
        return auth("RP_ADMIN", "bob@example.com", Set.of(T1, T2));
    }

    private static Authentication auth(String role, String email, Set<UUID> tenants) {
        AdminUserDetails p = new AdminUserDetails(UUID.randomUUID(), email, "x", role, tenants, true, null, Clock.systemUTC());
        return new UsernamePasswordAuthenticationToken(p, "x", p.getAuthorities());
    }

    static LicenseView view(String mode, String state, long days, Long graceHours) {
        return new LicenseView(mode, state, "acme", "lic_1", Instant.parse("2026-11-10T00:00:00Z"), days, List.of(), null, graceHours, null);
    }

    private Document page(String path, Authentication a) throws Exception {
        MvcResult r = mvc.perform(get(path).with(authentication(a))).andReturn();
        assertThat(r.getResponse().getStatus()).as(path).isEqualTo(200);
        return Jsoup.parse(r.getResponse().getContentAsString());
    }

    private static List<String> navHrefs(Document d) {
        return d.select("aside.sidebar a.nav-item").stream().map(e -> e.attr("href")).collect(Collectors.toList());
    }

    // ── 사이드바 (Sidebar.test.tsx) ─────────────────────────

    @Test
    @DisplayName("PLATFORM: 플랫폼/관리 그룹, tenants·activity·audit-chain·license·settings 링크, 활성 항목 aria-current")
    void platformSidebar() throws Exception {
        Document d = page("/admin/tenants", platform());
        assertThat(d.select("aside.sidebar").attr("aria-label")).isEqualTo("주 메뉴");
        assertThat(d.select(".sidebar__label").eachText()).containsExactly("플랫폼", "관리");
        assertThat(navHrefs(d)).containsExactly("/admin/tenants", "/admin/activity", "/admin/audit-chain", "/admin/license", "/admin/settings");
        assertThat(d.select("a.nav-item").eachText()).anyMatch(t -> t.contains("Tenants")).anyMatch(t -> t.contains("활동"))
                .anyMatch(t -> t.contains("감사 체인")).anyMatch(t -> t.contains("라이선스") && t.contains("온프렘")).anyMatch(t -> t.contains("설정"));
        Elements current = d.select("a.nav-item[aria-current=page]");
        assertThat(current).hasSize(1);
        assertThat(current.attr("href")).isEqualTo("/admin/tenants");
        assertThat(current.hasClass("nav-item--active")).isTrue();
        // 환경 배지는 하드코딩하지 않는다.
        assertThat(d.select("aside.sidebar").text()).doesNotContain("prod");
        assertThat(d.select(".sidebar__env").text()).isEqualTo("Passkey Admin");
        assertThat(d.select(".sidebar__copy").text()).isEqualTo("© Crosscert");
    }

    @Test
    @DisplayName("라이선스 메뉴는 온프렘에서만(TSX mode === 'onprem')")
    void licenseLinkOnlyOnPrem() throws Exception {
        when(license.get()).thenReturn(ApiResponse.ok(view("saas", null, 0, null)));
        assertThat(navHrefs(page("/admin/tenants", platform()))).containsExactly("/admin/tenants", "/admin/activity", "/admin/audit-chain", "/admin/settings");
        when(license.get()).thenThrow(new IllegalStateException("boom"));
        assertThat(navHrefs(page("/admin/tenants", platform()))).as("조회 실패는 saas 로 본다").doesNotContain("/admin/license");
    }

    @Test
    @DisplayName("RP_ADMIN: 설정만, 플랫폼 그룹·체인 카드·체인 조회 없음")
    void rpAdminSidebar() throws Exception {
        Document d = page("/admin/settings", rp());
        assertThat(navHrefs(d)).containsExactly("/admin/settings");
        assertThat(d.select(".sidebar__label").eachText()).containsExactly("관리");
        assertThat(d.select("#chain-card")).isEmpty();
        assertThat(d.text()).doesNotContain("AUDIT CHAIN").doesNotContain("검증 중");
        verify(chain, never()).overview(org.mockito.ArgumentMatchers.anyInt());
        verify(license, never()).get(); // 라이선스 메뉴는 PLATFORM 에게만 → 배너 조각이 불릴 때까지 조회하지 않는다
        assertThat(d.select("a.nav-item[aria-current=page]").attr("href")).isEqualTo("/admin/settings");
    }

    @Test
    @DisplayName("현재 경로 강조: 테넌트 상세는 Tenants, 각 화면은 자기 항목")
    void activeNavFollowsPath() throws Exception {
        assertThat(page("/admin/tenants/abc", platform()).select("a.nav-item[aria-current=page]").attr("href")).isEqualTo("/admin/tenants");
        assertThat(page("/admin/audit-chain", platform()).select("a.nav-item[aria-current=page]").attr("href")).isEqualTo("/admin/audit-chain");
        assertThat(page("/admin/activity", platform()).select("a.nav-item[aria-current=page]").attr("href")).isEqualTo("/admin/activity");
        assertThat(page("/admin/license", platform()).select("a.nav-item[aria-current=page]").attr("href")).isEqualTo("/admin/license");
    }

    @Test
    @DisplayName("PLATFORM 체인 카드 자리: 30초 폴링·120초 시간 제한·'검증 중…' 초기 상태, 다시 시도")
    void chainCardHost() throws Exception {
        Element host = page("/admin/tenants", platform()).selectFirst("#chain-card");
        assertThat(host).isNotNull();
        assertThat(host.attr("hx-get")).isEqualTo("/admin/_/shell/chain-card");
        assertThat(host.attr("hx-trigger")).isEqualTo("load, every 30s");
        assertThat(host.attr("hx-request")).contains("120000");
        assertThat(host.attr("x-data")).isEqualTo("chainCard");
        assertThat(host.text()).contains("검증 중…").contains("0초째 확인 중").contains("검증 상태 확인 불가").contains("다시 시도");
        assertThat(host.select("[x-text=\"secs + '초째 확인 중'\"]")).hasSize(1);
        assertThat(host.select("[role=status]").text()).isEqualTo("검증 상태 확인 불가");
        assertThat(host.select("#nav-chain-count")).isEmpty();
    }

    @Test
    @DisplayName("체인 카드 자리: hx-preserve(+id), 카드 안 링크가 target/swap/sync 를 상속하지 않음, 폴링·다시 시도가 같은 hx-sync(replace)")
    void chainCardHostPreserveDisinheritSync() throws Exception {
        Element host = page("/admin/tenants", platform()).selectFirst("#chain-card");
        assertThat(host.hasAttr("hx-preserve")).isTrue();
        assertThat(host.attr("hx-disinherit").split("\\s+")).contains("hx-target", "hx-swap", "hx-sync");
        assertThat(host.attr("hx-sync")).isEqualTo("#chain-card:replace");
        Element retry = host.selectFirst("button[hx-get]");
        assertThat(retry.attr("hx-sync")).as("다시 시도도 같은 동기화 대상").isEqualTo(host.attr("hx-sync"));
        assertThat(retry.attr("hx-target")).isEqualTo("#chain-card-body");
        assertThat(retry.attr("hx-swap")).isEqualTo("innerHTML");
        assertThat(retry.hasAttr("data-quiet")).isTrue();
        assertThat(host.attr("hx-target")).isEqualTo("#chain-card-body");
        // 배지는 Alpine store 로 그린다(OOB 아님)
        Element badge = page("/admin/tenants", platform()).selectFirst("a.nav-item[href=/admin/audit-chain] .nav-item__count");
        assertThat(badge.attr("x-show")).contains("$store.chain.tampered");
    }

    @Test
    @DisplayName("셸 조각 유지: 라이선스 배너 자리·RP 전환 자리에 hx-preserve + id")
    void shellPartsPreserved() throws Exception {
        assertThat(page("/admin/tenants", platform()).selectFirst("#license-banner").hasAttr("hx-preserve")).isTrue();
        Element sw = page("/admin/settings", rp()).selectFirst("#tenant-switcher");
        assertThat(sw.hasAttr("hx-preserve")).isTrue();
        assertThat(sw.attr("hx-swap")).as("자리(id·preserve)는 남기고 안쪽만 교체").isEqualTo("innerHTML");
    }

    @Test
    @DisplayName("조각 요청은 메뉴 강조·라이선스 조회를 하지 않는다")
    void fragmentsSkipShellAdvice() throws Exception {
        when(chain.overview(24)).thenReturn(overview(0, 1, Instant.parse("2026-10-11T00:00:00Z")));
        org.mockito.Mockito.clearInvocations(license);
        mvc.perform(get("/admin/_/shell/chain-card").with(authentication(platform()))).andReturn();
        verify(license, never()).get();
    }

    // ── 레이아웃 규약 ───────────────────────────────────────

    @Test
    @DisplayName("<body> hx-boost=true + hx-headers 에 X-XSRF-TOKEN")
    void bodyBoostAndCsrf() throws Exception {
        Element body = page("/admin/tenants", platform()).body();
        assertThat(body.attr("hx-boost")).isEqualTo("true");
        assertThat(body.attr("hx-headers")).matches("\\{\"X-XSRF-TOKEN\": \"[^\"]+\"}");
    }

    @Test
    @DisplayName("head: 제목·스타일 4개·스크립트 순서 htmx → alpine-focus → ui.js → app.js → alpine(defer)")
    void headOrder() throws Exception {
        Document d = page("/admin/tenants", platform());
        assertThat(d.title()).isEqualTo("테스트 페이지");
        assertThat(d.select("head link[rel=stylesheet]").eachAttr("href")).containsExactly(
                "/admin/css/fonts.css", "/admin/css/tokens.css", "/admin/css/globals.css", "/admin/css/components.css");
        Elements scripts = d.select("head script[src]");
        assertThat(scripts.eachAttr("src")).containsExactly(
                "/admin/js/vendor/htmx.min.js", "/admin/js/vendor/alpine-focus.min.js", "/admin/js/ui.js",
                "/admin/js/app.js", "/admin/js/vendor/alpine.min.js");
        assertThat(scripts.get(0).hasAttr("defer")).isFalse();
        assertThat(scripts.get(1).hasAttr("defer")).isTrue();
        assertThat(scripts.get(4).hasAttr("defer")).isTrue();
        assertThat(d.select("script[src^=http], link[href^=http]")).isEmpty();
    }

    @Test
    @DisplayName("구조: .app > 사이드바 + .content(상단 바, main), 토스트 호스트·이동 확인(unsavedGuard) 한 번씩")
    void structure() throws Exception {
        Document d = page("/admin/tenants", platform());
        assertThat(d.select("body > div.app > aside.sidebar")).hasSize(1);
        assertThat(d.select("body > div.app > div.content > header.topbar")).hasSize(1);
        assertThat(d.select("body > div.app > div.content > main #page-content").text()).isEqualTo("본문");
        assertThat(d.select("[x-data=toastHost]")).hasSize(1);
        assertThat(d.select("[x-data=unsavedGuard]")).hasSize(1);
        assertThat(d.select("#license-banner")).hasSize(1);
        // 라이선스 배너는 .app 위(App.tsx: LicenseBanner → .app)
        assertThat(d.body().children().first().id()).isEqualTo("license-banner");
    }

    @Test
    @DisplayName("라이선스 배너 자리: load + 5분(300초) 폴링")
    void licenseHost() throws Exception {
        Element host = page("/admin/tenants", platform()).selectFirst("#license-banner");
        assertThat(host.attr("hx-get")).isEqualTo("/admin/_/shell/license-banner");
        assertThat(host.attr("hx-trigger")).isEqualTo("load, every 300s");
    }

    @Test
    @DisplayName("crumbs 조각이 없는 페이지도 렌더된다(상단 바에 브레드크럼 없음)")
    void pageWithoutCrumbs() throws Exception {
        Document d = page("/admin/settings/crumbless", platform());
        assertThat(d.select("header.topbar nav.crumbs")).isEmpty();
        assertThat(d.title()).isEqualTo("크럼 없음");
        assertThat(d.select("main #page-content")).hasSize(1);
    }

    // ── 브레드크럼 (Breadcrumb.tsx, App.tsx buildBreadcrumb, useCrumbTenant) ──

    @Test
    @DisplayName("브레드크럼: 마지막은 현재 위치(aria-current=page), 앞은 링크, 칸 사이 화살표")
    void breadcrumb() throws Exception {
        Element nav = page("/admin/tenants", platform()).selectFirst("header.topbar nav.crumbs");
        assertThat(nav.attr("aria-label")).isEqualTo("현재 위치");
        assertThat(nav.select("a.crumbs__item").eachText()).containsExactly("Tenants");
        assertThat(nav.select("a.crumbs__item").attr("href")).isEqualTo("/admin/tenants");
        assertThat(nav.select("span.crumbs__current[aria-current=page]").text()).isEqualTo("Acme");
        assertThat(nav.select("svg")).hasSize(1);
    }

    @Test
    @DisplayName("Crumbs: Tenants › {이름} › {탭}(PLATFORM), 이름 조회 실패면 Tenants 만, RP 는 이름 › 탭")
    void crumbsBuilder() throws Exception {
        List<Crumb> p = Crumbs.tenant(true, "t1", "Demo RP", "apikeys");
        assertThat(p).extracting(Crumb::label).containsExactly("Tenants", "Demo RP", "API 키");
        assertThat(p).extracting(Crumb::href).containsExactly("/admin/tenants", "/admin/tenants/t1?tab=overview", null);
        assertThat(Crumbs.tenant(true, "t1", null, "overview")).extracting(Crumb::label).containsExactly("Tenants");
        assertThat(Crumbs.tenant(false, "t1", "Demo RP", "audit")).extracting(Crumb::label).containsExactly("Demo RP", "감사 로그");
        assertThat(Crumbs.tenant(false, "t1", null, "overview")).isEmpty();
        assertThat(Crumbs.tenant(true, "t1", "X", "zzz")).extracting(Crumb::label).last().isEqualTo("zzz");
        for (String[] kv : new String[][] {{"overview", "개요"}, {"webauthn", "WebAuthn"}, {"aaguid", "AAGUID 정책"}, {"credentials", "Credentials"}, {"funnel", "퍼널"}}) {
            assertThat(Crumbs.tenant(true, "t", "n", kv[0])).extracting(Crumb::label).last().isEqualTo(kv[1]);
        }
        assertThat(Crumbs.tenants()).extracting(Crumb::label).containsExactly("Tenants");
        assertThat(Crumbs.activity()).extracting(Crumb::label).containsExactly("활동");
        assertThat(Crumbs.auditChain()).extracting(Crumb::label).containsExactly("감사 체인 모니터");
        assertThat(Crumbs.settings()).extracting(Crumb::label).containsExactly("설정");
        assertThat(Crumbs.license()).extracting(Crumb::label).containsExactly("라이선스");
        // 테넌트 상세 경로의 브레드크럼이 템플릿으로도 그려진다
        Element nav = page("/admin/tenants/t1", platform()).selectFirst("nav.crumbs");
        assertThat(nav.select("a.crumbs__item").eachText()).containsExactly("Tenants", "Demo RP");
        assertThat(nav.select("a.crumbs__item").eachAttr("href")).containsExactly("/admin/tenants", "/admin/tenants/t1?tab=overview");
        assertThat(nav.select(".crumbs__current").text()).isEqualTo("WebAuthn");
    }

    @Test
    @DisplayName("navActive: 경로 → 사이드바 항목")
    void navActiveMapping() {
        assertThat(ShellAdvice.navActive("/admin/tenants")).isEqualTo("tenants");
        assertThat(ShellAdvice.navActive("/admin/tenants/abc")).isEqualTo("tenants");
        assertThat(ShellAdvice.navActive("/admin/settings")).isEqualTo("settings");
        assertThat(ShellAdvice.navActive("/admin/_/shell/chain-card")).isNull();
        assertThat(ShellAdvice.navActive("/admin/tenantsfoo")).isNull();
    }

    // ── 상단 바 (Header.test.tsx) ──────────────────────────

    @Test
    @DisplayName("상단 바: 검색 버튼은 실제 검색 범위를 말하고 팔레트 이벤트를 올린다, 단축키 표기는 클라이언트(Ctrl K 기본)")
    void headerSearch() throws Exception {
        Element search = page("/admin/tenants", platform()).selectFirst("header.topbar button.topbar__search");
        assertThat(search.text()).contains("tenant 이름 · slug 검색").doesNotContain("credential");
        assertThat(search.attr("aria-label")).isEqualTo("tenant 이름 · slug 검색 (Ctrl K)");
        assertThat(search.attr("x-bind:aria-label")).contains("key");
        assertThat(search.attr("x-data")).isEqualTo("platformKey");
        assertThat(search.attr("x-on:click")).contains("open-palette");
        assertThat(search.selectFirst("kbd").text()).isEqualTo("Ctrl K");
        assertThat(search.selectFirst("kbd").attr("x-text")).isEqualTo("key");
    }

    @Test
    @DisplayName("사용자 메뉴는 로그아웃만(역할 전환 없음), 이름·이메일·역할 배지, 로그아웃은 CSRF 가 붙은 POST")
    void userMenu() throws Exception {
        Document d = page("/admin/tenants", platform());
        Element pill = d.selectFirst("button.user-pill");
        assertThat(pill.attr("aria-label")).isEqualTo("사용자 메뉴");
        assertThat(pill.attr("aria-haspopup")).isEqualTo("menu");
        assertThat(pill.selectFirst(".avatar").text()).isEqualTo("A");
        assertThat(pill.select(".badge").text()).isEqualTo("PLATFORM");
        assertThat(pill.select(".badge").hasClass("badge--violet")).isTrue();
        assertThat(pill.text()).contains("alice@example.com");
        Element menu = d.selectFirst("div.menu[aria-label=사용자 메뉴]");
        assertThat(menu.select("[role=menuitem]").eachText()).containsExactly("로그아웃");
        Element form = menu.selectFirst("form");
        assertThat(form.attr("method")).isEqualToIgnoringCase("post");
        assertThat(form.attr("action")).isEqualTo("/admin/logout");
        assertThat(form.attr("hx-boost")).isEqualTo("false");
        assertThat(form.select("input[name=_csrf]").attr("value")).isNotBlank();
        assertThat(menu.select("button.menu__item--danger").text()).isEqualTo("로그아웃");
        assertThat(menu.text()).contains("alice@example.com");
    }

    @Test
    @DisplayName("RP_ADMIN 배지는 RP_ADMIN(info)")
    void rpBadge() throws Exception {
        Element pill = page("/admin/settings", rp()).selectFirst("button.user-pill");
        assertThat(pill.select(".badge").text()).isEqualTo("RP_ADMIN");
        assertThat(pill.select(".badge").hasClass("badge--info")).isTrue();
        assertThat(pill.selectFirst(".avatar").text()).isEqualTo("B");
    }

    @Test
    @DisplayName("도움말: 주소가 비어 있으면 문서 항목을 숨기고 키보드 단축키만, 단축키 시트 이벤트")
    void helpMenuDefault() throws Exception {
        Document d = page("/admin/tenants", platform());
        Element trigger = d.selectFirst("header.topbar button[aria-haspopup=menu]:not(.user-pill)");
        assertThat(trigger.text()).isEqualTo("도움말");
        Element menu = d.selectFirst("div.menu[role=menu][aria-label=도움말]");
        assertThat(menu.select("[role=menuitem]")).hasSize(1);
        Element item = menu.selectFirst("[role=menuitem]");
        assertThat(item.text()).isEqualTo("키보드 단축키?");
        assertThat(item.attr("aria-keyshortcuts")).isEqualTo("?");
        assertThat(item.attr("x-on:click")).contains("open-shortcuts");
        assertThat(menu.select(".menu__sep")).isEmpty();
        assertThat(menu.parent().attr("x-data")).isEqualTo("shellMenu(true)");
    }

    @Test
    @DisplayName("RP_ADMIN 은 RP 전환 자리를 load 로 채운다, PLATFORM 은 없다")
    void switcherHostOnlyForRp() throws Exception {
        Element host = page("/admin/settings", rp()).selectFirst("header.topbar div[hx-get]");
        assertThat(host.attr("hx-get")).isEqualTo("/admin/_/shell/tenant-switcher");
        assertThat(host.attr("hx-trigger")).isEqualTo("load");
        assertThat(page("/admin/tenants", platform()).select("header.topbar [hx-get]")).isEmpty();
    }

    // ── 도움말 링크 설정은 ShellHelpLinksTest ──

    // ── RP 전환 (Header.tsx RpSwitcher) ────────────────────

    private ActiveTenantController.ActiveTenantView twoTenants() {
        return new ActiveTenantController.ActiveTenantView(T1, List.of(T1, T2),
                List.of(new ActiveTenantController.TenantRef(T1, "Acme"), new ActiveTenantController.TenantRef(T2, "Beta")));
    }

    @Test
    @DisplayName("전환 목록: 허용 RP 2개 이상이면 현재 RP 이름 버튼 + 라디오 메뉴(활성 체크), 고르면 POST")
    void switcherFragment() throws Exception {
        when(activeTenants.current()).thenReturn(twoTenants());
        String html = mvc.perform(get("/admin/_/shell/tenant-switcher").header("HX-Request", "true").with(authentication(rp())))
                .andReturn().getResponse().getContentAsString();
        Element root = Jsoup.parseBodyFragment(html).body();
        assertThat(root.selectFirst("button.btn--ghost").text()).contains("Acme");
        Element menu = root.selectFirst("div.menu[role=menu][aria-label=RP 전환]");
        assertThat(menu.selectFirst(".t-over").text()).isEqualTo("RP 전환");
        Elements items = menu.select("[role=menuitemradio]");
        assertThat(items.eachText()).containsExactly("Acme", "Beta");
        assertThat(items.get(0).attr("aria-checked")).isEqualTo("true");
        assertThat(items.get(0).select("svg")).hasSize(1);
        assertThat(items.get(1).attr("aria-checked")).isEqualTo("false");
        assertThat(items.get(1).attr("hx-post")).isEqualTo("/admin/_/active-tenant");
        assertThat(items.get(1).attr("hx-vals")).isEqualTo("{\"tenantId\": \"" + T2 + "\"}");
        assertThat(items.get(1).attr("hx-swap")).isEqualTo("none");
        assertThat(items.get(0).hasAttr("hx-post")).as("현재 RP 를 고르면 닫기만").isFalse();
        assertThat(root.select("span[x-show=switching]").text()).isEqualTo("전환 중…");
    }

    @Test
    @DisplayName("전환 목록: 이름이 없으면 id 끝 8자, 허용 RP 가 1개이거나 조회 실패면 빈 응답")
    void switcherFallbacksAndEmpty() throws Exception {
        when(activeTenants.current()).thenReturn(new ActiveTenantController.ActiveTenantView(T1, List.of(T1, T2), List.of()));
        String html = mvc.perform(get("/admin/_/shell/tenant-switcher").with(authentication(rp()))).andReturn().getResponse().getContentAsString();
        assertThat(Jsoup.parseBodyFragment(html).select("[role=menuitemradio]").eachText()).containsExactly("000000a1", "000000b2");
        assertThat(Jsoup.parseBodyFragment(html).selectFirst("button.btn--ghost").text()).contains("000000a1");

        when(activeTenants.current()).thenReturn(new ActiveTenantController.ActiveTenantView(T1, List.of(T1), List.of()));
        MvcResult one = mvc.perform(get("/admin/_/shell/tenant-switcher").with(authentication(rp()))).andReturn();
        assertThat(one.getResponse().getStatus()).isEqualTo(200);
        assertThat(one.getResponse().getContentAsString().trim()).isEmpty();

        when(activeTenants.current()).thenThrow(new IllegalStateException("boom"));
        MvcResult failed = mvc.perform(get("/admin/_/shell/tenant-switcher").with(authentication(rp()))).andReturn();
        assertThat(failed.getResponse().getStatus()).isEqualTo(200);
        assertThat(failed.getResponse().getContentAsString().trim()).isEmpty();
    }

    @Test
    @DisplayName("POST /admin/_/active-tenant: switchTenant 호출 후 현재 화면 새로고침(HX-Refresh, TSX reload)")
    void switchTenantRedirects() throws Exception {
        when(activeTenants.switchTenant(any())).thenReturn(twoTenants());
        MvcResult r = mvc.perform(post("/admin/_/active-tenant").param("tenantId", T2.toString())
                        .header("HX-Request", "true").with(authentication(rp())).with(csrf()))
                .andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getHeader("HX-Refresh")).isEqualTo("true");
        assertThat(r.getResponse().getHeader("HX-Redirect")).isNull();
        verify(activeTenants).switchTenant(new ActiveTenantController.SwitchRequest(T2));
    }

    @Test
    @DisplayName("htmx 가 아닌 전환 POST: 같은 출처 /admin Referer 로(쿼리 유지), 아니면 선택한 RP 상세로 302")
    void switchTenantPlainRedirect() throws Exception {
        String fallback = "/admin/tenants/" + T2;
        org.springframework.test.web.servlet.ResultActions ok = mvc.perform(post("/admin/_/active-tenant").param("tenantId", T2.toString())
                .header("Referer", "http://localhost/admin/settings?tab=security").with(authentication(rp())).with(csrf()));
        assertThat(ok.andReturn().getResponse().getRedirectedUrl()).isEqualTo("/admin/settings?tab=security");
        for (String ref : new String[] {null, "", "http://evil.example/admin/settings", "https://localhost/admin/settings", "http://localhost/other", "http://localhost:9999/admin/x", "::bad"}) {
            var req = post("/admin/_/active-tenant").param("tenantId", T2.toString()).with(authentication(rp())).with(csrf());
            if (ref != null) req.header("Referer", ref);
            MvcResult r = mvc.perform(req).andReturn();
            assertThat(r.getResponse().getStatus()).as("referer " + ref).isEqualTo(302);
            assertThat(r.getResponse().getRedirectedUrl()).as("referer " + ref).isEqualTo(fallback);
        }
    }

    @Test
    @DisplayName("전환 실패: 실제 오류 상태 + 오류 토스트(제목·쉬운 문구·현재 RP 유지), 리다이렉트 없음, 본문 없음")
    void switchTenantFailureToasts() throws Exception {
        when(activeTenants.current()).thenReturn(twoTenants());
        when(activeTenants.switchTenant(any())).thenThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "HTTP 503"));
        MvcResult r = mvc.perform(post("/admin/_/active-tenant").param("tenantId", T2.toString())
                        .header("HX-Request", "true").with(authentication(rp())).with(csrf()))
                .andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(503);
        assertThat(r.getResponse().getHeader("HX-Redirect")).isNull();
        assertThat(r.getResponse().getContentAsString()).isEmpty();
        String trigger = r.getResponse().getHeader("HX-Trigger");
        assertThat(trigger).contains("\"kind\":\"error\"");
        com.fasterxml.jackson.databind.JsonNode toast = new com.fasterxml.jackson.databind.ObjectMapper().readTree(trigger).get("toast");
        assertThat(toast.get("title").asText()).isEqualTo("RP를 전환하지 못했습니다");
        assertThat(toast.get("message").asText()).isEqualTo("서버가 잠시 응답하지 않습니다. 지금 보고 있는 RP(Acme)는 그대로입니다.");
        assertThat(toast.get("detail").asText()).startsWith("HTTP 503");
    }

    @Test
    @DisplayName("전환 거부(허용 범위 밖)는 403 문구")
    void switchTenantDenied() throws Exception {
        when(activeTenants.current()).thenReturn(twoTenants());
        when(activeTenants.switchTenant(any())).thenThrow(new org.springframework.security.access.AccessDeniedException("denied"));
        MvcResult r = mvc.perform(post("/admin/_/active-tenant").param("tenantId", T2.toString())
                        .header("HX-Request", "true").with(authentication(rp())).with(csrf())).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        com.fasterxml.jackson.databind.JsonNode toast = new com.fasterxml.jackson.databind.ObjectMapper().readTree(r.getResponse().getHeader("HX-Trigger")).get("toast");
        assertThat(toast.get("message").asText()).isEqualTo("권한이 없습니다. 지금 보고 있는 RP(Acme)는 그대로입니다.");
    }

    // ── 라이선스 배너 (LicenseBanner.test.tsx) ─────────────

    private String banner(Authentication a, String currentUrl) throws Exception {
        var req = get("/admin/_/shell/license-banner").header("HX-Request", "true").with(authentication(a));
        if (currentUrl != null) req.header("HX-Current-URL", currentUrl);
        return mvc.perform(req).andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("WARNING: 남은 일수 문구 + '라이선스 보기' 링크(/license), role=status")
    void bannerWarning() throws Exception {
        when(license.get()).thenReturn(ApiResponse.ok(view("onprem", "WARNING", 30, null)));
        Element b = Jsoup.parseBodyFragment(banner(platform(), "http://localhost/admin/tenants")).selectFirst(".topbanner");
        assertThat(b.hasClass("topbanner--warning")).isTrue();
        assertThat(b.attr("role")).isEqualTo("status");
        assertThat(b.selectFirst("strong").text()).isEqualTo("라이선스가 30일 후 만료됩니다. 영업 담당자에게 갱신을 요청하세요.");
        assertThat(b.selectFirst("a.btn--sm").text()).isEqualTo("라이선스 보기");
        assertThat(b.selectFirst("a.btn--sm").attr("href")).isEqualTo("/admin/license");
        assertThat(b.select("svg")).hasSize(1);
    }

    @Test
    @DisplayName("NETWORK_GRACE: 남은 시간(없으면 '곧') + '조치 방법 보기'")
    void bannerNetworkGrace() throws Exception {
        when(license.get()).thenReturn(ApiResponse.ok(view("onprem", "NETWORK_GRACE", 30, 12L)));
        Element b = Jsoup.parseBodyFragment(banner(platform(), null)).selectFirst(".topbanner");
        assertThat(b.hasClass("topbanner--warning")).isTrue();
        assertThat(b.attr("role")).isEqualTo("status");
        assertThat(b.selectFirst("strong").text()).isEqualTo("라이선스 서버에 연결할 수 없습니다. 12시간 안에 연결되지 않으면 서비스가 중단됩니다.");
        assertThat(b.selectFirst("a.btn--sm").text()).isEqualTo("조치 방법 보기");

        when(license.get()).thenReturn(ApiResponse.ok(view("onprem", "NETWORK_GRACE", 30, null)));
        assertThat(Jsoup.parseBodyFragment(banner(platform(), null)).selectFirst("strong").text())
                .isEqualTo("라이선스 서버에 연결할 수 없습니다. 곧 연결되지 않으면 서비스가 중단됩니다.");
    }

    @Test
    @DisplayName("DEAD: role=alert + 쉬운 영향 문구 + '갱신 방법 보기'")
    void bannerDead() throws Exception {
        when(license.get()).thenReturn(ApiResponse.ok(view("onprem", "DEAD", 0, null)));
        Element b = Jsoup.parseBodyFragment(banner(platform(), null)).selectFirst(".topbanner");
        assertThat(b.hasClass("topbanner--danger")).isTrue();
        assertThat(b.attr("role")).isEqualTo("alert");
        assertThat(b.selectFirst("strong").text()).isEqualTo("라이선스가 만료되어 패스키 등록·인증이 중단되었습니다. 관리 콘솔은 계속 쓸 수 있습니다.");
        assertThat(b.selectFirst("a.btn--sm").text()).isEqualTo("갱신 방법 보기");
    }

    @Test
    @DisplayName("링크 없음: RP 관리자. PLATFORM 링크는 /license 에서 숨길 표식(data-hide-on-path)")
    void bannerLinkHidden() throws Exception {
        when(license.get()).thenReturn(ApiResponse.ok(view("onprem", "WARNING", 30, null)));
        assertThat(Jsoup.parseBodyFragment(banner(rp(), null)).select("a")).isEmpty();
        assertThat(Jsoup.parseBodyFragment(banner(rp(), null)).select("strong").text()).contains("30일 후 만료됩니다");
        // /license 화면에서 숨기는 일은 app.js 가 현재 주소로 한다(배너가 이동 사이에 유지되므로) — 링크에 표식이 있다.
        Element link = Jsoup.parseBodyFragment(banner(platform(), "http://localhost:8080/admin/license")).selectFirst("a[data-license-link]");
        assertThat(link.attr("data-hide-on-path")).isEqualTo("/admin/license");
    }

    @Test
    @DisplayName("VALID·SaaS 는 아무것도 그리지 않는다(WARNING·DEAD 라도 SaaS 는 숨김)")
    void bannerHidden() throws Exception {
        when(license.get()).thenReturn(ApiResponse.ok(view("onprem", "VALID", 30, null)));
        assertThat(banner(platform(), null).trim()).isEmpty();
        for (String state : new String[] {"WARNING", "DEAD", null}) {
            when(license.get()).thenReturn(ApiResponse.ok(view("saas", state, 30, null)));
            assertThat(banner(platform(), null).trim()).as("saas " + state).isEmpty();
        }
    }

    @Test
    @DisplayName("조회 실패는 알리지 않는다: 503·본문 없음·토스트 없음(htmx 가 스왑하지 않아 마지막 배너 유지)")
    void bannerFailureIsQuiet() throws Exception {
        when(license.get()).thenThrow(new IllegalStateException("boom"));
        MvcResult r = mvc.perform(get("/admin/_/shell/license-banner").header("HX-Request", "true").with(authentication(platform()))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(503);
        assertThat(r.getResponse().getContentAsString().trim()).isEmpty();
        assertThat(r.getResponse().getHeader("HX-Trigger")).isNull();
    }

    // ── 감사 체인 카드 (Sidebar.test.tsx) ──────────────────

    private AuditChainOverview overview(int tampered, long rows, Instant verifiedAt) {
        return new AuditChainOverview(verifiedAt, 24, 60,
                new AuditChainOverview.Totals(5 - tampered, 5, tampered, rows, 10), List.of());
    }

    @Test
    @DisplayName("정상: AUDIT CHAIN OK + '마지막 검증 · 방금 · 1,234 행', 메뉴 배지 비움(OOB)")
    void chainCardOk() throws Exception {
        when(chain.overview(24)).thenReturn(overview(0, 1234, Instant.parse("2026-10-11T00:00:00Z")));
        String html = mvc.perform(get("/admin/_/shell/chain-card").header("HX-Request", "true").with(authentication(platform())))
                .andReturn().getResponse().getContentAsString();
        Element root = Jsoup.parseBodyFragment(html).body();
        Element card = root.selectFirst("a.chain-card");
        assertThat(card.hasClass("chain-card--bad")).isFalse();
        assertThat(card.attr("href")).isEqualTo("/admin/audit-chain");
        assertThat(card.selectFirst(".chain-card__title").text()).isEqualTo("AUDIT CHAIN OK");
        assertThat(card.selectFirst(".chain-card__sub").text()).isEqualTo("마지막 검증 · 방금 · 1,234 행");
        assertThat(root.selectFirst("[data-chain-tampered]").attr("data-tampered")).isEqualTo("0");
        assertThat(html).doesNotContain("hx-swap-oob");
    }

    @Test
    @DisplayName("위변조: 빨간 카드 '위변조 의심 · N tenant' + 감사 체인 항목에 '위변조 N' 배지, 카드는 감사 체인 링크")
    void chainCardTampered() throws Exception {
        when(chain.overview(24)).thenReturn(overview(1, 1234, Instant.parse("2026-10-10T22:00:00Z")));
        String html = mvc.perform(get("/admin/_/shell/chain-card").with(authentication(platform()))).andReturn().getResponse().getContentAsString();
        Element root = Jsoup.parseBodyFragment(html).body();
        Element card = root.selectFirst("a.chain-card");
        assertThat(card.hasClass("chain-card--bad")).isTrue();
        assertThat(card.attr("href")).isEqualTo("/admin/audit-chain");
        assertThat(card.selectFirst(".chain-card__title").text()).isEqualTo("위변조 의심 · 1 tenant");
        assertThat(card.selectFirst(".chain-card__sub").text()).isEqualTo("즉시 확인이 필요합니다");
        assertThat(root.selectFirst("[data-chain-tampered]").attr("data-tampered")).isEqualTo("1");
        assertThat(html).doesNotContain("hx-swap-oob");
    }

    @Test
    @DisplayName("경과 시간 표기: 5분 전·2시간 전")
    void chainCardTimeAgo() throws Exception {
        when(chain.overview(24)).thenReturn(overview(0, 10, Instant.parse("2026-10-10T23:55:00Z")));
        assertThat(mvc.perform(get("/admin/_/shell/chain-card").with(authentication(platform()))).andReturn().getResponse().getContentAsString())
                .contains("마지막 검증 · 5분 전 · 10 행");
        when(chain.overview(24)).thenReturn(overview(0, 10, Instant.parse("2026-10-10T22:00:00Z")));
        assertThat(mvc.perform(get("/admin/_/shell/chain-card").with(authentication(platform()))).andReturn().getResponse().getContentAsString())
                .contains("마지막 검증 · 2시간 전");
        assertThat(ShellController.timeAgo(Instant.parse("2026-10-09T20:00:00Z"), Instant.parse("2026-10-11T00:00:00Z"))).as("일 단위 없음").isEqualTo("28시간 전");
        assertThat(ShellController.timeAgo(Instant.parse("2026-10-11T00:00:30Z"), Instant.parse("2026-10-11T00:00:00Z"))).as("미래는 방금").isEqualTo("방금");
    }

    @Test
    @DisplayName("조회 실패·빈 응답은 503(본문·토스트 없음) — 사이드바가 '검증 상태 확인 불가'를 보인다")
    void chainCardFailure() throws Exception {
        when(chain.overview(24)).thenThrow(new IllegalStateException("503"));
        MvcResult r = mvc.perform(get("/admin/_/shell/chain-card").header("HX-Request", "true").with(authentication(platform()))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(503);
        assertThat(r.getResponse().getContentAsString().trim()).isEmpty();
        assertThat(r.getResponse().getHeader("HX-Trigger")).isNull();

        org.mockito.Mockito.doReturn(null).when(chain).overview(24);
        assertThat(mvc.perform(get("/admin/_/shell/chain-card").with(authentication(platform()))).andReturn().getResponse().getStatus()).isEqualTo(503);
        org.mockito.Mockito.doReturn(new AuditChainOverview(Instant.now(), 24, 60, null, List.of())).when(chain).overview(24);
        assertThat(mvc.perform(get("/admin/_/shell/chain-card").with(authentication(platform()))).andReturn().getResponse().getStatus()).isEqualTo(503);
    }

    @Test
    @DisplayName("체인 카드 권한 거부는 삼키지 않고 403")
    void chainCardAccessDenied() throws Exception {
        when(chain.overview(24)).thenThrow(new org.springframework.security.access.AccessDeniedException("no"));
        assertThat(mvc.perform(get("/admin/_/shell/chain-card").header("HX-Request", "true").with(authentication(rp()))).andReturn().getResponse().getStatus())
                .isEqualTo(403);
    }

    // ── 조각 경로는 인증 필요 ──────────────────────────────

    @Test
    @DisplayName("조각 엔드포인트는 미인증이면 HX-Redirect(로그인)")
    void fragmentsRequireLogin() throws Exception {
        for (String p : List.of("/admin/_/shell/license-banner", "/admin/_/shell/chain-card", "/admin/_/shell/tenant-switcher")) {
            MvcResult r = mvc.perform(get(p).header("HX-Request", "true")).andReturn();
            assertThat(r.getResponse().getStatus()).as(p).isEqualTo(401);
            assertThat(r.getResponse().getHeader("HX-Redirect")).as(p).isEqualTo("/admin/login");
        }
    }
}
