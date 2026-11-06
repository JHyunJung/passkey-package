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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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

/** 인증 현황 탭(URL 키 funnel). 브라우저 동작(표로 보기 토글)은 마크업까지만 본다. */
@AdminWebSliceTest(controllers = FunnelTabController.class)
class FunnelTabControllerTest {

    @Autowired MockMvc mvc;
    @Autowired Clock clock;
    @MockitoBean TenantAdminController tenants;
    @MockitoBean FunnelController funnel;
    @MockitoBean com.crosscert.passkey.admin.funnel.AdoptionController adoption;
    @MockitoBean AaguidPolicyController aaguid;
    @MockitoBean LicenseController license;
    @MockitoBean AuditChainMonitorController chain;
    @MockitoBean ActiveTenantController activeTenants;

    static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");
    static final UUID T1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
    static final String BASE = "/admin/_/tenants/" + T1;
    /** 인증 품질 지표 줄(도입·사용 지표 줄 제외). */
    static final String Q = "div.kpis:not([data-adoption]) ";

    static List<FunnelDto.DailyPoint> days(int n, java.util.function.IntToLongFunction success) {
        return days(n, i -> 10, success);
    }

    static List<FunnelDto.DailyPoint> days(int n, java.util.function.IntToLongFunction attempts, java.util.function.IntToLongFunction success) {
        List<FunnelDto.DailyPoint> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new FunnelDto.DailyPoint(String.format("10-%02d", i + 1), attempts.applyAsLong(i), success.applyAsLong(i)));
        return out;
    }

    static final List<FunnelDto.FailureCount> FAILS = List.of(
            new FunnelDto.FailureCount("SIGNATURE_INVALID", 5), new FunnelDto.FailureCount("SIGN_COUNT_REPLAY", 2));

    /** 등록 100→80, 인증 70→63. 일별: 인증은 시도 10, 등록은 시도 4. */
    static FunnelDto.View data(int w, List<FunnelDto.DailyPoint> reg, List<FunnelDto.DailyPoint> auth, List<FunnelDto.FailureCount> fails) {
        return new FunnelDto.View(w, new FunnelDto.Stage(100, 80, 0.8), new FunnelDto.Stage(70, 63, 0.9), 0.84, List.of(), List.of(), reg, auth, fails);
    }

    static FunnelDto.View data(int w, List<FunnelDto.DailyPoint> auth) {
        return data(w, days(w, i -> 4, i -> 3), auth, FAILS);
    }

    static com.crosscert.passkey.admin.funnel.AdoptionDto.View adoptionData(int w) {
        return new com.crosscert.passkey.admin.funnel.AdoptionDto.View(w, 20, 3, 12, 9, 15, 10,
                List.of(new com.crosscert.passkey.admin.funnel.AdoptionDto.Month("2026-08", false, 300, 40, 5),
                        new com.crosscert.passkey.admin.funnel.AdoptionDto.Month("2026-09", false, 1234, 52, 7),
                        new com.crosscert.passkey.admin.funnel.AdoptionDto.Month("2026-10", true, 90, 9, 3)),
                List.of(new com.crosscert.passkey.admin.funnel.AdoptionDto.Device("iCloud 키체인", 15, 40),
                        new com.crosscert.passkey.admin.funnel.AdoptionDto.Device("<b>x</b>", 5, 2)));
    }

    @BeforeEach
    void setUp() {
        when(adoption.get(eq(T1), anyInt())).thenAnswer(inv -> adoptionData(inv.getArgument(1)));
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(license.get()).thenReturn(ApiResponse.ok(new LicenseView("saas", "VALID", "acme", "lic_1", NOW.plusSeconds(86400L * 30), 30, List.of(), null, null, null)));
        when(tenants.get(T1.toString())).thenReturn(ApiResponse.ok(tenant()));
        // 30일: 등록은 최근 7일 80% vs 그 앞 7일 50%(+30.0%p), 인증은 90% 그대로(±0.0%p)
        when(funnel.get(eq(T1), eq(30))).thenReturn(data(30, days(30, i -> 10, i -> i >= 23 ? 8 : 5), days(30, i -> 9), FAILS));
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
    @DisplayName("7d 지표: 등록·인증 성공률을 따로(성공/시도, 직전 7일 대비는 각자의 30일 series), 인증 실패 건수와 원인, 기간 — '전환' 표현 없음")
    void summaryKpis() throws Exception {
        MvcResult r = hget(BASE + "/tab/funnel", platform());
        assertThat(body(r)).doesNotContain("<html").doesNotContain("class=\"shell");
        Document d = Jsoup.parse(body(r));
        assertThat(d.text()).doesNotContain("전환율").doesNotContain("Funnel").doesNotContain("이탈");
        verify(funnel).get(T1, 7);
        verify(funnel).get(T1, 30);
        assertThat(d.select(Q + ".kpi__label").eachText()).containsExactly("등록 성공률", "인증 성공률", "차단된 위험 인증", "기간");
        assertThat(d.select(Q + ".kpi__value").eachText()).containsExactly("80.0%", "90.0%", "7건", "최근 7일");
        assertThat(d.select(Q + ".kpi__hint").eachText()).containsExactly(
                "성공 80 / 시도 100 · 직전 7일 대비 +30.0%p",
                "성공 63 / 시도 70 · 직전 7일 대비 ±0.0%p",
                "서명 검증 실패 5 · 서명 카운터 재사용 2",
                "2026-10-05 – 2026-10-11");
        assertThat(d.select(Q + ".kpi__hint").get(2).hasClass("kpi__hint--warn")).isTrue();
        assertThat(d.select("#funnel-root p.hint").text()).contains("사용자 취소와 시간 초과");
    }

    @Test
    @DisplayName("시도 0·실패 0: 성공률은 '—' + '시도 없음', 실패는 '0건' + '실패 없음'(경고 아님), 실패 원인 카드는 빈 문구만")
    void emptyKpis() throws Exception {
        when(funnel.get(eq(T1), eq(7))).thenReturn(new FunnelDto.View(7, new FunnelDto.Stage(0, 0, 0), new FunnelDto.Stage(0, 0, 0), 0,
                List.of(), List.of(), days(7, i -> 0, i -> 0), days(7, i -> 0, i -> 0), List.of()));
        Document d = tab("");
        assertThat(d.select(Q + ".kpi__value").eachText()).containsExactly("—", "—", "0건", "최근 7일");
        assertThat(d.select(Q + ".kpi__hint").eachText()).startsWith("시도 없음", "시도 없음", "실패 없음");
        assertThat(d.select(Q + ".kpi__hint").get(2).hasClass("kpi__hint--warn")).isFalse();
        Element card = d.selectFirst("section[data-failures]");
        assertThat(card.text()).contains("기간 안에 인증 실패가 없습니다.");
        assertThat(card.select("table, a[download]")).isEmpty();
        // 시도 0 인 날의 막대 높이는 0, 성공률은 —
        assertThat(d.select("section[data-series=auth] .fn-day > span").eachAttr("style")).containsOnly("height: 0.0%");
        assertThat(d.select("section[data-series=auth] table tbody tr").first().select("td").eachText()).containsExactly("10-01", "0", "0", "—");
    }

    @Test
    @DisplayName("24h·30d 에서는 직전 기간 대비를 계산·표시하지 않고 30일 비교 조회도 하지 않는다")
    void noComparisonOutside7d() throws Exception {
        Document d24 = tab("?win=1");
        assertThat(d24.select(Q + ".kpi__hint").eachText()).noneMatch(t -> t.contains("직전 7일 대비"));
        assertThat(d24.select(Q + ".kpi__value").get(3).text()).isEqualTo("최근 24시간");
        assertThat(d24.select(Q + ".kpi__hint").get(3).text()).isEqualTo("2026-10-10 – 2026-10-11"); // 24h: 하루 전부터
        verify(funnel, never()).get(T1, 30);

        Document d30 = tab("?win=30");
        assertThat(d30.select(Q + ".kpi__hint").eachText()).noneMatch(t -> t.contains("직전 7일 대비"));
        assertThat(d30.select(Q + ".kpi__value").get(3).text()).isEqualTo("최근 30일");
        verify(funnel, times(1)).get(T1, 30); // 본 조회 1회(비교 조회 없음)
        // 알 수 없는 기간은 7일
        assertThat(tab("?win=999").select(Q + ".kpi__value").get(3).text()).isEqualTo("최근 7일");
    }

    @Test
    @DisplayName("30일 비교 조회가 실패해도 비교 문구만 생략하고 나머지는 그린다")
    void comparisonFailureOmitsLineOnly() throws Exception {
        when(funnel.get(eq(T1), eq(30))).thenThrow(new IllegalStateException("boom"));
        Document d = tab("");
        assertThat(d.select(Q + ".kpi__value").first().text()).isEqualTo("80.0%");
        assertThat(d.select(Q + ".kpi__hint").first().text()).isEqualTo("성공 80 / 시도 100");
        assertThat(d.select(Q + ".kpi__hint").get(1).text()).isEqualTo("성공 63 / 시도 70");
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
    @DisplayName("일별 카드 2장(인증·등록 따로): 날짜별 막대 두 개(시도·성공 높이 숫자), 축 라벨 3개, 표로 보기(헤더+7행), CSV 링크")
    void seriesCards() throws Exception {
        Document d = tab("");
        assertThat(d.select("section[data-series] .card__title").eachText()).containsExactly("일별 인증 시도와 성공", "일별 등록 시도와 성공");

        Element auth = d.selectFirst("section[data-series=auth]");
        assertThat(auth.attr("x-data")).isEqualTo("{ asTable: false }");
        assertThat(auth.select(".fn-day")).hasSize(7);
        // 날짜 말풍선(마우스 오버): 날짜 + 시도·성공·성공률, 보조기기에는 숨김(표로 보기가 같은 데이터)
        Element tip = auth.select(".fn-day").first().selectFirst(".fn-tip");
        assertThat(tip.attr("aria-hidden")).isEqualTo("true");
        assertThat(tip.selectFirst("strong").text()).isEqualTo("10월 1일");
        assertThat(tip.selectFirst("span").text()).isEqualTo("시도 10 · 성공 9 · 90.0%");
        assertThat(auth.select(".fn-day").last().selectFirst(".fn-tip strong").text()).isEqualTo("10월 7일");
        assertThat(auth.select(".fn-day").first().select("> span").eachAttr("style")).containsExactly("height: 100.0%", "height: 90.0%");
        assertThat(auth.selectFirst("[role=img]").attr("aria-label")).isEqualTo("일별 인증 시도와 성공 막대 차트, 7일");
        assertThat(auth.select("div.row.hint > span").eachText()).containsExactly("10-01", "10-04", "오늘");
        assertThat(auth.select("table th").eachText()).containsExactly("날짜", "시도", "성공", "성공률");
        assertThat(auth.select("table tbody tr")).hasSize(7);
        assertThat(auth.select("table tbody tr").first().select("td").eachText()).containsExactly("10-01", "10", "9", "90.0%");
        Element toggle = auth.select("button").stream().filter(b -> b.text().equals("표로 보기")).findFirst().orElseThrow();
        assertThat(toggle.attr("x-bind:aria-pressed")).isEqualTo("asTable");
        assertThat(auth.select("a[download]").attr("href")).isEqualTo("/admin/tenants/" + T1 + "/funnel.csv?win=7&kind=auth");

        // 등록 카드는 등록 값만(인증과 섞이지 않는다)
        Element reg = d.selectFirst("section[data-series=reg]");
        assertThat(reg.select(".fn-day").first().select(".fn-tip span").text()).isEqualTo("시도 4 · 성공 3 · 75.0%");
        assertThat(reg.select(".fn-day").first().select("> span").eachAttr("style")).containsExactly("height: 100.0%", "height: 75.0%");
        assertThat(reg.select("table tbody tr").first().select("td").eachText()).containsExactly("10-01", "4", "3", "75.0%");
        assertThat(reg.select("a[download]").attr("href")).isEqualTo("/admin/tenants/" + T1 + "/funnel.csv?win=7&kind=reg");

        // 색: 시도는 회색(fn-att), 성공은 카드별(인증 fn-ok-auth, 등록 fn-ok-reg) — 범례도 같은 색
        assertThat(auth.select(".fn-day").first().select("> span").eachAttr("class")).containsExactly("fn-att", "fn-ok-auth");
        assertThat(reg.select(".fn-day").first().select("> span").eachAttr("class")).containsExactly("fn-att", "fn-ok-reg");
        assertThat(auth.select(".fn-legend-dot").eachAttr("class")).containsExactly("fn-legend-dot fn-att", "fn-legend-dot fn-ok-auth");
        assertThat(reg.select(".fn-legend-dot").eachAttr("class")).containsExactly("fn-legend-dot fn-att", "fn-legend-dot fn-ok-reg");
        // style 속성에는 숫자 + % 만
        for (Element e : d.select(".fn-day > span")) assertThat(e.attr("style")).matches("height: [0-9.]+%");
    }

    @Test
    @DisplayName("인증 실패 원인: 한글 이름·의미·건수, 원래 코드는 title, 모르는 코드는 그대로, 원인 없음은 '원인 미기록', CSV 링크")
    void failureCard() throws Exception {
        when(funnel.get(eq(T1), eq(7))).thenReturn(data(7, days(7, i -> 4, i -> 3), days(7, i -> 9), List.of(
                new FunnelDto.FailureCount("SIGNATURE_INVALID", 5), new FunnelDto.FailureCount("NEW_REASON", 2), new FunnelDto.FailureCount(null, 1))));
        Element card = tab("").selectFirst("section[data-failures]");
        assertThat(card.select(".card__title").text()).isEqualTo("인증 실패 원인");
        assertThat(card.select("table th").eachText()).containsExactly("원인", "의미", "건수");
        assertThat(card.select("tbody tr strong").eachText()).containsExactly("서명 검증 실패", "NEW_REASON", "원인 미기록");
        assertThat(card.select("tbody tr strong").eachAttr("title")).containsExactly("SIGNATURE_INVALID", "NEW_REASON"); // 원인 없음은 title 없음
        assertThat(card.select("tbody tr").first().select("td").get(1).text()).contains("위조 시도");
        assertThat(card.select("tbody tr td.num").eachText()).containsExactly("5", "2", "1");
        assertThat(card.select("a[download]").attr("href")).isEqualTo("/admin/tenants/" + T1 + "/funnel.csv?win=7&kind=failures");
    }

    @Test
    @DisplayName("실패 원인 코드는 이스케이프된다(th:text/th:title) — 속성 중 JS 로 평가되는 곳에는 없다")
    void escapesFailureReason() throws Exception {
        when(funnel.get(eq(T1), eq(7))).thenReturn(data(7, days(7, i -> 1), days(7, i -> 1),
                List.of(new FunnelDto.FailureCount("<img src=x onerror=alert(1)>", 3))));
        String html = body(hget(BASE + "/tab/funnel", platform()));
        assertThat(html).doesNotContain("<img src=x");
        Document d = Jsoup.parse(html);
        assertThat(d.select("img[src=x]")).isEmpty();
        assertThat(d.select("section[data-failures] tbody strong").text()).isEqualTo("<img src=x onerror=alert(1)>");
    }

    // ── 패스키 도입·사용 ─────────────────────────────────────

    @Test
    @DisplayName("도입·사용 지표: 활성 사용자·신규·누적 패스키·패스키 1개뿐인 사용자 비율(50% 이상이면 경고), 기간은 탭과 같다")
    void adoptionKpis() throws Exception {
        Document d = tab("?win=30");
        verify(adoption).get(T1, 30);
        Element row = d.selectFirst("div.kpis[data-adoption]");
        assertThat(row.select(".kpi__label").eachText()).containsExactly("활성 사용자", "신규 패스키", "누적 패스키", "패스키 1개뿐인 사용자");
        assertThat(row.select(".kpi__value").eachText()).containsExactly("9명", "3개", "20개", "66.7%");
        assertThat(row.select(".kpi__hint").eachText()).containsExactly(
                "기간 중 인증 성공 · 사용된 패스키 12개", "기간 중 새로 등록", "사용자 15명",
                "10명 / 15명 · 기기를 잃으면 로그인할 수 없어요");
        assertThat(row.select(".kpi__hint").get(3).hasClass("kpi__hint--warn")).isTrue();
        assertThat(d.select("h2.fn-section").eachText()).containsExactly("인증 품질", "패스키 도입·사용");
    }

    @Test
    @DisplayName("사용자 0명: 1개뿐 비율은 '—' + '사용자 없음'(경고 아님), 기기 분포는 빈 문구만")
    void adoptionEmpty() throws Exception {
        when(adoption.get(eq(T1), anyInt())).thenReturn(new com.crosscert.passkey.admin.funnel.AdoptionDto.View(7, 0, 0, 0, 0, 0, 0, List.of(), List.of()));
        Document d = tab("");
        Element row = d.selectFirst("div.kpis[data-adoption]");
        assertThat(row.select(".kpi__value").eachText()).containsExactly("0명", "0개", "0개", "—");
        assertThat(row.select(".kpi__hint").get(3).text()).isEqualTo("사용자 없음");
        assertThat(row.select(".kpi__hint").get(3).hasClass("kpi__hint--warn")).isFalse();
        Element dev = d.selectFirst("section[data-devices]");
        assertThat(dev.text()).contains("등록된 패스키가 없습니다.");
        assertThat(dev.select(".dv-row, a[download]")).isEmpty();
    }

    @Test
    @DisplayName("월간 사용량: 최근 3개월(이번 달은 진행 중), 인증 성공·활성 사용자·신규 패스키, CSV 링크")
    void monthlyCard() throws Exception {
        Element card = tab("").selectFirst("section[data-monthly]");
        assertThat(card.select(".card__title").text()).isEqualTo("월간 사용량");
        assertThat(card.select("table th").eachText()).containsExactly("월", "인증 성공", "활성 사용자", "신규 패스키");
        assertThat(card.select("tbody tr").get(1).select("td").eachText()).containsExactly("2026년 9월", "1,234", "52명", "7");
        assertThat(card.select("tbody tr").get(2).select("td").first().text()).isEqualTo("2026년 10월 (진행 중)");
        assertThat(card.select("a[download]").attr("href")).isEqualTo("/admin/tenants/" + T1 + "/funnel.csv?win=7&kind=monthly");
    }

    @Test
    @DisplayName("기기 분포: 인증기 이름(이스케이프)·비율 막대(숫자만 style)·비율·개수·기간 인증 건수, CSV 링크")
    void devicesCard() throws Exception {
        String html = body(hget(BASE + "/tab/funnel", platform()));
        assertThat(html).doesNotContain("<b>x</b>");
        Element card = Jsoup.parse(html).selectFirst("section[data-devices]");
        assertThat(card.select(".dv-row__name").eachText()).containsExactly("iCloud 키체인", "<b>x</b>");
        assertThat(card.select(".dv-row__bar").eachAttr("style")).containsExactly("width: 75.0%", "width: 25.0%");
        assertThat(card.select(".dv-row__num").eachText()).containsExactly("75.0% 15개", "25.0% 5개");
        assertThat(card.select(".dv-row__sub").eachText()).containsExactly("기간 인증 40건", "기간 인증 2건");
        assertThat(card.select("a[download]").attr("href")).isEqualTo("/admin/tenants/" + T1 + "/funnel.csv?win=7&kind=devices");
    }

    @Test
    @DisplayName("도입 지표 조회가 실패해도 인증 품질 영역은 그대로, 도입 영역만 오류(내부 메시지 비노출)")
    void adoptionFailureIsolated() throws Exception {
        when(adoption.get(eq(T1), anyInt())).thenThrow(new IllegalStateException("jdbc:secret"));
        MvcResult r = hget(BASE + "/tab/funnel", platform());
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(r)).doesNotContain("secret");
        Document d = Jsoup.parse(body(r));
        assertThat(d.select(Q + ".kpi__value").first().text()).isEqualTo("80.0%");
        assertThat(d.select("[role=alert]").text()).contains("패스키 도입 지표를 불러오지 못했습니다");
        assertThat(d.select("div.kpis[data-adoption], section[data-monthly], section[data-devices]")).isEmpty();
    }

    @Test
    @DisplayName("CSV: 월간 사용량·기기 분포 — 퍼널 조회 없이 도입 지표만, 수식 문자 무력화")
    void adoptionCsv() throws Exception {
        String[] disp = new String[1];
        String monthly = csv("?win=7&kind=monthly", disp);
        assertThat(disp[0]).isEqualTo("attachment; filename=\"monthly-usage-acme-2026-10-11.csv\"");
        assertThat(monthly.split("\n")).containsExactly("month,inProgress,authSuccess,activeUsers,newPasskeys",
                "2026-08,N,300,40,5", "2026-09,N,1234,52,7", "2026-10,Y,90,9,3");
        when(adoption.get(eq(T1), eq(30))).thenReturn(new com.crosscert.passkey.admin.funnel.AdoptionDto.View(30, 1, 0, 0, 0, 1, 1, List.of(),
                List.of(new com.crosscert.passkey.admin.funnel.AdoptionDto.Device("=cmd", 1, 0))));
        String devices = csv("?win=30&kind=devices", disp);
        assertThat(disp[0]).isEqualTo("attachment; filename=\"devices-acme-30d-2026-10-11.csv\"");
        assertThat(devices.split("\n")).containsExactly("authenticator,passkeys,authSuccessInWindow", "'=cmd,1,0");
        verify(funnel, never()).get(any(), anyInt());
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
        assertThat(d.select("[role=alert]").text()).contains("인증 현황을 불러오지 못했습니다").contains("오류 정보 복사");
        assertThat(d.select(".kpi, section.card")).isEmpty();
        assertThat(d.select("#funnel-root > .row button[disabled]").text()).isEqualTo("내보내기");
        assertThat(d.select("#funnel-root > .row a[download]")).isEmpty();
        Element retry = d.select("[role=alert] button").stream().filter(b -> b.text().equals("다시 시도")).findFirst().orElseThrow();
        assertThat(retry.attr("hx-get")).isEqualTo(BASE + "/tab/funnel?win=7");
        verify(funnel, never()).get(T1, 30);
    }

    @Test
    @DisplayName("기간 전환 중: 루트가 pending 을 들고 이전 결과를 숨기고 스켈레톤을, 내보내기·CSV 링크는 비활성(요청 실패 시 after-request 로 복구 + 선택을 보이는 기간 data-win 으로)")
    void pendingStateHidesResults() throws Exception {
        Element root = tab("").selectFirst("#funnel-root");
        // pending·실패 시 되돌리기는 Alpine funnelTab(app.js) — 화면에 보이는 기간은 data-win(서버 값)
        assertThat(root.attr("x-data")).isEqualTo("funnelTab");
        assertThat(root.attr("x-on:htmx:before-request")).isEqualTo("begin()");
        assertThat(root.attr("x-on:htmx:after-request")).isEqualTo("end($event)");
        assertThat(root.attr("data-win")).isEqualTo("7");
        assertThat(tab("?win=30").selectFirst("#funnel-root").attr("data-win")).isEqualTo("30");
        assertThat(root.select("[x-show=!pending]")).isNotEmpty();
        assertThat(root.select("[role=status][aria-busy=true]")).hasSize(1);
        for (Element a : root.select("a[download]")) assertThat(a.attr("x-on:click")).isEqualTo("if (pending) $event.preventDefault()");
    }

    @Test
    @DisplayName("CSV 링크 6개(내보내기·일별 인증·일별 등록·실패 원인·월간 사용량·기기 분포) 모두 공통 다운로드 표식")
    void csvLinksCarryMarker() throws Exception {
        Document d = tab("");
        assertThat(d.select("a[download]")).hasSize(6);
        assertThat(d.select("a[download]").stream().allMatch(a -> a.hasAttr("data-csv-download"))).isTrue();
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
    @DisplayName("CSV 4종: 요약(내보내기) · 일별 인증 · 일별 등록 · 실패 원인 — 코드와 한글 이름을 함께, 성공률 toFixed(4), 파일명")
    void csvExports() throws Exception {
        String[] disp = new String[1];
        String summary = csv("?win=7&kind=summary", disp);
        assertThat(disp[0]).isEqualTo("attachment; filename=\"auth-status-acme-7d-2026-10-11.csv\"");
        assertThat(summary.split("\n")).containsExactly("ceremony,label,attempts,success,successRate",
                "registration,등록,100,80,0.8000", "authentication,인증,70,63,0.9000");
        String auth = csv("?win=7&kind=auth", disp);
        assertThat(disp[0]).isEqualTo("attachment; filename=\"auth-daily-acme-7d-2026-10-11.csv\"");
        assertThat(auth.split("\n")).hasSize(8);
        assertThat(auth.split("\n")[0]).isEqualTo("day,attempts,success,successRate");
        assertThat(auth.split("\n")[1]).isEqualTo("10-01,10,9,0.9000");
        String reg = csv("?win=7&kind=reg", disp);
        assertThat(disp[0]).isEqualTo("attachment; filename=\"registration-daily-acme-7d-2026-10-11.csv\"");
        assertThat(reg.split("\n")[1]).isEqualTo("10-01,4,3,0.7500");
        String fails = csv("?win=30&kind=failures", disp);
        assertThat(disp[0]).isEqualTo("attachment; filename=\"auth-failures-acme-30d-2026-10-11.csv\"");
        assertThat(fails.split("\n")).containsExactly("reason,label,n", "SIGNATURE_INVALID,서명 검증 실패,5", "SIGN_COUNT_REPLAY,서명 카운터 재사용,2");
        // 기본 kind 는 요약, 탭의 내보내기 링크가 같은 주소
        assertThat(csv("?win=7", disp)).startsWith("ceremony,label");
        Document d = tab("?win=30");
        assertThat(d.select("#funnel-root > .row a[download]").attr("href")).isEqualTo("/admin/tenants/" + T1 + "/funnel.csv?win=30&kind=summary");
    }

    @Test
    @DisplayName("CSV: 실패 원인 코드의 수식 문자는 무력화(' 접두)")
    void csvNeutralizesFormula() throws Exception {
        when(funnel.get(eq(T1), eq(7))).thenReturn(data(7, days(1, i -> 1), days(1, i -> 1), List.of(new FunnelDto.FailureCount("=HYPERLINK(\"x\")", 3))));
        String[] disp = new String[1];
        assertThat(csv("?win=7&kind=failures", disp)).contains("\"'=HYPERLINK(\"\"x\"\")\",");
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
