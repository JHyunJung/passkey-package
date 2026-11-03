package com.crosscert.passkey.admin.web.tenant;

import com.crosscert.passkey.admin.auth.ActiveTenantController;
import com.crosscert.passkey.admin.auth.AdminUserDetails;
import com.crosscert.passkey.admin.audit.AuditChainMonitorController;
import com.crosscert.passkey.admin.funnel.FunnelController;
import com.crosscert.passkey.admin.funnel.FunnelDto;
import com.crosscert.passkey.admin.license.LicenseController;
import com.crosscert.passkey.admin.license.LicenseView;
import com.crosscert.passkey.admin.policy.AaguidPolicyController;
import com.crosscert.passkey.admin.tenant.TenantAdminController;
import com.crosscert.passkey.admin.tenant.TenantAdminDto;
import com.crosscert.passkey.admin.web.AdminWebSliceTest;
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
import org.springframework.boot.test.mock.mockito.MockBean;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** 퍼널 탭. 원본: FunnelTab.test.tsx, funnelHelpers.test.ts(→ FunnelHelpersTest), api/funnel.ts. 브라우저 동작(표로 보기 토글)은 마크업까지만 본다. */
@AdminWebSliceTest(controllers = FunnelTabController.class)
class FunnelTabControllerTest {

    @Autowired MockMvc mvc;
    @Autowired Clock clock;
    @MockBean TenantAdminController tenants;
    @MockBean FunnelController funnel;
    @MockBean AaguidPolicyController aaguid;
    @MockBean LicenseController license;
    @MockBean AuditChainMonitorController chain;
    @MockBean ActiveTenantController activeTenants;

    static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");
    static final UUID T1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
    static final String BASE = "/admin/_/tenants/" + T1;

