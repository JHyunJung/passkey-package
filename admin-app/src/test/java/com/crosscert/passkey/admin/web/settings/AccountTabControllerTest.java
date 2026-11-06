package com.crosscert.passkey.admin.web.settings;

import com.crosscert.passkey.admin.audit.AuditChainMonitorController;
import com.crosscert.passkey.admin.auth.ActiveTenantController;
import com.crosscert.passkey.admin.auth.AdminUserDetails;
import com.crosscert.passkey.admin.auth.MfaController;
import com.crosscert.passkey.admin.license.LicenseController;
import com.crosscert.passkey.admin.license.LicenseView;
import com.crosscert.passkey.admin.operator.SignupRequestController;
import com.crosscert.passkey.admin.system.SystemInfoController;
import com.crosscert.passkey.admin.web.AdminWebSliceTest;
import com.crosscert.passkey.core.api.ApiResponse;
import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 설정 › 내 계정 탭(MFA). 원본: AccountTab.test.tsx, RecoveryCodesModal.test.tsx, api/mfa.ts.
 * 다이얼로그 열기·OTP 입력·포커스·복사·다운로드는 Alpine 이라 속성·마크업까지만 본다(브라우저 미확인).
 */
@AdminWebSliceTest(controllers = {AccountTabController.class, SettingsPageController.class, SystemInfoTabController.class})
class AccountTabControllerTest {

    @Autowired MockMvc mvc;
    @Autowired Clock clock;
    @Autowired AdminUserRepository users;
    @MockitoBean MfaController mfa;
    @MockitoBean SignupRequestController signups;
    @MockitoBean SystemInfoController systemInfo;
    @MockitoBean LicenseController license;
    @MockitoBean AuditChainMonitorController chain;
    @MockitoBean ActiveTenantController activeTenants;

