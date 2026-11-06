package com.crosscert.passkey.admin.web.tenant;

import com.crosscert.passkey.admin.audit.AuditChainMonitorController;
import com.crosscert.passkey.admin.audit.AuditLogController;
import com.crosscert.passkey.admin.audit.AuditLogController.AuditLogView;
import com.crosscert.passkey.admin.auth.ActiveTenantController;
import com.crosscert.passkey.admin.auth.AdminUserDetails;
import com.crosscert.passkey.admin.license.LicenseController;
import com.crosscert.passkey.admin.license.LicenseView;
import com.crosscert.passkey.admin.policy.AaguidPolicyController;
import com.crosscert.passkey.admin.tenant.TenantAdminController;
import com.crosscert.passkey.admin.tenant.TenantAdminDto;
import com.crosscert.passkey.admin.web.AdminWebSliceTest;
import com.crosscert.passkey.core.api.ApiResponse;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import java.time.Duration;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 감사 로그 탭. 원본: AuditTab.test.tsx, auditHelpers.test.ts(→ AuditHelpersTest), api/audit.ts, lib/csvExport.ts.
 * 원본에는 자동 갱신(폴링)이 없다 — 그래서 "폴링이 필터를 유지한다" 대신 "자동 갱신 트리거가 없고 모든 컨트롤이 현재 필터를 싣는다"를 본다.
 * 브라우저에서 도는 부분(행 펼침·다이얼로그 열기·체인 검증 타이머)은 속성·소스 수준까지만 본다.
 */
@AdminWebSliceTest(controllers = AuditTabController.class)
class AuditTabControllerTest {

    @Autowired MockMvc mvc;
    @Autowired Clock clock;
    @MockitoBean TenantAdminController tenants;
    @MockitoBean AuditLogController audit;
    @MockitoBean AuditChainMonitorController chain;
    @MockitoBean AaguidPolicyController aaguid;
    @MockitoBean LicenseController license;
    @MockitoBean ActiveTenantController activeTenants;

