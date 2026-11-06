package com.crosscert.passkey.admin.web.tenant;

import com.crosscert.passkey.admin.apikey.ApiKeyAdminController;
import com.crosscert.passkey.admin.audit.AuditChainMonitorController;
import com.crosscert.passkey.admin.auth.ActiveTenantController;
import com.crosscert.passkey.admin.auth.AdminUserDetails;
import com.crosscert.passkey.admin.license.LicenseController;
import com.crosscert.passkey.admin.license.LicenseView;
import com.crosscert.passkey.admin.tenant.TenantAdminController;
import com.crosscert.passkey.admin.tenant.TenantAdminDto;
import com.crosscert.passkey.admin.tenant.WebauthnConfigDiff;
import com.crosscert.passkey.admin.tenant.WebauthnDiffRequest;
import com.crosscert.passkey.admin.web.AdminWebSliceTest;
import com.crosscert.passkey.admin.web.tenant.WebauthnTabController.Change;
import com.crosscert.passkey.core.api.ApiResponse;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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
import java.util.Map;
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
 * WebAuthn 설정 탭. 원본: WebauthnConfigTab.test.ts(validateOrigin → OriginValidationTest), WebauthnConfigTab.view.test.tsx, api/webauthn.ts.
 * 브라우저에서 도는 부분(Alpine 상태·saveBar 개수·htmx 교체·다이얼로그 열기·포커스)은 속성·소스 수준까지만 본다.
 */
@AdminWebSliceTest(controllers = WebauthnTabController.class)
class WebauthnTabControllerTest {

    @Autowired MockMvc mvc;
    @Autowired Clock clock;
    @MockitoBean TenantAdminController tenants;
    @MockitoBean ApiKeyAdminController apiKeys;
    @MockitoBean LicenseController license;
    @MockitoBean AuditChainMonitorController chain;
    @MockitoBean ActiveTenantController activeTenants;

