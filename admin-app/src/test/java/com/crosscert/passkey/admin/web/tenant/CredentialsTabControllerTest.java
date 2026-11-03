package com.crosscert.passkey.admin.web.tenant;

import com.crosscert.passkey.admin.auth.ActiveTenantController;
import com.crosscert.passkey.admin.auth.AdminUserDetails;
import com.crosscert.passkey.admin.audit.AuditChainMonitorController;
import com.crosscert.passkey.admin.credential.CredentialAdminController;
import com.crosscert.passkey.admin.credential.CredentialAdminDto.AuthEventView;
import com.crosscert.passkey.admin.credential.CredentialAdminDto.CredentialView;
import com.crosscert.passkey.admin.license.LicenseController;
import com.crosscert.passkey.admin.license.LicenseView;
import com.crosscert.passkey.admin.policy.AaguidPolicyController;
import com.crosscert.passkey.admin.tenant.TenantAdminController;
import com.crosscert.passkey.admin.tenant.TenantAdminDto;
import com.crosscert.passkey.admin.web.AdminWebSliceTest;
import com.crosscert.passkey.core.api.ApiResponse;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import com.crosscert.passkey.core.api.PageView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 자격 증명 탭. 원본: CredentialsTab.test.tsx, CredentialDetailDialog.test.tsx, credentialHelpers.test.ts, api/credentials.ts.
 * 브라우저에서 도는 부분(행 클릭 이벤트·다이얼로그 열기/닫기·입력 확인 게이트·디바운스)은 속성·소스 수준까지만 본다.
 */
@AdminWebSliceTest(controllers = CredentialsTabController.class)
class CredentialsTabControllerTest {

    @Autowired MockMvc mvc;
    @Autowired Clock clock;
    @MockBean TenantAdminController tenants;
    @MockBean CredentialAdminController credentials;
    @MockBean AaguidPolicyController aaguid;
    @MockBean LicenseController license;
    @MockBean AuditChainMonitorController chain;
    @MockBean ActiveTenantController activeTenants;