    static final ObjectMapper JSON = new ObjectMapper();
    static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");
    static final UUID T1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
    static final String BASE = "/admin/_/tenants/" + T1;
    /** 첫 화면이 고정한 구간 끝(NOW)을 인코딩한 값. */
    static final String TO0 = "2026-10-11T00%3A00%3A00.000Z";

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(license.get()).thenReturn(ApiResponse.ok(new LicenseView("saas", "VALID", "acme", "lic_1", NOW.plusSeconds(86400L * 30), 30, List.of(), null, null, null)));
        when(tenants.get(T1.toString())).thenReturn(ApiResponse.ok(tenant()));
        stubPage(0, List.of(
                ev("e1", "admin@x.com", "WEBAUTHN_CONFIG_UPDATED", "TENANT", "t1", "{\"timeoutMs\":{\"from\":60000,\"to\":120000}}", -0.01),
                ev("e2", null, "AUTH_SUCCESS", "CREDENTIAL", "credAAAAAAAAAAAAAAAAAAAA1", null, -0.02),
                ev("e3", null, "ATTESTATION_TRUST_FAILED", "CREDENTIAL", "c3", "{\"reason\":\"bad\"}", -0.03),
                ev("e4", "ops@x.com", "API_KEY_ROTATED", "API_KEY", "k1", "not json", -1.0)));
    }

    // ── fixtures ────────────────────────────────────────────

    static OffsetDateTime at(double days) {
        return OffsetDateTime.ofInstant(NOW.plusSeconds((long) (days * 86400)), ZoneOffset.UTC);
    }

    static UUID uid(String s) {
        return UUID.nameUUIDFromBytes(s.getBytes(StandardCharsets.UTF_8));
    }

    static AuditLogView ev(String id, String actor, String action, String targetType, String targetId, String payload, double days) {
        return new AuditLogView(uid(id), actor == null ? null : uid(actor), actor, action, targetType, targetId, T1, payload, at(days));
    }

    private void stubPage(int page, List<AuditLogView> rows) {
        when(audit.list(isNull(), isNull(), eq(T1), any(), any(), eq(page), eq(50))).thenReturn(ApiResponse.ok(rows));
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

    private static Document doc(MvcResult r) throws Exception {
        return Jsoup.parse(body(r));
    }

    private MvcResult hget(String url, Authentication a) throws Exception {
        return mvc.perform(htmx(get(URI.create(url)), a)).andReturn();
    }

    private Document tab(String q, Authentication a) throws Exception {
        MvcResult r = hget(BASE + "/tab/audit" + q, a);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return doc(r);
    }

    private Document tab(String q) throws Exception {
        return tab(q, platform());
    }

    private static Element rowOf(Document d, String eventType) {
        return d.select("#audit-card tbody tr.is-clickable").stream().filter(tr -> tr.select(".badge").text().equals(eventType)).findFirst().orElseThrow();
    }

    // ── 표 ──────────────────────────────────────────────────

    @Test
    @DisplayName("열·행: 시각(HH:mm:ss + 상대), 이벤트 배지, 행위자(ADMIN/SYSTEM + 끝 10자), 대상(타입 + 끝 12자), 상세 내용(compact JSON), 날짜 그룹, 조각만")
    void rendersTable() throws Exception {
        MvcResult r = hget(BASE + "/tab/audit", platform());
        String html = body(r);
        assertThat(html).doesNotContain("<html").doesNotContain("class=\"shell");
        Document d = Jsoup.parse(html);
        assertThat(d.select("#audit-root #audit-card")).hasSize(1);
        assertThat(d.select("#audit-card thead th").eachText()).containsExactly("시각", "이벤트", "행위자", "대상", "상세 내용", "펼치기");
        assertThat(d.select("#audit-card thead th .sr-only").text()).isEqualTo("펼치기");
        assertThat(d.select("#audit-card tr.table__group").eachText()).containsExactly("오늘", "어제");

        Element a = rowOf(d, "WEBAUTHN_CONFIG_UPDATED");
        assertThat(a.select("td").get(0).text()).isEqualTo("08:45:36 14분 전");
        assertThat(a.select("td").get(1).select(".badge").hasClass("mono")).isTrue();
        assertThat(a.select("td").get(1).select(".badge").hasClass("badge--warning")).isTrue();
        assertThat(a.select("td").get(2).select(".tag").text()).isEqualTo("ADMIN");
        assertThat(a.select("td").get(2).select(".mono").text()).isEqualTo("…dmin@x.com");
        assertThat(a.select("td").get(3).select(".tag").text()).isEqualTo("TENANT");
        assertThat(a.select("td").get(3).select(".mono").text()).isEqualTo("…t1");
        assertThat(a.select("td").get(4).text()).isEqualTo("{\"timeoutMs\":{\"from\":60000,\"to\":120000}}");

        Element s = rowOf(d, "AUTH_SUCCESS");
        assertThat(s.select("td").get(2).select(".tag").text()).isEqualTo("SYSTEM");
        assertThat(s.select("td").get(2).select(".mono")).isEmpty();
        String sid = "credAAAAAAAAAAAAAAAAAAAA1";
        assertThat(s.select("td").get(3).select(".mono").text()).isEqualTo("…" + sid.substring(sid.length() - 12));
        assertThat(s.select("td").get(4).text()).isEqualTo("null"); // JSON.stringify(null)
        assertThat(s.select("td").get(1).select(".badge").hasClass("badge--info")).isTrue();
        assertThat(rowOf(d, "ATTESTATION_TRUST_FAILED").select(".badge").hasClass("badge--danger")).isTrue();
        // 파싱 못 한 payload → {raw}
        assertThat(rowOf(d, "API_KEY_ROTATED").select("td").get(4).text()).isEqualTo("{\"raw\":\"not json\"}");
    }

    @Test
    @DisplayName("건수: total 이 없으므로 '최근 N건'(서버 처음 값), 필터가 있으면 '이 페이지 N건 중 M건'은 클라이언트(auditFilter.countText)가 다시 센다")
    void countText() throws Exception {
        Document d = tab("");
        Element hint = d.selectFirst("#audit-card .card__foot .hint");
        assertThat(hint.text()).isEqualTo("최근 4건");
        assertThat(hint.attr("x-text")).isEqualTo("countText");
        assertThat(d.selectFirst("#audit-root").attr("x-data")).isEqualTo("auditFilter");
    }

    @Test
    @DisplayName("필터 판정값: 행마다 data-g(이벤트 그룹)·data-fail(실패 여부)·data-day(KST 날짜 키), 날짜 머리글도 data-day — 서버 상수만")
    void rowFilterData() throws Exception {
        Document d = tab("");
        assertThat(rowOf(d, "WEBAUTHN_CONFIG_UPDATED").attr("data-g")).isEqualTo("config");
        assertThat(rowOf(d, "AUTH_SUCCESS").attr("data-g")).isEqualTo("auth");
        assertThat(rowOf(d, "API_KEY_ROTATED").attr("data-g")).isEqualTo("keys");
        assertThat(rowOf(d, "ATTESTATION_TRUST_FAILED").attr("data-fail")).isEqualTo("1");
        assertThat(rowOf(d, "AUTH_SUCCESS").attr("data-fail")).isEqualTo("0");
        assertThat(rowOf(d, "AUTH_SUCCESS").hasAttr("data-ev")).isTrue();
        assertThat(rowOf(d, "AUTH_SUCCESS").attr("data-day")).isEqualTo("2026-10-11");
        assertThat(rowOf(d, "API_KEY_ROTATED").attr("data-day")).isEqualTo("2026-10-10");
        assertThat(d.select("#audit-rows tr.table__group").eachAttr("data-day")).containsExactly("2026-10-11", "2026-10-10");
        // 처음 응답은 모든 행을 그린다(필터는 클라이언트)
        assertThat(d.select("#audit-rows tr[data-ev]")).hasSize(4);
    }
    // ── REST 호출 ───────────────────────────────────────────

    @Test
    @DisplayName("기간 세그먼트를 서버 from/to 로 보낸다(24시간 → 7일), 페이지 0·크기 50·tenantId 고정, 알 수 없는 기간은 24시간")
    void windowSentAsRange() throws Exception {
        tab("");
        ArgumentCaptor<OffsetDateTime> from = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> to = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(audit).list(isNull(), isNull(), eq(T1), from.capture(), to.capture(), eq(0), eq(50));
        assertThat(Duration.between(from.getValue(), to.getValue())).isEqualTo(Duration.ofHours(24));
        assertThat(to.getValue().toInstant()).isEqualTo(NOW);

        tab("?win=7d");
        ArgumentCaptor<OffsetDateTime> f2 = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> t2 = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(audit, times(2)).list(isNull(), isNull(), eq(T1), f2.capture(), t2.capture(), eq(0), eq(50));
        assertThat(Duration.between(f2.getValue(), t2.getValue())).isEqualTo(Duration.ofHours(168));

        tab("?win=bogus");
        ArgumentCaptor<OffsetDateTime> f3 = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> t3 = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(audit, times(3)).list(isNull(), isNull(), eq(T1), f3.capture(), t3.capture(), eq(0), eq(50));
        assertThat(Duration.between(f3.getValue(), t3.getValue())).isEqualTo(Duration.ofHours(24));
    }

    @Test
    @DisplayName("기간 세그먼트의 주소: 기간을 바꾸면 카드를 새로 그린다(구간 끝을 새로 잡고 첫 쪽, 필터는 카드 밖 Alpine 상태라 주소에 없다)")
    void windowSegmentUrls() throws Exception {
        Document d = tab("");
        Element seg = d.selectFirst("#audit-card .seg");
        assertThat(seg.attr("aria-label")).isEqualTo("기간");
        assertThat(seg.select("button").eachText()).containsExactly("24시간", "7일", "30일");
        assertThat(seg.select("button").get(1).attr("hx-get")).isEqualTo(BASE + "/tab/audit/card?win=7d");
        assertThat(seg.select("button").get(0).attr("aria-pressed")).isEqualTo("true");
    }
    /** 더 보기 응답(OOB 조각)을 표 안에서 파싱한다 — 브라우저(htmx)는 &lt;template&gt; 로 파싱해 tbody 를 살린다. */
    private static Document moreDoc(MvcResult r) throws Exception {
        return Jsoup.parse("<table>" + body(r) + "</table>");
    }

    private static List<AuditLogView> fullPage(String prefix, double startDays) {
        List<AuditLogView> out = new ArrayList<>();
        for (int i = 0; i < 50; i++) out.add(ev(prefix + i, null, i % 2 == 0 ? "WEBAUTHN_CONFIG_UPDATED" : "ATTESTATION_TRUST_FAILED", "TENANT", "t1", null, startDays - 0.0001 * i));
        return out;
    }

    @Test
    @DisplayName("더 보기 버튼: 50건이면 보이고 같은 구간(숨은 상태 win·to·page=1·lastDay)을 싣고 OOB 만 적용(hx-swap=none) — 50건 미만이면 버튼 없음")
    void loadMoreButtonCarriesState() throws Exception {
        stubPage(0, fullPage("a", -0.001));
        Document d = tab("");
        Element more = d.selectFirst("#audit-card #audit-more button");
        assertThat(more.text()).isEqualTo("더 보기");
        assertThat(more.attr("hx-get")).isEqualTo(BASE + "/tab/audit/more");
        assertThat(more.attr("hx-include")).isEqualTo("#audit-state");
        assertThat(more.attr("hx-swap")).isEqualTo("none");
        Element st = d.selectFirst("#audit-card #audit-state");
        assertThat(st.select("input[name=win]").val()).isEqualTo("24h");
        assertThat(st.select("input[name=to]").val()).isEqualTo("2026-10-11T00:00:00.000Z");
        assertThat(st.select("#audit-page").val()).isEqualTo("1");
        assertThat(st.select("#audit-last-day").val()).isEqualTo("2026-10-11");

        stubPage(0, List.of(ev("x", null, "AUTH_SUCCESS", "CREDENTIAL", "c", null, -0.1)));
        assertThat(tab("").select("#audit-more button")).isEmpty();
    }

    @Test
    @DisplayName("더 보기 = 같은 구간의 다음 한 쪽만 읽어 표 끝에 붙인다(OOB beforeend #audit-rows) — 앞 쪽을 다시 읽지 않고, 다음 쪽 번호·마지막 날짜·버튼을 OOB 로 갱신")
    void loadMoreFetchesOnlyNextPage() throws Exception {
        stubPage(1, List.of(ev("b0", null, "AUTH_SUCCESS", "CREDENTIAL", "c", null, -0.2), ev("b1", null, "AUTH_SUCCESS", "CREDENTIAL", "c", null, -1.2)));
        MvcResult r = hget(BASE + "/tab/audit/more?win=24h&to=2026-10-10T23:00:00.000Z&page=1&lastDay=2026-10-11", platform());
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(r).trim()).startsWith("<tbody"); // htmx 의 template 파싱에서 tr 이 살아남도록 표 조각이 맨 앞
        Document d = moreDoc(r);
        Element oob = d.selectFirst("tbody[hx-swap-oob]");
        assertThat(oob.attr("hx-swap-oob")).isEqualTo("beforeend:#audit-rows");
        assertThat(oob.select("tr[data-ev]")).hasSize(2);
        // 첫 행은 화면 마지막 그룹(2026-10-11)과 같은 날짜 → 머리글 없음, 다음 날짜는 머리글
        assertThat(oob.select("tr.table__group").eachText()).containsExactly("어제");
        assertThat(d.selectFirst("#audit-page").attr("hx-swap-oob")).isEqualTo("true");
        assertThat(d.selectFirst("#audit-page").val()).isEqualTo("2");
        assertThat(d.selectFirst("#audit-last-day").val()).isEqualTo("2026-10-10");
        assertThat(d.selectFirst("#audit-more").attr("hx-swap-oob")).isEqualTo("true");
        assertThat(d.select("#audit-more button")).isEmpty(); // 50건 미만 → 끝
        // 같은 구간(to 고정)의 1쪽만
        ArgumentCaptor<OffsetDateTime> from = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> to = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(audit).list(isNull(), isNull(), eq(T1), from.capture(), to.capture(), eq(1), eq(50));
        verify(audit, never()).list(any(), any(), any(), any(), any(), eq(0), anyInt());
        assertThat(to.getValue().toInstant()).isEqualTo(Instant.parse("2026-10-10T23:00:00Z"));
        assertThat(Duration.between(from.getValue(), to.getValue())).isEqualTo(Duration.ofHours(24));
    }

    @Test
    @DisplayName("더 보기에 쪽 수 상한이 없다(이전 40쪽 상한 제거): 45쪽도 그 쪽 하나만 읽고, 50건이면 다음 쪽(46) 버튼")
    void loadMoreHasNoPageCap() throws Exception {
        stubPage(45, fullPage("p", -0.5));
        MvcResult r = hget(BASE + "/tab/audit/more?win=30d&to=2026-10-10T23:00:00.000Z&page=45&lastDay=2026-10-10", platform());
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        Document d = moreDoc(r);
        assertThat(d.select("tbody[hx-swap-oob] tr[data-ev]")).hasSize(50);
        assertThat(d.selectFirst("#audit-page").val()).isEqualTo("46");
        assertThat(d.select("#audit-more button").text()).isEqualTo("더 보기");
        verify(audit).list(isNull(), isNull(), eq(T1), any(), any(), eq(45), eq(50));
        verify(audit, times(1)).list(any(), any(), any(), any(), any(), anyInt(), anyInt());
        assertThat(body(r)).doesNotContain("더 불러올 수 없습니다");
    }
    @Test
    @DisplayName("더 보기 쪽 읽기가 실패하면 실제 오류 상태 + 토스트(본문 없음) — 붙인 행·숨은 상태·버튼은 그대로라 다시 누를 수 있다. 내부 메시지 비노출")
    void loadMoreFailure() throws Exception {
        when(audit.list(isNull(), isNull(), eq(T1), any(), any(), eq(1), eq(50))).thenThrow(new IllegalStateException("db password=secret"));
        MvcResult r = hget(BASE + "/tab/audit/more?win=24h&to=2026-10-11T00:00:00.000Z&page=1&lastDay=2026-10-11", platform());
        assertThat(r.getResponse().getStatus()).isGreaterThanOrEqualTo(500);
        assertThat(body(r).trim()).isEmpty();
        JsonNode t = JSON.readTree(r.getResponse().getHeader("HX-Trigger"));
        assertThat(t.toString()).contains("감사 로그를 더 불러오지 못했습니다").doesNotContain("secret");
    }
    // ── 필터 ────────────────────────────────────────────────

    @Test
    @DisplayName("실패만 스위치·필터 지우기는 클라이언트 필터(auditFilter): 서버 요청 없이 상태를 바꾼다(role=switch, failures 를 따름)")
    void failuresOnly() throws Exception {
        Document d = tab("");
        Element sw = d.selectFirst("#audit-card input[role=switch]");
        assertThat(sw.attr("x-bind:checked")).isEqualTo("failures");
        assertThat(sw.attr("x-on:change")).isEqualTo("setFailures($el.checked)");
        assertThat(sw.hasAttr("hx-get")).isFalse();
        assertThat(sw.parent().text()).isEqualTo("실패만");
        Element clear = d.select("#audit-card .card__head button").stream().filter(b -> b.text().equals("필터 지우기")).findFirst().orElseThrow();
        assertThat(clear.attr("x-show")).isEqualTo("filtering");
        assertThat(clear.attr("x-on:click")).isEqualTo("clear()");
        assertThat(clear.hasAttr("hx-get")).isFalse();
    }
    @Test
    @DisplayName("이벤트 종류 그룹 칩: 라벨 5개(원시 코드는 title), 눌림 상태·토글은 클라이언트(has/toggle, data-g 는 서버 상수), 어느 그룹에도 없는 건 '기타'")
    void groupChips() throws Exception {
        Document d = tab("");
        Element group = d.selectFirst("#audit-card [role=group][aria-label=이벤트 종류]");
        assertThat(group.select("button").eachText()).containsExactly("인증", "등록", "설정 변경", "키 관리", "기타");
        Element keys = group.select("button").get(3);
        assertThat(keys.attr("title")).isEqualTo("API_KEY_* · CREDENTIAL_REVOKE*");
        assertThat(keys.attr("data-g")).isEqualTo("keys");
        assertThat(keys.attr("aria-pressed")).isEqualTo("false");
        assertThat(keys.attr("x-bind:aria-pressed")).isEqualTo("has($el.dataset.g) ? 'true' : 'false'");
        assertThat(keys.attr("x-on:click")).isEqualTo("toggle($el.dataset.g)");
        assertThat(keys.hasAttr("hx-get")).isFalse();
        assertThat(group.select("button").eachAttr("data-g")).containsExactly("auth", "registration", "config", "keys", "other");

        stubPage(0, List.of(ev("x1", null, "MDS_BLOB_SYNC", "SYSTEM", "s", null, -0.1), ev("x2", null, "AUTH_SUCCESS", "CREDENTIAL", "c", null, -0.1)));
        assertThat(rowOf(tab(""), "MDS_BLOB_SYNC").attr("data-g")).isEqualTo("other");
    }
    @Test
    @DisplayName("빈 목록: 필터가 없으면 '이 기간에 감사 이벤트가 없습니다.', 필터가 있으면 '조건에 맞는 이벤트가 없습니다.'(보이는 행이 0일 때, 클라이언트 판정)")
    void emptyStates() throws Exception {
        stubPage(0, List.of());
        Document d = tab("");
        Element empty = d.selectFirst("#audit-card tr.table__empty");
        assertThat(empty.attr("x-show")).isEqualTo("shown === 0");
        assertThat(empty.hasAttr("x-cloak")).isFalse(); // 행이 없으면 처음부터 보인다
        assertThat(empty.select("[x-show=!filtering]").text()).isEqualTo("이 기간에 감사 이벤트가 없습니다.");
        assertThat(empty.select("[x-show=filtering]").text()).isEqualTo("조건에 맞는 이벤트가 없습니다.");
        // 행이 있으면 빈 행은 처음에 숨는다(x-cloak)
        stubPage(0, List.of(ev("e1", null, "AUTH_SUCCESS", "CREDENTIAL", "c", null, -0.1)));
        assertThat(tab("").selectFirst("#audit-card tr.table__empty").hasAttr("x-cloak")).isTrue();
    }
    @Test
    @DisplayName("자동 갱신(폴링)은 없다(원본에 없음) — every 트리거가 없다. 카드 요청의 대상은 #tenant-tab 이 아니라 카드(this), 필터 상태는 카드 밖(#audit-root)")
    void pollingKeepsFilters() throws Exception {
        MvcResult r = hget(BASE + "/tab/audit?win=7d", platform());
        Document d = doc(r);
        assertThat(body(r)).doesNotContain("every ").doesNotContain("hx-trigger=\"load\"");
        Element card = d.selectFirst("#audit-card");
        assertThat(card.attr("hx-target")).isEqualTo("this");
        assertThat(card.attr("hx-swap")).isEqualTo("outerHTML");
        assertThat(card.attr("hx-sync")).isEqualTo("this:replace");
        assertThat(d.select("[hx-target=#tenant-tab]")).isEmpty();
        Element root = d.selectFirst("#audit-root");
        assertThat(root.attr("x-on:htmx:after-swap")).isEqualTo("apply()");
        assertThat(root.attr("x-on:htmx:after-settle")).isEqualTo("apply()");
        for (Element e : card.select("[hx-get]")) {
            String u = e.attr("hx-get");
            assertThat(u).satisfiesAnyOf(x -> assertThat(x).startsWith(BASE + "/tab/audit/card?win="), x -> assertThat(x).isEqualTo(BASE + "/tab/audit/more"));
        }
        // CSV 링크: 구간(win·to)은 서버가, 불러온 쪽 수·필터는 클라이언트(csvHref)가 붙인다. 처음 href 는 1쪽
        Element a = card.selectFirst("a[download]");
        assertThat(a.attr("data-base")).isEqualTo("/admin/tenants/" + T1 + "/audit.csv?win=7d&to=" + TO0);
        assertThat(a.attr("href")).isEqualTo("/admin/tenants/" + T1 + "/audit.csv?win=7d&to=" + TO0 + "&pages=1");
        assertThat(a.attr("x-bind:href")).isEqualTo("csvHref($el.dataset.base)");
        assertThat(a.attr("x-show")).isEqualTo("shown > 0");
    }
    // ── 오류 ────────────────────────────────────────────────

    @Test
    @DisplayName("로드 실패: '이벤트 없음'이 아니라 LoadError(다시 시도 = 새 구간 1쪽), 건수 —, CSV 비활성, 더 보기 없음, 내부 메시지 비노출")
    void loadFailure() throws Exception {
        when(audit.list(isNull(), isNull(), eq(T1), any(), any(), anyInt(), anyInt())).thenThrow(new IllegalStateException("jdbc:secret"));
        MvcResult r = hget(BASE + "/tab/audit?win=7d", platform());
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(r)).doesNotContain("secret");
        Document d = doc(r);
        assertThat(d.select("#audit-card [role=alert]").text()).contains("감사 로그를 불러오지 못했습니다");
        assertThat(d.select("#audit-card tr.table__empty")).isEmpty();
        assertThat(d.select("#audit-card .card__foot .hint").text()).isEqualTo("—");
        assertThat(d.select("#audit-card .card__head button[disabled]").stream().anyMatch(b -> b.text().startsWith("CSV"))).isTrue();
        assertThat(d.select("#audit-card a[download]")).isEmpty();
        assertThat(d.select("#audit-more button")).isEmpty();
        Element retry = d.select("#audit-card [role=alert] button").stream().filter(b -> b.text().equals("다시 시도")).findFirst().orElseThrow();
        assertThat(retry.attr("hx-get")).isEqualTo(BASE + "/tab/audit/card?win=7d");
    }
    // ── 펼침·다이얼로그 ─────────────────────────────────────

    @Test
    @DisplayName("펼친 행: diff 칩(from → to)·payload(JSON 2칸 들여쓰기)·JSON 복사·상세 다이얼로그 버튼. 행은 하나만 펼쳐진다(exp)")
    void expandedRow() throws Exception {
        Document d = tab("");
        Element table = d.selectFirst("#audit-card table");
        assertThat(table.attr("x-data")).isEqualTo("{ exp: null }");
        Element a = rowOf(d, "WEBAUTHN_CONFIG_UPDATED");
        String id = a.attr("data-id");
        assertThat(id).isEqualTo(uid("e1").toString());
        assertThat(a.attr("x-on:click")).isEqualTo("exp = exp === $el.dataset.id ? null : $el.dataset.id");
        Element exp = d.selectFirst("tr.table__expanded[data-id=" + id + "]");
        assertThat(exp.attr("x-show")).isEqualTo("exp === $el.dataset.id");
        assertThat(exp.select("del").text()).isEqualTo("60000");
        assertThat(exp.select("ins").text()).isEqualTo("120000");
        assertThat(exp.select("pre").first().wholeText()).isEqualTo("{\n  \"timeoutMs\": {\n    \"from\": 60000,\n    \"to\": 120000\n  }\n}");
        assertThat(exp.select("button").stream().map(Element::text)).contains("JSON 복사", "상세 다이얼로그");
        Element open = exp.select("button").stream().filter(b -> b.text().equals("상세 다이얼로그")).findFirst().orElseThrow();
        assertThat(open.attr("data-dlg")).isEqualTo("audit-evt-" + id);
        // payload 가 없는 이벤트의 pre 는 {}
        assertThat(d.selectFirst("tr.table__expanded[data-id=" + uid("e2") + "] pre").wholeText()).isEqualTo("{}");
    }

    @Test
    @DisplayName("상세 다이얼로그: 제목 'Audit Event' + 이벤트 배지, 언제(KST + ISO)·누가·대상·payload, 닫기 — x-data 는 상수 id 만")
    void eventDialog() throws Exception {
        Document d = tab("");
        String id = uid("e1").toString();
        Element dlg = d.selectFirst("tr.table__expanded[data-id=" + id + "] [role=dialog]");
        assertThat(dlg.attr("aria-labelledby")).isEqualTo("audit-evt-" + id + "-title");
        assertThat(dlg.select(".dialog__title > .row > span").first().text()).isEqualTo("Audit Event");
        assertThat(dlg.select(".dialog__title .badge").text()).isEqualTo("WEBAUTHN_CONFIG_UPDATED");
        assertThat(dlg.select(".t-over").eachText()).containsExactly("언제", "누가 (actor)", "대상 (subject)", "payload");
        assertThat(dlg.select(".dialog__body > .stack-1").first().text()).isEqualTo("언제 2026-10-11 08:45:36 KST 2026-10-10T23:45:36.000Z");
        assertThat(dlg.text()).contains("admin@x.com").contains("TENANT");
        assertThat(dlg.select(".dialog__foot button").text()).isEqualTo("닫기");
        Element host = d.selectFirst("tr.table__expanded[data-id=" + id + "] div[x-data]");
        assertThat(host.attr("x-data")).contains("\"id\":\"audit-evt-" + id + "\"");
    }

    @Test
    @DisplayName("사용자가 정하는 값(action·행위자·대상·payload)은 이스케이프되고 JS 평가 속성에는 들어가지 않는다")
    void escapesUserControlledValues() throws Exception {
        String evil = "<script>alert(1)</script>";
        stubPage(0, List.of(ev("x", evil + "@x.com", evil, evil, evil, "{\"k\":\"" + evil + "\",\"before\":{\"a\":\"" + evil + "\"},\"after\":{\"a\":\"2\"}}", -0.1)));
        MvcResult r = hget(BASE + "/tab/audit", platform());
        String html = body(r);
        assertThat(html).doesNotContain(evil);
        Document d = Jsoup.parse(html);
        assertThat(d.select("script")).isEmpty();
        assertThat(d.select("#audit-card .badge").first().text()).isEqualTo(evil);
        // x-data·x-on 등 JS 평가 속성에 사용자 값이 없다
        for (Element e : d.getAllElements()) {
            for (org.jsoup.nodes.Attribute at : e.attributes()) {
                boolean jsAttr = at.getKey().startsWith("x-") || at.getKey().startsWith("hx-vals");
                // x-data 는 서버가 JSON 으로 직렬화한 데이터(copyBtn·dialog)만 — 코드로 실행되는 자리가 아니다
                if (jsAttr && !(at.getKey().equals("x-data") && at.getValue().startsWith("copyBtn("))) assertThat(at.getValue()).doesNotContain("alert(1)");
            }
        }
    }

    // ── 체인 검증 카드 ──────────────────────────────────────

    @Test
    @DisplayName("체인 검증 카드·다이얼로그는 PLATFORM 전용(RP_ADMIN 에게는 그리지 않는다)")
    void chainCardPlatformOnly() throws Exception {
        when(tenants.get(T1.toString())).thenReturn(ApiResponse.ok(tenant()));
        Document p = tab("", platform());
        assertThat(p.select("#audit-chain .card__title").text()).isEqualTo("감사 로그 위변조 검증");
        assertThat(p.select("#audit-chain .badge").eachText()).contains("PLATFORM 전용");
        assertThat(p.select("#audit-chain [x-data^=dialog], #audit-chain [x-data*=audit-verify]")).isNotEmpty();
        Element wrap = p.selectFirst("#audit-chain");
        assertThat(wrap.attr("hx-get")).isEqualTo(BASE + "/audit/verify");
        assertThat(wrap.attr("hx-trigger")).isEqualTo("verify-run");
        assertThat(wrap.attr("hx-sync")).isEqualTo("this:drop");
        assertThat(wrap.attr("hx-request")).contains("120000");
        assertThat(wrap.select("[role=dialog] , template").html()).contains("전체 체인 검증").contains("검증 중…");
        assertThat(p.select("#audit-chain a.btn")).isEmpty(); // 월간 보고서는 무결 결과가 있을 때만

        Document rpDoc = tab("", rp(T1));
        assertThat(rpDoc.select("#audit-chain")).isEmpty();
        assertThat(rpDoc.select("#audit-card")).hasSize(1);
    }

    @Test
    @DisplayName("검증 실행: verifyTenant(tenant) 호출, 무결 → '위변조 0건' + 마지막 검증(KST) + 월간 보고서 링크")
    void verifyIntact() throws Exception {
        when(chain.verifyTenant(T1.toString())).thenReturn(new AuditChainMonitorController.TenantVerifyResponse(T1, true, null, NOW));
        MvcResult r = hget(BASE + "/audit/verify", platform());
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        Document d = doc(r);
        verify(chain).verifyTenant(T1.toString());
        assertThat(d.select("#audit-chain > .card .badge:not([x-show=running])").eachText()).containsExactly("위변조 0건", "PLATFORM 전용");
        assertThat(d.select("#audit-chain .card__sub").text()).contains("마지막 검증 2026-10-11 09:00 KST");
        assertThat(d.select("#audit-chain a.btn").text()).isEqualTo("월간 보고서");
        assertThat(d.select("#audit-chain a.btn").attr("href")).isEqualTo("/admin/audit-chain");
        assertThat(d.select("#audit-chain .chain-ico--ok")).hasSize(1);
        assertThat(body(r)).doesNotContain("<html");
    }

    @Test
    @DisplayName("검증 결과가 위변조면 '위변조 N건'(tamperedEntryIds 개수), 월간 보고서 없음")
    void verifyTampered() throws Exception {
        when(chain.verifyTenant(T1.toString())).thenReturn(new AuditChainMonitorController.TenantVerifyResponse(T1, false, UUID.randomUUID(), NOW));
        Document d = doc(hget(BASE + "/audit/verify", platform()));
        assertThat(d.select("#audit-chain > .card .badge:not([x-show=running])").eachText()).containsExactly("위변조 1건", "PLATFORM 전용");
        assertThat(d.select("#audit-chain .chain-ico--bad")).hasSize(1);
        assertThat(d.select("#audit-chain a.btn")).isEmpty();
    }

    @Test
    @DisplayName("검증 실패: 실제 오류 상태 + 토스트(본문 없음) — 카드는 교체되지 않는다. RP_ADMIN 은 403 이고 호출 없음")
    void verifyFailureAndRole() throws Exception {
        when(chain.verifyTenant(T1.toString())).thenThrow(new IllegalStateException("secret-stack"));
        MvcResult r = hget(BASE + "/audit/verify", platform());
        assertThat(r.getResponse().getStatus()).isGreaterThanOrEqualTo(500);
        assertThat(body(r).trim()).isEmpty();
        String trig = JSON.readTree(r.getResponse().getHeader("HX-Trigger")).toString();
        assertThat(trig).contains("체인 검증에 실패했습니다").doesNotContain("secret-stack");

        org.mockito.Mockito.clearInvocations(chain);
        assertThat(hget(BASE + "/audit/verify", rp(T1)).getResponse().getStatus()).isEqualTo(403);
        verify(chain, never()).verifyTenant(any());
    }

    // ── CSV ─────────────────────────────────────────────────

    @Test
    @DisplayName("CSV(csvMatchesOriginalFormat): BOM·헤더·열 순서·payload 는 compact JSON(없으면 {})·현재 필터의 행만·파일명 audit-{slug}-{KST 오늘}.csv")
    void csvMatchesOriginalFormat() throws Exception {
        MvcResult r = mvc.perform(htmx(get(URI.create("/admin/tenants/" + T1 + "/audit.csv")), platform())).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"audit-acme-2026-10-11.csv\"");
        assertThat(r.getResponse().getContentType()).startsWith("text/csv");
        byte[] raw = r.getResponse().getContentAsByteArray();
        assertThat(raw[0] & 0xff).isEqualTo(0xEF); // UTF-8 BOM
        assertThat(raw[1] & 0xff).isEqualTo(0xBB);
        assertThat(raw[2] & 0xff).isEqualTo(0xBF);
        String csv = new String(raw, StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿");
        String[] lines = csv.substring(1).split("\n");
        assertThat(lines[0]).isEqualTo("ts,eventType,actorType,actorId,subjectType,subjectId,payload");
        assertThat(lines).hasSize(5);
        // payload 의 쉼표·따옴표 → 따옴표로 감싸고 "" 로 이스케이프
        assertThat(lines[1]).contains(",WEBAUTHN_CONFIG_UPDATED,ADMIN,admin@x.com,TENANT,t1,\"{\"\"timeoutMs\"\":{\"\"from\"\":60000,\"\"to\"\":120000}}\"");
        assertThat(lines[2]).contains(",AUTH_SUCCESS,SYSTEM,,CREDENTIAL,credAAAAAAAAAAAAAAAAAAAA1,{}");
        assertThat(lines[3]).endsWith(",ATTESTATION_TRUST_FAILED,SYSTEM,,CREDENTIAL,c3,\"{\"\"reason\"\":\"\"bad\"\"}\"");
        assertThat(lines[4]).contains("API_KEY_ROTATED,ADMIN,ops@x.com").endsWith("\"{\"\"raw\"\":\"\"not json\"\"}\"");
        assertThat(lines[1]).startsWith("2026-10-10T23:"); // 서버 JSON 의 ts 문자열(원본 e.ts)

        // 필터: 현재 화면과 같은 행만
        String filtered = new String(mvc.perform(htmx(get(URI.create("/admin/tenants/" + T1 + "/audit.csv?failures=true")), platform())).andReturn()
                .getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(filtered.substring(1).split("\n")).hasSize(2);
        assertThat(filtered).contains("ATTESTATION_TRUST_FAILED").doesNotContain("AUTH_SUCCESS,SYSTEM");
    }

    @Test
    @DisplayName("CSV 수식 주입 방지: = + - @ 로 시작하는 행위자·대상·payload 앞에 '를 붙인다. 파일명은 안전한 문자만")
    void csvNeutralizesFormulas() throws Exception {
        stubPage(0, List.of(ev("f1", "=cmd|' /C calc'!A0", "AUTH_SUCCESS", "+SUM(1)", "@x", "-1", -0.1)));
        when(tenants.get(T1.toString())).thenReturn(ApiResponse.ok(new TenantAdminDto.TenantView(T1, "a/b\"c\r\n", "Acme", "ACTIVE", "x.com", "Acme",
                List.of(), Set.of(), true, false, "NONE", 60000, 0, 0, null, NOW.atOffset(ZoneOffset.UTC), NOW.atOffset(ZoneOffset.UTC))));
        MvcResult r = mvc.perform(htmx(get(URI.create("/admin/tenants/" + T1 + "/audit.csv")), platform())).andReturn();
        String csv = new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(csv).contains(",'=cmd|' /C calc'!A0,").contains(",'+SUM(1),").contains(",'@x,").endsWith(",'-1");
        assertThat(r.getResponse().getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"audit-a_b_c__-2026-10-11.csv\"");
    }

    @Test
    @DisplayName("체인 카드 래퍼는 hx-target/swap/sync/request 를 자식에 상속시키지 않는다(월간 보고서 링크가 카드를 바꾸지 않게), begin/end 는 자기 요청만")
    void chainWrapperDisinheritsAndGuardsOwnRequest() throws Exception {
        Element wrap = tab("").selectFirst("#audit-chain");
        // hx-request 는 htmx 2.0.4 가 disinherit 와 무관하게 읽으므로 목록에 없다
        assertThat(wrap.attr("hx-disinherit")).isEqualTo("hx-target hx-swap hx-sync");
        assertThat(wrap.attr("x-on:htmx:before-request")).contains("$event.detail.elt === $el");
        assertThat(wrap.attr("x-on:htmx:after-request")).contains("$event.detail.elt === $el");
        when(chain.verifyTenant(T1.toString())).thenReturn(new AuditChainMonitorController.TenantVerifyResponse(T1, true, null, NOW));
        Element link = doc(hget(BASE + "/audit/verify", platform())).selectFirst("#audit-chain a.btn");
        assertThat(link.attr("href")).isEqualTo("/admin/audit-chain");
        assertThat(link.parents().stream().filter(e -> e.hasAttr("hx-disinherit")).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("CSV: 이후 쪽 읽기가 실패하면 부분 파일을 주지 않고 오류(5xx)")
    void csvRejectsPartialFailure() throws Exception {
        List<AuditLogView> first = new ArrayList<>();
        for (int i = 0; i < 50; i++) first.add(ev("a" + i, null, "AUTH_SUCCESS", "CREDENTIAL", "c", null, -0.001 * i));
        stubPage(0, first);
        when(audit.list(isNull(), isNull(), eq(T1), any(), any(), eq(1), eq(50))).thenThrow(new IllegalStateException("boom"));
        MvcResult r = mvc.perform(htmx(get(URI.create("/admin/tenants/" + T1 + "/audit.csv?pages=2&to=2026-10-11T00:00:00.000Z")), platform())).andReturn();
        assertThat(r.getResponse().getStatus()).isGreaterThanOrEqualTo(500);
        assertThat(r.getResponse().getContentAsByteArray()).isEmpty();
    }

    @Test
    @DisplayName("CSV(불러온 범위): pages = 화면이 불러온 쪽 수 — 같은 구간의 0..pages-1 쪽을 읽는다(40쪽 상한 없음), 50건 미만 쪽에서 멈춘다")
    void csvReadsLoadedPages() throws Exception {
        for (int p = 0; p < 44; p++) stubPage(p, fullPage("c" + p + "_", -0.001 - p * 0.006));
        stubPage(44, List.of(ev("last", null, "AUTH_SUCCESS", "CREDENTIAL", "c", null, -0.9)));
        MvcResult r = mvc.perform(htmx(get(URI.create("/admin/tenants/" + T1 + "/audit.csv?win=30d&pages=45&to=2026-10-11T00:00:00.000Z")), platform())).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        String csv = new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(csv.substring(1).split("\n")).hasSize(1 + 44 * 50 + 1);
        verify(audit, times(45)).list(isNull(), isNull(), eq(T1), any(), any(), anyInt(), eq(50));
        verify(audit, never()).list(any(), any(), any(), any(), any(), eq(45), anyInt());
        // 필터(g·failures)는 화면과 같다 — 실패만이면 ATTESTATION_TRUST_FAILED 행만
        org.mockito.Mockito.clearInvocations(audit);
        MvcResult f = mvc.perform(htmx(get(URI.create("/admin/tenants/" + T1 + "/audit.csv?win=30d&pages=2&failures=true&g=registration&g=auth&to=2026-10-11T00:00:00.000Z")), platform())).andReturn();
        String fc = new String(f.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(fc.substring(1).split("\n")).hasSize(1 + 50); // 두 쪽(100건) 중 ATTESTATION_TRUST_FAILED(registration 그룹·실패) 50건
        assertThat(fc).doesNotContain("WEBAUTHN_CONFIG_UPDATED");
    }

    @Test
    @DisplayName("CSV 안전 상한: pages 가 400쪽을 넘으면 422 + 이유 문구(JSON message) — 조용한 일부 파일 없음, REST 미호출")
    void csvSanityLimit() throws Exception {
        MvcResult r = mvc.perform(htmx(get(URI.create("/admin/tenants/" + T1 + "/audit.csv?pages=401&to=2026-10-11T00:00:00.000Z")), platform())).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
        assertThat(r.getResponse().getContentType()).startsWith("application/json");
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("\"message\"").contains("20,000건");
        verify(audit, never()).list(any(), any(), any(), any(), any(), anyInt(), anyInt());
        // 400쪽은 허용
        assertThat(mvc.perform(htmx(get(URI.create("/admin/tenants/" + T1 + "/audit.csv?pages=400")), platform())).andReturn().getResponse().getStatus()).isEqualTo(200);
    }
    @Test
    @DisplayName("CSV 링크는 공통 다운로드 표식(data-csv-download)과 href 를 함께 가진다(JS 없이도 동작)")
    void csvLinkCarriesMarker() throws Exception {
        Element a = tab("").selectFirst("#audit-card a[download]");
        assertThat(a.hasAttr("data-csv-download")).isTrue();
        assertThat(a.attr("href")).contains("/audit.csv?");
    }

    // ── 경계 ────────────────────────────────────────────────

    @Test
    @DisplayName("RP_ADMIN 이 다른 tenant 의 탭·카드·검증·CSV → 403, REST 목록·검증 호출 없음 (TenantTabs.load 먼저)")
    void crossTenantForbidden() throws Exception {
        when(tenants.get(OTHER.toString())).thenThrow(new BusinessException(ErrorCode.ACCESS_DENIED, "RP_ADMIN cannot access tenant " + OTHER));
        String base = "/admin/_/tenants/" + OTHER;
        Authentication rp = rp(T1);
        assertThat(hget(base + "/tab/audit", rp).getResponse().getStatus()).isEqualTo(403);
        assertThat(hget(base + "/tab/audit/card", rp).getResponse().getStatus()).isEqualTo(403);
        assertThat(hget(base + "/tab/audit/more?page=1", rp).getResponse().getStatus()).isEqualTo(403);
        assertThat(hget(base + "/audit/verify", rp).getResponse().getStatus()).isEqualTo(403);
        assertThat(hget("/admin/tenants/" + OTHER + "/audit.csv", rp).getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(get(URI.create("/admin/tenants/" + OTHER + "/audit.csv")).with(authentication(rp))).andReturn().getResponse().getStatus()).isEqualTo(403);
        verify(audit, never()).list(any(), any(), any(), any(), any(), anyInt(), anyInt());
        verify(chain, never()).verifyTenant(any());
    }

    @Test
    @DisplayName("리터럴 탭 매핑이 일반 {tab} 매핑(개요)보다 우선한다")
    void literalMappingWins() throws Exception {
        Document d = tab("");
        assertThat(d.select("#audit-root")).hasSize(1);
        assertThat(d.select(".kpis")).isEmpty();
    }
}
