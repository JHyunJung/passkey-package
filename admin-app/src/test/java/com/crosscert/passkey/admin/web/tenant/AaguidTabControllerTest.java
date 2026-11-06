package com.crosscert.passkey.admin.web.tenant;

import com.crosscert.passkey.admin.apikey.ApiKeyAdminController;
import com.crosscert.passkey.admin.audit.AuditChainMonitorController;
import com.crosscert.passkey.admin.auth.ActiveTenantController;
import com.crosscert.passkey.admin.auth.AdminUserDetails;
import com.crosscert.passkey.admin.license.LicenseController;
import com.crosscert.passkey.admin.license.LicenseView;
import com.crosscert.passkey.admin.policy.AaguidPolicyController;
import com.crosscert.passkey.admin.policy.AaguidPolicyDto;
import com.crosscert.passkey.admin.tenant.TenantAdminController;
import com.crosscert.passkey.admin.tenant.TenantAdminDto;
import com.crosscert.passkey.admin.web.AdminWebSliceTest;
import com.crosscert.passkey.admin.web.tenant.AaguidTabController.Entry;
import com.crosscert.passkey.admin.web.tenant.AaguidTabController.Policy;
import com.crosscert.passkey.core.api.ApiResponse;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import com.crosscert.passkey.core.entity.TenantAaguidPolicy;
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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * AAGUID 정책 탭. 원본: AaguidPolicyTab.test.ts, AaguidPolicyTab.view.test.tsx, api/aaguidPolicy.ts, tenantSignals.ts.
 * 브라우저에서 도는 부분(방식 카드 선택·목록 개수에 따른 경고·saveBar 개수·다이얼로그 열기)은 속성·소스 수준까지만 본다.
 */
@AdminWebSliceTest(controllers = AaguidTabController.class)
class AaguidTabControllerTest {

    @Autowired MockMvc mvc;
    @Autowired Clock clock;
    @MockitoBean TenantAdminController tenants;
    @MockitoBean AaguidPolicyController aaguid;
    @MockitoBean ApiKeyAdminController apiKeys;
    @MockitoBean LicenseController license;
    @MockitoBean AuditChainMonitorController chain;
    @MockitoBean ActiveTenantController activeTenants;