    static List<FunnelDto.DailyPoint> days(int n, java.util.function.IntToLongFunction success) {
        List<FunnelDto.DailyPoint> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new FunnelDto.DailyPoint(String.format("10-%02d", i + 1), 10, success.applyAsLong(i)));
        return out;
    }

    static FunnelDto.View data(int w, List<FunnelDto.DailyPoint> series) {
        return new FunnelDto.View(w, new FunnelDto.Stage(100, 80, 0.8), new FunnelDto.Stage(70, 63, 0.9), 0.63, series,
                List.of(new FunnelDto.EventCount("AUTH_SUCCESS", 63), new FunnelDto.EventCount("AUTH_FAIL", 7)));
    }

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(license.get()).thenReturn(ApiResponse.ok(new LicenseView("saas", "VALID", "acme", "lic_1", NOW.plusSeconds(86400L * 30), 30, List.of(), null, null, null)));
        when(tenants.get(T1.toString())).thenReturn(ApiResponse.ok(tenant()));
        when(funnel.get(eq(T1), eq(30))).thenReturn(data(30, days(30, i -> i >= 23 ? 8 : 5)));
        when(funnel.get(eq(T1), eq(7))).thenReturn(data(7, days(7, i -> 9)));
        when(funnel.get(eq(T1), eq(1))).thenReturn(data(1, days(1, i -> 9)));
    }

    static Authentication platform() { return auth("PLATFORM_OPERATOR", Set.of()); }
    static Authentication rp(UUID... ids) { return auth("RP_ADMIN", Set.of(ids)); }

    private static Authentication auth(String role, Set<UUID> tenants) {
        AdminUserDetails p = new AdminUserDetails(UUID.randomUUID(), "alice@example.com", "x", role, tenants, true, null, Clock.systemUTC());
        return new UsernamePasswordAuthenticationToken(p, "x", p.getAuthorities());
    }

    static TenantAdminDto.TenantView tenant() {
        return new TenantAdminDto.TenantView(T1, "acme", "Acme", "ACTIVE", "passkey.acme.com", "Acme",
                List.of("https://passkey.acme.com"), Set.of("none"), true, false, "NONE", 60000, 3, 1, null,
                OffsetDateTime.parse("2026-03-01T16:00:00Z"), OffsetDateTime.parse("2026-03-01T16:00:00Z"));
    }

    private static MockHttpServletRequestBuilder htmx(MockHttpServletRequestBuilder b, Authentication a) {
        return b.with(authentication(a)).with(csrf()).header("HX-Request", "true");
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private MvcResult hget(String url, Authentication a) throws Exception {
        return mvc.perform(htmx(get(URI.create(url)), a)).andReturn();
    }

    private Document tab(String q) throws Exception {
        MvcResult r = hget(BASE + "/tab/funnel" + q, platform());
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return Jsoup.parse(body(r));
    }

    // ── 요약 ────────────────────────────────────────────────

    @Test
    @DisplayName("funnelPercentagesMatchHelper — 7d: 전체 전환율·직전 7일 대비(30일 series)·최대 이탈 단계·기간, 단계 사이 전환율 칩, 단계 값·비율")
    void funnelPercentagesMatchHelper() throws Exception {
        MvcResult r = hget(BASE + "/tab/funnel", platform());
        assertThat(body(r)).doesNotContain("<html").doesNotContain("class=\"shell");
        Document d = Jsoup.parse(body(r));
        verify(funnel).get(T1, 7);
        verify(funnel).get(T1, 30);
        assertThat(d.select(".kpi .kpi__value").eachText()).containsExactly("63.0%", "등록 시도 → 등록 성공", "최근 7일");
        assertThat(d.select(".kpi .kpi__hint").eachText()).containsExactly("직전 7일 대비 +30.0%p (인증 성공률)", "이탈 20건 · 20.0%", "2026-10-05 – 2026-10-11");
        assertThat(d.select(".kpi__label").first().text()).isEqualTo("전체 전환율 (등록 시도 → 인증 성공)");
        assertThat(d.select(".fn-between .tag").eachText()).containsExactly("80.0%", "87.5%", "90.0%");
        assertThat(d.select(".fn-step strong").eachText()).containsExactly("등록 시도", "등록 성공", "인증 시도", "인증 성공");
        assertThat(d.select(".fn-step .hint").eachText()).containsExactly("100건 · 100%", "80건 · 80%", "이탈 20건", "70건 · 70%", "이탈 10건", "63건 · 63%", "이탈 7건");
    }

    @Test
    @DisplayName("막대: 높이는 값/최댓값 퍼센트 숫자만 th:style 에, 색은 클래스(fn-s0..3). 0/0 이면 높이 0")
    void barsUseNumbersOnly() throws Exception {
        Document d = tab("");
        List<String> styles = d.select(".fn-track .fn-bar").eachAttr("style");
        assertThat(styles).containsExactly("height: 100.0%", "height: 80.0%", "height: 70.0%", "height: 63.0%");
        assertThat(d.select(".fn-track .fn-bar").eachAttr("class")).containsExactly("fn-bar fn-s0", "fn-bar fn-s1", "fn-bar fn-s2", "fn-bar fn-s3");
        // style 속성에는 숫자 + % 만
        for (Element e : d.select("[style*=height], [style*=width]")) {
            if (e.hasClass("fn-bar") || e.parent().hasClass("fn-day") || e.parent().hasClass("fn-dist")) assertThat(e.attr("style")).matches("(height|width): [0-9.]+%");
        }
        when(funnel.get(eq(T1), eq(7))).thenReturn(new FunnelDto.View(7, new FunnelDto.Stage(0, 0, 0), new FunnelDto.Stage(0, 0, 0), 0, List.of(), List.of()));
        Document z = tab("?win=7");
        assertThat(z.select(".fn-track .fn-bar").eachAttr("style")).containsOnly("height: 0.0%");
        assertThat(z.select(".kpi .kpi__value").first().text()).isEqualTo("—");
        assertThat(z.select(".kpi .kpi__hint").get(1).text()).isEqualTo("이탈 없음");
        assertThat(z.select(".fn-between .tag").eachText()).containsOnly("—");
    }

    @Test
    @DisplayName("24h·30d 에서는 직전 기간 대비를 계산·표시하지 않고 30일 비교 조회도 하지 않는다")
    void noComparisonOutside7d() throws Exception {
        Document d24 = tab("?win=1");
        assertThat(d24.select(".kpi__hint").eachText()).noneMatch(t -> t.contains("직전 7일 대비"));
        assertThat(d24.select(".kpi .kpi__value").get(2).text()).isEqualTo("최근 24시간");
        assertThat(d24.select(".kpi__hint").get(1).text()).isEqualTo("2026-10-10 – 2026-10-11"); // 24h: 하루 전부터
        verify(funnel, never()).get(T1, 30);

        Document d30 = tab("?win=30");
        assertThat(d30.select(".kpi__hint").eachText()).noneMatch(t -> t.contains("직전 7일 대비"));
        assertThat(d30.select(".kpi .kpi__value").get(2).text()).isEqualTo("최근 30일");
        verify(funnel, times(1)).get(T1, 30); // 본 조회 1회(비교 조회 없음)
        // 알 수 없는 기간은 7일
        assertThat(tab("?win=999").select(".kpi .kpi__value").get(2).text()).isEqualTo("최근 7일");
    }

    @Test
    @DisplayName("30일 비교 조회가 실패해도 비교 줄만 생략하고 나머지는 그린다")
    void comparisonFailureOmitsLineOnly() throws Exception {
        when(funnel.get(eq(T1), eq(30))).thenThrow(new IllegalStateException("boom"));
        Document d = tab("");
        assertThat(d.select(".kpi .kpi__value").first().text()).isEqualTo("63.0%");
        assertThat(d.select(".kpi__hint").first().text()).isEqualTo("이탈 20건 · 20.0%");
    }

    @Test
    @DisplayName("기간 세그먼트: 24h·7d·30d, 선택 상태, 주소는 #funnel-root(this) 교체 — #tenant-tab 아님")
    void windowSegment() throws Exception {
        Document d = tab("?win=30");
        Element seg = d.selectFirst("#funnel-root .seg");
        assertThat(seg.attr("aria-label")).isEqualTo("기간");
        assertThat(seg.select("button").eachText()).containsExactly("24h", "7d", "30d");
        assertThat(seg.select("button").eachAttr("hx-get")).containsExactly(BASE + "/tab/funnel?win=1", BASE + "/tab/funnel?win=7", BASE + "/tab/funnel?win=30");
        assertThat(seg.select("button").get(2).attr("aria-pressed")).isEqualTo("true");
        Element root = d.selectFirst("#funnel-root");
        assertThat(root.attr("hx-target")).isEqualTo("this");
        assertThat(root.attr("hx-swap")).isEqualTo("outerHTML");
        assertThat(d.select("[hx-target=#tenant-tab]")).isEmpty();
        assertThat(d.select("[hx-trigger]").eachAttr("hx-trigger")).noneMatch(t -> t.contains("every"));
    }

    // ── 차트·표 ─────────────────────────────────────────────

    @Test
    @DisplayName("일별 시리즈: 날짜별 막대 두 개(시도·성공 높이 숫자), 축 라벨 3개, 표로 보기에 같은 데이터(헤더+7행), CSV 링크")
    void seriesCard() throws Exception {
        Document d = tab("");
        Element card = d.select("section.card").stream().filter(c -> c.select(".card__title").text().equals("일별 인증 시도 vs 성공")).findFirst().orElseThrow();
        assertThat(card.attr("x-data")).isEqualTo("{ asTable: false }");
        assertThat(card.select(".fn-day")).hasSize(7);
        assertThat(card.select(".fn-day").first().attr("title")).isEqualTo("10-01 · 시도 10 · 성공 9");
        assertThat(card.select(".fn-day").first().select("span").eachAttr("style")).containsExactly("height: 100.0%", "height: 90.0%");
        assertThat(card.selectFirst("[role=img]").attr("aria-label")).isEqualTo("일별 인증 시도와 성공 막대 차트, 7일");
        assertThat(card.select("div.row.hint > span").eachText()).containsExactly("10-01", "10-04", "오늘");
        assertThat(card.select("table th").eachText()).containsExactly("날짜", "시도", "성공", "성공률");
        assertThat(card.select("table tbody tr")).hasSize(7);
        assertThat(card.select("table tbody tr").first().select("td").eachText()).containsExactly("10-01", "10", "9", "90.0%");
        Element toggle = card.select("button").stream().filter(b -> b.text().equals("표로 보기")).findFirst().orElseThrow();
        assertThat(toggle.attr("x-bind:aria-pressed")).isEqualTo("asTable");
        assertThat(card.select("a[download]").attr("href")).isEqualTo("/admin/tenants/" + T1 + "/funnel.csv?win=7&kind=series");
    }

    @Test
    @DisplayName("이벤트 분포: 막대 너비는 건수/합계 퍼센트 숫자, FAIL 은 fn-bad, 나머지는 순환 색, 범례는 본문 글꼴(mono 아님), 표에는 비율")
    void distributionCard() throws Exception {
        Document d = tab("");
        Element card = d.select("section.card").stream().filter(c -> c.select(".card__title").text().equals("이벤트 타입별 분포")).findFirst().orElseThrow();
        assertThat(card.select(".fn-dist > span").eachAttr("style")).containsExactly("width: 90.0%", "width: 10.0%");
        assertThat(card.select(".fn-dist > span").eachAttr("class")).containsExactly("fn-c0", "fn-bad");
        Element legend = card.select(".truncate").stream().filter(e -> e.text().equals("AUTH_SUCCESS")).findFirst().orElseThrow();
        assertThat(legend.hasClass("mono")).isFalse();
        assertThat(card.selectFirst("[role=img]").attr("aria-label")).isEqualTo("이벤트 타입 분포, 총 70건");
        assertThat(card.select("table th").eachText()).containsExactly("이벤트 타입", "건수", "비율");
        assertThat(card.select("table tbody tr").get(1).select("td").eachText()).containsExactly("AUTH_FAIL", "7", "10.0%");
        assertThat(card.select("a[download]").attr("href")).endsWith("kind=events");
    }

    @Test
    @DisplayName("이벤트 타입 이름은 이스케이프된다(th:text) — 속성 중 JS 로 평가되는 곳에는 없다")
    void escapesEventTypes() throws Exception {
        when(funnel.get(eq(T1), eq(7))).thenReturn(new FunnelDto.View(7, new FunnelDto.Stage(1, 1, 1), new FunnelDto.Stage(1, 1, 1), 1, days(7, i -> 1),
                List.of(new FunnelDto.EventCount("<img src=x onerror=alert(1)>", 3))));
        MvcResult r = hget(BASE + "/tab/funnel", platform());
        String html = body(r);
        assertThat(html).doesNotContain("<img src=x");
        Document d = Jsoup.parse(html);
        assertThat(d.select("img[src=x]")).isEmpty();
        assertThat(d.select(".fn-legend-dot ~ .truncate").text()).isEqualTo("<img src=x onerror=alert(1)>");
    }

    // ── 오류 ────────────────────────────────────────────────

    @Test
    @DisplayName("조회 실패: 지표·차트 없이 LoadError(다시 시도 = #funnel-root 재요청), 내보내기 비활성, 내부 메시지 비노출, 7d 비교 조회 없음")
    void loadFailure() throws Exception {
        when(funnel.get(eq(T1), eq(7))).thenThrow(new IllegalStateException("jdbc:secret"));
        MvcResult r = hget(BASE + "/tab/funnel", platform());
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(r)).doesNotContain("secret");
        Document d = Jsoup.parse(body(r));
        assertThat(d.select("[role=alert]").text()).contains("퍼널 데이터를 불러오지 못했습니다").contains("오류 정보 복사");
        assertThat(d.select(".kpi, .fn-steps, section.card")).isEmpty();
        assertThat(d.select("#funnel-root > .row button[disabled]").text()).isEqualTo("내보내기");
        assertThat(d.select("#funnel-root > .row a[download]")).isEmpty();
        Element retry = d.select("[role=alert] button").stream().filter(b -> b.text().equals("다시 시도")).findFirst().orElseThrow();
        assertThat(retry.attr("hx-get")).isEqualTo(BASE + "/tab/funnel?win=7");
        verify(funnel, never()).get(T1, 30);
    }

    // ── CSV ─────────────────────────────────────────────────

    private String csv(String q, String[] disposition) throws Exception {
        MvcResult r = mvc.perform(htmx(get(URI.create("/admin/tenants/" + T1 + "/funnel.csv" + q)), platform())).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        disposition[0] = r.getResponse().getHeader("Content-Disposition");
        byte[] raw = r.getResponse().getContentAsByteArray();
        assertThat(raw[0] & 0xff).isEqualTo(0xEF);
        return new String(raw, StandardCharsets.UTF_8).substring(1);
    }

    @Test
    @DisplayName("CSV 3종(원본 downloadCsv): 단계(내보내기) · 일별 시리즈 · 이벤트 분포 — 헤더·값·toFixed(4)·파일명")
    void csvExports() throws Exception {
        String[] disp = new String[1];
        String steps = csv("?win=7&kind=steps", disp);
        assertThat(disp[0]).isEqualTo("attachment; filename=\"funnel-acme-7d-2026-10-11.csv\"");
        assertThat(steps.split("\n")).containsExactly("step,count,conversionFromPrev,dropFromPrev",
                "등록 시도,100,,0", "등록 성공,80,0.8000,20", "인증 시도,70,0.8750,10", "인증 성공,63,0.9000,7");
        String series = csv("?win=7&kind=series", disp);
        assertThat(disp[0]).isEqualTo("attachment; filename=\"funnel-series-acme-2026-10-11.csv\"");
        assertThat(series.split("\n")).hasSize(8);
        assertThat(series.split("\n")[0]).isEqualTo("day,attempts,success");
        assertThat(series.split("\n")[1]).isEqualTo("10-01,10,9");
        String events = csv("?win=30&kind=events", disp);
        assertThat(disp[0]).isEqualTo("attachment; filename=\"funnel-events-acme-2026-10-11.csv\"");
        assertThat(events.split("\n")).containsExactly("type,n", "AUTH_SUCCESS,63", "AUTH_FAIL,7");
        // 탭의 링크가 같은 주소
        Document d = tab("?win=30");
        assertThat(d.select("#funnel-root > .row a[download]").attr("href")).isEqualTo("/admin/tenants/" + T1 + "/funnel.csv?win=30&kind=steps");
    }

    @Test
    @DisplayName("CSV: 이벤트 타입의 수식 문자는 무력화(' 접두)")
    void csvNeutralizesFormula() throws Exception {
        when(funnel.get(eq(T1), eq(7))).thenReturn(new FunnelDto.View(7, new FunnelDto.Stage(1, 1, 1), new FunnelDto.Stage(1, 1, 1), 1, days(1, i -> 1),
                List.of(new FunnelDto.EventCount("=HYPERLINK(\"x\")", 3))));
        String[] disp = new String[1];
        assertThat(csv("?win=7&kind=events", disp)).contains("\"'=HYPERLINK(\"\"x\"\")\",3");
    }

    // ── 경계 ────────────────────────────────────────────────

    @Test
    @DisplayName("RP_ADMIN 이 다른 tenant 의 탭·CSV → 403, 퍼널 REST 호출 없음 (TenantTabs.load 먼저)")
    void crossTenantForbidden() throws Exception {
        when(tenants.get(OTHER.toString())).thenThrow(new BusinessException(ErrorCode.ACCESS_DENIED, "RP_ADMIN cannot access tenant " + OTHER));
        assertThat(hget("/admin/_/tenants/" + OTHER + "/tab/funnel", rp(T1)).getResponse().getStatus()).isEqualTo(403);
        assertThat(hget("/admin/tenants/" + OTHER + "/funnel.csv", rp(T1)).getResponse().getStatus()).isEqualTo(403);
        verify(funnel, never()).get(any(), anyInt());
    }

    @Test
    @DisplayName("리터럴 탭 매핑이 일반 {tab} 매핑(개요)보다 우선하고, 자기 tenant 의 RP_ADMIN 은 볼 수 있다")
    void literalMappingWinsAndRpOwnTenant() throws Exception {
        MvcResult r = hget(BASE + "/tab/funnel", rp(T1));
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(Jsoup.parse(body(r)).select("#funnel-root")).hasSize(1);
    }
}
