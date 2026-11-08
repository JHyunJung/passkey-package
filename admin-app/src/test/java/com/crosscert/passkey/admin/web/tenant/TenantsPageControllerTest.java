package com.crosscert.passkey.admin.web.tenant;

import com.crosscert.passkey.admin.apikey.ApiKeyAdminController;
import com.crosscert.passkey.admin.apikey.ApiKeyAdminDto;
import com.crosscert.passkey.admin.auth.ActiveTenantController;
import com.crosscert.passkey.admin.audit.AuditChainMonitorController;
import com.crosscert.passkey.admin.auth.AdminUserDetails;
import com.crosscert.passkey.admin.license.LicenseController;
import com.crosscert.passkey.admin.license.LicenseView;
import com.crosscert.passkey.admin.tenant.TenantAdminController;
import com.crosscert.passkey.admin.tenant.TenantAdminDto;
import com.crosscert.passkey.admin.web.AdminWebSliceTest;
import com.crosscert.passkey.admin.web.tenant.TenantsPageController.TenantRow;
import com.crosscert.passkey.core.api.ApiResponse;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Tenant 목록·생성 다이얼로그. 원본: pages/TenantsListPage.test.tsx, NewTenantDialog.test.tsx(= TenantsListPage.tsx 의 NewTenantDialog).
 * 브라우저에서 도는 부분(Alpine·htmx 의 실제 교체·포커스)은 속성·소스 수준까지만 본다.
 */
@AdminWebSliceTest(controllers = TenantsPageController.class)
class TenantsPageControllerTest {

    @Autowired MockMvc mvc;
    @Autowired Clock clock;
    @MockitoBean TenantAdminController tenants;
    @MockitoBean ApiKeyAdminController apiKeys;
    @MockitoBean LicenseController license;
    @MockitoBean AuditChainMonitorController chain;
    @MockitoBean ActiveTenantController activeTenants;