    static final ObjectMapper JSON = new ObjectMapper();
    static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");
    static final UUID T1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
    static final UUID YUBI = UUID.fromString("ea9b8d66-4d01-1d21-3ce4-b6b48cb575d4");
    static final UUID UNKNOWN = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final String BASE = "/admin/_/tenants/" + T1;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(license.get()).thenReturn(ApiResponse.ok(new LicenseView("saas", "VALID", "acme", "lic_1", NOW.plusSeconds(86400L * 30), 30, List.of(), null, null, null)));
        when(tenants.get(T1.toString())).thenReturn(ApiResponse.ok(tenant()));
        when(aaguid.get(T1)).thenReturn(policy(TenantAaguidPolicy.Mode.ALLOWLIST, true, yubi(), unknown()));
    }

    // ── fixtures ────────────────────────────────────────────

    static Authentication platform() { return auth("PLATFORM_OPERATOR", Set.of()); }
    static Authentication rp(UUID... ids) { return auth("RP_ADMIN", Set.of(ids)); }

    private static Authentication auth(String role, Set<UUID> tenants) {
        AdminUserDetails p = new AdminUserDetails(UUID.randomUUID(), "alice@example.com", "x", role, tenants, true, null, Clock.systemUTC());
        return new UsernamePasswordAuthenticationToken(p, "x", p.getAuthorities());
    }

    static TenantAdminDto.TenantView tenant() {
        return new TenantAdminDto.TenantView(T1, "acme", "Acme", "ACTIVE", "passkey.acme.com", "Acme",
                List.of("https://passkey.acme.com"), Set.of("none"), true, false, "NONE", 60000, 0, 0, null,
                OffsetDateTime.parse("2026-03-01T16:00:00Z"), OffsetDateTime.parse("2026-03-01T16:00:00Z"));
    }

    static AaguidPolicyDto.Entry yubi() { return new AaguidPolicyDto.Entry(YUBI, "내 키", "YubiKey 5"); }
    static AaguidPolicyDto.Entry unknown() { return new AaguidPolicyDto.Entry(UNKNOWN, null, null); }

    static AaguidPolicyDto.View policy(TenantAaguidPolicy.Mode mode, boolean strict, AaguidPolicyDto.Entry... entries) {
        return new AaguidPolicyDto.View(T1, mode, strict, List.of(entries), null, null);
    }

    private static MockHttpServletRequestBuilder htmx(MockHttpServletRequestBuilder b, Authentication a) {
        return b.with(authentication(a)).with(csrf()).header("HX-Request", "true");
    }

    private MvcResult getTab() throws Exception {
        return mvc.perform(htmx(get(URI.create(BASE + "/tab/aaguid")), platform())).andReturn();
    }

    private static Document doc(MvcResult r) throws Exception {
        return Jsoup.parse(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private Document tab() throws Exception {
        MvcResult r = getTab();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return doc(r);
    }

    private MockHttpServletRequestBuilder previewReq(String mode, String strict, String... aaguids) {
        MockHttpServletRequestBuilder b = htmx(post(URI.create(BASE + "/aaguid/preview")), platform()).param("mode", mode).param("mdsStrict", strict);
        if (aaguids.length > 0) b.param("aaguid", aaguids);
        return b;
    }

    private MockHttpServletRequestBuilder saveReq(String mode, String strict, String... aaguids) {
        MockHttpServletRequestBuilder b = htmx(put(URI.create(BASE + "/aaguid")), platform()).header("X-Confirm", "1").param("mode", mode).param("mdsStrict", strict);
        if (aaguids.length > 0) b.param("aaguid", aaguids);
        return b;
    }

    private static JsonNode trigger(MvcResult r) throws Exception {
        String h = r.getResponse().getHeader("HX-Trigger");
        assertThat(h).as("HX-Trigger").isNotNull();
        return JSON.readTree(h);
    }

    // ── 순수 함수(AaguidPolicyTab.test.ts) ──────────────────

    private static Entry e(String a) { return new Entry(a, null, null); }
    private static Entry e(String a, String note, String mds) { return new Entry(a, note, mds); }
    private static Policy p(String mode, boolean strict, Entry... es) { return new Policy(mode, strict, List.of(es)); }

    @Test
    @DisplayName("formatAaguid: 하이픈 없는 32 hex → UUID 형식, 8자까지는 하이픈 없음, 대문자 소문자화, 비 hex 제거, 32 초과 절단, 빈/하이픈/공백 처리, 세그먼트 경계")
    void formatAaguid() {
        assertThat(AaguidTabController.formatAaguid("ea9b8d664d011d213ce4b6b48cb575d4")).isEqualTo("ea9b8d66-4d01-1d21-3ce4-b6b48cb575d4");
        assertThat(AaguidTabController.formatAaguid("ea9b8d66")).isEqualTo("ea9b8d66");
        assertThat(AaguidTabController.formatAaguid("ea9b8d664")).isEqualTo("ea9b8d66-4");
        assertThat(AaguidTabController.formatAaguid("EA9B8D66-4D01")).isEqualTo("ea9b8d66-4d01");
        assertThat(AaguidTabController.formatAaguid("ea9b8d66-4d01-1d21")).isEqualTo("ea9b8d66-4d01-1d21");
        assertThat(AaguidTabController.formatAaguid("ea9b!@#8d66xyz4d01")).isEqualTo("ea9b8d66-4d01");
        assertThat(AaguidTabController.formatAaguid("ea9b8d664d011d213ce4b6b48cb575d4EXTRA")).isEqualTo("ea9b8d66-4d01-1d21-3ce4-b6b48cb575d4");
        assertThat(AaguidTabController.formatAaguid("")).isEmpty();
        assertThat(AaguidTabController.formatAaguid("----")).isEmpty();
        assertThat(AaguidTabController.formatAaguid("  ea9b 8d66  ")).isEqualTo("ea9b8d66");
        assertThat(AaguidTabController.formatAaguid("ea9b8d664d01")).isEqualTo("ea9b8d66-4d01");
        assertThat(AaguidTabController.formatAaguid("ea9b8d664d011d21")).isEqualTo("ea9b8d66-4d01-1d21");
        assertThat(AaguidTabController.formatAaguid("ea9b8d664d011d213ce4")).isEqualTo("ea9b8d66-4d01-1d21-3ce4");
    }

    @Test
    @DisplayName("saveConfirmKind: 허용 목록이 비면 block-all(MDS 엄격보다 우선), 차단 목록·전체 허용이 비는 건 아니다, MDS 엄격을 켜면 mds-strict, 끄면 diff")
    void saveConfirmKind() {
        Policy base = p("ALLOWLIST", false, e("a"));
        assertThat(AaguidTabController.saveConfirmKind(base, p("ALLOWLIST", true))).isEqualTo(AaguidTabController.ConfirmKind.BLOCK_ALL);
        assertThat(AaguidTabController.saveConfirmKind(base, p("ALLOWLIST", false, e("")))).isEqualTo(AaguidTabController.ConfirmKind.BLOCK_ALL);
        assertThat(AaguidTabController.saveConfirmKind(base, p("DENYLIST", false))).isEqualTo(AaguidTabController.ConfirmKind.DIFF);
        assertThat(AaguidTabController.saveConfirmKind(base, p("ANY", false))).isEqualTo(AaguidTabController.ConfirmKind.DIFF);
        assertThat(AaguidTabController.saveConfirmKind(base, p("ALLOWLIST", true, e("a")))).isEqualTo(AaguidTabController.ConfirmKind.MDS_STRICT);
        assertThat(AaguidTabController.saveConfirmKind(p("ALLOWLIST", true, e("a")), p("ALLOWLIST", false, e("a")))).isEqualTo(AaguidTabController.ConfirmKind.DIFF);
    }

    @Test
    @DisplayName("policyChanges: 같으면 변경 없음(순서·mdsName 차이는 변경 아님), 삭제 후 재추가 = 변경 없음, 방식·추가·삭제·메모·MDS 엄격을 각각 한 건씩")
    void policyChanges() {
        Policy saved = p("ALLOWLIST", false, e("a"), e("b"));
        assertThat(AaguidTabController.policyChanges(saved, saved)).isEmpty();
        assertThat(AaguidTabController.policyChanges(saved, p("ALLOWLIST", false, e("b"), e("a", null, "X")))).isEmpty();
        assertThat(AaguidTabController.policyChanges(saved, p("ALLOWLIST", false, e("b"), e("a")))).isEmpty();
        var c = AaguidTabController.policyChanges(saved, p("DENYLIST", true, e("a", "n", null), e("c")));
        assertThat(c.stream().map(x -> x.getClass().getSimpleName()).sorted().toList())
                .containsExactly("EntryChange", "EntryChange", "MdsChange", "ModeChange", "NoteChange");
    }

    @Test
    @DisplayName("changedFields(SaveBar 요약 키): 바뀐 필드만, 목록 변경은 aaguid")
    void changedFields() {
        Policy saved = p("ALLOWLIST", true, e("a"), e("b"));
        assertThat(AaguidTabController.changedFields(saved, saved)).isEmpty();
        assertThat(AaguidTabController.changedFields(saved, p("DENYLIST", false, e("a"), e("b")))).containsExactly("mode", "mdsStrict");
        assertThat(AaguidTabController.changedFields(saved, p("ALLOWLIST", true, e("a")))).containsExactly("aaguid");
    }

    @Test
    @DisplayName("unverifiedAaguidCount(tenantSignals): ANY 는 0, mdsName null 만 센다")
    void unverifiedCount() {
        assertThat(AaguidTabController.unverifiedCount(p("ANY", false, e("a")))).isZero();
        assertThat(AaguidTabController.unverifiedCount(p("ALLOWLIST", false, e("a", null, "X"), e("b")))).isEqualTo(1);
    }

    // ── 화면(AaguidPolicyTab.view.test.tsx 'V2') ─────────────

    @Test
    @DisplayName("mode radiogroup(3개, 허용 목록 선택), 원문 title 툴팁, MDS 확인/미확인, 경고 제목, MDS strict 스위치 — 변경 전에는 변경 표시 없음")
    void rendersTsxStrings() throws Exception {
        Document d = tab();
        assertThat(d.select("#tenant-tab, aside")).isEmpty();
        Element group = d.selectFirst("[role=radiogroup][aria-label=mode]");
        assertThat(group.select("[role=radio]")).hasSize(3);
        assertThat(group.select("[role=radio][aria-checked=true] .choice__title").text()).isEqualTo("허용 목록");
        assertThat(group.select(".choice__title[title=DENYLIST]").text()).isEqualTo("차단 목록");
        assertThat(group.select(".choice__desc").eachText()).containsExactly("모든 인증기 허용", "목록의 모델만 허용", "목록의 모델만 차단");
        assertThat(d.select(".banner--warning .banner__title").text()).isEqualTo("MDS 미확인 AAGUID 1건 — 인증기 모델을 확인하세요");
        assertThat(d.select(".banner--warning").text()).contains("허용 목록의 아래 “MDS 미확인” 항목이 MDS에 없습니다.");
        assertThat(d.select(".banner--warning button").text()).isEqualTo("항목 보기");
        assertThat(d.select("[role=switch]").attr("aria-checked")).isEqualTo("true");
        assertThat(d.select("label.switch").text()).isEqualTo("MDS strict 켜짐");
        assertThat(d.select("[data-changed]")).isEmpty();
        assertThat(d.select("h2.card__title").text()).isEqualTo("AAGUID 정책");
        assertThat(d.select(".card__sub").text()).isEqualTo("등록할 수 있는 인증기 모델을 AAGUID로 제한합니다.");
        assertThat(d.selectFirst("form#ag-form").attr("data-savebar-labels")).isEqualTo("{\"mode\":\"mode\",\"aaguid\":\"AAGUID 목록\",\"mdsStrict\":\"MDS strict\"}");
        assertThat(d.select(".savebar .btn--primary").text()).contains("저장…");
        assertThat(d.select("form#ag-form input[name=mode]").val()).isEqualTo("ALLOWLIST");
        assertThat(d.select("form#ag-form input[name=mdsStrict]").val()).isEqualTo("true");
    }

    @Test
    @DisplayName("MDS 엄격 모드 설명은 MDS 등록 여부만 — REVOKED/폐기를 약속하지 않고, 허용 목록의 MDS 미확인 개수를 덧붙인다")
    void strictHint() throws Exception {
        Document d = tab();
        assertThat(d.text()).doesNotContain("REVOKED").doesNotContain("폐기");
        assertThat(d.text()).contains("MDS에 등록되지 않은 모든 인증기의 등록이 거부됩니다. 허용 목록 중 MDS 미확인 1개도 차단됩니다.");
        when(aaguid.get(T1)).thenReturn(policy(TenantAaguidPolicy.Mode.ANY, false));
        Document off = tab();
        assertThat(off.select(".switch ~ .hint").text()).isEqualTo("켜면 MDS에 등록되지 않은 모든 인증기의 등록이 거부됩니다.");
        assertThat(off.select("label.switch").text()).isEqualTo("MDS strict 꺼짐");
        assertThat(off.select(".banner--warning")).isEmpty();
        Element list = off.selectFirst("#aaguid-list");
        assertThat(list.attr("x-show")).isEqualTo("mode !== 'ANY'");
        assertThat(list.hasAttr("x-cloak")).isTrue(); // 전체 허용이면 목록 행은 처음부터 숨김
    }

    @Test
    @DisplayName("미확인 항목이 없으면 경고 Alert 가 없다")
    void noAlertWhenAllVerified() throws Exception {
        when(aaguid.get(T1)).thenReturn(policy(TenantAaguidPolicy.Mode.ALLOWLIST, true, yubi()));
        assertThat(tab().select(".banner--warning")).isEmpty();
    }

    @Test
    @DisplayName("목록 칸: chipInput 컴포넌트(AAGUID 서버 검증·정규화), 항목별 MDS 이름·확인 여부는 meta 로, 이름 없는 항목과 새 항목은 'MDS 에 없음'")
    void listWiring() throws Exception {
        Document d = tab();
        Element chips = d.selectFirst("#aaguid-list [x-data^=chipInput]");
        String x = chips.attr("x-data");
        assertThat(x).contains("\"name\":\"aaguid\"").contains("\"validateUrl\":\"" + BASE + "/aaguid/entry-check\"")
                .contains(YUBI.toString()).contains("YubiKey 5").contains("\"metaFallback\":\"MDS 에 없음\"");
        assertThat(x).doesNotContain("\"added\"");
        assertThat(d.select("input[aria-label=AAGUID 입력]").attr("placeholder")).isEqualTo("xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx");
        assertThat(d.select("input[aria-label=AAGUID 입력]").attr("maxlength")).isEqualTo("36");
        assertThat(d.selectFirst("[x-data^=policyForm]").attr("x-data")).contains("\"mode\":\"ALLOWLIST\"").contains("\"inputId\":\"aaguid-input\"")
                .contains("\"known\":[\"" + YUBI + "\"]");
        assertThat(d.select("#aaguid-list .set__label").text()).isEqualTo("허용된 AAGUID");
        // 삭제 버튼 이름은 '<aaguid> 삭제'(x-for 안에서 바인딩)
        assertThat(d.select("template[x-for] button[x-bind:aria-label]").attr("x-bind:aria-label")).isEqualTo("v + ' 삭제'");
        // 빈 허용 목록 경고는 처음엔 숨김(x-cloak), 목록이 비면 보인다
        assertThat(d.select(".banner--danger").first().parent().hasAttr("x-cloak")).isTrue();
        when(aaguid.get(T1)).thenReturn(policy(TenantAaguidPolicy.Mode.ALLOWLIST, false));
        Document empty = tab();
        Element bad = empty.selectFirst(".banner--danger");
        assertThat(bad.attr("role")).isEqualTo("alert");
        assertThat(bad.text()).contains("허용 목록이 비어 있습니다").contains("이대로 저장하면 이 tenant의 모든 패스키 등록·인증이 차단됩니다.");
        assertThat(bad.parent().hasAttr("x-cloak")).isFalse();
        assertThat(bad.select("button").eachText()).containsExactly("AAGUID 추가", "전체 허용으로 되돌리기");
        assertThat(bad.select("button").get(1).attr("x-on:click")).isEqualTo("setMode('ANY')");
        when(aaguid.get(T1)).thenReturn(policy(TenantAaguidPolicy.Mode.DENYLIST, false));
        Document deny = tab();
        assertThat(deny.select("#aaguid-list .set__label").text()).isEqualTo("차단된 AAGUID");
        assertThat(deny.select("p.policy-empty").text()).isEqualTo("비어 있음 — 이 상태에서는 모든 패스키 등록·인증이 허용됩니다.");
        assertThat(deny.select("p.policy-empty").hasAttr("x-cloak")).isFalse();
    }

    @Test
    @DisplayName("폼은 미리보기 POST(data-preview), 확인 슬롯은 form 밖")
    void formWiring() throws Exception {
        Document d = tab();
        Element f = d.selectFirst("form#ag-form");
        assertThat(f.attr("hx-post")).isEqualTo(BASE + "/aaguid/preview");
        assertThat(f.hasAttr("data-preview")).isTrue();
        assertThat(f.attr("hx-target")).isEqualTo("#ag-confirm-slot");
        assertThat(d.select("#ag-confirm-slot")).hasSize(1);
        assertThat(f.select("#ag-confirm-slot")).isEmpty();
        assertThat(d.select("p.field__error[id^=ag-err-]").eachAttr("id")).containsExactly("ag-err-mode", "ag-err-aaguid", "ag-err-form");
        assertThat(f.attr("data-savebar-unordered")).isEqualTo("aaguid");
    }

    @Test
    @DisplayName("로드 실패: 인라인 LoadError + 다시 시도, 원문 메시지 비노출")
    void loadFailure() throws Exception {
        when(aaguid.get(T1)).thenThrow(new IllegalStateException("secret-db-detail"));
        MvcResult r = getTab();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        Document d = doc(r);
        assertThat(d.select(".empty[role=alert] .empty__title").text()).isEqualTo("AAGUID 정책을 불러오지 못했습니다");
        assertThat(d.selectFirst("button.btn--primary").attr("hx-get")).isEqualTo(BASE + "/tab/aaguid");
        assertThat(d.text()).doesNotContain("secret-db-detail");
    }

    // ── 항목 검증(ChipInput 원격) ───────────────────────────

    private MvcResult check(String value) throws Exception {
        return mvc.perform(get(URI.create(BASE + "/aaguid/entry-check")).with(authentication(platform())).header("HX-Request", "true").param("value", value)).andReturn();
    }

    @Test
    @DisplayName("entry-check: 32 hex·대문자는 8-4-4-4-12 소문자로 맞춰 통과(X-Normalized-Value), 모자라면 오류 문구 — 검증 실패를 통과로 보지 않는다")
    void entryCheck() throws Exception {
        MvcResult ok = check("ABCDEF0123456789ABCDEF0123456789");
        assertThat(ok.getResponse().getContentAsString()).isEmpty();
        assertThat(ok.getResponse().getHeader("X-Normalized-Value")).isEqualTo("abcdef01-2345-6789-abcd-ef0123456789");
        MvcResult bad = check("abcdef01-2345");
        assertThat(bad.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo("AAGUID 형식이 올바르지 않습니다. 8-4-4-4-12 형식의 16진수 32자를 입력하세요.");
        assertThat(bad.getResponse().getHeader("X-Normalized-Value")).isNull();
        assertThat(check("").getResponse().getContentAsString(StandardCharsets.UTF_8)).isNotEmpty();
    }

    // ── 미리보기(저장 확인 등급) ────────────────────────────

    private static Element dialog(Document d) { return d.selectFirst("form#ag-confirm-form [x-data^=dialog]"); }

    @Test
    @DisplayName("추가 → L2 변경 비교: 제목 '변경 사항 N개를 저장할까요?', + <aaguid> 한 줄, 확인 버튼은 PUT(탭 교체), 비교한 값을 hidden 으로")
    void previewDiff() throws Exception {
        String added = "abcdef01-2345-6789-abcd-ef0123456789";
        MvcResult r = mvc.perform(previewReq("ALLOWLIST", "true", YUBI.toString(), UNKNOWN.toString(), added)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        Document d = doc(r);
        assertThat(dialog(d).attr("x-data")).contains("\"id\":\"ag-confirm\"").contains("\"level\":\"L2\"").contains("\"verb\":\"저장\"");
        assertThat(d.html()).contains("변경 사항 1개를 저장할까요?").contains("다음 패스키 등록부터 적용됩니다.");
        assertThat(d.select("ul.changes li").eachText()).containsExactly("+ " + added);
        assertThat(d.select("form#ag-confirm-form input[name=aaguid]").eachAttr("value")).containsExactly(YUBI.toString(), UNKNOWN.toString(), added);
        assertThat(d.select("form#ag-confirm-form input[name=mode]").val()).isEqualTo("ALLOWLIST");
        assertThat(d.select("form#ag-confirm-form input[name=mdsStrict]").val()).isEqualTo("true");
        Element go = d.selectFirst("button[data-confirm]");
        assertThat(go.attr("hx-put")).isEqualTo(BASE + "/aaguid");
        assertThat(go.attr("hx-target")).isEqualTo("#tenant-tab");
        assertThat(go.attr("hx-sync")).isEqualTo("#tenant-tab:replace");
        assertThat(d.selectFirst("form#ag-confirm-form").attr("x-init")).contains("dialog:open").contains("ag-confirm");
        verify(aaguid, never()).update(any(), any(), any());
    }

    @Test
    @DisplayName("허용 목록을 비우고 저장하면 L3 — '이해했습니다' 체크 전에는 저장 불가, 결과·영향 문구")
    void previewBlockAll() throws Exception {
        when(aaguid.get(T1)).thenReturn(policy(TenantAaguidPolicy.Mode.ALLOWLIST, false, yubi()));
        MvcResult r = mvc.perform(previewReq("ALLOWLIST", "false")).andReturn();
        Document d = doc(r);
        assertThat(dialog(d).attr("x-data")).contains("\"level\":\"L3\"").contains("\"verb\":\"차단\"").contains("\"ack\":true");
        assertThat(d.html()).contains("모든 등록·인증을 차단할까요?");
        assertThat(d.select("label.confirm__ack").text()).isEqualTo("차단된다는 것을 이해했습니다");
        assertThat(d.select(".dialog__body p").text()).contains("결과: 허용 목록(0개)").contains("영향: 이 tenant의 모든 사용자가 패스키로 등록·인증할 수 없습니다.");
        assertThat(d.select("button[data-confirm]").hasClass("btn--danger-solid")).isTrue();
        assertThat(d.select("ul.changes li").eachText()).containsExactly("- " + YUBI);
    }

    @Test
    @DisplayName("MDS 엄격을 켜는 저장은 L2 영향 요약 — '켜고 저장', 허용 목록의 MDS 미확인 개수·기존 Credential 유지 문구")
    void previewMdsStrict() throws Exception {
        when(aaguid.get(T1)).thenReturn(policy(TenantAaguidPolicy.Mode.ALLOWLIST, false, yubi(), unknown()));
        MvcResult r = mvc.perform(previewReq("ALLOWLIST", "true", YUBI.toString(), UNKNOWN.toString())).andReturn();
        Document d = doc(r);
        assertThat(d.html()).contains("MDS 엄격 모드를 켜고 저장할까요?");
        assertThat(dialog(d).attr("x-data")).contains("\"level\":\"L2\"");
        String body = d.select(".dialog__body p").text();
        assertThat(body).contains("MDS 엄격: 꺼짐 → 켜짐").contains("MDS에 등록되지 않은 모든 인증기의 등록이 거부됩니다.")
                .contains("허용 목록 중 MDS 미확인 1개도 차단됩니다.").contains("기존 Credential은 유지됩니다.");
        assertThat(d.select("ul.changes li").eachText()).containsExactly("MDS 엄격: 꺼짐 → 켜짐");
        // 전체 허용에서는 목록과 무관하게 같은 문구, 미확인 개수는 붙지 않는다
        when(aaguid.get(T1)).thenReturn(policy(TenantAaguidPolicy.Mode.ANY, false));
        Document any = doc(mvc.perform(previewReq("ANY", "true")).andReturn());
        assertThat(any.select(".dialog__body p").text()).doesNotContain("미확인").contains("기존 Credential은 유지됩니다.");
    }

    @Test
    @DisplayName("저장 확인 목록은 실제로 바뀐 항목만(방식·MDS 엄격), 모드 원문은 라벨로")
    void previewListsOnlyChanged() throws Exception {
        MvcResult r = mvc.perform(previewReq("DENYLIST", "false", YUBI.toString(), UNKNOWN.toString())).andReturn();
        Document d = doc(r);
        assertThat(d.html()).contains("변경 사항 2개를 저장할까요?");
        assertThat(d.select("ul.changes li").eachText()).containsExactly("방식: 허용 목록 → 차단 목록", "MDS 엄격: 켜짐 → 꺼짐");
        assertThat(d.select("ul.changes").text()).doesNotContain(YUBI.toString());
    }

    @Test
    @DisplayName("변경이 없으면 경고 토스트만, 모르는 방식·깨진 AAGUID 는 422 + 필드 옆 오류(REST 미호출)")
    void previewNoChangeAndInvalid() throws Exception {
        MvcResult none = mvc.perform(previewReq("ALLOWLIST", "true", YUBI.toString(), UNKNOWN.toString())).andReturn();
        assertThat(none.getResponse().getStatus()).isEqualTo(200);
        assertThat(trigger(none).get("toast").get("kind").asText()).isEqualTo("warn");
        assertThat(doc(none).select("form#ag-confirm-form")).isEmpty();

        MvcResult bad = mvc.perform(previewReq("EVERYTHING", "true", "not-a-uuid")).andReturn();
        assertThat(bad.getResponse().getStatus()).isEqualTo(422);
        assertThat(bad.getResponse().getHeader("HX-Trigger")).isNull();
        Document d = doc(bad);
        assertThat(d.selectFirst("#ag-err-mode").text()).isEqualTo("정책 방식이 올바르지 않습니다.");
        assertThat(d.selectFirst("#ag-err-mode").attr("hx-swap-oob")).isEqualTo("true");
        assertThat(d.selectFirst("#ag-err-aaguid").text()).isEqualTo("'not-a-uuid' 는 올바른 AAGUID 형식이 아닙니다.");
        assertThat(d.select("form#ag-form")).isEmpty(); // 입력 폼 교체 없음 → 입력·변경 상태 유지
        verify(aaguid, never()).update(any(), any(), any());
    }

    // ── 저장 ────────────────────────────────────────────────

    @Test
    @DisplayName("저장: AaguidPolicyController.update 에 같은 payload(mode·mdsStrict·entries), 기존 항목의 메모는 서버 값 그대로·새 항목 null, 성공 토스트 + saved, 탭 새 정책으로")
    void saveCallsRestAndTriggersSaved() throws Exception {
        String added = "abcdef01-2345-6789-abcd-ef0123456789";
        AaguidPolicyDto.View saved = policy(TenantAaguidPolicy.Mode.ALLOWLIST, true, yubi(), unknown(), new AaguidPolicyDto.Entry(UUID.fromString(added), null, "Some Key"));
        when(aaguid.update(eq(T1), any(), any())).thenReturn(saved);
        MvcResult r = mvc.perform(saveReq("ALLOWLIST", "true", YUBI.toString(), UNKNOWN.toString(), added)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        ArgumentCaptor<AaguidPolicyDto.UpdateRequest> cap = ArgumentCaptor.forClass(AaguidPolicyDto.UpdateRequest.class);
        verify(aaguid).update(eq(T1), cap.capture(), any());
        AaguidPolicyDto.UpdateRequest u = cap.getValue();
        assertThat(u.mode()).isEqualTo(TenantAaguidPolicy.Mode.ALLOWLIST);
        assertThat(u.mdsStrict()).isTrue();
        assertThat(u.entries()).extracting(AaguidPolicyDto.EntryInput::aaguid).containsExactly(YUBI, UNKNOWN, UUID.fromString(added));
        assertThat(u.entries()).extracting(AaguidPolicyDto.EntryInput::note).containsExactly("내 키", null, null);
        JsonNode t = trigger(r);
        assertThat(t.has("saved")).isTrue();
        assertThat(t.get("toast").get("kind").asText()).isEqualTo("success");
        assertThat(t.get("toast").get("title").asText()).isEqualTo("AAGUID 정책 저장 완료");
        Document d = doc(r);
        assertThat(d.select("form#ag-form")).hasSize(1);
        assertThat(d.select("template[x-for]").first().parent().select("[x-data^=chipInput]")).isNotNull();
        assertThat(d.selectFirst("[x-data^=chipInput]").attr("x-data")).contains(added).contains("Some Key");
    }

    @Test
    @DisplayName("저장 뒤 탭 줄의 AAGUID 배지가 새 정책 기준으로 갱신된다(OOB, 원본 onSaved) — 미확인이 있으면 '확인 필요 N', 없으면 비움")
    void badgeRefreshedAfterSave() throws Exception {
        when(aaguid.update(eq(T1), any(), any())).thenReturn(policy(TenantAaguidPolicy.Mode.ALLOWLIST, true, yubi(), unknown()));
        Document d = doc(mvc.perform(saveReq("ALLOWLIST", "true", YUBI.toString(), UNKNOWN.toString())).andReturn());
        Element slot = d.selectFirst("span#tenant-tab-aaguid-badge");
        assertThat(slot.attr("hx-swap-oob")).isEqualTo("true");
        assertThat(slot.select(".tabs__badge").text()).isEqualTo("확인 필요 1");
        assertThat(slot.select(".tabs__badge").attr("title")).isEqualTo("MDS 미확인 AAGUID 1건");

        when(aaguid.update(eq(T1), any(), any())).thenReturn(policy(TenantAaguidPolicy.Mode.ALLOWLIST, true, yubi()));
        Document ok = doc(mvc.perform(saveReq("ALLOWLIST", "true", YUBI.toString())).andReturn());
        assertThat(ok.selectFirst("span#tenant-tab-aaguid-badge").attr("hx-swap-oob")).isEqualTo("true");
        assertThat(ok.select("span#tenant-tab-aaguid-badge .tabs__badge")).isEmpty();

        when(aaguid.update(eq(T1), any(), any())).thenReturn(policy(TenantAaguidPolicy.Mode.ANY, false, unknown()));
        Document any = doc(mvc.perform(saveReq("ANY", "false", UNKNOWN.toString())).andReturn());
        assertThat(any.select("span#tenant-tab-aaguid-badge .tabs__badge")).isEmpty(); // 전체 허용이면 0건
        // 처음 탭 조각(GET)에는 OOB 배지가 없다
        assertThat(tab().select("#tenant-tab-aaguid-badge")).isEmpty();
    }

    @Test
    @DisplayName("저장 실패(REST 거절)는 확인 다이얼로그 안 인라인 오류(confirmError, 토스트 없음), 검증 실패는 400 + confirmError, 둘 다 본문 없음")
    void saveFailureInline() throws Exception {
        when(aaguid.update(eq(T1), any(), any())).thenThrow(new BusinessException(ErrorCode.TENANT_NOT_FOUND));
        MvcResult r = mvc.perform(saveReq("DENYLIST", "false", YUBI.toString())).andReturn();
        assertThat(r.getResponse().getStatus()).isGreaterThanOrEqualTo(400);
        assertThat(r.getResponse().getHeader("HX-Trigger")).contains("confirmError").doesNotContain("\"toast\"").doesNotContain("\"saved\"");
        assertThat(r.getResponse().getContentAsString()).isEmpty();

        MvcResult bad = mvc.perform(saveReq("NOPE", "false")).andReturn();
        assertThat(bad.getResponse().getStatus()).isEqualTo(400);
        assertThat(bad.getResponse().getHeader("HX-Trigger")).contains("confirmError");
        assertThat(bad.getResponse().getContentAsString()).isEmpty();
    }

    // ── 접근 경계 ───────────────────────────────────────────

    @Test
    @DisplayName("tenantBoundary: 모든 엔드포인트가 먼저 경계를 확인한다 — RP_ADMIN 의 다른 tenant 는 403, 정책을 읽거나 쓰지 않는다")
    void rpAdminOtherTenantDenied() throws Exception {
        when(tenants.get(OTHER.toString())).thenThrow(new BusinessException(ErrorCode.ACCESS_DENIED, "RP_ADMIN cannot access tenant " + OTHER));
        String base = "/admin/_/tenants/" + OTHER;
        Authentication a = rp(T1);
        assertThat(mvc.perform(htmx(get(URI.create(base + "/tab/aaguid")), a)).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(htmx(get(URI.create(base + "/aaguid/entry-check")).param("value", "x"), a)).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(htmx(post(URI.create(base + "/aaguid/preview")), a).param("mode", "ANY")).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(htmx(put(URI.create(base + "/aaguid")), a).header("X-Confirm", "1").param("mode", "ANY")).andReturn().getResponse().getStatus()).isEqualTo(403);
        verify(aaguid, never()).get(any());
        verify(aaguid, never()).update(any(), any(), any());
    }

    @Test
    @DisplayName("RP_ADMIN 은 자기 tenant 의 탭을 볼 수 있다")
    void rpAdminOwnTenantAllowed() throws Exception {
        MvcResult r = mvc.perform(htmx(get(URI.create(BASE + "/tab/aaguid")), rp(T1))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(doc(r).select("form#ag-form")).hasSize(1);
    }
}