    static final ObjectMapper JSON = new ObjectMapper();
    static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");
    static final UUID T1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(license.get()).thenReturn(ApiResponse.ok(new LicenseView("saas", "VALID", "acme", "lic_1", NOW.plusSeconds(86400L * 30), 30, List.of(), null, null, null)));
        when(tenants.get(T1.toString())).thenReturn(ApiResponse.ok(view(true)));
    }

    // ── fixtures ────────────────────────────────────────────

    static Authentication platform() { return auth("PLATFORM_OPERATOR", Set.of()); }
    static Authentication rp(UUID... ids) { return auth("RP_ADMIN", Set.of(ids)); }

    private static Authentication auth(String role, Set<UUID> tenants) {
        AdminUserDetails p = new AdminUserDetails(UUID.randomUUID(), "alice@example.com", "x", role, tenants, true, null, Clock.systemUTC());
        return new UsernamePasswordAuthenticationToken(p, "x", p.getAuthorities());
    }

    /** rpId passkey.acme.com, 대기 60000, UV 필수, 증명 NONE, 숨은 값 mdsRequired / acceptedFormats 는 인자·고정. */
    static TenantAdminDto.TenantView view(boolean mdsRequired) {
        return new TenantAdminDto.TenantView(T1, "acme", "Acme", "ACTIVE", "passkey.acme.com", "Acme Passkey",
                List.of("https://passkey.acme.com"), Set.of("none", "packed"), true, mdsRequired, "NONE", 60000,
                10, 2, null, OffsetDateTime.parse("2026-03-01T16:00:00Z"), OffsetDateTime.parse("2026-03-01T16:00:00Z"));
    }

    static final String BASE = "/admin/_/tenants/" + T1;

    private static MockHttpServletRequestBuilder htmx(MockHttpServletRequestBuilder b, Authentication a) {
        return b.with(authentication(a)).with(csrf()).header("HX-Request", "true");
    }

    private MvcResult getTab(Authentication a) throws Exception {
        return mvc.perform(htmx(get(URI.create(BASE + "/tab/webauthn")), a)).andReturn();
    }

    private static Document doc(MvcResult r) throws Exception {
        return Jsoup.parse(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private Document tab() throws Exception {
        MvcResult r = getTab(platform());
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return doc(r);
    }

    /** 미리보기 요청. 기본 입력 = 현재 값. */
    private MockHttpServletRequestBuilder diffReq(String rpName, String timeout, String uv, String at, String... origins) {
        MockHttpServletRequestBuilder b = htmx(post(URI.create(BASE + "/webauthn/diff")), platform())
                .param("rpName", rpName).param("timeoutMs", timeout).param("userVerification", uv).param("attestationConveyance", at);
        if (origins.length > 0) b.param("origins", origins);
        return b;
    }

    private MockHttpServletRequestBuilder diffReq(String timeout) {
        return diffReq("Acme Passkey", timeout, "REQUIRED", "NONE", "https://passkey.acme.com");
    }

    private MockHttpServletRequestBuilder saveReq(String rpName, String timeout, String uv, String at, String... origins) {
        MockHttpServletRequestBuilder b = htmx(put(URI.create(BASE + "/webauthn")), platform()).header("X-Confirm", "1")
                .param("rpName", rpName).param("timeoutMs", timeout).param("userVerification", uv).param("attestationConveyance", at).param("changes", "1");
        if (origins.length > 0) b.param("origins", origins);
        return b;
    }

    private static WebauthnConfigDiff diff(WebauthnConfigDiff.FieldChange... changes) {
        return new WebauthnConfigDiff(null, null, List.of(changes), List.of());
    }

    private static JsonNode trigger(MvcResult r) throws Exception {
        String h = r.getResponse().getHeader("HX-Trigger");
        assertThat(h).as("HX-Trigger").isNotNull();
        return JSON.readTree(h);
    }

    // ── 화면(WebauthnConfigTab.view.test.tsx 'V2') ───────────

    @Test
    @DisplayName("설명 좌/컨트롤 우 행, rpId 는 읽기 전용 + 변경 불가, 라벨 title 툴팁, 사용자 검증은 필수·권장만, 저장 바는 변경 후에만(서버 렌더는 폼 + 저장 바 조각)")
    void rendersRowsWithTsxStrings() throws Exception {
        Document d = tab();
        assertThat(d.select("#tenant-tab, header, aside")).isEmpty(); // 조각: 레이아웃 없음
        assertThat(d.selectFirst("h2.card__title").text()).isEqualTo("WebAuthn 설정");
        assertThat(d.selectFirst(".card__sub").text()).isEqualTo("변경 사항은 저장 후 다음 패스키 등록·인증부터 적용됩니다.");
        Element rpId = d.selectFirst("#wa-rpid");
        assertThat(rpId.hasAttr("readonly")).isTrue();
        assertThat(rpId.val()).isEqualTo("passkey.acme.com");
        assertThat(d.select("label[for=wa-rpid] span").attr("title")).isEqualTo("rpId");
        assertThat(d.text()).contains("변경 불가");
        assertThat(d.select("label[for=wa-timeout] span[title=timeoutMs]").text()).isEqualTo("대기 시간");
        assertThat(d.select("label[for=wa-rpname]").text()).isEqualTo("RP 표시 이름");
        assertThat(d.select("[role=group][aria-label=사용자 검증] button").eachText()).containsExactly("필수", "권장");
        assertThat(d.select("[role=group][aria-label=사용자 검증] button[aria-pressed=true]").text()).isEqualTo("필수");
        assertThat(d.select("[role=group][aria-label=사용자 검증] button[title=REQUIRED]")).hasSize(1);
        assertThat(d.select("[role=group][aria-label=인증기 증명] button").eachText()).containsExactly("요청 안 함", "익명 허용", "직접", "기업용");
        assertThat(d.select("[role=group][aria-label=인증기 증명] button[aria-pressed=true]").text()).isEqualTo("요청 안 함");
        // 변경 표시는 변경이 생길 때만(CSS 가 [data-changed] 로 켠다) — 서버 렌더 시점엔 어떤 행도 data-changed 가 없다
        assertThat(d.select("[data-changed]")).isEmpty();
        assertThat(d.select("[data-field] .chg")).hasSize(5);
        // 저장 바: 요약은 form 의 라벨표(JSON, 화면 순서)로 바뀐 필드 이름을 잇는다
        assertThat(d.select(".savebar[aria-label=저장되지 않은 변경]")).hasSize(1);
        assertThat(d.selectFirst("form#wa-form").attr("data-savebar-labels"))
                .isEqualTo("{\"rpName\":\"RP 표시 이름\",\"timeoutMs\":\"대기 시간\",\"origins\":\"허용 origin\",\"userVerification\":\"사용자 검증\",\"attestationConveyance\":\"인증기 증명\"}");
        assertThat(d.select(".savebar .btn--primary").text()).contains("저장…");
        // 선택에 따른 설명 상자(필수만 처음에 보임)
        assertThat(d.select(".desc-box:not([x-cloak])").eachText()).hasSize(2);
        assertThat(d.select(".desc-box").first().text()).contains("필수 — PIN·지문·얼굴 등 사용자 확인(UV)을 반드시 요구합니다");
        assertThat(d.select(".desc-box[x-cloak]")).hasSize(4);
        // 문구에 ceremony 를 쓰지 않는다
        assertThat(d.text()).doesNotContainIgnoringCase("ceremony");
    }

    @Test
    @DisplayName("대기 시간 안내: 처음 값을 서버가 같은 문구로 그린다(권장 범위 안 ok, 벗어나면 warn)")
    void timeoutGuideRenderedByServer() throws Exception {
        Document d = tab();
        assertThat(d.selectFirst("#wa-timeout-hint").text()).isEqualTo("= 60초 (1분) — 권장 범위(60–120초) 안입니다.");
        assertThat(d.selectFirst("#wa-timeout-hint").hasAttr("style")).isFalse();
        when(tenants.get(T1.toString())).thenReturn(ApiResponse.ok(new TenantAdminDto.TenantView(T1, "acme", "Acme", "ACTIVE", "passkey.acme.com", "Acme Passkey",
                List.of("https://passkey.acme.com"), Set.of("none"), true, true, "NONE", 30000, 0, 0, null, OffsetDateTime.parse("2026-03-01T16:00:00Z"), OffsetDateTime.parse("2026-03-01T16:00:00Z"))));
        Document w = tab();
        assertThat(w.selectFirst("#wa-timeout-hint").text()).startsWith("= 30초 — 권장(60초)보다 짧습니다");
        assertThat(w.selectFirst("#wa-timeout-hint").attr("style")).contains("var(--warn)");
        assertThat(WebauthnTabController.timeoutGuide(120000).text()).isEqualTo("= 120초 (2분) — 권장 범위(60–120초) 안입니다.");
        assertThat(WebauthnTabController.timeoutGuide(90000).text()).startsWith("= 90초 (1분 30초)");
        assertThat(WebauthnTabController.timeoutGuide(0).text()).isEqualTo("0보다 큰 값을 입력하세요. 권장 60000–120000ms(60–120초).");
        assertThat(WebauthnTabController.timeoutGuide(null).tone()).isEqualTo("warn");
        assertThat(WebauthnTabController.timeoutGuide(150000).text()).contains("권장(120초)보다 깁니다");
    }

    @Test
    @DisplayName("폼은 미리보기 POST(data-preview, 확인 슬롯 대상, 연타 drop), origin 칩 입력은 서버 검증 주소·added 없음(처음 값 기준 추가 표시), 앱 키 입력은 chipAdder")
    void formWiring() throws Exception {
        Document d = tab();
        Element f = d.selectFirst("form#wa-form");
        assertThat(f.attr("hx-post")).isEqualTo(BASE + "/webauthn/diff");
        assertThat(f.hasAttr("data-preview")).isTrue();
        assertThat(f.attr("hx-target")).isEqualTo("#wa-confirm-slot");
        assertThat(f.attr("hx-sync")).isEqualTo("this:drop");
        assertThat(d.select("#wa-confirm-slot")).hasSize(1);
        assertThat(f.select("#wa-confirm-slot")).isEmpty(); // form 중첩 금지
        Element chips = d.selectFirst("[x-data^=chipInput]");
        assertThat(chips.attr("x-data")).contains("\"name\":\"origins\"").contains("\"validateUrl\":\"" + BASE + "/webauthn/origin-check\"")
                .doesNotContain("\"added\"").contains("https://passkey.acme.com");
        assertThat(d.select("input[aria-label=origin 추가]").attr("placeholder")).isEqualTo("https://passkey.acme.com 입력 후 Enter");
        assertThat(d.select("[x-data^=chipAdder]").attr("x-data")).contains(BASE + "/webauthn/apk-key").contains("\"target\":\"origins\"");
        assertThat(d.select("input[aria-label=Android APK 키 해시]").attr("name")).isEmpty(); // 제출 필드가 아니다
        assertThat(d.select("button").eachText()).contains("앱 키 추가");
        // 입력 이름 = 저장 때 받는 이름
        assertThat(d.select("form#wa-form input[name=rpName]")).hasSize(1);
        assertThat(d.select("form#wa-form input[name=timeoutMs][type=number]")).hasSize(1);
        assertThat(d.select("form#wa-form input[name=userVerification]").val()).isEqualTo("REQUIRED");
        assertThat(d.select("form#wa-form input[name=attestationConveyance]").val()).isEqualTo("NONE");
        // 필드 오류 자리
        assertThat(d.select("p.field__error[id^=wa-err-]").eachAttr("id")).containsExactly("wa-err-rpName", "wa-err-timeoutMs", "wa-err-origins", "wa-err-userVerification", "wa-err-attestationConveyance", "wa-err-form");
        assertThat(d.select("p.field__error[data-field-error]")).hasSize(6);
        assertThat(f.attr("data-savebar-numeric")).isEqualTo("timeoutMs");
    }

    @Test
    @DisplayName("\"자세히\" 문서 링크와 RP 연동 가이드 — 주소가 비면 숨긴다(기본 설정은 빈 주소)")
    void helpLinksHiddenWhenEmpty() throws Exception {
        Document d = tab();
        assertThat(d.select("a[aria-label$=자세히], a.btn--ghost")).isEmpty();
    }

    @Test
    @DisplayName("로드 실패: 스켈레톤에 멈추지 않고 인라인 LoadError + 다시 시도(탭 영역 교체 속성), 원문 메시지는 싣지 않는다")
    void loadFailureIsInlineLoadError() throws Exception {
        when(tenants.get(T1.toString())).thenThrow(new IllegalStateException("secret-db-detail"));
        MvcResult r = getTab(platform());
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        Document d = doc(r);
        assertThat(d.select(".empty[role=alert] .empty__title").text()).isEqualTo("WebAuthn 설정을 불러오지 못했습니다");
        Element retry = d.selectFirst("button.btn--primary");
        assertThat(retry.attr("hx-get")).isEqualTo(BASE + "/tab/webauthn");
        assertThat(retry.attr("hx-target")).isEqualTo("#tenant-tab");
        assertThat(retry.attr("hx-sync")).isEqualTo("#tenant-tab:replace");
        assertThat(d.text()).doesNotContain("secret-db-detail");
        assertThat(d.select("form")).isEmpty();
    }

    // ── 저장: 숨은 값 보존(api/webauthn.ts adaptConfig + 역변환) ──

    @Test
    @DisplayName("hiddenMdsRequiredPreservedOnSave: 화면에 없는 값(mdsRequired·acceptedFormats·rpId·displayName)은 서버의 현재 값 그대로 REST 에 간다 — 요청에 실려 와도 무시")
    void hiddenMdsRequiredPreservedOnSave() throws Exception {
        when(tenants.update(eq(T1.toString()), any(), any())).thenReturn(ApiResponse.ok(view(true)));
        MvcResult r = mvc.perform(saveReq("Acme Passkey", "120000", "REQUIRED", "NONE", "https://passkey.acme.com")
                .param("mdsRequired", "false").param("rpId", "evil.example").param("acceptedFormats", "x").param("displayName", "forged")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        ArgumentCaptor<TenantAdminDto.TenantUpdateRequest> cap = ArgumentCaptor.forClass(TenantAdminDto.TenantUpdateRequest.class);
        verify(tenants).update(eq(T1.toString()), cap.capture(), any());
        TenantAdminDto.TenantUpdateRequest u = cap.getValue();
        assertThat(u.mdsRequired()).isTrue();
        assertThat(u.acceptedFormats()).isEqualTo(Set.of("none", "packed"));
        assertThat(u.rpId()).isEqualTo("passkey.acme.com");
        assertThat(u.displayName()).isEqualTo("Acme");
        // 사용자가 바꾼 값
        assertThat(u.webauthnTimeoutMs()).isEqualTo(120000);
        assertThat(u.rpName()).isEqualTo("Acme Passkey");
        assertThat(u.allowedOrigins()).containsExactly("https://passkey.acme.com");
        assertThat(u.requireUserVerification()).isTrue();
        assertThat(u.attestationConveyance()).isEqualTo("NONE");
    }

    @Test
    @DisplayName("mdsRequired=false 인 서버 값도 그대로(강제로 true/false 로 만들지 않는다) + UV 권장 → requireUserVerification=false")
    void mdsFalsePreservedAndUvMapping() throws Exception {
        when(tenants.get(T1.toString())).thenReturn(ApiResponse.ok(view(false)));
        when(tenants.update(eq(T1.toString()), any(), any())).thenReturn(ApiResponse.ok(view(false)));
        mvc.perform(saveReq("Acme Passkey", "60000", "PREFERRED", "DIRECT", "https://passkey.acme.com")).andReturn();
        ArgumentCaptor<TenantAdminDto.TenantUpdateRequest> cap = ArgumentCaptor.forClass(TenantAdminDto.TenantUpdateRequest.class);
        verify(tenants).update(eq(T1.toString()), cap.capture(), any());
        assertThat(cap.getValue().mdsRequired()).isFalse();
        assertThat(cap.getValue().requireUserVerification()).isFalse();
        assertThat(cap.getValue().attestationConveyance()).isEqualTo("DIRECT");
    }

    @Test
    @DisplayName("saveTriggersSavedEvent: 저장 성공은 HX-Trigger 에 saved + 성공 토스트(\"WebAuthn 설정을 저장했습니다\" / N개 항목이 바뀌었습니다.), 본문은 새 값으로 다시 그린 탭")
    void saveTriggersSavedEvent() throws Exception {
        TenantAdminDto.TenantView saved = new TenantAdminDto.TenantView(T1, "acme", "Acme", "ACTIVE", "passkey.acme.com", "Acme Passkey",
                List.of("https://passkey.acme.com"), Set.of("none", "packed"), true, true, "NONE", 120000, 10, 2, null,
                OffsetDateTime.parse("2026-03-01T16:00:00Z"), OffsetDateTime.parse("2026-03-01T16:00:00Z"));
        when(tenants.update(eq(T1.toString()), any(), any())).thenReturn(ApiResponse.ok(saved));
        MvcResult r = mvc.perform(saveReq("Acme Passkey", "120000", "REQUIRED", "NONE", "https://passkey.acme.com")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        JsonNode t = trigger(r);
        assertThat(t.has("saved")).isTrue();
        assertThat(t.get("toast").get("kind").asString()).isEqualTo("success");
        assertThat(t.get("toast").get("title").asString()).isEqualTo("WebAuthn 설정을 저장했습니다");
        assertThat(t.get("toast").get("message").asString()).isEqualTo("1개 항목이 바뀌었습니다.");
        Document d = doc(r);
        assertThat(d.select("form#wa-form")).hasSize(1);
        assertThat(d.selectFirst("#wa-timeout").val()).isEqualTo("120000");
        assertThat(d.selectFirst("#wa-timeout-hint").text()).startsWith("= 120초 (2분)");
    }

    @Test
    @DisplayName("저장 실패(REST 거절)는 다이얼로그 안 인라인 오류(확인 요청의 confirmError, 토스트 없음), 스왑되는 본문 없음")
    void saveFailureIsInlineOnly() throws Exception {
        when(tenants.update(eq(T1.toString()), any(), any())).thenThrow(new BusinessException(ErrorCode.TENANT_NOT_FOUND));
        MvcResult r = mvc.perform(saveReq("Acme Passkey", "120000", "REQUIRED", "NONE", "https://passkey.acme.com")).andReturn();
        assertThat(r.getResponse().getStatus()).isGreaterThanOrEqualTo(400);
        String h = r.getResponse().getHeader("HX-Trigger");
        assertThat(h).contains("confirmError").doesNotContain("\"toast\"").doesNotContain("\"saved\"");
        assertThat(r.getResponse().getContentAsString()).isEmpty();
    }

    @Test
    @DisplayName("저장 단계의 검증 실패(위조·경합)는 400 + confirmError 만 — REST 호출 없음, 탭을 비우는 스왑 없음")
    void saveValidationFailureIsInline() throws Exception {
        MvcResult r = mvc.perform(saveReq("Acme Passkey", "5", "REQUIRED", "NONE", "https://passkey.acme.com")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(r.getResponse().getHeader("HX-Trigger")).contains("confirmError");
        assertThat(r.getResponse().getContentAsString()).isEmpty();
        verify(tenants, never()).update(any(), any(), any());
    }

    // ── 미리보기(diff) ──────────────────────────────────────

    @Test
    @DisplayName("diffShowsChangedFieldsOnly: 서버 diff 가 비어도 로컬 변경(대기 시간)이 확인 목록에 보이고, 바뀌지 않은 필드는 없다(화면 라벨·초 단위, 원시 키는 title)")
    void diffShowsChangedFieldsOnly() throws Exception {
        when(tenants.diff(eq(T1.toString()), any())).thenReturn(diff());
        MvcResult r = mvc.perform(diffReq("120000")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        Document d = doc(r);
        assertThat(d.select(".diff__row")).hasSize(1);
        Element row = d.selectFirst(".diff__row");
        assertThat(row.selectFirst(".diff__key").text()).isEqualTo("대기 시간");
        assertThat(row.selectFirst(".diff__key").attr("title")).isEqualTo("timeoutMs");
        assertThat(row.select(".tag").eachText()).containsExactly("60초", "120초");
        assertThat(d.text()).contains("저장하면 감사 로그에 “WebAuthn 설정 변경”이 기록됩니다.");
        assertThat(d.select("template[x-if=open] [role=alertdialog], [role=alertdialog]")).isNotEmpty(); // 다이얼로그 본체는 x-if 템플릿 안(열릴 때 그려짐)
        Element dlg = d.selectFirst("[x-data^=dialog]");
        assertThat(dlg.attr("x-data")).contains("\"id\":\"wa-confirm\"").contains("\"level\":\"L2\"");
        assertThat(d.html()).contains("변경 사항 1개를 저장할까요?").contains("다음 패스키 등록·인증부터 적용됩니다.");
        assertThat(d.select("th, td").text()).doesNotContain("RP 표시 이름"); // 바뀌지 않은 필드는 없다
        assertThat(d.select(".diff__key").eachText()).doesNotContain("RP 표시 이름", "허용 origin", "사용자 검증", "인증기 증명");
    }

    @Test
    @DisplayName("diff REST 요청 본문은 api/webauthn.ts diff 와 같다(rpId·rpName·origins·formats·uv boolean·mdsRequired=서버 값)")
    void diffRequestBody() throws Exception {
        when(tenants.diff(eq(T1.toString()), any())).thenReturn(diff());
        mvc.perform(diffReq("Acme 2", "60000", "PREFERRED", "NONE", "https://passkey.acme.com", "https://a.passkey.acme.com")).andReturn();
        ArgumentCaptor<WebauthnDiffRequest> cap = ArgumentCaptor.forClass(WebauthnDiffRequest.class);
        verify(tenants).diff(eq(T1.toString()), cap.capture());
        WebauthnDiffRequest q = cap.getValue();
        assertThat(q.rpId()).isEqualTo("passkey.acme.com");
        assertThat(q.rpName()).isEqualTo("Acme 2");
        assertThat(q.allowedOrigins()).containsExactly("https://passkey.acme.com", "https://a.passkey.acme.com");
        assertThat(q.acceptedFormats()).isEqualTo(Set.of("none", "packed"));
        assertThat(q.requireUserVerification()).isFalse();
        assertThat(q.mdsRequired()).isTrue();
    }

    @Test
    @DisplayName("서버 diff(requireUserVerification 불리언·origins added/removed)와 로컬 변경(인증기 증명)을 합쳐 한국어로, 화면 순서로 보인다")
    void mergedServerAndLocalChanges() throws Exception {
        when(tenants.diff(eq(T1.toString()), any())).thenReturn(diff(
                new WebauthnConfigDiff.FieldChange("requireUserVerification", true, false, null, null),
                new WebauthnConfigDiff.FieldChange("origins", null, null, List.of("https://a.passkey.acme.com"), List.of())));
        MvcResult r = mvc.perform(diffReq("Acme Passkey", "60000", "PREFERRED", "DIRECT", "https://passkey.acme.com", "https://a.passkey.acme.com")).andReturn();
        Document d = doc(r);
        assertThat(d.select(".diff__key").eachText()).containsExactly("허용 origin", "사용자 검증", "인증기 증명");
        assertThat(d.select(".diff__key").get(1).attr("title")).isEqualTo("requireUserVerification");
        Element origins = d.select(".diff__row").get(0);
        assertThat(origins.select(".tag").eachText()).containsExactly("+ https://a.passkey.acme.com");
        assertThat(d.select(".diff__row").get(1).select(".tag").eachText()).containsExactly("필수", "권장");
        assertThat(d.select(".diff__row").get(2).select(".tag").eachText()).containsExactly("요청 안 함", "직접");
        assertThat(d.select(".diff__row").text()).doesNotContain("requireUserVerification").doesNotContain("true").doesNotContain("false");
        assertThat(d.html()).contains("변경 사항 3개를 저장할까요?");
    }

    @Test
    @DisplayName("확인 다이얼로그는 비교한 값을 hidden input 으로 들고, 확인 버튼은 그 form 의 값으로 PUT(탭 교체)한다 — 저장은 정확히 비교한 값")
    void dialogCarriesComparedValues() throws Exception {
        when(tenants.diff(eq(T1.toString()), any())).thenReturn(diff());
        MvcResult r = mvc.perform(diffReq("Acme Passkey", "120000", "REQUIRED", "ENTERPRISE", "https://passkey.acme.com", "https://b.passkey.acme.com")).andReturn();
        Document d = doc(r);
        Element form = d.selectFirst("form#wa-confirm-form");
        assertThat(form.attr("x-init")).contains("dialog:open").contains("wa-confirm");
        assertThat(form.select("input[name=rpName]").val()).isEqualTo("Acme Passkey");
        assertThat(form.select("input[name=timeoutMs]").val()).isEqualTo("120000");
        assertThat(form.select("input[name=origins]").eachAttr("value")).containsExactly("https://passkey.acme.com", "https://b.passkey.acme.com");
        assertThat(form.select("input[name=userVerification]").val()).isEqualTo("REQUIRED");
        assertThat(form.select("input[name=attestationConveyance]").val()).isEqualTo("ENTERPRISE");
        assertThat(form.select("input[name=changes]").val()).isEqualTo("3"); // 대기 시간·origin·인증기 증명
        Element go = form.selectFirst("button[data-confirm]");
        assertThat(go.attr("hx-put")).isEqualTo(BASE + "/webauthn");
        assertThat(go.attr("hx-target")).isEqualTo("#tenant-tab");
        assertThat(go.attr("hx-swap")).isEqualTo("innerHTML");
        assertThat(go.attr("hx-sync")).isEqualTo("#tenant-tab:replace");
        assertThat(go.attr("hx-headers")).contains("X-Confirm");
        assertThat(go.hasAttr("data-quiet")).isTrue();
    }

    @Test
    @DisplayName("변경이 없으면 경고 토스트 \"변경 사항이 없습니다.\" 만(다이얼로그 없음, REST 저장 없음)")
    void noChangesWarnsOnly() throws Exception {
        when(tenants.diff(eq(T1.toString()), any())).thenReturn(diff());
        MvcResult r = mvc.perform(diffReq("60000")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        JsonNode t = trigger(r);
        assertThat(t.get("toast").get("kind").asString()).isEqualTo("warn");
        assertThat(t.get("toast").get("title").asString()).isEqualTo("변경 사항이 없습니다.");
        assertThat(doc(r).select("form#wa-confirm-form")).isEmpty();
        verify(tenants, never()).update(any(), any(), any());
    }

    @Test
    @DisplayName("diff REST 실패: 제목 있는 오류 토스트 \"변경 사항을 비교하지 못했습니다\" + 실제 상태, 본문 없음")
    void diffFailureToast() throws Exception {
        when(tenants.diff(eq(T1.toString()), any())).thenThrow(new IllegalStateException("secret"));
        MvcResult r = mvc.perform(diffReq("120000")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(500);
        JsonNode t = trigger(r);
        assertThat(t.get("toast").get("kind").asString()).isEqualTo("error");
        assertThat(t.get("toast").get("title").asString()).isEqualTo("변경 사항을 비교하지 못했습니다");
        assertThat(r.getResponse().getHeader("HX-Trigger")).doesNotContain("secret");
        assertThat(r.getResponse().getContentAsString()).isEmpty();
    }

    // ── 검증(ruling G: 422 + 필드 옆 인라인, 입력·변경 상태 유지) ──

    @Test
    @DisplayName("invalidOriginInlineError: 서버 AllowedOriginFormat 위반은 422 + origin 필드 옆 오류(OOB), REST 호출·다이얼로그 없음")
    void invalidOriginInlineError() throws Exception {
        MvcResult r = mvc.perform(diffReq("Acme Passkey", "60000", "REQUIRED", "NONE", "https://passkey.acme.com", "ftp://bad.passkey.acme.com")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
        assertThat(r.getResponse().getHeader("HX-Trigger")).isNull(); // 토스트 없음(인라인만)
        Document d = doc(r);
        Element err = d.selectFirst("#wa-err-origins");
        assertThat(err.attr("hx-swap-oob")).isEqualTo("true");
        assertThat(err.attr("role")).isEqualTo("alert");
        assertThat(err.text()).isEqualTo("'ftp://bad.passkey.acme.com' 는 올바른 origin 형식이 아닙니다.");
        assertThat(d.select("form#wa-confirm-form")).isEmpty();
        assertThat(d.select("form#wa-form")).isEmpty(); // 입력 폼을 교체하지 않는다 → 입력값·변경 상태 유지
        // 오류가 없는 필드의 자리는 비워서(OOB) 이전 오류를 지운다
        assertThat(d.selectFirst("#wa-err-timeoutMs").text()).isEmpty();
        verify(tenants, never()).diff(any(), any());
        verify(tenants, never()).update(any(), any(), any());
    }

    @Test
    @DisplayName("필드 검증: 빈 이름·범위 밖 대기 시간·origin 없음·모르는 UV/증명 값은 각 필드 옆 오류(422)")
    void fieldErrors() throws Exception {
        MvcResult r = mvc.perform(diffReq("  ", "999999", "BOGUS", "NOPE")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
        Document d = doc(r);
        assertThat(d.selectFirst("#wa-err-rpName").text()).isEqualTo("RP 표시 이름을 입력하세요.");
        assertThat(d.selectFirst("#wa-err-timeoutMs").text()).isEqualTo("대기 시간은 1000–600000ms 범위의 숫자여야 합니다.");
        assertThat(d.selectFirst("#wa-err-origins").text()).isEqualTo("허용 origin 을 하나 이상 추가하세요.");
        assertThat(d.selectFirst("#wa-err-userVerification").text()).contains("필수 또는 권장");
        assertThat(d.selectFirst("#wa-err-attestationConveyance").text()).isNotEmpty();
        // "요구 안 함"(DISCOURAGED) 은 1단계에서 숨김 — 서버도 받지 않는다
        MvcResult dis = mvc.perform(diffReq("Acme Passkey", "60000", "DISCOURAGED", "NONE", "https://passkey.acme.com")).andReturn();
        assertThat(dis.getResponse().getStatus()).isEqualTo(422);
        // 숫자가 아닌 대기 시간
        MvcResult nan = mvc.perform(diffReq("Acme Passkey", "abc", "REQUIRED", "NONE", "https://passkey.acme.com")).andReturn();
        assertThat(nan.getResponse().getStatus()).isEqualTo(422);
        // 너무 긴 이름은 REST 와 같은 Bean Validation
        MvcResult longName = mvc.perform(diffReq("x".repeat(257), "60000", "REQUIRED", "NONE", "https://passkey.acme.com")).andReturn();
        assertThat(longName.getResponse().getStatus()).isEqualTo(422);
        assertThat(doc(longName).selectFirst("#wa-err-rpName").text()).isEqualTo("RP 표시 이름은 256자 이하로 입력하세요.");
        verify(tenants, never()).diff(any(), any());
    }

    @Test
    @DisplayName("매핑되지 않은 Bean Validation 위반(예: 서버 displayName 이 너무 김)은 일반 인라인 오류로 422 — 조용히 넘기지 않고 REST 는 부르지 않는다")
    void unmappedViolationIsGeneralError() throws Exception {
        TenantAdminDto.TenantView v = new TenantAdminDto.TenantView(T1, "acme", "N".repeat(300), "ACTIVE", "passkey.acme.com", "Acme Passkey",
                List.of("https://passkey.acme.com"), Set.of("none"), true, true, "NONE", 60000, 0, 0, null, OffsetDateTime.parse("2026-03-01T16:00:00Z"), OffsetDateTime.parse("2026-03-01T16:00:00Z"));
        when(tenants.get(T1.toString())).thenReturn(ApiResponse.ok(v));
        MvcResult r = mvc.perform(diffReq("120000")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
        assertThat(doc(r).selectFirst("#wa-err-form").text()).startsWith("저장할 수 없는 값이 있습니다");
        verify(tenants, never()).diff(any(), any());
        MvcResult p = mvc.perform(saveReq("Acme Passkey", "120000", "REQUIRED", "NONE", "https://passkey.acme.com")).andReturn();
        assertThat(p.getResponse().getStatus()).isEqualTo(400);
        verify(tenants, never()).update(any(), any(), any());
    }

    // ── ChipInput 원격 검증 ─────────────────────────────────

    private MvcResult check(String path, String value, String... existing) throws Exception {
        MockHttpServletRequestBuilder b = get(URI.create(BASE + "/webauthn/" + path)).with(authentication(platform())).header("HX-Request", "true").param("value", value);
        if (existing.length > 0) b.param("existing", existing);
        return mvc.perform(b).andReturn();
    }

    @Test
    @DisplayName("origin 검증 엔드포인트: 통과 = 빈 본문 + X-Normalized-Value(정규화), 범위 밖·형식 오류·중복(정규화 후)은 본문에 오류 문구")
    void originCheck() throws Exception {
        MvcResult ok = check("origin-check", "sub.passkey.acme.com/login?x=1");
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(ok.getResponse().getContentAsString()).isEmpty();
        assertThat(ok.getResponse().getHeader("X-Normalized-Value")).isEqualTo("https://sub.passkey.acme.com");
        assertThat(check("origin-check", "evil.com").getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("범위를 벗어납니다");
        assertThat(check("origin-check", "evil.com").getResponse().getHeader("X-Normalized-Value")).isNull();
        MvcResult dup = check("origin-check", "passkey.acme.com", "https://passkey.acme.com");
        assertThat(dup.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo("'https://passkey.acme.com' 는 이미 추가되어 있습니다.");
        assertThat(dup.getResponse().getHeader("X-Normalized-Value")).isNull();
        assertThat(check("origin-check", "   ").getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo("origin 을 입력하세요.");
        String apk = "android:apk-key-hash:" + "A".repeat(43);
        assertThat(check("origin-check", apk).getResponse().getHeader("X-Normalized-Value")).isEqualTo(apk);
    }

    @Test
    @DisplayName("Android 앱 키: 64 hex 지문 → apk-key-hash origin(X-Normalized-Value), 잘못된 길이·중복은 오류 문구")
    void apkKeyCheck() throws Exception {
        assertThat(check("apk-key", "abc").getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo("SHA-256 지문은 32바이트(64 hex)여야 합니다. 입력 길이: 3");
        String fp = "00:".repeat(31) + "00";
        MvcResult ok = check("apk-key", fp);
        assertThat(ok.getResponse().getContentAsString()).isEmpty();
        String origin = "android:apk-key-hash:" + "A".repeat(43);
        assertThat(ok.getResponse().getHeader("X-Normalized-Value")).isEqualTo(origin);
        assertThat(check("apk-key", fp, origin).getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo("이미 추가된 Android 키입니다.");
    }

    // ── 접근 경계 ───────────────────────────────────────────

    @Test
    @DisplayName("tenantBoundary: 모든 엔드포인트가 먼저 경계를 확인한다 — RP_ADMIN 의 다른 tenant 는 403, 어떤 REST 도 부르지 않는다")
    void rpAdminOtherTenantDenied() throws Exception {
        when(tenants.get(OTHER.toString())).thenThrow(new BusinessException(ErrorCode.ACCESS_DENIED, "RP_ADMIN cannot access tenant " + OTHER));
        String base = "/admin/_/tenants/" + OTHER;
        Authentication a = rp(T1);
        assertThat(mvc.perform(htmx(get(URI.create(base + "/tab/webauthn")), a)).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(htmx(get(URI.create(base + "/webauthn/origin-check")).param("value", "x"), a)).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(htmx(get(URI.create(base + "/webauthn/apk-key")).param("value", "x"), a)).andReturn().getResponse().getStatus()).isEqualTo(403);
        MvcResult d = mvc.perform(htmx(post(URI.create(base + "/webauthn/diff")), a).param("rpName", "x")).andReturn();
        assertThat(d.getResponse().getStatus()).isEqualTo(403);
        assertThat(d.getResponse().getContentAsString()).doesNotContain("wa-confirm");
        MvcResult p = mvc.perform(htmx(put(URI.create(base + "/webauthn")), a).header("X-Confirm", "1").param("rpName", "x")).andReturn();
        assertThat(p.getResponse().getStatus()).isEqualTo(403);
        verify(tenants, never()).diff(any(), any());
        verify(tenants, never()).update(any(), any(), any());
    }

    @Test
    @DisplayName("RP_ADMIN 은 자기 tenant 의 탭을 볼 수 있다")
    void rpAdminOwnTenantAllowed() throws Exception {
        MvcResult r = getTab(rp(T1));
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(doc(r).select("form#wa-form")).hasSize(1);
    }

    // ── 순수 함수 ───────────────────────────────────────────

    @Test
    @DisplayName("mergeChanges·changedFieldNames: 서버가 비교하지 않는 필드만 로컬에서 더하고 화면 순서로 정렬, requireUserVerification 은 userVerification 으로 본다")
    void mergeAndOrder() {
        List<Change> server = List.of(new Change("requireUserVerification", true, false), new Change("origins", List.of(), List.of("a")));
        List<Change> local = List.of(new Change("timeoutMs", 60000, 90000), new Change("userVerification", "REQUIRED", "PREFERRED"), new Change("attestationConveyance", "NONE", "DIRECT"));
        assertThat(WebauthnTabController.mergeChanges(server, local).stream().map(Change::key).toList())
                .containsExactly("timeoutMs", "origins", "requireUserVerification", "attestationConveyance");
        assertThat(WebauthnTabController.changedFieldNames(List.of("origins", "timeoutMs"))).containsExactly("timeoutMs", "origins");
        assertThat(WebauthnTabController.showValue("timeoutMs", 90500)).isEqualTo("90.5초");
        assertThat(WebauthnTabController.showValue("mdsRequired", false)).isEqualTo("필수 아님");
        assertThat(WebauthnTabController.showValue("userVerification", "PREFERRED")).isEqualTo("권장");
    }

    @Test
    @DisplayName("리터럴 탭 경로 GET /admin/_/tenants/{id}/tab/webauthn 은 이 컨트롤러가 맡는다(일반 {tab} 매핑보다 우선)")
    void literalMapping() throws Exception {
        assertThat(Map.of("k", getTab(platform()).getResponse().getContentAsString(StandardCharsets.UTF_8)).get("k")).contains("wa-form");
    }
}