    static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");
    static final UUID RP1 = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(license.get()).thenReturn(ApiResponse.ok(new LicenseView("saas", "VALID", "acme", "lic_1", NOW.plusSeconds(86400L * 30), 30, List.of(), null, null, null)));
        when(apiKeys.list(null)).thenReturn(ApiResponse.ok(List.of()));
    }

    // ── fixtures ────────────────────────────────────────────

    static Authentication platform() { return auth("PLATFORM_OPERATOR", Set.of()); }
    static Authentication rp(Set<UUID> ids) { return auth("RP_ADMIN", ids); }

    private static Authentication auth(String role, Set<UUID> tenants) {
        AdminUserDetails p = new AdminUserDetails(UUID.randomUUID(), "alice@example.com", "x", role, tenants, true, null, Clock.systemUTC());
        return new UsernamePasswordAuthenticationToken(p, "x", p.getAuthorities());
    }

    static UUID id(int n) { return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", n)); }

    static TenantAdminDto.TenantView tv(int n, String name, String slug, String status) {
        return new TenantAdminDto.TenantView(id(n), slug, name, status, "t" + n + ".example.org", name,
                List.of("https://t" + n + ".example.org"), Set.of("none"), true, false, "NONE", 60000,
                10, 2, null, OffsetDateTime.parse("2026-10-01T00:00:00Z"), OffsetDateTime.parse("2026-10-01T00:00:00Z"));
    }

    static TenantAdminDto.TenantView tv(int n) { return tv(n, "Tenant " + n, "slug-" + n, "ACTIVE"); }

    private void tenants(TenantAdminDto.TenantView... views) {
        when(tenants.list()).thenReturn(ApiResponse.ok(List.of(views)));
    }

    private void manyTenants(int count) {
        List<TenantAdminDto.TenantView> l = new ArrayList<>();
        for (int i = 1; i <= count; i++) l.add(tv(i, String.format("Tenant n%d", i), "slug-n" + i, "ACTIVE"));
        when(tenants.list()).thenReturn(ApiResponse.ok(l));
    }

    private MvcResult fullPage(String url) throws Exception {
        return mvc.perform(get(URI.create(url)).with(authentication(platform()))).andReturn();
    }

    private Document doc(MvcResult r) throws Exception {
        return Jsoup.parse(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private Document page(String url) throws Exception {
        MvcResult r = fullPage(url);
        assertThat(r.getResponse().getStatus()).as(url).isEqualTo(200);
        return doc(r);
    }

    private MvcResult fragment(String url) throws Exception {
        return mvc.perform(get(URI.create(url)).with(authentication(platform())).header("HX-Request", "true")).andReturn();
    }

    private static List<String> rowNames(Document d) {
        return d.select("#tenants-results tbody tr.is-clickable strong").eachText();
    }

    // ── 전체 페이지 vs 조각 ────────────────────────────────

    @Test
    @DisplayName("전체 페이지는 레이아웃 안에서 그려지고, HX-Request 조각은 레이아웃 없이 목록 영역과 OOB 만 돌려준다")
    void fullPageVsFragment() throws Exception {
        tenants(tv(1), tv(2));
        Document full = page("/admin/tenants");
        assertThat(full.select("aside.sidebar")).hasSize(1);
        assertThat(full.select("h1.page__title").text()).isEqualTo("Tenants");
        assertThat(full.select(".page__sub").text()).contains("RP 회사별로 격리된 Passkey 환경");
        assertThat(full.select("nav.crumbs, .crumbs").text()).contains("Tenants");
        assertThat(full.select("#tenants-results")).hasSize(1);

        MvcResult r = fragment("/admin/_/tenants/list?status=ACTIVE");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        Document frag = doc(r);
        assertThat(frag.select("aside.sidebar, header.topbar, body > .app")).isEmpty();
        assertThat(frag.select("#tenants-results")).hasSize(1);
        // OOB: 필터 컨트롤, 활성 KPI, 검색 입력
        assertThat(frag.select("#tenants-controls[hx-swap-oob=true]")).hasSize(1);
        assertThat(frag.select("#tenants-kpi-active[hx-swap-oob=true]")).hasSize(1);
        // 검색 입력은 지우기(reset=q) 요청에서만 되돌려 쓴다
        assertThat(frag.select("#tenant-q")).isEmpty();
        // 주소창은 화면 주소로(조각 경로가 아니라)
        assertThat(r.getResponse().getHeader("HX-Push-Url")).isEqualTo("/admin/tenants?status=ACTIVE");
    }

    @Test
    @DisplayName("기본값 조각 요청의 HX-Push-Url 은 /admin/tenants, 검색어는 UTF-8 로 인코딩하고 기본값(1쪽·50건·전체)은 생략")
    void pushUrlCanonical() throws Exception {
        tenants(tv(1));
        assertThat(fragment("/admin/_/tenants/list").getResponse().getHeader("HX-Push-Url")).isEqualTo("/admin/tenants");
        assertThat(fragment("/admin/_/tenants/list?q=%EC%95%84&size=100&status=SUSPENDED&page=1").getResponse().getHeader("HX-Push-Url"))
                .isEqualTo("/admin/tenants?q=%EC%95%84&status=SUSPENDED&size=100");
        assertThat(fragment("/admin/_/tenants/list?q=a%2Bb%26c").getResponse().getHeader("HX-Push-Url")).isEqualTo("/admin/tenants?q=a%2Bb%26c");
    }

    @Test
    @DisplayName("검색 입력에서 온 조각 요청(HX-Trigger: tenant-q)은 검색 입력을 OOB 로 되돌려 쓰지 않는다")
    void typingDoesNotRewriteSearchInput() throws Exception {
        tenants(tv(1));
        MvcResult r = mvc.perform(get(URI.create("/admin/_/tenants/list?q=ten")).with(authentication(platform()))
                .header("HX-Request", "true").header("HX-Trigger", "tenant-q")).andReturn();
        Document d = doc(r);
        assertThat(d.select("#tenant-q")).isEmpty();
        assertThat(d.select("#tenants-results")).hasSize(1);
    }

    // ── 목록·KPI ───────────────────────────────────────────

    @Test
    @DisplayName("서버가 준 순서를 그대로 유지한다(정렬 없음): 원본도 정렬하지 않는다")
    void keepsServiceOrder() throws Exception {
        tenants(tv(1, "beta", "beta", "ACTIVE"), tv(2, "Alpha", "alpha", "ACTIVE"), tv(3, "가나다", "gnd", "SUSPENDED"), tv(4, "alpha2", "alpha2", "ACTIVE"));
        assertThat(rowNames(page("/admin/tenants"))).containsExactly("beta", "Alpha", "가나다", "alpha2");
        assertThat(rowNames(doc(fragment("/admin/_/tenants/list?status=ALL")))).containsExactly("beta", "Alpha", "가나다", "alpha2");
        assertThat(rowNames(doc(fragment("/admin/_/tenants/list?status=ACTIVE")))).containsExactly("beta", "Alpha", "alpha2");
    }

    @Test
    @DisplayName("행: 이니셜(대문자)·id 끝 8자·슬러그·RP ID·숫자(천 단위)·상태 배지·마지막 이벤트·생성일, 상세 링크 ?tab=overview")
    void rowCells() throws Exception {
        TenantAdminDto.TenantView v = new TenantAdminDto.TenantView(id(77), "acme", "acme corp", "ACTIVE", "passkey.acme.com", "acme corp",
                List.of(), Set.of(), true, false, "NONE", 60000, 1234567, 12, OffsetDateTime.parse("2026-10-10T23:57:00Z"),
                OffsetDateTime.parse("2026-10-01T00:00:00Z"), null);
        tenants(v);
        Document d = page("/admin/tenants");
        Element row = d.select("#tenants-results tbody tr.is-clickable").first();
        assertThat(row.select(".avatar").text()).isEqualTo("A");
        assertThat(row.select("strong").text()).isEqualTo("acme corp");
        assertThat(row.select(".mono.muted").text()).isEqualTo("…00000077");
        assertThat(row.select("td").get(1).text()).isEqualTo("acme");
        assertThat(row.select("td").get(2).text()).isEqualTo("passkey.acme.com");
        assertThat(row.select("td.num").eachText()).containsExactly("1,234,567", "12");
        assertThat(row.select(".badge").text()).isEqualTo("활성");
        assertThat(row.select("td").get(6).text()).isEqualTo("3분 전");
        assertThat(row.select("td").get(7).text()).isEqualTo("2026-10-01");
        assertThat(row.select("a.row-link").attr("href")).isEqualTo("/admin/tenants/" + id(77) + "?tab=overview");
        assertThat(d.select("thead th").eachText()).contains("Tenant", "슬러그", "RP ID", "Credentials", "API 키", "상태", "마지막 이벤트", "생성일", "열기");
    }

    @Test
    @DisplayName("lastEventAt/createdAt 이 없으면 — 로 표시")
    void rowDashes() throws Exception {
        TenantAdminDto.TenantView v = new TenantAdminDto.TenantView(id(1), "s", "n", "ACTIVE", "s.example.org", "n",
                List.of(), Set.of(), true, false, "NONE", 60000, 0, 0, null, null, null);
        tenants(v);
        Element row = page("/admin/tenants").select("#tenants-results tbody tr.is-clickable").first();
        assertThat(row.select("td").get(6).text()).isEqualTo("—");
        assertThat(row.select("td").get(7).text()).isEqualTo("—");
    }

    @Test
    @DisplayName("KPI: 활성 수·전체 건수 힌트·credential 합·API key 합, 7일 증감은 표시하지 않음(서버에 값 없음)")
    void kpis() throws Exception {
        tenants(tv(1), tv(2), tv(3, "Tenant s1", "slug-s1", "SUSPENDED"));
        Document d = page("/admin/tenants");
        List<Element> kpis = d.select(".kpis .kpi");
        assertThat(kpis).hasSize(3);
        assertThat(kpis.get(0).select(".kpi__label").text()).isEqualTo("활성 Tenant");
        assertThat(kpis.get(0).select(".kpi__value").text()).isEqualTo("2");
        assertThat(kpis.get(0).select(".kpi__hint").text()).isEqualTo("전체 3건 · 클릭하면 활성만 보기");
        assertThat(kpis.get(1).select(".kpi__value").text()).isEqualTo("30");
        assertThat(kpis.get(1).select(".kpi__hint").text()).isEqualTo("모든 tenant 합산");
        assertThat(d.text()).doesNotContain("7일");
        assertThat(kpis.get(2).select(".kpi__value").text()).isEqualTo("6");
        assertThat(kpis.get(2).select(".kpi__hint").text()).startsWith("ACTIVE 만");
    }

    @Test
    @DisplayName("활성 KPI 는 버튼(aria-pressed): 클릭 = ACTIVE 토글, 켜져 있으면 ALL 로, 검색어는 유지, 페이지는 1")
    void activeKpiToggle() throws Exception {
        tenants(tv(1), tv(2), tv(3, "Tenant s1", "slug-s1", "SUSPENDED"));
        Element kpi = page("/admin/tenants").select("button#tenants-kpi-active").first();
        assertThat(kpi.attr("aria-pressed")).isEqualTo("false");
        assertThat(kpi.attr("hx-get")).isEqualTo("/admin/_/tenants/list?status=ACTIVE&size=50");
        assertThat(kpi.attr("hx-target")).isEqualTo("#tenants-results");

        Element on = page("/admin/tenants?status=ACTIVE&q=Ten&page=1").select("button#tenants-kpi-active").first();
        assertThat(on.attr("aria-pressed")).isEqualTo("true");
        assertThat(on.hasClass("kpi--selected")).isTrue();
        assertThat(on.attr("hx-get")).isEqualTo("/admin/_/tenants/list?status=ALL&size=50");
    }

    @Test
    @DisplayName("만료 임박 N = ACTIVE 키 중 30일 이내 만료(만료됨·회수됨·40일 뒤·무기한 제외)")
    void expiringKeys() throws Exception {
        tenants(tv(1));
        when(apiKeys.list(null)).thenReturn(ApiResponse.ok(List.of(
                key("k1", NOW.plusSeconds(86400L * 10), null),
                key("k2", NOW.plusSeconds(86400L * 40), null),
                key("k3", NOW.plusSeconds(86400L * 5), NOW.minusSeconds(86400)),
                key("k4", NOW.minusSeconds(86400), null),
                key("k5", null, null))));
        Document d = page("/admin/tenants");
        assertThat(d.select(".kpis .kpi").get(2).select(".kpi__hint span").text()).isEqualTo("만료 임박 1");
    }

    @Test
    @DisplayName("키 목록 조회가 실패하면 만료 임박 표시만 숨기고 목록은 그대로")
    void expiringHiddenOnKeyFailure() throws Exception {
        tenants(tv(1));
        when(apiKeys.list(null)).thenThrow(new IllegalStateException("403"));
        Document d = page("/admin/tenants");
        assertThat(d.text()).doesNotContain("만료 임박");
        assertThat(rowNames(d)).containsExactly("Tenant 1");
    }

    private static ApiKeyAdminDto.ApiKeyView key(String n, Instant expires, Instant revoked) {
        return new ApiKeyAdminDto.ApiKeyView(UUID.nameUUIDFromBytes(n.getBytes()), id(1), n, "pk_x", Set.of(),
                OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                expires == null ? null : OffsetDateTime.ofInstant(expires, ZoneOffset.UTC),
                revoked == null ? null : OffsetDateTime.ofInstant(revoked, ZoneOffset.UTC), null);
    }

    // ── 검색·필터 ──────────────────────────────────────────

    @Test
    @DisplayName("세그먼트는 상태별 건수를 보이고 선택한 상태만 남긴다. '1 / 3건' 과 상태 칩")
    void segmentAndCounts() throws Exception {
        tenants(tv(1), tv(2), tv(3, "Tenant s1", "slug-s1", "SUSPENDED"));
        Document all = page("/admin/tenants");
        org.jsoup.select.Elements btns = all.select("#tenants-controls .seg__btn");
        assertThat(btns.eachText()).containsExactly("전체 3", "활성 2", "정지 1");
        assertThat(btns.get(0).attr("aria-pressed")).isEqualTo("true");
        assertThat(all.select("#tenants-controls .muted").text()).isEqualTo("3 / 3건");
        assertThat(all.select(".applied")).isEmpty();

        Document susp = page("/admin/tenants?status=SUSPENDED");
        assertThat(rowNames(susp)).containsExactly("Tenant s1");
        assertThat(susp.select("#tenants-controls .seg__btn").get(2).attr("aria-pressed")).isEqualTo("true");
        assertThat(susp.select("#tenants-controls .muted").text()).isEqualTo("1 / 3건");
        assertThat(susp.select(".applied .chip").text()).contains("상태: 정지");
        assertThat(susp.select("#tenants-controls .seg__btn").get(1).attr("hx-get")).isEqualTo("/admin/_/tenants/list?status=ACTIVE&size=50");
    }

    @Test
    @DisplayName("적용 필터 칩: 상태/검색 칩 제거 버튼(라벨), 모두 지우기. 제거 주소는 나머지 필터를 유지한다")
    void appliedChips() throws Exception {
        tenants(tv(1), tv(2), tv(3, "Tenant s1", "slug-s1", "SUSPENDED"));
        Document d = page("/admin/tenants?status=SUSPENDED&q=s1");
        Element applied = d.select(".applied").first();
        assertThat(applied.text()).contains("적용된 필터").contains("상태: 정지").contains("검색: s1");
        assertThat(applied.select("button[aria-label=상태 필터 제거]").attr("hx-get")).isEqualTo("/admin/_/tenants/list?status=ALL&size=50");
        assertThat(applied.select("button[aria-label=검색어 제거]").attr("hx-get")).isEqualTo("/admin/_/tenants/list?status=SUSPENDED&size=50&reset=q");
        assertThat(applied.select("button:contains(모두 지우기)").attr("hx-get")).isEqualTo("/admin/_/tenants/list?status=ALL&size=50&reset=q");
        // 필터가 없으면 영역 자체가 없다
        assertThat(page("/admin/tenants").select(".applied")).isEmpty();
    }

    @Test
    @DisplayName("검색은 name(대소문자 무시) 또는 slug 부분 일치, 검색어는 trim 하지 않는다")
    void searchRules() throws Exception {
        tenants(tv(1, "Acme Corp", "acme", "ACTIVE"), tv(2, "Beta", "BETA-X", "ACTIVE"), tv(3, "크로스서트 Inc", "crosscert", "ACTIVE"), tv(4, "Delta", "delta", "ACTIVE"));
        assertThat(rowNames(page("/admin/tenants?q=ACME"))).containsExactly("Acme Corp");
        assertThat(rowNames(page("/admin/tenants?q=acme%20c"))).containsExactly("Acme Corp");
        // slug 는 소문자로 저장되는 값 — 질의는 소문자로 바꿔 slug 와 비교한다(원본 t.slug.includes(q.toLowerCase()))
        assertThat(rowNames(page("/admin/tenants?q=CROSSCERT"))).containsExactly("크로스서트 Inc");
        assertThat(rowNames(page("/admin/tenants?q=%ED%81%AC%EB%A1%9C%EC%8A%A4"))).containsExactly("크로스서트 Inc");
        assertThat(rowNames(page("/admin/tenants?q=%20acme"))).isEmpty(); // 앞 공백은 그대로 비교
        assertThat(rowNames(page("/admin/tenants?q=e"))).containsExactly("Acme Corp", "Beta", "크로스서트 Inc", "Delta"); // 입력 순서 유지
        // 검색어 입력칸 값과 칩 문구는 이스케이프되어 되돌아간다
        Document d = page("/admin/tenants?q=%3Cb%3E");
        assertThat(d.select("#tenant-q").val()).isEqualTo("<b>");
        assertThat(d.select(".applied .chip").text()).contains("검색: <b>");
        assertThat(d.html()).doesNotContain("<b>검색");
    }

    @Test
    @DisplayName("검색과 상태 필터는 AND, 알 수 없는 status 는 ALL")
    void searchAndStatusCombined() throws Exception {
        tenants(tv(1, "Alpha", "alpha", "ACTIVE"), tv(2, "Alpine", "alpine", "SUSPENDED"), tv(3, "Beta", "beta", "SUSPENDED"));
        assertThat(rowNames(page("/admin/tenants?q=alp&status=SUSPENDED"))).containsExactly("Alpine");
        assertThat(rowNames(page("/admin/tenants?status=BOGUS"))).containsExactly("Alpha", "Alpine", "Beta");
        // 활성이 아닌 값은 모두 '정지' 로 센다(adaptTenant)
        tenants(tv(1, "Alpha", "alpha", "active"), tv(2, "Gamma", "gamma", "WHATEVER"));
        Document d = page("/admin/tenants");
        assertThat(d.select("#tenants-controls .seg__btn").eachText()).containsExactly("전체 2", "활성 1", "정지 1");
    }

    @Test
    @DisplayName("filter()/normStatus()/normSize() 단위: 빈 q, 대소문자, 공백, 상태")
    void filterUnit() {
        List<TenantRow> rows = List.of(
                new TenantRow("1", "Acme", "acme-x", "a.com", "ACTIVE", 0, 0, null, null),
                new TenantRow("2", "", "zzz", "b.com", "SUSPENDED", 0, 0, null, null));
        assertThat(TenantsPageController.filter(rows, "", "ALL")).hasSize(2);
        assertThat(TenantsPageController.filter(rows, "ACME", "ALL")).extracting(TenantRow::getId).containsExactly("1");
        assertThat(TenantsPageController.filter(rows, "X", "ALL")).extracting(TenantRow::getId).containsExactly("1");
        assertThat(TenantsPageController.filter(rows, "Z", "ALL")).extracting(TenantRow::getId).containsExactly("2");
        assertThat(TenantsPageController.filter(rows, "", "SUSPENDED")).extracting(TenantRow::getId).containsExactly("2");
        assertThat(TenantsPageController.filter(rows, " ", "ALL")).isEmpty();
        assertThat(TenantsPageController.normStatus("active")).isEqualTo("ALL");
        assertThat(TenantsPageController.normSize(100)).isEqualTo(100);
        assertThat(TenantsPageController.normSize(7)).isEqualTo(50);
        assertThat(TenantsPageController.normSize(-1)).isEqualTo(50);
        assertThat(rows.get(1).initial()).isEmpty();
    }

    // ── 페이지네이션 ───────────────────────────────────────

    @Test
    @DisplayName("서버 페이지네이션 50건: '1–50 / 60건' → 다음 → '51–60 / 60건', 첫 쪽에 없는 행은 둘째 쪽에")
    void pagination() throws Exception {
        manyTenants(60);
        Document p1 = page("/admin/tenants");
        assertThat(p1.select("#tenants-results .pager > span").first().text()).isEqualTo("1–50 / 60건");
        assertThat(rowNames(p1)).hasSize(50).contains("Tenant n1").doesNotContain("Tenant n55");
        assertThat(p1.select("button[aria-label=이전 페이지]").hasAttr("disabled")).isTrue();
        Element next = p1.select("button[aria-label=다음 페이지]").first();
        assertThat(next.hasAttr("disabled")).isFalse();
        assertThat(next.attr("hx-get")).isEqualTo("/admin/_/tenants/list?status=ALL&size=50&page=2");

        Document p2 = doc(fragment("/admin/_/tenants/list?status=ALL&size=50&page=2"));
        assertThat(p2.select("#tenants-results .pager > span").first().text()).isEqualTo("51–60 / 60건");
        assertThat(rowNames(p2)).hasSize(10).contains("Tenant n55").doesNotContain("Tenant n1");
        assertThat(p2.select("button[aria-label=다음 페이지]").hasAttr("disabled")).isTrue();
        assertThat(p2.select("button[aria-label=이전 페이지]").attr("hx-get")).isEqualTo("/admin/_/tenants/list?status=ALL&size=50");
        assertThat(p2.select("#tenants-results .pager").text()).contains("2 / 2");
    }

    @Test
    @DisplayName("페이지 크기 50/100 선택, 범위를 넘은 page 는 마지막 쪽으로, 잘못된 size 는 50")
    void pageSizeAndClamp() throws Exception {
        manyTenants(120);
        Document d = page("/admin/tenants?size=100");
        assertThat(rowNames(d)).hasSize(100);
        assertThat(d.select("#tenants-results .pager > span").first().text()).isEqualTo("1–100 / 120건");
        assertThat(d.select(".pager select option").eachText()).containsExactly("50", "100");
        assertThat(d.select(".pager select option[selected]").text()).isEqualTo("100");
        assertThat(d.select(".pager select").attr("hx-include")).contains("#tenant-q").contains("#tenants-state");
        assertThat(d.select("input#tenants-size").val()).isEqualTo("100");

        assertThat(page("/admin/tenants?page=99").select("#tenants-results .pager > span").first().text()).isEqualTo("101–120 / 120건");
        assertThat(rowNames(page("/admin/tenants?page=0"))).hasSize(50);
        assertThat(rowNames(page("/admin/tenants?page=-3"))).hasSize(50);
        assertThat(rowNames(page("/admin/tenants?size=7"))).hasSize(50);
        // page 보정 결과가 주소창에도 반영된다
        assertThat(fragment("/admin/_/tenants/list?page=99").getResponse().getHeader("HX-Push-Url")).isEqualTo("/admin/tenants?page=3");
    }

    @Test
    @DisplayName("검색 입력·선택 상자가 다음 요청에 이어 받는 상태값: status, size 숨은 입력")
    void hiddenState() throws Exception {
        manyTenants(3);
        Document d = page("/admin/tenants?status=ACTIVE&size=100");
        assertThat(d.select("#tenants-state input[name=status]").val()).isEqualTo("ACTIVE");
        assertThat(d.select("#tenant-q").attr("hx-include")).contains("#tenants-state").contains("#tenants-size");
        assertThat(d.select("#tenant-q").attr("hx-trigger")).contains("input changed delay:250ms");
        assertThat(d.select("#tenants-card").attr("hx-sync")).isEqualTo("this:replace");
    }

    // ── 빈 상태·실패 ───────────────────────────────────────

    @Test
    @DisplayName("tenant 0개: emptyTenants 빈 상태(검색 결과 없음과 구분), 표·검색·CSV 없음, 신규 버튼")
    void emptyTenants() throws Exception {
        when(tenants.list()).thenReturn(ApiResponse.ok(List.of()));
        Document d = page("/admin/tenants");
        assertThat(d.text()).contains("아직 tenant 가 없습니다").doesNotContain("검색 결과가 없습니다");
        assertThat(d.select("img.empty__art").attr("src")).isEqualTo("/admin/img/empty-tenants.webp");
        assertThat(d.select(".empty__sub").text()).contains("생성 후 WebAuthn 설정 → AAGUID 정책 → API key 발급 순으로 진행하세요.");
        assertThat(d.select(".page table")).isEmpty();
        assertThat(d.select("#tenant-q")).isEmpty();
        assertThat(d.select(".empty button.btn--primary").text()).isEqualTo("신규 tenant");
        assertThat(d.select(".kpis .kpi__value").eachText()).containsExactly("0", "0", "0");
    }

    @Test
    @DisplayName("검색 결과 없음: noResults 빈 상태 + 필터 모두 지우기(아직 tenant 가 없다는 안내와 구분)")
    void noResults() throws Exception {
        tenants(tv(1), tv(2));
        Document d = page("/admin/tenants?q=zzz");
        assertThat(d.text()).contains("검색 결과가 없습니다").doesNotContain("아직 tenant 가 없습니다");
        assertThat(d.select("#tenants-results img.empty__art").attr("src")).isEqualTo("/admin/img/no-results.webp");
        assertThat(d.select("#tenants-results .empty__sub").text()).isEqualTo("검색어나 상태 필터를 바꿔 보세요.");
        assertThat(d.select("#tenants-results .empty button").attr("hx-get")).isEqualTo("/admin/_/tenants/list?status=ALL&size=50&reset=q");
        assertThat(d.select("#tenants-results .empty button").text()).isEqualTo("필터 모두 지우기");
        assertThat(d.select("#tenants-results .pager > span").first().text()).isEqualTo("0건");
        assertThat(d.select("#tenants-controls .muted").text()).isEqualTo("0 / 2건");
    }

    @Test
    @DisplayName("로드 실패: 빈 목록 안내 대신 불러오기 실패 상태(KPI 는 —, CSV·검색 없음, alert 하나), 다시 시도는 같은 페이지 요청")
    void loadFailure() throws Exception {
        when(tenants.list()).thenThrow(new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR));
        MvcResult r = fullPage("/admin/tenants?status=ACTIVE");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        Document d = doc(r);
        assertThat(d.select(".empty[role=alert] .empty__title").text()).isEqualTo("tenant 목록을 불러오지 못했습니다");
        assertThat(d.text()).doesNotContain("아직 tenant 가 없습니다");
        assertThat(d.select(".kpis .kpi__value").eachText()).containsExactly("—", "—", "—");
        assertThat(d.select(".kpis button")).isEmpty();
        assertThat(d.select("a:contains(CSV), button:contains(CSV)")).isEmpty();
        assertThat(d.select("main [role=alert]")).hasSize(1);
        assertThat(r.getResponse().getHeader("HX-Trigger")).isNull();
        Element retry = d.select(".empty[role=alert] button.btn--primary").first();
        assertThat(retry.text()).isEqualTo("다시 시도");
        assertThat(retry.attr("hx-get")).isEqualTo("/admin/tenants?status=ACTIVE");
        assertThat(retry.attr("hx-target")).isEqualTo("body");
        // 5xx 도 같은 처리: 서버 오류 문구
        assertThat(page("/admin/tenants").select(".empty[role=alert] .empty__sub").text()).contains("서버가 잠시 응답하지 않습니다");
    }

    @Test
    @DisplayName("조각 요청에서 목록 조회가 실패하면 5xx + 오류 토스트, 본문 없음(화면은 그대로)")
    void fragmentFailureIsToast() throws Exception {
        when(tenants.list()).thenThrow(new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR));
        MvcResult r = fragment("/admin/_/tenants/list?q=a");
        assertThat(r.getResponse().getStatus()).isEqualTo(500);
        assertThat(r.getResponse().getContentAsString()).isEmpty();
        assertThat(r.getResponse().getHeader("HX-Trigger")).contains("toast").contains("error");
    }

    @Test
    @DisplayName("조각 요청 중 tenant 가 모두 사라졌으면 전체 페이지로 이동(HX-Redirect)")
    void fragmentWhenEmptyRedirects() throws Exception {
        when(tenants.list()).thenReturn(ApiResponse.ok(List.of()));
        MvcResult r = fragment("/admin/_/tenants/list");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getHeader("HX-Redirect")).isEqualTo("/admin/tenants");
    }

    // ── 권한 ───────────────────────────────────────────────

    @Test
    @DisplayName("RP_ADMIN 은 HomeRedirect 와 같은 규칙으로 자기 테넌트로 리다이렉트, REST 목록은 부르지 않는다")
    void rpAdminRedirects() throws Exception {
        MvcResult r = mvc.perform(get("/admin/tenants").with(authentication(rp(Set.of(id(9), RP1))))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(302);
        assertThat(r.getResponse().getRedirectedUrl()).isEqualTo("/admin/tenants/" + id(9)); // MeController 가 UUID 순으로 정렬: [0] 이 기본 활성 RP
        verify(tenants, never()).list();
    }

    @Test
    @DisplayName("테넌트가 없는 RP_ADMIN 은 계정 구성 오류 화면(리다이렉트 루프 없음)")
    void rpAdminWithoutTenantSeesConfigError() throws Exception {
        MvcResult r = mvc.perform(get("/admin/tenants").with(authentication(rp(Set.of())))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        Document d = doc(r);
        assertThat(d.select("h1.page__title").text()).isEqualTo("계정 구성 오류");
        assertThat(d.select(".page__sub").text()).isEqualTo("RP_ADMIN 계정에 테넌트가 지정돼 있지 않습니다. 플랫폼 운영자에게 문의하세요.");
        assertThat(d.select("#tenants-results, #nt-form")).isEmpty();
        verify(tenants, never()).list();
    }

    @Test
    @DisplayName("RP_ADMIN 의 조각·CSV·생성 요청은 403 이고 REST 빈을 부르지 않는다")
    void rpAdminCannotUseFragments() throws Exception {
        Authentication rp = rp(Set.of(RP1));
        assertThat(mvc.perform(get(URI.create("/admin/_/tenants/list")).with(authentication(rp)).header("HX-Request", "true")).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(get(URI.create("/admin/_/tenants/export")).with(authentication(rp))).andReturn().getResponse().getStatus()).isEqualTo(403);
        MvcResult create = mvc.perform(post("/admin/_/tenants").with(authentication(rp)).with(csrf()).header("HX-Request", "true")
                .param("name", "Acme").param("slug", "acme").param("rpId", "passkey.acme.com")).andReturn();
        assertThat(create.getResponse().getStatus()).isEqualTo(403);
        verify(tenants, never()).list();
        verify(tenants, never()).create(any(), any());
    }

    @Test
    @DisplayName("미인증은 로그인으로")
    void anonymous() throws Exception {
        assertThat(mvc.perform(get("/admin/tenants")).andReturn().getResponse().getRedirectedUrl()).isEqualTo("/admin/login");
        assertThat(mvc.perform(get("/admin/_/tenants/list").header("HX-Request", "true")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    // ── 신규 다이얼로그 ────────────────────────────────────

    @Test
    @DisplayName("신규 버튼이 다이얼로그를 연다: dialog 조각(id=new-tenant), 제목·설명, 다음 단계 3개, RP ID 잠금 안내, placeholder")
    void dialogMarkup() throws Exception {
        tenants(tv(1));
        Document d = page("/admin/tenants");
        Element open = d.select(".page__head button.btn--primary").first();
        assertThat(open.text()).isEqualTo("신규 tenant");
        assertThat(open.attr("x-on:click")).contains("dialog:open").contains("new-tenant");

        Element dlg = d.select("[x-data*=\"new-tenant\"]").first();
        assertThat(dlg).isNotNull();
        Element tpl = dlg.select("template[x-if=open]").first();
        Document inner = Jsoup.parse(tpl.html());
        assertThat(inner.select("h3.dialog__title").text()).isEqualTo("신규 Tenant 생성");
        assertThat(inner.select(".dialog__sub").text()).isEqualTo("RP 회사 하나에 대한 격리된 Passkey 환경을 만듭니다.");
        Element form = inner.select("form#nt-form").first();
        assertThat(form.attr("hx-post")).isEqualTo("/admin/_/tenants");
        assertThat(form.attr("hx-target")).isEqualTo("this");
        assertThat(form.attr("hx-swap")).isEqualTo("innerHTML");
        assertThat(form.attr("hx-sync")).isEqualTo("this:drop");
        assertThat(inner.select("input[name=name]").attr("placeholder")).isEqualTo("예: Acme Corp");
        assertThat(inner.select("input[name=slug]").attr("placeholder")).isEqualTo("acme-corp");
        assertThat(inner.select("input[name=rpId]").attr("placeholder")).isEqualTo("예: passkey.acme.com");
        assertThat(inner.select("label[for=nt-rpid]").text()).isEqualTo("RP ID");
        assertThat(inner.select("label[for=nt-name]").text()).isEqualTo("표시 이름");
        assertThat(inner.select("label[for=nt-slug]").text()).isEqualTo("Slug (영문 식별자)");
        assertThat(inner.text()).contains("소문자·숫자·하이픈. URL 과 로그에서 tenant 를 식별합니다.")
                .contains("생성 후에는 변경할 수 없습니다. 기존 credential 이 이 값에 묶입니다.");
        assertThat(inner.select("ol[aria-labelledby=nt-next-steps] li strong").eachText()).containsExactly("WebAuthn config", "AAGUID 정책", "API key 발급");
        assertThat(inner.select("ol li .muted").eachText()).containsExactly("origin · UV · attestation", "허용할 인증기", "RP 서버 연동");
        // 입력은 요청 중 readonly(busy), slug·rpId 는 입력 시 소문자 정규화
        assertThat(inner.select("input[name=name]").attr("x-bind:readonly")).isEqualTo("busy");
        assertThat(inner.select("input[name=slug]").attr("x-on:input")).contains("toLowerCase");
        assertThat(inner.select("input[name=rpId]").attr("x-on:input")).contains("toLowerCase").contains("trim");
        // 푸터: 취소(busy 중 비활성) · 생성(form 속성으로 폼 제출, '생성 중…')
        Element submit = inner.select("button#nt-submit").first();
        assertThat(submit.attr("form")).isEqualTo("nt-form");
        assertThat(submit.attr("type")).isEqualTo("submit");
        assertThat(submit.attr("x-bind:disabled")).isEqualTo("busy");
        assertThat(submit.select("span").eachText()).containsExactly("생성하고 설정으로 이동", "생성 중…");
        assertThat(inner.select(".dialog__foot button.btn").first().text()).isEqualTo("취소");
        // 폼 요청 중 busy 는 dialog 컴포넌트 상태: 닫기를 막는다
        assertThat(form.attr("x-on:htmx:before-request")).startsWith("busy = true");
        assertThat(form.attr("x-on:htmx:after-request")).contains("busy = false");
        // 평소에는 닫혀 있다
        assertThat(dlg.attr("x-data")).contains("\"id\":\"new-tenant\"").doesNotContain("\"open\":true");
    }

    @Test
    @DisplayName("?new=1(팔레트의 신규 tenant)은 다이얼로그를 처음부터 열고 주소에서 new 를 지운다(검색·필터는 유지). new 가 없으면 열지 않는다")
    void newQueryOpensDialog() throws Exception {
        tenants(tv(1));
        Document d = page("/admin/tenants?new=1");
        assertThat(d.select("[x-data*=\"new-tenant\"]").attr("x-data")).contains("\"open\":true");
        Element clean = d.select("span[data-clean-url]").first();
        assertThat(clean.attr("data-clean-url")).isEqualTo("/admin/tenants");
        assertThat(clean.attr("x-init")).contains("history.replaceState");

        Document withFilter = page("/admin/tenants?new=1&q=ac&status=ACTIVE");
        assertThat(withFilter.select("span[data-clean-url]").attr("data-clean-url")).isEqualTo("/admin/tenants?q=ac&status=ACTIVE");

        Document plain = page("/admin/tenants");
        assertThat(plain.select("[x-data*=\"new-tenant\"]").attr("x-data")).doesNotContain("\"open\":true");
        assertThat(plain.select("span[data-clean-url]")).isEmpty();
        assertThat(page("/admin/tenants?new=0").select("span[data-clean-url]")).isEmpty();
        // 목록이 비어 있어도(팔레트 → 첫 tenant) 다이얼로그는 열린다
        when(tenants.list()).thenReturn(ApiResponse.ok(List.of()));
        assertThat(page("/admin/tenants?new=1").select("[x-data*=\"new-tenant\"]").attr("x-data")).contains("\"open\":true");
    }

    // ── 생성 ───────────────────────────────────────────────

    private MockHttpServletRequestBuilder createReq(String name, String slug, String rpId) {
        return post("/admin/_/tenants").with(authentication(platform())).with(csrf()).header("HX-Request", "true")
                .param("name", name).param("slug", slug).param("rpId", rpId);
    }

    private static TenantAdminDto.TenantCreateRequest expected(String name, String slug, String rpId) {
        return new TenantAdminDto.TenantCreateRequest(slug, name, rpId, name, List.of("https://" + rpId),
                new LinkedHashSet<>(List.of("none", "packed")), true, false, "NONE", 60000);
    }

    @Test
    @DisplayName("생성 성공: REST 요청 본문(원본 tenantsApi.create 와 같은 값), 새 tenant WebAuthn 탭으로 HX-Redirect, 제목 있는 성공 토스트")
    void createSuccess() throws Exception {
        when(tenants.create(any(), any())).thenReturn(ApiResponse.ok("Tenant created", tv(5, "Acme", "acme", "ACTIVE")));
        MvcResult r = mvc.perform(createReq("Acme", "acme", "passkey.acme.com")).andReturn();
        MockHttpServletResponse res = r.getResponse();
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getHeader("HX-Redirect")).isEqualTo("/admin/tenants/" + id(5) + "?tab=webauthn");
        assertThat(res.getContentAsString()).isEmpty();
        String trigger = res.getHeader("HX-Trigger");
        assertThat(trigger).matches("\\A\\p{ASCII}*\\z"); // 헤더는 ASCII(한글은 \\uXXXX)
        tools.jackson.databind.JsonNode toast = new tools.jackson.databind.ObjectMapper().readTree(trigger).get("toast");
        assertThat(toast.get("kind").asString()).isEqualTo("success");
        assertThat(toast.get("title").asString()).isEqualTo("Tenant 생성 완료");
        assertThat(toast.get("message").asString()).isEqualTo("Acme · WebAuthn 설정으로 이동합니다");
        verify(tenants).create(eq(expected("Acme", "acme", "passkey.acme.com")), any());
    }

    @Test
    @DisplayName("입력 그대로 전달: 숫자로 시작하는 slug 허용, 한글 이름 그대로, slug/rpId 는 소문자·rpId trim 정규화")
    void createVerbatimAndNormalized() throws Exception {
        when(tenants.create(any(), any())).thenReturn(ApiResponse.ok(tv(6, "크로스서트", "123abc", "ACTIVE")));
        mvc.perform(createReq("크로스서트", "123abc", "passkey.crosscert.com")).andReturn();
        verify(tenants).create(eq(expected("크로스서트", "123abc", "passkey.crosscert.com")), any());

        reset(tenants);
        when(tenants.create(any(), any())).thenReturn(ApiResponse.ok(tv(6, "Acme", "acme", "ACTIVE")));
        mvc.perform(createReq("Acme", "ACME", "  Passkey.Acme.COM ")).andReturn();
        verify(tenants).create(eq(expected("Acme", "acme", "passkey.acme.com")), any());
    }

    @Test
    @DisplayName("비 htmx 제출(JS 없음)은 302 로 새 tenant 의 WebAuthn 탭")
    void createNonHtmxRedirects() throws Exception {
        when(tenants.create(any(), any())).thenReturn(ApiResponse.ok(tv(5, "Acme", "acme", "ACTIVE")));
        MvcResult r = mvc.perform(post("/admin/_/tenants").with(authentication(platform())).with(csrf())
                .param("name", "Acme").param("slug", "acme").param("rpId", "passkey.acme.com")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(302);
        assertThat(r.getResponse().getRedirectedUrl()).isEqualTo("/admin/tenants/" + id(5) + "?tab=webauthn");
    }

    @Test
    @DisplayName("slug 형식 오류: 422 + 폼 조각(다이얼로그 안), 입력값 유지, REST 호출 없음")
    void createInvalidSlug() throws Exception {
        for (String bad : List.of("a_b", "-abc", "a", "a b", "한글")) {
            reset(tenants);
            MvcResult r = mvc.perform(createReq("Acme", bad, "passkey.acme.com")).andReturn();
            assertThat(r.getResponse().getStatus()).as(bad).isEqualTo(422);
            Document d = Jsoup.parse(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
            assertThat(d.select("#nt-slug-error").text()).as(bad).isEqualTo("영문 소문자·숫자로 시작하고, 영문 소문자·숫자·하이픈(-)만 사용해 2~63자로 입력하세요.");
            assertThat(d.select("#nt-slug").attr("aria-invalid")).isEqualTo("true");
            assertThat(d.select("#nt-slug").attr("aria-describedby")).isEqualTo("nt-slug-error");
            assertThat(d.select("#nt-name").val()).isEqualTo("Acme");
            assertThat(d.select("#nt-rpid").val()).isEqualTo("passkey.acme.com");
            assertThat(r.getResponse().getContentAsString()).doesNotContain("<form").doesNotContain("<html"); // 안쪽 조각만(htmx 가 #nt-form 안쪽을 교체)
            verify(tenants, never()).create(any(), any());
        }
    }

    @Test
    @DisplayName("RP ID 오류: placeholder 도메인(example.com), 스킴·포트·경로, 점 없는 호스트, 빈 값")
    void createInvalidRpId() throws Exception {
        for (String bad : List.of("passkey.example.com", "https://a.acme.com", "a.acme.com:8443", "a.acme.com/x", "localhost", "")) {
            MvcResult r = mvc.perform(createReq("Acme", "acme", bad)).andReturn();
            assertThat(r.getResponse().getStatus()).as(bad).isEqualTo(422);
            Document d = Jsoup.parse(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
            assertThat(d.select("#nt-rpid-error").text()).as(bad).contains("example.com은 placeholder라 사용할 수 없습니다")
                    .contains("스킴·포트·경로 없이 실제 도메인 hostname을 입력하세요(예: passkey.acme.com).");
            assertThat(d.select("#nt-slug-error")).isEmpty();
            // 오류가 있으면 RP ID 잠금 안내는 오류로 대체(Field: error 가 help 를 대신)
            assertThat(d.text()).doesNotContain("생성 후에는 변경할 수 없습니다");
        }
        // example.com 자체가 아닌 비슷한 이름은 허용(원본 정규식과 같다)
        when(tenants.create(any(), any())).thenReturn(ApiResponse.ok(tv(5, "Acme", "acme", "ACTIVE")));
        assertThat(mvc.perform(createReq("Acme", "acme", "passkey.notexample.com")).andReturn().getResponse().getStatus()).isEqualTo(200);
        verify(tenants, never()).create(eq(expected("Acme", "acme", "passkey.example.com")), any());
    }

    @Test
    @DisplayName("이름이 비었거나 공백뿐이면 이름 오류, 256자 초과는 길이 오류(Bean Validation), 입력값 유지")
    void createInvalidName() throws Exception {
        for (String bad : List.of("", "   ")) {
            MvcResult r = mvc.perform(createReq(bad, "acme", "passkey.acme.com")).andReturn();
            assertThat(r.getResponse().getStatus()).isEqualTo(422);
            Document d = Jsoup.parse(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
            assertThat(d.select("#nt-name-error").text()).isEqualTo("표시 이름을 입력하세요.");
            assertThat(d.select("#nt-slug").val()).isEqualTo("acme");
        }
        MvcResult r = mvc.perform(createReq("x".repeat(257), "acme", "passkey.acme.com")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
        assertThat(Jsoup.parse(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).select("#nt-name-error").text()).isEqualTo("표시 이름은 256자 이하로 입력하세요.");
        verify(tenants, never()).create(any(), any());
    }

    @Test
    @DisplayName("서버 거절(5xx): 422 + 다이얼로그 안 경고 'Tenant를 만들지 못했습니다', HTTP 상태, 입력값 유지, 오류 토스트로 중복하지 않음")
    void createServerFailureInline() throws Exception {
        when(tenants.create(any(), any())).thenThrow(new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR));
        MvcResult r = mvc.perform(createReq("Acme", "acme", "passkey.acme.com")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
        assertThat(r.getResponse().getHeader("HX-Trigger")).isNull();
        assertThat(r.getResponse().getHeader("HX-Redirect")).isNull();
        Document d = Jsoup.parse(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
        Element alert = d.select("[role=alert]").first();
        assertThat(d.select("[role=alert]")).hasSize(1);
        assertThat(alert.select(".banner__title").text()).isEqualTo("Tenant를 만들지 못했습니다");
        assertThat(alert.text()).contains("서버가 잠시 응답하지 않습니다").contains("입력한 내용은 그대로 있습니다");
        assertThat(alert.select(".mono").text()).startsWith("HTTP 500");
        assertThat(d.select("#nt-name").val()).isEqualTo("Acme");
        assertThat(d.select("#nt-slug").val()).isEqualTo("acme");
        assertThat(d.select("#nt-rpid").val()).isEqualTo("passkey.acme.com");
        assertThat(d.select("#nt-slug-error, #nt-rpid-error, #nt-name-error")).isEmpty();
    }

    @Test
    @DisplayName("중복 slug(409)도 같은 인라인 경고. 서버 원문은 경고 본문에, HTTP 409 는 메타에")
    void createDuplicateSlug() throws Exception {
        when(tenants.create(any(), any())).thenThrow(new BusinessException(ErrorCode.TENANT_DUPLICATE));
        MvcResult r = mvc.perform(createReq("Acme", "acme", "passkey.acme.com")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
        Document d = Jsoup.parse(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(d.select("[role=alert]").text()).contains("요청을 처리하지 못했습니다").contains("Tenant code already exists");
        assertThat(d.select("[role=alert] .mono").text()).startsWith("HTTP 409");
    }

    @Test
    @DisplayName("CSRF 토큰 없는 생성은 거부")
    void createRequiresCsrf() throws Exception {
        MvcResult r = mvc.perform(post("/admin/_/tenants").with(authentication(platform())).header("HX-Request", "true")
                .param("name", "Acme").param("slug", "acme").param("rpId", "passkey.acme.com")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        verify(tenants, never()).create(any(), any());
    }

    // ── CSV ────────────────────────────────────────────────

    @Test
    @DisplayName("CSV: 필터 적용 행 전체(페이지 무시), 열·BOM·파일명(KST 오늘), 수식 주입 방지")
    void csvExport() throws Exception {
        manyTenants(0);
        TenantAdminDto.TenantView a = tv(1, "=SUM(1)", "evil", "ACTIVE");
        TenantAdminDto.TenantView b = tv(2, "Beta, Inc", "beta", "SUSPENDED");
        TenantAdminDto.TenantView c = tv(3, "Gamma", "gamma", "ACTIVE");
        tenants(a, b, c);
        MvcResult r = mvc.perform(get(URI.create("/admin/_/tenants/export?status=ACTIVE")).with(authentication(platform()))).andReturn();
        MockHttpServletResponse res = r.getResponse();
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getContentType()).startsWith("text/csv");
        assertThat(res.getHeader(HttpHeaders.CONTENT_DISPOSITION)).isEqualTo("attachment; filename=\"tenants-2026-10-11.csv\"");
        String body = new String(res.getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(body).startsWith("﻿name,slug,rpId,status,credentials,apiKeys,createdAt\n");
        assertThat(body.split("\n")).hasSize(3);
        assertThat(body).contains("'=SUM(1),evil,t1.example.org,ACTIVE,10,2,2026-10-01T00:00:00Z")
                .contains("Gamma,gamma,t3.example.org,ACTIVE,10,2,2026-10-01T00:00:00Z").doesNotContain("Beta");

        MvcResult q = mvc.perform(get(URI.create("/admin/_/tenants/export?q=beta")).with(authentication(platform()))).andReturn();
        String qb = new String(q.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(qb).contains("\"Beta, Inc\",beta,t2.example.org,SUSPENDED,10,2,");
        assertThat(qb.split("\n")).hasSize(2);
    }

    @Test
    @DisplayName("CSV 링크는 현재 필터를 싣고 boost·htmx 를 거치지 않는다")
    void csvLink() throws Exception {
        tenants(tv(1));
        Element csv = page("/admin/tenants?q=a%26b&status=ACTIVE").select("#tenants-controls a.btn").first();
        assertThat(csv.text()).isEqualTo("CSV");
        assertThat(csv.attr("href")).isEqualTo("/admin/_/tenants/export?q=a%26b&status=ACTIVE");
        assertThat(csv.attr("hx-boost")).isEqualTo("false");
        assertThat(csv.hasAttr("download")).isTrue();
        assertThat(csv.hasAttr("data-csv-download")).isTrue();
    }

    // ── 리뷰 수정 1 ────────────────────────────────────────

    @Test
    @DisplayName("상태·KPI·페이지 요청은 검색어를 주소에 굽지 않고 현재 입력값(#tenant-q)을 싣는다")
    void statusRequestsCarryCurrentQuery() throws Exception {
        manyTenants(60);
        Document d = page("/admin/tenants?q=ten");
        for (Element b : d.select("#tenants-controls .seg__btn")) {
            assertThat(b.attr("hx-get")).doesNotContain("q=");
            assertThat(b.attr("hx-include")).isEqualTo("#tenant-q");
        }
        assertThat(d.select("#tenants-kpi-active").attr("hx-include")).isEqualTo("#tenant-q");
        assertThat(d.select("button[aria-label=다음 페이지]").attr("hx-include")).isEqualTo("#tenant-q");
        assertThat(d.select("button[aria-label=상태 필터 제거]").size()).isEqualTo(0);
        // 칩 제거(상태)도 입력값을 싣고, 검색어 지우기는 싣지 않는다
        Document f = page("/admin/tenants?q=ten&status=ACTIVE");
        assertThat(f.select("button[aria-label=상태 필터 제거]").attr("hx-include")).isEqualTo("#tenant-q");
        assertThat(f.select("button[aria-label=검색어 제거]").hasAttr("hx-include")).isFalse();
    }

    @Test
    @DisplayName("상태 요청이 현재 q 를 실어 오면 그 q 로 필터하고 검색 입력은 OOB 로 덮어쓰지 않는다. 지우기(reset=q)만 되돌려 쓴다")
    void statusWithCurrentQueryDoesNotOverwriteInput() throws Exception {
        tenants(tv(1, "Alpha", "alpha", "ACTIVE"), tv(2, "Alpine", "alpine", "SUSPENDED"), tv(3, "Beta", "beta", "ACTIVE"));
        MvcResult r = fragment("/admin/_/tenants/list?status=ACTIVE&size=50&q=alp");
        Document d = doc(r);
        assertThat(rowNames(d)).containsExactly("Alpha");
        assertThat(d.select("#tenant-q")).isEmpty();
        assertThat(r.getResponse().getHeader("HX-Push-Url")).isEqualTo("/admin/tenants?q=alp&status=ACTIVE");
        Document cleared = doc(fragment("/admin/_/tenants/list?status=ACTIVE&size=50&reset=q"));
        assertThat(cleared.select("#tenant-q[hx-swap-oob=true]").val()).isEmpty();
        assertThat(rowNames(cleared)).containsExactly("Alpha", "Beta");
    }

    @Test
    @DisplayName("디바운스된 검색 요청은 HX-Replace-Url, 그 밖은 HX-Push-Url")
    void searchReplacesHistory() throws Exception {
        tenants(tv(1));
        MvcResult r = mvc.perform(get(URI.create("/admin/_/tenants/list?q=te")).with(authentication(platform()))
                .header("HX-Request", "true").header("HX-Trigger", "tenant-q")).andReturn();
        assertThat(r.getResponse().getHeader("HX-Replace-Url")).isEqualTo("/admin/tenants?q=te");
        assertThat(r.getResponse().getHeader("HX-Push-Url")).isNull();
        assertThat(fragment("/admin/_/tenants/list?q=te").getResponse().getHeader("HX-Replace-Url")).isNull();
    }

    // ── 수정 2·3·4·7·8 ─────────────────────────────────────

    @Test
    @DisplayName("생성 폼은 네트워크 오류·시간 초과를 인라인 경고로(전역 토스트 data-quiet), 성공 이동 전에는 busy 를 풀지 않는다, 422 뒤 첫 오류 칸 포커스")
    void dialogFormHandlesNetworkErrorsLocally() throws Exception {
        tenants(tv(1));
        Document d = page("/admin/tenants");
        Document inner = Jsoup.parse(d.select("[x-data*=\"new-tenant\"] template").first().html());
        Element form = inner.select("form#nt-form").first();
        assertThat(form.hasAttr("data-quiet")).isTrue();
        assertThat(form.attr("x-on:htmx:send-error")).contains("netErr").contains("네트워크에 연결할 수 없습니다").contains("입력한 내용은 그대로 있습니다");
        assertThat(form.attr("x-on:htmx:timeout")).contains("서버가 잠시 응답하지 않습니다").contains("입력한 내용은 그대로 있습니다");
        assertThat(form.attr("x-on:htmx:before-request")).contains("busy = true").contains("netErr = null");
        // 성공(HX-Redirect)은 htmx 가 successful 을 정하기 전에 이동을 시작한다 — 응답 헤더로 성공을 알아보고 busy 를 유지(이동 중 다시 제출 방지)
        assertThat(form.attr("x-on:htmx:after-request"))
                .isEqualTo("if (!$event.detail.successful && !($event.detail.xhr && $event.detail.xhr.getResponseHeader('HX-Redirect'))) busy = false");
        assertThat(form.attr("x-on:htmx:after-swap")).contains("aria-invalid=true").contains("focus()");
        Element alert = inner.select("[x-show=netErr]").first();
        assertThat(alert.select(".banner__title").text()).isEqualTo("Tenant를 만들지 못했습니다");
        assertThat(inner.select("[autofocus]")).isEmpty();
        MvcResult bad = mvc.perform(createReq("Acme", "a_b", "passkey.acme.com")).andReturn();
        assertThat(bad.getResponse().getContentAsString()).doesNotContain("autofocus");
    }

    @Test
    @DisplayName("빈 목록의 활성 KPI 는 클릭형이 아니다(가리킬 영역 없음)")
    void emptyListKpiNotClickable() throws Exception {
        when(tenants.list()).thenReturn(ApiResponse.ok(List.of()));
        Document d = page("/admin/tenants");
        assertThat(d.select(".kpis button, #tenants-kpi-active, .kpis [hx-get]")).isEmpty();
    }

    @Test
    @DisplayName("숫자가 아닌 page·size 는 400 이 아니라 기본값")
    void nonNumericParams() throws Exception {
        manyTenants(60);
        assertThat(rowNames(page("/admin/tenants?page=abc&size=x"))).hasSize(50).contains("Tenant n1");
        assertThat(doc(fragment("/admin/_/tenants/list?page=&size=9999999999")).select("#tenants-results .pager > span").first().text()).isEqualTo("1–50 / 60건");
    }

    @Test
    @DisplayName("목록 조회가 실패한 화면에서도 ?new=1 은 주소에서 지운다")
    void newQueryCleanedOnLoadFailure() throws Exception {
        when(tenants.list()).thenThrow(new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR));
        Document d = page("/admin/tenants?new=1&q=a");
        assertThat(d.select("span[data-clean-url]").attr("data-clean-url")).isEqualTo("/admin/tenants?q=a");
        assertThat(d.select("[x-data*=\"new-tenant\"]").attr("x-data")).contains("\"open\":true");
    }
}