    static final ObjectMapper JSON = new ObjectMapper();
    static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");
    static final String EMAIL = "alice@example.com";
    static final UUID T1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final String SECRET = "JBSWY3DPEHPK3PXP";
    static final String URI = "otpauth://totp/Passkey%20Admin:alice%40example.com?secret=" + SECRET + "&issuer=Passkey%20Admin";
    static final List<String> CODES = List.of("AAAA-BBBB", "CCCC-DDDD", "EEEE-FFFF", "GGGG-HHHH", "JJJJ-KKKK",
            "LLLL-MMMM", "NNNN-PPPP", "QQQQ-RRRR", "SSSS-TTTT", "UUUU-VVVV");
    static final String CODE_MISMATCH = "코드가 맞지 않습니다. 앱에 표시된 새 코드(30초마다 바뀜)를 입력하세요.";
    static final String RC_TITLE = "복구 코드 — 지금만 표시됩니다";

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(license.get()).thenReturn(ApiResponse.ok(new LicenseView("saas", null, null, null, null, 0, List.of(), null, null, null)));
        when(users.findByEmail(anyString())).thenReturn(Optional.of(user(false)));
    }

    // ── fixtures ────────────────────────────────────────────

    static AdminUser user(boolean mfaEnabled) {
        AdminUser u = new AdminUser(EMAIL, "x", "RP_ADMIN");
        u.setMfaEnabled(mfaEnabled);
        return u;
    }

    static Authentication rp() { return auth("RP_ADMIN", Set.of(T1)); }
    static Authentication platform() { return auth("PLATFORM_OPERATOR", Set.of()); }

    private static Authentication auth(String role, Set<UUID> tenants) {
        AdminUserDetails p = new AdminUserDetails(UUID.randomUUID(), EMAIL, "x", role, tenants, true, null, Clock.systemUTC());
        return new UsernamePasswordAuthenticationToken(p, "x", p.getAuthorities());
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

    private static JsonNode trigger(MvcResult r) throws Exception {
        String h = r.getResponse().getHeader("HX-Trigger");
        assertThat(h).as("HX-Trigger").isNotNull();
        return JSON.readTree(h);
    }

    private MvcResult tab(Authentication a) throws Exception {
        return mvc.perform(htmx(get(AccountTabController.TAB_PATH), a)).andReturn();
    }

    private MvcResult enroll() throws Exception {
        return mvc.perform(htmx(post(AccountTabController.ENROLL_PATH), rp())).andReturn();
    }

    private MvcResult confirm(String code) throws Exception {
        return mvc.perform(htmx(post(AccountTabController.CONFIRM_PATH), rp()).param("code", code)).andReturn();
    }

    private MvcResult disable(String code) throws Exception {
        return mvc.perform(htmx(post(AccountTabController.DISABLE_PATH), rp()).param("code", code)).andReturn();
    }

    private void stubEnrollOk() {
        when(mfa.enroll(any(), any())).thenAnswer(i -> ResponseEntity.ok(Map.of("secret", SECRET, "otpauthUri", URI)));
    }

    private void stubConfirmOk() {
        when(mfa.confirm(any(), any(), any())).thenAnswer(i -> ResponseEntity.ok(Map.of("confirmed", true, "recoveryCodes", CODES)));
    }

    // ── MFA 꺼짐 ────────────────────────────────────────────

    @Test
    @DisplayName("꺼짐: 배지·일러스트(장식)·켜기 카드, 켜기 버튼은 enroll 조각 요청, 조각은 레이아웃 없이")
    void offShowsEnrollCard() throws Exception {
        MvcResult r = tab(rp());
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        Document d = doc(r);
        assertThat(d.select("aside, nav.sidebar, header.topbar")).isEmpty();
        Element card = d.selectFirst("#mfa-account");
        assertThat(card.select("h2.card__title").text()).isEqualTo("2단계 인증 (MFA)");
        Element badge = card.selectFirst(".badge");
        assertThat(badge.text()).isEqualTo("꺼짐");
        assertThat(badge.hasClass("badge--success")).isFalse();
        assertThat(card.text()).contains("로그인 시 비밀번호에 더해 authenticator 앱의 6자리 코드를 요구합니다.")
                .contains("계정을 한 단계 더 보호하세요")
                .contains("3단계로 끝납니다 — 앱으로 QR 스캔 → 코드 확인 → 복구 코드 저장.");
        assertThat(card.selectFirst("img").attr("alt")).isEmpty();
        Element btn = card.selectFirst("button.btn--primary");
        assertThat(btn.text()).isEqualTo("2단계 인증 켜기");
        assertThat(btn.attr("hx-post")).isEqualTo(AccountTabController.ENROLL_PATH);
        assertThat(btn.attr("hx-target")).isEqualTo("#mfa-enroll-slot");
        assertThat(btn.attr("hx-disabled-elt")).isEqualTo("this");
        assertThat(card.text()).doesNotContain("위험 영역");
        assertThat(d.select("#mfa-enroll-slot, #mfa-rc-slot")).hasSize(2);
        // 슬롯은 카드 바깥(카드 교체가 다이얼로그를 지우지 않는다)
        assertThat(card.select("#mfa-enroll-slot, #mfa-rc-slot")).isEmpty();
    }

    // ── MFA 켜짐 ────────────────────────────────────────────

    @Test
    @DisplayName("켜짐: badge--success, 등록일·남은 복구 코드 줄 없음(서버 값 없음), 보관 안내, 위험 영역 + 끄기 확인창(alertdialog)")
    void onShowsDangerZone() throws Exception {
        when(users.findByEmail(anyString())).thenReturn(Optional.of(user(true)));
        Document d = doc(tab(rp()));
        Element card = d.selectFirst("#mfa-account");
        assertThat(card.selectFirst(".badge").text()).isEqualTo("켜짐");
        assertThat(card.selectFirst(".badge").hasClass("badge--success")).isTrue();
        assertThat(card.text()).contains("로그인 시 authenticator 코드가 필요합니다.")
                .doesNotContain("등록일").doesNotContain("남은 복구 코드")
                .contains("복구 코드 보관").contains("한 번만 표시됩니다")
                .contains("위험 영역").contains("끄면 비밀번호만으로 로그인됩니다. 현재 authenticator 코드가 필요합니다.");
        assertThat(card.select("button").stream().map(Element::text)).contains("2단계 인증 끄기");
        assertThat(card.text()).doesNotContain("계정을 한 단계 더 보호하세요");

        Element tpl = card.selectFirst("template");
        Document dlg = Jsoup.parse(tpl.html());
        Element dialog = dlg.selectFirst("[role=alertdialog]");
        assertThat(dialog.attr("aria-labelledby")).isEqualTo("mfa-disable-title");
        assertThat(dlg.selectFirst("#mfa-disable-title").text()).isEqualTo("2단계 인증 끄기");
        assertThat(dlg.text()).contains("본인 확인을 위해 현재 authenticator 코드를 입력하세요.");
        Element input = dlg.selectFirst("#mfa-disable-code");
        assertThat(dlg.selectFirst("label[for=mfa-disable-code]").text()).isEqualTo("인증 코드");
        assertThat(input.attr("name")).isEqualTo("code");
        assertThat(input.attr("inputmode")).isEqualTo("numeric");
        assertThat(input.attr("autocomplete")).isEqualTo("one-time-code");
        assertThat(input.attr("placeholder")).isEqualTo("000000");
        assertThat(input.attr("x-bind:readonly")).isEqualTo("disabling");
        Element form = dlg.selectFirst("#mfa-disable-form");
        assertThat(form.attr("hx-post")).isEqualTo(AccountTabController.DISABLE_PATH);
        assertThat(form.attr("hx-target")).isEqualTo("#mfa-account");
        assertThat(form.attr("hx-swap")).isEqualTo("outerHTML");
        assertThat(form.hasAttr("data-quiet")).isTrue();
        Element off = dlg.selectFirst("button[type=submit]");
        assertThat(off.text()).isEqualTo("끄기");
        assertThat(off.hasClass("btn--danger-solid")).isTrue();
        assertThat(off.hasAttr("disabled")).isTrue();
        assertThat(off.attr("x-bind:disabled")).isEqualTo("code.length !== 6 || disabling");
        assertThat(dlg.select("button").stream().map(Element::text)).contains("취소");
    }

    // ── 켜기 1–2 / 3 ────────────────────────────────────────

    @Test
    @DisplayName("enroll: QR(data URI)·수동 키·진행 표시·OTP 6칸·확인 버튼(6자리 전 비활성), no-store·hx-history=false")
    void enrollRendersDialog() throws Exception {
        stubEnrollOk();
        MvcResult r = enroll();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getHeader("Cache-Control")).contains("no-store");
        Document d = doc(r);
        Element wrap = d.selectFirst("[data-mfa-wrap]");
        assertThat(wrap.attr("hx-history")).isEqualTo("false");
        Document in = Jsoup.parse(wrap.selectFirst("template").html());
        Element dialog = in.selectFirst("[role=dialog]");
        assertThat(dialog.attr("aria-label")).isEqualTo("2단계 인증 켜기");
        assertThat(in.select(".stepper__label").eachText()).containsExactly("QR 스캔", "코드 확인", "복구 코드 저장");
        assertThat(in.text()).contains("1–2 / 3").contains("앱으로 스캔하고 코드 확인").contains("또는 수동 입력 키")
                .contains("Google Authenticator · 1Password 등 TOTP 앱에 입력하세요.").contains("앱에 표시된 인증 코드");
        assertThat(in.selectFirst(".secret__value").text()).isEqualTo(SECRET);
        assertThat(in.selectFirst("img[alt=QR 코드]").attr("src")).startsWith("data:image/svg+xml;base64,");
        assertThat(in.select(".otp__cell")).hasSize(6);
        assertThat(in.selectFirst(".otp__cell").attr("aria-label")).isEqualTo("인증 코드 1번째 자리");
        Element form = in.selectFirst("#mfa-confirm-form");
        assertThat(form.attr("hx-post")).isEqualTo(AccountTabController.CONFIRM_PATH);
        assertThat(form.attr("hx-target")).isEqualTo("#mfa-rc-slot");
        assertThat(form.hasAttr("data-quiet")).isTrue();
        assertThat(form.selectFirst("input[type=hidden][name=code]")).isNotNull();
        Element ok = in.selectFirst("button[type=submit][form=mfa-confirm-form]");
        assertThat(ok.text()).isEqualTo("확인 → 복구 코드");
        assertThat(ok.hasAttr("disabled")).isTrue();
        assertThat(ok.attr("x-bind:disabled")).isEqualTo("code.length !== 6 || confirming");
        assertThat(in.select("button").stream().map(Element::text)).contains("취소");
        // 진행 표시는 코드 입력 여부로 바뀐다(Alpine stepState).
        assertThat(in.select(".stepper__item").get(0).attr("x-bind:aria-current")).contains("stepState(0)");
        assertThat(in.select(".stepper__item").get(2).hasClass("stepper__item--todo")).isTrue();
        // secret 은 본문에만
        for (String h : r.getResponse().getHeaderNames()) {
            for (String v : r.getResponse().getHeaders(h)) assertThat(v).as(h).doesNotContain(SECRET);
        }
    }

    @Test
    @DisplayName("qrIsInlineDataUriNoExternal: 켜기 응답의 QR 은 인라인 data URI, 디코드한 SVG 에 외부 참조 없음")
    void qrIsInlineDataUriNoExternal() throws Exception {
        stubEnrollOk();
        Document d = doc(enroll());
        Document in = Jsoup.parse(d.selectFirst("template").html());
        String src = in.selectFirst("img[alt=QR 코드]").attr("src");
        assertThat(src).startsWith("data:image/svg+xml;base64,");
        String svg = new String(Base64.getDecoder().decode(src.substring("data:image/svg+xml;base64,".length())), StandardCharsets.UTF_8);
        assertThat(svg).startsWith("<svg").contains("width=\"152\"");
        assertThat(svg.replace("xmlns=\"http://www.w3.org/2000/svg\"", "")).doesNotContain("http").doesNotContain("//")
                .doesNotContain("href").doesNotContain("url(").doesNotContain("<image").doesNotContain("<script");
        assertThat(body(enroll())).doesNotContain("<svg xmlns"); // 인라인 SVG(th:utext) 아님
    }

    @Test
    @DisplayName("enroll 실패: 오류 토스트 '2단계 인증을 시작하지 못했습니다' + 실제 상태 + 빈 본문")
    void enrollFailureToast() throws Exception {
        when(mfa.enroll(any(), any())).thenAnswer(i -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "mfa_required")));
        MvcResult r = enroll();
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        JsonNode t = trigger(r).get("toast");
        assertThat(t.get("kind").asString()).isEqualTo("error");
        assertThat(t.get("title").asString()).isEqualTo("2단계 인증을 시작하지 못했습니다");
        assertThat(t.get("message").asString()).isEqualTo("권한이 없습니다. 이 작업을 할 권한이 없습니다. 운영자에게 문의하세요.");
        assertThat(body(r).trim()).isEmpty();

        when(mfa.enroll(any(), any())).thenThrow(new IllegalStateException("db secret leaked"));
        MvcResult boom = enroll();
        assertThat(boom.getResponse().getStatus()).isEqualTo(500);
        assertThat(trigger(boom).toString()).contains("서버가 잠시 응답하지 않습니다").doesNotContain("leaked");
    }

    // ── 확인 → 복구 코드 ────────────────────────────────────

    @Test
    @DisplayName("confirm 성공: 복구 코드 alertdialog(10개, 01–10, 목록 이름), 파일명, 체크 전 닫기 비활성, 켜기 다이얼로그 닫기, 카드 OOB(켜짐), 성공 토스트")
    void confirmShowsRecoveryCodes() throws Exception {
        stubConfirmOk();
        when(users.findByEmail(anyString())).thenReturn(Optional.of(user(false)), Optional.of(user(true)));
        MvcResult r = confirm("123456");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        ArgumentCaptor<MfaController.VerifyRequest> req = ArgumentCaptor.forClass(MfaController.VerifyRequest.class);
        verify(mfa).confirm(req.capture(), any(), any());
        assertThat(req.getValue().code()).isEqualTo("123456");

        Document d = doc(r);
        assertThat(d.selectFirst("[x-init]").attr("x-init")).contains("mfa-enroll-done");
        Element wrap = d.selectFirst("[data-mfa-wrap]");
        assertThat(wrap.attr("hx-history")).isEqualTo("false");
        assertThat(wrap.attr("x-data")).contains("\"dismissible\":false").contains("\"closeOnScrim\":false");
        Document in = Jsoup.parse(wrap.selectFirst("template").html());
        Element dialog = in.selectFirst("[role=alertdialog]");
        assertThat(dialog.attr("aria-label")).isEqualTo(RC_TITLE);
        assertThat(dialog.attr("aria-modal")).isEqualTo("true");
        assertThat(dialog.attr("data-filename")).isEqualTo("passkey-admin-recovery-codes-alice-20261011.txt");
        assertThat(in.selectFirst("img").attr("alt")).isEmpty();
        Element list = in.selectFirst("ol[aria-label=복구 코드 목록]");
        assertThat(list.children()).hasSize(10);
        assertThat(list.select("[data-rc-code]").eachText()).containsExactlyElementsOf(CODES);
        assertThat(list.select(".rc-list__no").first().text()).isEqualTo("01");
        assertThat(list.select(".rc-list__no").last().text()).isEqualTo("10");
        assertThat(in.text()).contains("authenticator 기기를 잃었을 때 각 코드를 한 번씩 로그인에 쓸 수 있습니다.")
                .contains("다운로드 (.txt)").contains("복사 실패 시: 직접 선택하거나 다운로드")
                .contains("복사에 실패했습니다. 위 코드를 직접 선택해 복사하거나 다운로드하세요.");
        assertThat(in.selectFirst("label.rc-ack").text()).isEqualTo("안전한 곳에 저장했습니다.");
        assertThat(in.selectFirst("label.rc-ack input[type=checkbox]")).isNotNull();
        Element close = in.selectFirst(".dialog__foot button");
        assertThat(close.text()).isEqualTo("닫기 · 체크 필요");
        assertThat(close.hasAttr("disabled")).isTrue();
        assertThat(close.attr("x-bind:disabled")).isEqualTo("!checked");

        Element oob = d.selectFirst("#mfa-account");
        assertThat(oob.attr("hx-swap-oob")).isEqualTo("true");
        assertThat(oob.selectFirst(".badge").text()).isEqualTo("켜짐");
        JsonNode t = trigger(r).get("toast");
        assertThat(t.get("kind").asString()).isEqualTo("success");
        assertThat(t.get("title").asString()).isEqualTo("2단계 인증이 켜졌습니다.");
        assertThat(r.getResponse().getHeader("Cache-Control")).contains("no-store");
    }

    @Test
    @DisplayName("recoveryCodesNotStoredInSession: 코드는 응답 본문에만 — 세션 속성·플래시·헤더·토스트·리다이렉트에 없음")
    void recoveryCodesNotStoredInSession() throws Exception {
        stubConfirmOk();
        MvcResult r = confirm("123456");
        String b = body(r);
        for (String c : CODES) assertThat(b).contains(c);
        HttpSession s = r.getRequest().getSession(false);
        if (s != null) {
            for (String n : Collections.list(s.getAttributeNames())) {
                String v = String.valueOf(s.getAttribute(n));
                for (String c : CODES) assertThat(v).as("session attribute " + n).doesNotContain(c);
            }
        }
        assertThat(r.getFlashMap().isEmpty()).isTrue();
        for (String h : r.getResponse().getHeaderNames()) {
            for (String v : r.getResponse().getHeaders(h)) {
                for (String c : CODES) assertThat(v).as("header " + h).doesNotContain(c);
            }
        }
        assertThat(r.getResponse().getRedirectedUrl()).isNull();
        assertThat(r.getResponse().getHeader("HX-Redirect")).isNull();
        assertThat(r.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(r.getResponse().getHeader("Pragma")).isEqualTo("no-cache");
    }

    @Test
    @DisplayName("confirm 후 계정 정보 새로고침이 실패해도 복구 코드는 보여 준다(카드 OOB 없음, 경고 토스트)")
    void confirmMeReloadFailureStillShowsCodes() throws Exception {
        stubConfirmOk();
        when(users.findByEmail(anyString())).thenReturn(Optional.of(user(false))).thenThrow(new IllegalStateException("db"));
        MvcResult r = confirm("123456");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        Document d = doc(r);
        assertThat(Jsoup.parse(d.selectFirst("template").html()).select("[data-rc-code]").eachText()).contains("AAAA-BBBB");
        assertThat(d.selectFirst("#mfa-account")).isNull();
        JsonNode t = trigger(r).get("toast");
        assertThat(t.get("kind").asString()).isEqualTo("warn");
        assertThat(t.get("title").asString()).isEqualTo("계정 정보를 새로고침하지 못했습니다.");
        assertThat(t.get("message").asString()).isEqualTo("페이지를 새로고침해 주세요.");
        assertThat(trigger(r).toString()).doesNotContain("2단계 인증 확인 실패");
    }

    @Test
    @DisplayName("잘못된 코드(401 invalid_code): 다이얼로그 안 인라인(422 + #mfa-enroll-error), 토스트 없음")
    void confirmInvalidCodeInline() throws Exception {
        when(mfa.confirm(any(), any(), any())).thenAnswer(i -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid_code")));
        MvcResult r = confirm("000000");
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
        assertThat(r.getResponse().getHeader("HX-Retarget")).isEqualTo("#mfa-enroll-error");
        assertThat(r.getResponse().getHeader("HX-Reswap")).isEqualTo("innerHTML");
        assertThat(r.getResponse().getHeader("HX-Trigger")).isNull();
        Element p = doc(r).selectFirst("p.field__error[role=alert]");
        assertThat(p.text()).isEqualTo(CODE_MISMATCH);
    }

    @Test
    @DisplayName("403(권한)·5xx·예외는 코드 불일치가 아니라 쉬운 문구로 인라인(원문 없음)")
    void confirmOtherFailuresInline() throws Exception {
        when(mfa.confirm(any(), any(), any())).thenAnswer(i -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "mfa_required")));
        MvcResult r = confirm("123456");
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
        assertThat(doc(r).text()).contains("권한이 없습니다").doesNotContain(CODE_MISMATCH);

        when(mfa.confirm(any(), any(), any())).thenThrow(new IllegalStateException("ORA-00001 secret"));
        MvcResult boom = confirm("123456");
        assertThat(boom.getResponse().getStatus()).isEqualTo(422);
        assertThat(doc(boom).text()).isEqualTo("서버가 잠시 응답하지 않습니다. 저장된 데이터는 그대로 있습니다. 잠시 후 다시 시도하세요.");
        assertThat(body(boom)).doesNotContain("ORA").doesNotContain("secret");
        assertThat(boom.getResponse().getHeader("HX-Trigger")).isNull();
    }

    // ── 끄기 ────────────────────────────────────────────────

    @Test
    @DisplayName("끄기: disable(code) 호출, 성공하면 카드가 꺼짐으로(#mfa-account 교체) + 경고 토스트")
    void disableSuccess() throws Exception {
        when(users.findByEmail(anyString())).thenReturn(Optional.of(user(true)), Optional.of(user(false)));
        when(mfa.disable(any(), any(), any())).thenAnswer(i -> ResponseEntity.ok(Map.of("disabled", true)));
        MvcResult r = disable("123456");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        ArgumentCaptor<MfaController.VerifyRequest> req = ArgumentCaptor.forClass(MfaController.VerifyRequest.class);
        verify(mfa).disable(req.capture(), any(), any());
        assertThat(req.getValue().code()).isEqualTo("123456");
        Document d = doc(r);
        Element card = d.selectFirst("#mfa-account");
        assertThat(card.hasAttr("hx-swap-oob")).isFalse();
        assertThat(card.selectFirst(".badge").text()).isEqualTo("꺼짐");
        assertThat(card.text()).contains("2단계 인증 켜기");
        JsonNode t = trigger(r).get("toast");
        assertThat(t.get("kind").asString()).isEqualTo("warn");
        assertThat(t.get("title").asString()).isEqualTo("2단계 인증이 꺼졌습니다.");
    }

    @Test
    @DisplayName("끄기 실패: 401 invalid_code → 코드 불일치, 429 → 요청 과다 문구, 확인창 안 인라인(422 + #mfa-disable-err), 토스트 없음")
    void disableFailureInline() throws Exception {
        when(mfa.disable(any(), any(), any())).thenAnswer(i -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid_code")));
        MvcResult r = disable("123456");
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
        assertThat(r.getResponse().getHeader("HX-Retarget")).isEqualTo("#mfa-disable-err");
        assertThat(r.getResponse().getHeader("HX-Trigger")).isNull();
        assertThat(doc(r).text()).isEqualTo(CODE_MISMATCH);

        when(mfa.disable(any(), any(), any())).thenAnswer(i -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", "error")));
        MvcResult many = disable("123456");
        assertThat(doc(many).text()).contains("요청이 너무 많습니다").doesNotContain(CODE_MISMATCH);
    }

    @Test
    @DisplayName("끄기 성공 뒤 계정 정보 새로고침 실패: 카드는 그대로(HX-Reswap none) + 경고 토스트")
    void disableMeReloadFailure() throws Exception {
        when(users.findByEmail(anyString())).thenReturn(Optional.of(user(true))).thenThrow(new IllegalStateException("db"));
        when(mfa.disable(any(), any(), any())).thenAnswer(i -> ResponseEntity.ok(Map.of("disabled", true)));
        MvcResult r = disable("123456");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getHeader("HX-Reswap")).isEqualTo("none");
        assertThat(trigger(r).get("toast").get("title").asString()).isEqualTo("계정 정보를 새로고침하지 못했습니다.");
    }

    @Test
    @DisplayName("MFA 변경 요청은 CSRF 가 필요하다")
    void mutationsRequireCsrf() throws Exception {
        MvcResult r = mvc.perform(post(AccountTabController.CONFIRM_PATH).with(authentication(rp())).header("HX-Request", "true").param("code", "123456")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        verify(mfa, never()).confirm(any(), any(), any());
    }

    @Test
    @DisplayName("codeErrorText: 401 invalid_code 만 코드 불일치, 그 밖은 describeError 의 '제목. 설명'")
    void codeErrorTextMapping() {
        assertThat(AccountTabController.codeErrorText(401, "invalid_code")).isEqualTo(CODE_MISMATCH);
        assertThat(AccountTabController.codeErrorText(401, "unauthorized")).isEqualTo("요청을 처리하지 못했습니다. unauthorized");
        assertThat(AccountTabController.codeErrorText(403, "mfa_required")).isEqualTo("권한이 없습니다. 이 작업을 할 권한이 없습니다. 운영자에게 문의하세요.");
        assertThat(AccountTabController.codeErrorText(429, "error")).isEqualTo("요청이 너무 많습니다. 잠시 후 다시 시도하세요.");
        assertThat(AccountTabController.codeErrorText(503, "error")).startsWith("서버가 잠시 응답하지 않습니다.");
    }

    @Test
    @DisplayName("PLATFORM 도 같은 내 계정 탭")
    void platformSameAccountTab() throws Exception {
        Document d = doc(tab(platform()));
        assertThat(d.selectFirst("#mfa-account")).isNotNull();
    }
}
