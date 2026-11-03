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
import org.springframework.boot.test.mock.mockito.MockBean;
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
    @MockBean TenantAdminController tenants;
    @MockBean AuditLogController audit;
    @MockBean AuditChainMonitorController chain;
    @MockBean AaguidPolicyController aaguid;
    @MockBean LicenseController license;
    @MockBean ActiveTenantController activeTenants;

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
    @DisplayName("건수: total 이 없으므로 '최근 N건', 필터가 있으면 '이 페이지 N건 중 M건'")
    void countText() throws Exception {
        assertThat(tab("").select("#audit-card .card__foot .hint").text()).isEqualTo("최근 4건");
        assertThat(tab("?failures=true").select("#audit-card .card__foot .hint").text()).isEqualTo("이 페이지 4건 중 1건");
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
    @DisplayName("기간 세그먼트의 주소: 기간을 바꾸면 구간 끝(to)을 새로 잡고 1쪽부터, 필터는 유지")
    void windowSegmentUrls() throws Exception {
        Document d = tab("?g=keys&failures=true&pages=1&to=2026-10-10T23:00:00.000Z");
        Element seg = d.selectFirst("#audit-card .seg");
        assertThat(seg.attr("aria-label")).isEqualTo("기간");
        assertThat(seg.select("button").eachText()).containsExactly("24시간", "7일", "30일");
        String seven = seg.select("button").get(1).attr("hx-get");
        assertThat(seven).isEqualTo(BASE + "/tab/audit/card?win=7d&pages=1&failures=true&g=keys");
        assertThat(seg.select("button").get(0).attr("aria-pressed")).isEqualTo("true");
    }

    @Test
    @DisplayName("더 보기: 50건이면 버튼, 같은 구간(to)으로 다음 쪽까지 다시 읽고 붙인다 — 50건 미만이면 버튼 없음")
    void loadMoreSameRange() throws Exception {
        List<AuditLogView> first = new ArrayList<>();
        for (int i = 0; i < 50; i++) first.add(ev("a" + i, null, "WEBAUTHN_CONFIG_UPDATED", "TENANT", "t1", null, -0.001 * i));
        stubPage(0, first);
        stubPage(1, List.of(ev("b0", null, "AUTH_SUCCESS", "CREDENTIAL", "c", null, -0.5)));

        Document d = tab("");
        Element more = d.selectFirst("#audit-card .card__foot button");
        assertThat(more.text()).isEqualTo("더 보기");
        String url = more.attr("hx-get");
        assertThat(url).startsWith(BASE + "/tab/audit/card?win=24h&pages=2&to=2026-10-11T00%3A00%3A00.000Z");

        Document d2 = doc(hget(url, platform()));
        assertThat(d2.select("#audit-card tbody tr.is-clickable")).hasSize(51);
        assertThat(d2.select("#audit-card .card__foot .hint").text()).isEqualTo("최근 51건");
        assertThat(d2.select("#audit-card .card__foot button")).isEmpty();
        // 두 쪽 모두 같은 from/to
        ArgumentCaptor<OffsetDateTime> from = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> to = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(audit, times(2)).list(isNull(), isNull(), eq(T1), from.capture(), to.capture(), eq(0), eq(50));
        verify(audit).list(isNull(), isNull(), eq(T1), eq(from.getAllValues().get(1)), eq(to.getAllValues().get(1)), eq(1), eq(50));
        assertThat(from.getAllValues().get(0).toInstant().plus(Duration.ofHours(24))).isEqualTo(to.getAllValues().get(0).toInstant());
    }

    @Test
    @DisplayName("더 보기 쪽 읽기가 실패하면 읽은 행은 남기고 토스트(+ 더 보기 다시 활성), 첫 쪽 실패는 LoadError")
    void loadMoreFailure() throws Exception {
        List<AuditLogView> first = new ArrayList<>();
        for (int i = 0; i < 50; i++) first.add(ev("a" + i, null, "WEBAUTHN_CONFIG_UPDATED", "TENANT", "t1", null, -0.001 * i));
        stubPage(0, first);
        when(audit.list(isNull(), isNull(), eq(T1), any(), any(), eq(1), eq(50))).thenThrow(new IllegalStateException("db password=secret"));
        MvcResult r = hget(BASE + "/tab/audit/card?pages=2&to=2026-10-11T00:00:00.000Z", platform());
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        Document d = doc(r);
        assertThat(d.select("#audit-card tbody tr.is-clickable")).hasSize(50);
        assertThat(d.select("#audit-card .card__foot button").text()).isEqualTo("더 보기");
        assertThat(d.select("#audit-card .card__foot button").attr("hx-get")).contains("pages=2");
        JsonNode t = JSON.readTree(r.getResponse().getHeader("HX-Trigger"));
        assertThat(t.toString()).contains("감사 로그를 더 불러오지 못했습니다").doesNotContain("secret");
        assertThat(body(r)).doesNotContain("secret");
    }

    // ── 필터 ────────────────────────────────────────────────

    @Test
    @DisplayName("실패만: 실패 액션 목록·이름 규칙으로 현재 페이지를 거른다 + 필터 지우기 노출")
    void failuresOnly() throws Exception {
        Document d = tab("?failures=true");
        assertThat(d.select("#audit-card tbody tr.is-clickable .badge").eachText()).containsExactly("ATTESTATION_TRUST_FAILED");
        Element sw = d.selectFirst("#audit-card input[role=switch]");
        assertThat(sw.hasAttr("checked")).isTrue();
        assertThat(d.select("#audit-card .card__head button").stream().anyMatch(b -> b.text().equals("필터 지우기"))).isTrue();
        // 스위치를 끄는 요청 = failures 없는 같은 상태
        assertThat(sw.attr("hx-get")).isEqualTo(BASE + "/tab/audit/card?win=24h&pages=1&to=" + TO0);
        assertThat(sw.attr("hx-trigger")).isEqualTo("change");
    }

    @Test
    @DisplayName("이벤트 종류 그룹 칩: 라벨 5개(원시 코드는 title), 눌림 상태, 토글 주소, 어느 그룹에도 없는 건 '기타'")
    void groupChips() throws Exception {
        Document d = tab("?g=keys");
        Element group = d.selectFirst("#audit-card [role=group][aria-label=이벤트 종류]");
        assertThat(group.select("button").eachText()).containsExactly("인증", "등록", "설정 변경", "키 관리", "기타");
        Element keys = group.select("button").get(3);
        assertThat(keys.attr("title")).isEqualTo("API_KEY_* · CREDENTIAL_REVOKE*");
        assertThat(keys.attr("aria-pressed")).isEqualTo("true");
        assertThat(group.select("button").get(0).attr("aria-pressed")).isEqualTo("false");
        assertThat(d.select("#audit-card tbody tr.is-clickable .badge").eachText()).containsExactly("API_KEY_ROTATED");
        // 켜진 칩을 누르면 g 에서 빠지고, 꺼진 칩을 누르면 추가된다
        assertThat(keys.attr("hx-get")).isEqualTo(BASE + "/tab/audit/card?win=24h&pages=1&to=" + TO0);
        assertThat(group.select("button").get(0).attr("hx-get")).endsWith("&g=keys&g=auth");
        // 필터 지우기
        Element clear = d.select("#audit-card .card__head button").stream().filter(b -> b.text().equals("필터 지우기")).findFirst().orElseThrow();
        assertThat(clear.attr("hx-get")).isEqualTo(BASE + "/tab/audit/card?win=24h&pages=1&to=" + TO0);

        stubPage(0, List.of(ev("x1", null, "MDS_BLOB_SYNC", "SYSTEM", "s", null, -0.1), ev("x2", null, "AUTH_SUCCESS", "CREDENTIAL", "c", null, -0.1)));
        assertThat(tab("?g=other").select("#audit-card tbody tr.is-clickable .badge").eachText()).containsExactly("MDS_BLOB_SYNC");
        // 알 수 없는 그룹 값은 무시
        assertThat(tab("?g=bogus").select("#audit-card tbody tr.is-clickable")).hasSize(2);
    }

    @Test
    @DisplayName("빈 목록: 필터가 없으면 '이 기간에 감사 이벤트가 없습니다.', 필터가 있으면 '조건에 맞는 이벤트가 없습니다.'")
    void emptyStates() throws Exception {
        stubPage(0, List.of());
        assertThat(tab("").select("#audit-card tr.table__empty").text()).isEqualTo("이 기간에 감사 이벤트가 없습니다.");
        stubPage(0, List.of(ev("e1", null, "AUTH_SUCCESS", "CREDENTIAL", "c", null, -0.1)));
        assertThat(tab("?failures=true").select("#audit-card tr.table__empty").text()).isEqualTo("조건에 맞는 이벤트가 없습니다.");
    }

    @Test
    @DisplayName("자동 갱신(폴링)은 없다(원본에 없음) — every 트리거가 없고, 모든 컨트롤의 hx-get 이 현재 필터·구간을 싣는다. 대상은 #tenant-tab 이 아니라 카드(this)")
    void pollingKeepsFilters() throws Exception {
        MvcResult r = hget(BASE + "/tab/audit?win=7d&g=auth&g=registration&failures=true&pages=1&to=2026-10-10T23:00:00.000Z", platform());
        Document d = doc(r);
        assertThat(body(r)).doesNotContain("every ").doesNotContain("hx-trigger=\"load\"");
        Element card = d.selectFirst("#audit-card");
        assertThat(card.attr("hx-target")).isEqualTo("this");
        assertThat(card.attr("hx-swap")).isEqualTo("outerHTML");
        assertThat(card.attr("hx-sync")).isEqualTo("this:replace");
        assertThat(d.select("[hx-target=#tenant-tab]")).isEmpty();
        // 구간 끝 to 가 컨트롤마다 이어진다(기간 세그먼트·다시 시도 제외)
        for (Element e : card.select("[hx-get]")) {
            String u = e.attr("hx-get");
            assertThat(u).startsWith(BASE + "/tab/audit/card?");
            if (e.parent().hasClass("seg")) continue;
            assertThat(u).contains("to=2026-10-10T23%3A00%3A00.000Z");
        }
        assertThat(card.select(".card__foot button, #audit-card .card__head button.btn--ghost").stream()
                .filter(b -> b.hasAttr("hx-get")).allMatch(b -> b.attr("hx-get").contains("g=auth&g=registration") || b.text().equals("필터 지우기"))).isTrue();
        // CSV 링크도 같은 상태
        assertThat(card.select("a[download]").attr("href")).startsWith("/admin/tenants/" + T1 + "/audit.csv?win=7d&pages=1&to=").contains("failures=true").contains("g=auth&g=registration");
    }

    // ── 오류 ────────────────────────────────────────────────

    @Test
    @DisplayName("로드 실패: '이벤트 없음'이 아니라 LoadError(다시 시도 = 새 구간 1쪽), 건수 —, CSV 비활성, 내부 메시지 비노출")
    void loadFailure() throws Exception {
        when(audit.list(isNull(), isNull(), eq(T1), any(), any(), anyInt(), anyInt())).thenThrow(new IllegalStateException("jdbc:secret"));
        MvcResult r = hget(BASE + "/tab/audit?win=7d&g=keys&to=2026-10-10T23:00:00.000Z", platform());
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(r)).doesNotContain("secret");
        Document d = doc(r);
        assertThat(d.select("#audit-card [role=alert]").text()).contains("감사 로그를 불러오지 못했습니다");
        assertThat(d.select("#audit-card tr.table__empty")).isEmpty();
        assertThat(d.select("#audit-card .card__foot .hint").text()).isEqualTo("—");
        assertThat(d.select("#audit-card .card__head button[disabled]").stream().anyMatch(b -> b.text().startsWith("CSV"))).isTrue();
        Element retry = d.select("#audit-card [role=alert] button").stream().filter(b -> b.text().equals("다시 시도")).findFirst().orElseThrow();
        assertThat(retry.attr("hx-get")).isEqualTo(BASE + "/tab/audit/card?win=7d&pages=1&g=keys");
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

    // ── 경계 ────────────────────────────────────────────────

    @Test
    @DisplayName("RP_ADMIN 이 다른 tenant 의 탭·카드·검증·CSV → 403, REST 목록·검증 호출 없음 (TenantTabs.load 먼저)")
    void crossTenantForbidden() throws Exception {
        when(tenants.get(OTHER.toString())).thenThrow(new BusinessException(ErrorCode.ACCESS_DENIED, "RP_ADMIN cannot access tenant " + OTHER));
        String base = "/admin/_/tenants/" + OTHER;
        Authentication rp = rp(T1);
        assertThat(hget(base + "/tab/audit", rp).getResponse().getStatus()).isEqualTo(403);
        assertThat(hget(base + "/tab/audit/card", rp).getResponse().getStatus()).isEqualTo(403);
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