    static final ObjectMapper JSON = new ObjectMapper();
    static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");
    static final UUID T1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
    static final String BASE = "/admin/_/tenants/" + T1;
    static final String CA = "credAAAAAAAAAAAAAAAAAAAA1";
    static final String CB = "credBBBBBBBBBBBBBBBBBBBB2";
    static final String CC = "credCCCCCCCCCCCCCCCCCCCC3";
    static final String FOREIGN = "credFOREIGNxxxxxxxxxxxx9";
    static final String AAGUID_A = "ea9b8d664d8f4b9d9a0f1c3b5e7a2d41";
    static final String AAGUID_B = "00000000000000000000000000000000";

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(license.get()).thenReturn(ApiResponse.ok(new LicenseView("saas", "VALID", "acme", "lic_1", NOW.plusSeconds(86400L * 30), 30, List.of(), null, null, null)));
        when(tenants.get(T1.toString())).thenReturn(ApiResponse.ok(tenant()));
        stubList(List.of(cred(CA, "user-a", "내 폰", AAGUID_A, "YubiKey 5", "usb,nfc", 5, at(-0.01)),
                cred(CB, "user-b", null, AAGUID_B, null, "internal,hybrid", 0, at(-1)),
                cred(CC, "user-c", null, null, null, null, 0, null)));
        // 기본: id 검색(q=credentialId)은 못 찾음
        when(credentials.list(eq(T1), eq(0), eq(50), eq(FOREIGN))).thenReturn(ApiResponse.ok(page(List.of(), 0)));
        when(credentials.authEvents(eq(T1), any(), eq(0), eq(20))).thenReturn(ApiResponse.ok(new PageView<>(List.of(
                new AuthEventView("SUCCESS", null, 6, at(-0.01)), new AuthEventView("FAILURE", "BAD_SIGNATURE", 5, at(-2))), 0, 20, 2, false)));
    }

    // ── fixtures ────────────────────────────────────────────

    static OffsetDateTime at(double days) {
        return OffsetDateTime.ofInstant(NOW.plusSeconds((long) (days * 86400)), ZoneOffset.UTC);
    }

    static CredentialView cred(String id, String user, String label, String aaguid, String name, String transports, long count, OffsetDateTime last) {
        return new CredentialView(id, user, label, aaguid, name, "packed", transports, count, last, at(-30));
    }

    static PageView<CredentialView> page(List<CredentialView> items, long total) {
        return new PageView<>(items, 0, 50, total, false);
    }

    private void stubList(List<CredentialView> items) {
        when(credentials.list(eq(T1), eq(0), eq(50), isNull())).thenReturn(ApiResponse.ok(page(items, items.size())));
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

    private static MockHttpServletRequestBuilder confirm(MockHttpServletRequestBuilder b, Authentication a) {
        return htmx(b, a).header("X-Confirm", "1");
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

    private Document tab(String q) throws Exception {
        MvcResult r = hget(BASE + "/tab/credentials" + q, platform());
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return doc(r);
    }

    private static JsonNode trigger(MvcResult r) throws Exception {
        String h = r.getResponse().getHeader("HX-Trigger");
        return h == null ? null : JSON.readTree(h);
    }

    private static Element rowOf(Document d, String userHandle) {
        return d.select("#cred-results tbody tr").stream().filter(tr -> tr.text().contains(userHandle)).findFirst().orElseThrow();
    }

    // ── 탭 화면 ─────────────────────────────────────────────

    @Test
    @DisplayName("열: Credential ID(+복사)·사용자(title 전체값)·별칭·인증기(MDS 이름 → aaguid 끝 8자 → unknown)·전송 방식(한국어, 원문 title)·카운터·상태·마지막 사용·회수(ACTIVE)")
    void rendersColumnsAndCells() throws Exception {
        MvcResult r = hget(BASE + "/tab/credentials", platform());
        String html = body(r);
        assertThat(html).doesNotContain("<html").doesNotContain("class=\"shell");
        Document d = Jsoup.parse(html);
        assertThat(d.select("#cred-results th").eachText()).containsExactly("Credential ID", "사용자", "별칭", "인증기", "전송 방식", "서명 카운터", "상태", "마지막 사용", "회수");
        assertThat(d.select("#cred-results th .sr-only").text()).isEqualTo("회수");
        assertThat(d.select("#cred-root #cred-card #cred-results")).hasSize(1);
        assertThat(d.select("#cred-detail-slot, #cred-confirm-slot")).hasSize(2);

        Element a = rowOf(d, "user-a");
        assertThat(a.select("td").get(0).select(".mono").attr("title")).isEqualTo(CA);
        assertThat(a.select("button[aria-label=Credential ID 복사]")).hasSize(1);
        assertThat(a.select("td").get(1).select(".mono").attr("title")).isEqualTo("user-a");
        assertThat(a.select("td").get(2).text()).isEqualTo("내 폰");
        assertThat(a.select("td").get(3).text()).isEqualTo("YubiKey 5");
        assertThat(a.select("td").get(3).select("[title]").attr("title")).isEqualTo(AAGUID_A);
        assertThat(a.select("td").get(4).select(".tag").eachText()).containsExactly("USB", "NFC");
        assertThat(a.select("td").get(4).select(".tag").eachAttr("title")).containsExactly("usb", "nfc");
        assertThat(a.select("td").get(5).text()).isEqualTo("5");
        assertThat(a.select("td").get(6).select(".badge").text()).isEqualTo("활성");
        assertThat(a.select("td").get(7).text()).isEqualTo("14분 전");
        assertThat(a.select("button[aria-label=회수 · …" + CA.substring(CA.length() - 8) + "]")).hasSize(1);

        Element b = rowOf(d, "user-b");
        assertThat(b.select("td").get(2).text()).isEqualTo("—");
        assertThat(b.select("td").get(3).text()).isEqualTo("00000000"); // aaguid 끝 8자
        assertThat(b.select("td").get(4).select(".tag").eachText()).containsExactly("내장", "휴대폰 연동");
        assertThat(b.select("td").get(5).text()).isEqualTo("N/A"); // 인증됐는데 counter 0
        assertThat(b.select("td").get(5).select("[title]").attr("title")).contains("counterless");
        Element c = rowOf(d, "user-c");
        assertThat(c.select("td").get(3).text()).isEqualTo("unknown");
        assertThat(c.select("td").get(5).text()).isEqualTo("0"); // 한 번도 안 씀
        assertThat(c.select("td").get(7).text()).isEqualTo("—");
    }

    @Test
    @DisplayName("행 클릭 = 상세(cred-open 이벤트 → hx-get, 상세 자리로), 셀 안 버튼·링크는 행 클릭이 아님, 키보드(Enter/Space)")
    void rowOpensDetail() throws Exception {
        Element tr = rowOf(tab(""), "user-a");
        assertThat(tr.attr("hx-get")).isEqualTo(BASE + "/credentials/" + CA + "?page=1&size=50");
        assertThat(tr.attr("hx-trigger")).isEqualTo("cred-open");
        assertThat(tr.attr("hx-target")).isEqualTo("#cred-detail-slot");
        assertThat(tr.attr("hx-swap")).isEqualTo("innerHTML");
        assertThat(tr.attr("x-on:click")).contains("closest('a, button')").contains("cred-open");
        assertThat(tr.hasAttr("x-on:keydown.self.enter.prevent")).isTrue();
        assertThat(tr.attr("tabindex")).isEqualTo("0");
        // 회수 버튼은 확인 자리로 가고 행 이벤트와 별개
        Element rev = tr.selectFirst("button[aria-label^=회수]");
        assertThat(rev.attr("hx-get")).isEqualTo(BASE + "/credentials/" + CA + "/revoke-confirm?page=1&size=50");
        assertThat(rev.attr("hx-target")).isEqualTo("#cred-confirm-slot");
    }

    @Test
    @DisplayName("credential 데이터(사용자 핸들·별칭·인증기 이름·AAGUID)는 th:text 로만: 이스케이프되고 JS 평가 속성·x-data 에 들어가지 않는다")
    void userInfluencedDataIsEscapedAndNotInJsAttrs() throws Exception {
        String evil = "<img src=x onerror=alert(1)>\"'";
        stubList(List.of(cred(CA, evil, evil, "ea9b" + evil, evil, evil, 1, at(-1))));
        MvcResult r = hget(BASE + "/tab/credentials", platform());
        String html = body(r);
        assertThat(html).doesNotContain("<img src=x").doesNotContain("onerror=alert(1)>");
        Document d = Jsoup.parse(html);
        Element tr = d.select("#cred-results tbody tr").first();
        assertThat(tr.select("td").get(1).text()).isEqualTo(evil);
        assertThat(tr.select("td").get(2).text()).isEqualTo(evil);
        for (Element e : d.select("#cred-results [x-data], #cred-results [x-on\\:click], #cred-results [x-bind\\:class]")) {
            for (var at : e.attributes()) {
                if (at.getKey().startsWith("x-")) assertThat(at.getValue()).as(at.getKey()).doesNotContain("onerror").doesNotContain("<img");
            }
        }
    }

    @Test
    @DisplayName("검색 입력: 실제 검색 범위 placeholder + 별칭은 안 된다는 title, 현재 입력값(hx-include)을 싣고 #cred-results 만 교체(#tenant-tab 아님)")
    void searchInputContract() throws Exception {
        Document d = tab("?q=%20abc%20");
        Element q = d.selectFirst("#cred-q");
        assertThat(q.attr("placeholder")).isEqualTo("Credential ID · 사용자 ID 검색");
        assertThat(q.attr("title")).isEqualTo("Credential ID 또는 사용자 ID 의 일부로 찾습니다. 별칭·인증기 이름은 검색되지 않습니다.");
        assertThat(q.attr("aria-label")).isEqualTo("Credential 검색");
        assertThat(q.val()).isEqualTo("abc"); // 앞뒤 공백 제거
        assertThat(q.attr("hx-get")).isEqualTo(BASE + "/tab/credentials/list");
        assertThat(q.attr("hx-trigger")).contains("input changed delay:300ms");
        assertThat(q.attr("hx-include")).contains("#cred-aaguid").contains("#cred-status").contains("#cred-size");
        assertThat(q.attr("hx-target")).isEqualTo("#cred-results");
        assertThat(q.attr("hx-swap")).isEqualTo("outerHTML");
        // 탭 영역(#tenant-tab)을 교체하는 요청은 이 조각 안에 없다
        for (Element e : d.select("[hx-target]")) assertThat(e.attr("hx-target")).isNotEqualTo("#tenant-tab");
        assertThat(d.select("#cred-card").attr("hx-sync")).isEqualTo("this:replace");
        // 검색어는 서버 REST q 로(공백 제거)
        verify(credentials).list(eq(T1), eq(0), eq(50), eq("abc"));
    }

    @Test
    @DisplayName("페이징·크기·필터 컨트롤은 현재 입력값(q·aaguid·status·size)을 hx-include 로 싣는다 — 새 요청이 입력을 잃지 않는다")
    void pagingControlsIncludeCurrentInputs() throws Exception {
        List<CredentialView> many = java.util.stream.IntStream.range(0, 50).mapToObj(i -> cred("c" + "x".repeat(20) + i, "u" + i, null, null, null, null, 1, null)).toList();
        when(credentials.list(eq(T1), eq(0), eq(50), isNull())).thenReturn(ApiResponse.ok(page(many, 120)));
        Document d = doc(hget(BASE + "/tab/credentials/list?status=ACTIVE&aaguid=ea9b", platform()));
        Element next = d.selectFirst("button[aria-label=다음 페이지]");
        assertThat(next.attr("hx-get")).isEqualTo(BASE + "/tab/credentials/list?page=2");
        assertThat(next.attr("hx-include")).contains("#cred-q").contains("#cred-aaguid").contains("#cred-status").contains("#cred-size");
        assertThat(next.attr("hx-target")).isEqualTo("#cred-results");
        assertThat(d.selectFirst("button[aria-label=이전 페이지]").hasAttr("disabled")).isTrue();
        assertThat(d.select(".pager > span").first().text()).isEqualTo("1–50 / 120건");
        assertThat(d.select(".pager > span").last().text()).isEqualTo("1 / 3");
        Element size = d.selectFirst(".pager select");
        assertThat(size.select("option").eachText()).containsExactly("50", "100", "200");
        assertThat(size.attr("hx-include")).contains("#cred-q").contains("#cred-aaguid").contains("#cred-status").doesNotContain("#cred-size");
        assertThat(size.attr("hx-trigger")).isEqualTo("change");
        assertThat(d.select("#cred-status").val()).isEqualTo("ACTIVE");
        assertThat(d.select("#cred-size").val()).isEqualTo("50");
    }

    @Test
    @DisplayName("목록 조각: 레이아웃 없음, 페이지·크기·검색어가 REST 로(page-1), 숫자가 아닌 page·size 는 기본값, 크기는 50/100/200 만")
    void listFragmentParamsAndDefaults() throws Exception {
        MvcResult r = hget(BASE + "/tab/credentials/list?page=abc&size=zzz&q=x", platform());
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(r)).doesNotContain("<html").doesNotContain("class=\"shell");
        verify(credentials).list(eq(T1), eq(0), eq(50), eq("x"));
        when(credentials.list(eq(T1), eq(1), eq(100), isNull())).thenReturn(ApiResponse.ok(new PageView<>(List.of(cred(CB, "p2", null, null, null, null, 1, null)), 1, 100, 101, false)));
        Document d = doc(hget(BASE + "/tab/credentials/list?page=2&size=100", platform()));
        assertThat(d.select("#cred-results tbody tr")).hasSize(1);
        assertThat(d.select(".pager > span").last().text()).isEqualTo("2 / 2");
        assertThat(d.select("#cred-size").val()).isEqualTo("100");
        when(credentials.list(eq(T1), eq(0), eq(50), isNull())).thenReturn(ApiResponse.ok(page(List.of(), 0)));
        hget(BASE + "/tab/credentials/list?size=77&page=0", platform());
        verify(credentials).list(eq(T1), eq(0), eq(50), isNull());
    }

    @Test
    @DisplayName("지운 뒤 마지막 페이지가 비어 버리면 새 마지막 페이지로 한 번 보정")
    void pageBeyondLastClamps() throws Exception {
        when(credentials.list(eq(T1), eq(2), eq(50), isNull())).thenReturn(ApiResponse.ok(new PageView<>(List.of(), 2, 50, 100, false)));
        when(credentials.list(eq(T1), eq(1), eq(50), isNull())).thenReturn(ApiResponse.ok(new PageView<>(List.of(cred(CA, "last-one", null, null, null, null, 1, null)), 1, 50, 100, false)));
        Document d = doc(hget(BASE + "/tab/credentials/list?page=3", platform()));
        assertThat(d.select("#cred-results tbody tr").text()).contains("last-one");
        assertThat(d.select(".pager > span").last().text()).isEqualTo("2 / 2");
    }

    @Test
    @DisplayName("목록 응답에는 필터 컨트롤 OOB(건수·CSV)가 들어 있고, 검색 입력·필터 패널은 reset 일 때만 OOB(타이핑 중 되돌려 쓰지 않는다)")
    void listResponseOob() throws Exception {
        Document d = doc(hget(BASE + "/tab/credentials/list?q=a", platform()));
        assertThat(d.selectFirst("#cred-controls").attr("hx-swap-oob")).isEqualTo("true");
        assertThat(d.select("#cred-q")).isEmpty();
        assertThat(d.select("#cred-filterpanel")).isEmpty();
        Document all = doc(hget(BASE + "/tab/credentials/list?reset=all&q=a&aaguid=zz&status=ACTIVE", platform()));
        assertThat(all.selectFirst("#cred-q").attr("hx-swap-oob")).isEqualTo("true");
        assertThat(all.selectFirst("#cred-q").val()).isEmpty();
        assertThat(all.selectFirst("#cred-filterpanel").attr("hx-swap-oob")).isEqualTo("true");
        assertThat(all.selectFirst("#cred-aaguid").val()).isEmpty();
        assertThat(all.select("#cred-status").val()).isEqualTo("ALL");
        assertThat(all.select(".chip")).isEmpty();
        Document ag = doc(hget(BASE + "/tab/credentials/list?reset=aaguid&q=a&aaguid=zz", platform()));
        assertThat(ag.select("#cred-q")).isEmpty(); // 검색어는 그대로(입력이 보낸 값)
        assertThat(ag.selectFirst("#cred-aaguid").val()).isEmpty();
    }

    @Test
    @DisplayName("건수·CSV 라벨: 필터가 없으면 서버 총 건수, 있으면 '이 페이지 N건 중 M건'")
    void countAndCsvLabels() throws Exception {
        when(credentials.list(eq(T1), eq(0), eq(50), isNull())).thenReturn(ApiResponse.ok(page(List.of(
                cred(CA, "user-a", null, AAGUID_A, null, null, 1, null), cred(CB, "user-b", null, AAGUID_B, null, null, 1, null)), 1234)));
        Document d = tab("");
        assertThat(d.select("#cred-controls .hint").text()).isEqualTo("1,234건");
        assertThat(d.select("#cred-controls a.btn").text()).isEqualTo("CSV 내보내기 (현재 페이지 2건)");
        Document f = doc(hget(BASE + "/tab/credentials/list?aaguid=EA9B8D66-4D8F", platform()));
        assertThat(f.select("#cred-controls .hint").text()).isEqualTo("이 페이지 2건 중 1건");
        assertThat(f.select("#cred-controls a.btn").text()).isEqualTo("CSV 내보내기 (현재 페이지 1건)");
        assertThat(f.select("#cred-results tbody tr")).hasSize(1);
        assertThat(f.select("#cred-results tbody").text()).contains("user-a").doesNotContain("user-b");
    }

    @Test
    @DisplayName("필터: aaguid 는 하이픈·대소문자 무시 부분 일치, 상태는 현재 페이지 안(서버 뷰에 status 없음 → 모두 활성, 회수됨은 0건), 적용 칩 + 제거 속성")
    void clientFilterAndChips() throws Exception {
        Document d = doc(hget(BASE + "/tab/credentials/list?status=REVOKED&aaguid=ea9b", platform()));
        assertThat(d.select("#cred-results tbody tr")).hasSize(1);
        assertThat(d.select("#cred-results .empty__title").text()).isEqualTo("검색 결과가 없습니다.");
        assertThat(d.select("#cred-results .empty__sub").text()).isEqualTo("검색어나 필터를 바꿔 보세요.");
        assertThat(d.select("#cred-results .empty__art").attr("src")).endsWith("/admin/img/no-results.webp");
        Element clear = d.select("#cred-results .empty button").stream().filter(e -> e.text().equals("필터 지우기")).findFirst().orElseThrow();
        assertThat(clear.attr("hx-get")).isEqualTo(BASE + "/tab/credentials/list?reset=all");
        assertThat(clear.attr("hx-target")).isEqualTo("#cred-results");
        Element chip = d.select("#cred-controls .chip").stream().filter(e -> e.text().startsWith("aaguid:")).findFirst().orElseThrow();
        assertThat(chip.text()).isEqualTo("aaguid: ea9b");
        assertThat(chip.selectFirst("button").attr("aria-label")).isEqualTo("aaguid 필터 제거");
        assertThat(chip.selectFirst("button").attr("hx-get")).isEqualTo(BASE + "/tab/credentials/list?reset=aaguid");
        Element st = d.select("#cred-controls .chip").stream().filter(e -> e.text().startsWith("상태:")).findFirst().orElseThrow();
        assertThat(st.text()).isEqualTo("상태: 회수됨");
        assertThat(st.attr("title")).isEqualTo("REVOKED");
        assertThat(st.selectFirst("button").attr("aria-label")).isEqualTo("상태 필터 제거");
        // 상태 필터 패널: 세그먼트(전체·활성·회수됨, title=원문)
        Element panel = d.selectFirst("#cred-filterpanel");
        assertThat(panel).isNull(); // 패널은 처음 렌더에만(reset 이 아니면 OOB 로 되돌려 쓰지 않음)
        Document t = tab("?status=ACTIVE");
        assertThat(t.select("#cred-filterpanel .seg__btn").eachText()).containsExactly("전체", "활성", "회수됨");
        assertThat(t.select("#cred-filterpanel .seg__btn").eachAttr("title")).contains("ACTIVE", "REVOKED");
        assertThat(t.select("#cred-filterpanel .seg__btn--on").text()).isEqualTo("활성");
        Element seg = t.select("#cred-filterpanel .seg__btn").get(2);
        assertThat(seg.attr("hx-get")).isEqualTo(BASE + "/tab/credentials/list?status=REVOKED");
        assertThat(seg.attr("hx-include")).contains("#cred-q").contains("#cred-aaguid");
        assertThat(t.selectFirst("#cred-aaguid").attr("placeholder")).isEqualTo("예: ea9b8d66");
        assertThat(t.select("#cred-filterpanel .hint").text()).isEqualTo("현재 페이지 안에서 거릅니다.");
        assertThat(t.selectFirst("#cred-filterpanel").attr("x-show")).isEqualTo("filtersOpen");
        assertThat(t.selectFirst("#cred-controls .fchip").text()).isEqualTo("필터 · aaguid · 상태");
    }

    @Test
    @DisplayName("빈 상태: credential 이 하나도 없으면 일러스트 + 안내(필터 지우기 없음), 검색 결과 0 은 결과 없음")
    void emptyStates() throws Exception {
        when(credentials.list(eq(T1), eq(0), eq(50), isNull())).thenReturn(ApiResponse.ok(page(List.of(), 0)));
        Document d = tab("");
        assertThat(d.select(".empty__title").text()).isEqualTo("Credential이 없습니다");
        assertThat(d.select(".empty__sub").text()).isEqualTo("사용자가 RP 에서 패스키를 등록하면 여기에 나타납니다.");
        assertThat(d.select(".empty__art").attr("src")).endsWith("/admin/img/empty-credentials.webp");
        assertThat(d.select(".empty button")).isEmpty();
        when(credentials.list(eq(T1), eq(0), eq(50), eq("zzz"))).thenReturn(ApiResponse.ok(page(List.of(), 0)));
        Document s = doc(hget(BASE + "/tab/credentials/list?q=zzz", platform()));
        assertThat(s.select(".empty__title").text()).isEqualTo("검색 결과가 없습니다.");
        assertThat(s.select("#cred-controls button").stream().filter(b -> b.text().startsWith("CSV")).findFirst().orElseThrow().hasAttr("disabled")).isTrue();
    }

    @Test
    @DisplayName("로드 실패: '없음'이 아니라 LoadError + 다시 시도(#cred-results 만 교체), 건수 —, CSV 비활성, 페이지네이션 없음, 내부 메시지 비노출")
    void loadError() throws Exception {
        when(credentials.list(eq(T1), eq(0), eq(50), isNull())).thenThrow(new IllegalStateException("db down secret-sql"));
        MvcResult r = hget(BASE + "/tab/credentials", platform());
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        String html = body(r);
        assertThat(html).doesNotContain("secret-sql").doesNotContain("Credential이 없습니다");
        Document d = Jsoup.parse(html);
        assertThat(d.select("[role=alert] .empty__title").text()).isEqualTo("Credential 목록을 불러오지 못했습니다");
        Element retry = d.select("button").stream().filter(e -> e.text().equals("다시 시도")).findFirst().orElseThrow();
        assertThat(retry.attr("hx-get")).isEqualTo(BASE + "/tab/credentials/list?page=1&size=50");
        assertThat(retry.attr("hx-target")).isEqualTo("#cred-results");
        assertThat(d.select("#cred-controls .hint").text()).isEqualTo("—");
        assertThat(d.select("#cred-controls button[disabled]").stream().anyMatch(b -> b.text().startsWith("CSV"))).isTrue();
        assertThat(d.select(".pager")).isEmpty();
    }

    @Test
    @DisplayName("hx-* 주소는 사용자 입력을 인코딩해 담는다(따옴표·&·꺾쇠가 속성을 깨지 못함)")
    void urlsEncodeUserInput() throws Exception {
        String q = "a&b\"<c>";
        when(credentials.list(eq(T1), eq(0), eq(50), eq(q))).thenReturn(ApiResponse.ok(page(List.of(cred(CA, "u", null, null, null, null, 1, null)), 1)));
        MvcResult r = hget(BASE + "/tab/credentials/list?q=" + java.net.URLEncoder.encode(q, StandardCharsets.UTF_8), platform());
        assertThat(body(r)).doesNotContain("<c>");
        Element tr = doc(r).select("#cred-results tbody tr").first();
        assertThat(tr.attr("hx-get")).contains("q=a%26b%22%3Cc%3E");
    }

    @Test
    @DisplayName("RP_ADMIN 자기 tenant 는 탭이 보인다")
    void rpAdminOwnTenant() throws Exception {
        MvcResult r = hget(BASE + "/tab/credentials", rp(T1));
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(doc(r).select("#cred-results tbody tr")).hasSize(3);
    }

    // ── CSV ─────────────────────────────────────────────────

    @Test
    @DisplayName("CSV: 헤더·열 순서·transports '|' 구분·현재 페이지에 보이는 행만, 파일명 credentials-{slug}-{KST 오늘}.csv")
    void csvExport() throws Exception {
        MvcResult r = mvc.perform(htmx(get(URI.create(BASE + "/credentials.csv?aaguid=ea9b")), platform())).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"credentials-acme-2026-10-11.csv\"");
        String csv = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String[] lines = csv.replace("﻿", "").split("\n");
        assertThat(lines[0]).isEqualTo("credentialId,externalUserId,label,authenticatorName,status,aaguid,transports,signatureCounter,lastUsedAt,createdAt");
        assertThat(lines).hasSize(2);
        assertThat(lines[1]).startsWith(CA + ",user-a,내 폰,YubiKey 5,ACTIVE," + AAGUID_A + ",usb|nfc,5,");
        // 컨트롤의 CSV 링크가 같은 상태를 가리킨다
        assertThat(tab("?aaguid=ea9b").selectFirst("#cred-controls a.btn").attr("href")).isEqualTo(BASE + "/credentials.csv?page=1&size=50&aaguid=ea9b");
    }

    // ── 상세 ────────────────────────────────────────────────

    @Test
    @DisplayName("상세: 이미 가진 값으로 식별 정보·인증기 섹션(dlg-sec), 인증 기록 표(결과 배지·사유·카운터), 열린 채(open=true), 푸터 회수·닫기, 조각만")
    void detailRenders() throws Exception {
        MvcResult r = hget(BASE + "/credentials/" + CA, platform());
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(r)).doesNotContain("<html").doesNotContain("class=\"shell");
        Document d = doc(r);
        assertThat(d.selectFirst("[x-data^=dialog]").attr("x-data")).contains("\"id\":\"cred-detail\"").contains("\"open\":true");
        Element dlg = d.selectFirst("[role=dialog][aria-labelledby=cred-detail-title]");
        assertThat(dlg.select("#cred-detail-title").text()).startsWith("Credential 상세").contains("활성");
        assertThat(dlg.select(".dialog__sub .mono").text()).isEqualTo(CA);
        assertThat(dlg.select(".dialog__sub button[aria-label=credentialId 복사]")).hasSize(1);
        assertThat(dlg.select("section.dlg-sec > .t-over").eachText()).containsExactly("식별 정보", "인증기", "인증 기록 (최근 20건)");
        assertThat(dlg.select("dl").get(0).select("dt").eachText()).containsExactly("externalUserId", "별칭", "생성", "마지막 사용");
        assertThat(dlg.select("dl").get(0).select("dd").eachText()).containsExactly("user-a", "내 폰", "2026-09-11 09:00", "14분 전");
        assertThat(dlg.select("dl").get(1).select("dt").eachText()).containsExactly("인증기", "전송 방식", "서명 카운터", "AAGUID", "attestation");
        assertThat(dlg.select("dl").get(1).select("dd").eachText()).containsExactly("YubiKey 5", "usbnfc", "5", AAGUID_A, "packed");
        assertThat(dlg.select("dl").get(1).select(".tag").eachText()).containsExactly("usb", "nfc"); // 원문(번역 없음)
        assertThat(dlg.select("table th").eachText()).containsExactly("시간", "결과", "사유", "서명 카운터");
        assertThat(dlg.select("table tbody tr").get(0).select("td").eachText()).containsExactly("2026-10-11 08:45", "성공", "—", "6");
        Element fail = dlg.select("table tbody tr").get(1);
        assertThat(fail.select(".badge").text()).isEqualTo("실패");
        assertThat(fail.select(".badge").hasClass("badge--danger")).isTrue();
        assertThat(fail.select(".tag").text()).isEqualTo("BAD_SIGNATURE");
        assertThat(dlg.select(".dialog__foot-start button").text()).isEqualTo("Credential 회수…");
        assertThat(dlg.select(".dialog__foot > button").text()).isEqualTo("닫기");
        verify(credentials).authEvents(T1, CA, 0, 20);
    }

    @Test
    @DisplayName("상세: 값이 없으면 —/unknown, 인증 이력이 없으면 안내, 이동 UI 는 목록 안에서 찾았을 때만")
    void detailEmptyValuesAndEvents() throws Exception {
        when(credentials.authEvents(eq(T1), eq(CC), eq(0), eq(20))).thenReturn(ApiResponse.ok(new PageView<>(List.of(), 0, 20, 0, false)));
        Document d = doc(hget(BASE + "/credentials/" + CC, platform()));
        assertThat(d.select("dl").get(0).select("dd").eachText()).containsExactly("user-c", "—", "2026-09-11 09:00", "—");
        assertThat(d.select("dl").get(1).select("dd").eachText()).containsExactly("unknown", "—", "0", "—", "packed");
        assertThat(d.select(".dlg-sec p.muted").text()).isEqualTo("아직 인증 이력이 없습니다.");
        assertThat(d.select("table")).isEmpty();
    }

    @Test
    @DisplayName("상세: 인증 기록 조회 실패는 그 섹션만 compact LoadError(+다시 시도), 상세 값은 그대로, 내부 메시지 비노출")
    void detailEventsFailureIsLocal() throws Exception {
        when(credentials.authEvents(eq(T1), eq(CA), eq(0), eq(20))).thenThrow(new IllegalStateException("boom secret-trace"));
        MvcResult r = hget(BASE + "/credentials/" + CA + "?page=1&size=50", platform());
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(r)).doesNotContain("secret-trace");
        Document d = doc(r);
        assertThat(d.select("[role=alert] .empty__title").text()).isEqualTo("인증 기록을 불러오지 못했습니다");
        assertThat(d.selectFirst("[role=alert]").hasClass("empty--compact")).isTrue();
        assertThat(d.select("dl").get(0).select("dd").first().text()).isEqualTo("user-a");
        Element retry = d.select("[role=alert] button").stream().filter(e -> e.text().equals("다시 시도")).findFirst().orElseThrow();
        assertThat(retry.attr("hx-get")).isEqualTo(BASE + "/credentials/" + CA + "?page=1&size=50");
        assertThat(retry.attr("hx-target")).isEqualTo("#cred-detail-slot");
    }

    @Test
    @DisplayName("상세 이전/다음: 페이지 안은 바로, 위치 'index / total', 처음·끝에서 해당 방향 비활성(첫 페이지 첫 항목)")
    void detailNavWithinPage() throws Exception {
        Document mid = doc(hget(BASE + "/credentials/" + CB + "?page=1&size=50", platform()));
        assertThat(mid.select("#cred-detail-title .hint").text()).isEqualTo("2 / 3");
        Element prev = mid.selectFirst("button[aria-label=이전 credential]");
        Element next = mid.selectFirst("button[aria-label=다음 credential]");
        assertThat(prev.attr("hx-get")).isEqualTo(BASE + "/credentials/" + CA + "?page=1&size=50");
        assertThat(next.attr("hx-get")).isEqualTo(BASE + "/credentials/" + CC + "?page=1&size=50");
        assertThat(prev.attr("hx-target")).isEqualTo("#cred-detail-slot");
        Document first = doc(hget(BASE + "/credentials/" + CA + "?page=1&size=50", platform()));
        assertThat(first.selectFirst("button[aria-label=이전 credential]").hasAttr("disabled")).isTrue();
        assertThat(first.selectFirst("button[aria-label=다음 credential]").hasAttr("disabled")).isFalse();
        Document last = doc(hget(BASE + "/credentials/" + CC + "?page=1&size=50", platform()));
        assertThat(last.selectFirst("button[aria-label=다음 credential]").hasAttr("disabled")).isTrue();
    }

    @Test
    @DisplayName("상세 이전/다음이 페이지 경계를 넘으면 이웃 페이지의 마지막/첫 항목(list=1 → 그 페이지 목록도 OOB), 필터가 있으면 경계에서 비활성")
    void detailNavAcrossPages() throws Exception {
        // 2페이지(size 50, 총 120): 첫 항목 CB → 이전 = 1페이지 마지막, 마지막 항목 → 다음 = 3페이지 첫 항목
        List<CredentialView> p2 = List.of(cred(CB, "user-b", null, null, null, null, 1, null), cred(CC, "user-c", null, null, null, null, 1, null));
        when(credentials.list(eq(T1), eq(1), eq(50), isNull())).thenReturn(ApiResponse.ok(new PageView<>(p2, 1, 50, 120, true)));
        when(credentials.list(eq(T1), eq(0), eq(50), isNull())).thenReturn(ApiResponse.ok(page(List.of(cred(CA, "p1-last", null, null, null, null, 1, null)), 120)));
        when(credentials.list(eq(T1), eq(2), eq(50), isNull())).thenReturn(ApiResponse.ok(new PageView<>(List.of(cred("credNEXT0000000000000000", "p3-first", null, null, null, null, 1, null)), 2, 50, 120, false)));
        Document d = doc(hget(BASE + "/credentials/" + CB + "?page=2&size=50", platform()));
        assertThat(d.select("#cred-detail-title .hint").text()).isEqualTo("51 / 120");
        assertThat(d.selectFirst("button[aria-label=이전 credential]").attr("hx-get")).isEqualTo(BASE + "/credentials/" + CA + "?page=1&size=50&list=1");
        Document e = doc(hget(BASE + "/credentials/" + CC + "?page=2&size=50", platform()));
        assertThat(e.selectFirst("button[aria-label=다음 credential]").attr("hx-get")).isEqualTo(BASE + "/credentials/credNEXT0000000000000000?page=3&size=50&list=1");
        // list=1 응답: 상세 + 그 페이지 목록 OOB
        Document oob = doc(hget(BASE + "/credentials/" + CA + "?page=1&size=50&list=1", platform()));
        assertThat(oob.selectFirst("#cred-results").attr("hx-swap-oob")).isEqualTo("true");
        assertThat(oob.selectFirst("#cred-controls").attr("hx-swap-oob")).isEqualTo("true");
        assertThat(oob.select("#cred-results tbody").text()).contains("p1-last");
        // list=1 이 아니면 목록 OOB 없음
        assertThat(doc(hget(BASE + "/credentials/" + CB + "?page=2&size=50", platform())).select("#cred-results")).isEmpty();
        // 필터가 있으면 경계에서 이웃 페이지로 넘지 않는다
        Document f = doc(hget(BASE + "/credentials/" + CB + "?page=2&size=50&aaguid=zz", platform()));
        assertThat(f.select("#cred-detail-title .hint")).isEmpty(); // zz 에 맞는 항목이 없어 창에서 못 찾음 → 이동 UI 없음
        verify(credentials, never()).list(eq(T1), eq(3), eq(50), any());
    }

    @Test
    @DisplayName("상세: 목록 창에 없으면 id 검색으로 찾고(이동 UI 없음), 그래도 없으면 404 + authEvents 호출 없음")
    void detailLookupFallbackAndNotFound() throws Exception {
        stubList(List.of(cred(CA, "user-a", null, null, null, null, 1, null)));
        when(credentials.list(eq(T1), eq(0), eq(50), eq(CB))).thenReturn(ApiResponse.ok(page(List.of(cred(CB, "via-search", null, null, null, null, 1, null)), 1)));
        Document d = doc(hget(BASE + "/credentials/" + CB, platform()));
        assertThat(d.select("dl").get(0).select("dd").first().text()).isEqualTo("via-search");
        assertThat(d.select("#cred-detail-title .hint")).isEmpty();
        MvcResult nf = hget(BASE + "/credentials/" + FOREIGN, platform());
        assertThat(nf.getResponse().getStatus()).isEqualTo(404);
        verify(credentials, never()).authEvents(any(), eq(FOREIGN), anyInt(), anyInt());
        // base64url 이 아닌 id 는 REST 를 부르지 않고 404
        MvcResult bad = hget(BASE + "/credentials/not%20valid!", platform());
        assertThat(bad.getResponse().getStatus()).isIn(404, 400);
    }

    // ── 경계 ────────────────────────────────────────────────

    @Test
    @DisplayName("RP_ADMIN 이 다른 tenant 의 탭·목록·상세·회수 확인·회수·CSV → 403, REST 목록·이벤트·변경 호출 없음")
    void rpAdminOtherTenantDenied() throws Exception {
        when(tenants.get(OTHER.toString())).thenThrow(new BusinessException(ErrorCode.ACCESS_DENIED, "RP_ADMIN cannot access tenant " + OTHER));
        String base = "/admin/_/tenants/" + OTHER;
        Authentication rp = rp(T1);
        assertThat(hget(base + "/tab/credentials", rp).getResponse().getStatus()).isEqualTo(403);
        assertThat(hget(base + "/tab/credentials/list", rp).getResponse().getStatus()).isEqualTo(403);
        assertThat(hget(base + "/credentials/" + CA, rp).getResponse().getStatus()).isEqualTo(403);
        assertThat(hget(base + "/credentials/" + CA + "/revoke-confirm", rp).getResponse().getStatus()).isEqualTo(403);
        assertThat(hget(base + "/credentials.csv", rp).getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(confirm(delete(URI.create(base + "/credentials/" + CA)), rp)).andReturn().getResponse().getStatus()).isEqualTo(403);
        verify(credentials, never()).list(any(), anyInt(), anyInt(), any());
        verify(credentials, never()).authEvents(any(), any(), anyInt(), anyInt());
        verify(credentials, never()).revoke(any(), any(), any());
    }

    @Test
    @DisplayName("자기 tenant id 에 다른 tenant 의 credential id → 상세·회수 확인·회수 모두 404, authEvents·revoke 호출 없음")
    void foreignCredentialViaOwnTenantNotTouched() throws Exception {
        Authentication rp = rp(T1);
        assertThat(hget(BASE + "/credentials/" + FOREIGN, rp).getResponse().getStatus()).isEqualTo(404);
        assertThat(hget(BASE + "/credentials/" + FOREIGN + "/revoke-confirm", rp).getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(confirm(delete(URI.create(BASE + "/credentials/" + FOREIGN)), rp)).andReturn().getResponse().getStatus()).isEqualTo(404);
        verify(credentials, never()).authEvents(any(), any(), anyInt(), anyInt());
        verify(credentials, never()).revoke(any(), any(), any());
        // 목록이 (잘못) 같은 id 를 섞어 줘도 정확히 같은 id 만 인정 — 부분 일치로 다른 credential 을 지우지 않는다
        when(credentials.list(eq(T1), eq(0), eq(50), eq(CA + "x"))).thenReturn(ApiResponse.ok(page(List.of(cred(CA, "u", null, null, null, null, 1, null)), 1)));
        assertThat(mvc.perform(confirm(delete(URI.create(BASE + "/credentials/" + CA + "x")), rp)).andReturn().getResponse().getStatus()).isEqualTo(404);
        verify(credentials, never()).revoke(any(), any(), any());
    }

    // ── 회수 ────────────────────────────────────────────────

    @Test
    @DisplayName("회수 확인: L3 — 마지막 8자 입력(expected), 확인 버튼 속성(X-Confirm·DELETE 주소에 현재 목록 상태), 본문 KV, 열린 채 응답")
    void revokeConfirmDialog() throws Exception {
        when(credentials.list(eq(T1), eq(0), eq(50), eq("user"))).thenReturn(ApiResponse.ok(page(List.of(cred(CA, "user-a", "내 폰", AAGUID_A, "YubiKey 5", "usb,nfc", 5, at(-0.01))), 1)));
        MvcResult r = hget(BASE + "/credentials/" + CA + "/revoke-confirm?page=1&size=50&q=user", platform());
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        Document d = doc(r);
        assertThat(d.selectFirst("[x-init]").attr("x-init")).contains("dialog:open").contains("cred-revoke");
        Element dlg = d.selectFirst("[x-data^=dialog]");
        assertThat(dlg.attr("x-data")).contains("\"id\":\"cred-revoke\"").contains("\"level\":\"L3\"").contains("\"typing\":true");
        String tail8 = CA.substring(CA.length() - 8);
        assertThat(d.selectFirst("[x-data^=confirmByTyping]").attr("x-data")).contains("\"expected\":\"" + tail8 + "\"");
        assertThat(d.select("label.label").text()).isEqualTo("확인을 위해 마지막 8자 " + tail8 + " 를 입력하세요");
        assertThat(d.select(".dialog__title").text()).isEqualTo("Credential을 회수할까요?");
        assertThat(d.select(".dialog__sub").text()).isEqualTo("이 사용자는 해당 패스키로 더 이상 로그인할 수 없습니다. 되돌릴 수 없습니다.");
        assertThat(d.select("dl dt").eachText()).containsExactly("credentialId", "사용자", "인증기");
        assertThat(d.select("dl dd").eachText()).containsExactly("…" + tail8, "user-a", "YubiKey 5");
        Element ok = d.selectFirst("button[data-confirm]");
        assertThat(ok.attr("hx-delete")).isEqualTo(BASE + "/credentials/" + CA + "?page=1&size=50&q=user");
        assertThat(ok.attr("hx-target")).isEqualTo("#cred-results");
        assertThat(ok.attr("hx-swap")).isEqualTo("outerHTML");
        assertThat(ok.attr("hx-headers")).contains("X-Confirm");
        assertThat(ok.hasClass("btn--danger-solid")).isTrue();
        assertThat(ok.text()).contains("회수");
    }

    @Test
    @DisplayName("회수: X-Confirm 없으면 400 + REST 호출 없음")
    void revokeRequiresConfirmHeader() throws Exception {
        MvcResult r = mvc.perform(htmx(delete(URI.create(BASE + "/credentials/" + CA)), platform())).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        verify(credentials, never()).revoke(any(), any(), any());
    }

    @Test
    @DisplayName("회수: REST revoke(tenant, credentialId, auth), 성공 토스트(제목+끝 12자·사용자), 갱신된 목록(#cred-results 본 대상)+OOB 컨트롤")
    void revokeCallsRestAndRefreshes() throws Exception {
        MvcResult[] holder = new MvcResult[1];
        when(credentials.revoke(eq(T1), eq(CA), any())).thenAnswer(inv -> {
            stubList(List.of(cred(CB, "user-b", null, null, null, null, 1, null)));
            return org.springframework.http.ResponseEntity.ok(ApiResponse.ok());
        });
        MvcResult r = mvc.perform(confirm(delete(URI.create(BASE + "/credentials/" + CA + "?page=1&size=50")), platform())).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        verify(credentials).revoke(eq(T1), eq(CA), any(Authentication.class));
        JsonNode t = trigger(r);
        JsonNode toast = t.get("toast");
        assertThat(toast.get("kind").asText()).isEqualTo("success");
        assertThat(toast.get("title").asText()).isEqualTo("Credential을 회수했습니다.");
        assertThat(toast.get("message").asText()).isEqualTo(CA.substring(CA.length() - 12) + " · user-a");
        Document d = doc(r);
        Element res = d.selectFirst("#cred-results");
        assertThat(res.hasAttr("hx-swap-oob")).isFalse(); // 본 대상(outerHTML)
        assertThat(res.select("tbody tr")).hasSize(1);
        assertThat(res.text()).contains("user-b").doesNotContain("user-a");
        assertThat(d.selectFirst("#cred-controls").attr("hx-swap-oob")).isEqualTo("true");
    }

    @Test
    @DisplayName("회수 성공 뒤 목록 새로고침이 실패해도 회수는 성공(토스트) — 목록 자리에 LoadError")
    void revokeSucceedsEvenIfRefreshFails() throws Exception {
        when(credentials.revoke(eq(T1), eq(CA), any())).thenAnswer(inv -> {
            when(credentials.list(eq(T1), eq(0), eq(50), isNull())).thenThrow(new IllegalStateException("db down"));
            return org.springframework.http.ResponseEntity.ok(ApiResponse.ok());
        });
        MvcResult r = mvc.perform(confirm(delete(URI.create(BASE + "/credentials/" + CA)), platform())).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(trigger(r).get("toast").get("title").asText()).isEqualTo("Credential을 회수했습니다.");
        assertThat(doc(r).select("#cred-results [role=alert] .empty__title").text()).isEqualTo("Credential 목록을 불러오지 못했습니다");
    }

    @Test
    @DisplayName("회수 실패는 확인 다이얼로그 안 인라인(confirmError), 토스트 없음")
    void revokeFailureIsInline() throws Exception {
        when(credentials.revoke(eq(T1), eq(CA), any())).thenThrow(new BusinessException(ErrorCode.ENTITY_NOT_FOUND, "credential 없음"));
        MvcResult r = mvc.perform(confirm(delete(URI.create(BASE + "/credentials/" + CA)), platform())).andReturn();
        assertThat(r.getResponse().getStatus()).isGreaterThanOrEqualTo(400);
        JsonNode t = trigger(r);
        assertThat(t.has("confirmError")).isTrue();
        assertThat(t.has("toast")).isFalse();
    }
}
