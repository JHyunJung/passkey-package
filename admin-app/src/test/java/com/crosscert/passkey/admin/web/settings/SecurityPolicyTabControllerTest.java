package com.crosscert.passkey.admin.web.settings;

import com.crosscert.passkey.admin.audit.AuditChainMonitorController;
import com.crosscert.passkey.admin.auth.ActiveTenantController;
import com.crosscert.passkey.admin.auth.AdminUserDetails;
import com.crosscert.passkey.admin.auth.MfaController;
import com.crosscert.passkey.admin.license.LicenseController;
import com.crosscert.passkey.admin.operator.SignupRequestController;
import com.crosscert.passkey.admin.policy.SecurityPolicyController;
import com.crosscert.passkey.admin.policy.SecurityPolicyDto;
import com.crosscert.passkey.admin.system.SystemInfoController;
import com.crosscert.passkey.admin.web.AdminWebSliceTest;
import com.crosscert.passkey.admin.web.format.PolicyValidation;
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

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** 설정 › 보안 정책 탭. 원본: SecurityPolicyTab.test.tsx, lib/policyValidation.ts. MockMvc 의 콘솔 origin 은 http://localhost. */
@AdminWebSliceTest(controllers = {SecurityPolicyTabController.class, SettingsPageController.class, AccountTabController.class})
class SecurityPolicyTabControllerTest {

    @Autowired MockMvc mvc;
    @MockBean SecurityPolicyController policy;
    @MockBean MfaController mfa;
    @MockBean SignupRequestController signups;
    @MockBean SystemInfoController systemInfo;
    @MockBean LicenseController license;
    @MockBean AuditChainMonitorController chain;
    @MockBean ActiveTenantController activeTenants;

    static final String CONSOLE = "http://localhost";
    static final String SAVE = SecurityPolicyTabController.SAVE_PATH;

    static SecurityPolicyDto.View view(int idle, boolean mfa, String... cors) {
        return new SecurityPolicyDto.View(idle, mfa, List.of(cors), OffsetDateTime.parse("2026-10-10T00:00:00Z"), "a@x.com");
    }

    @BeforeEach
    void setUp() {
        when(policy.get()).thenReturn(view(30, true, "https://a.example.com", CONSOLE));
        when(policy.update(any(), any())).thenReturn(view(45, false, "https://a.example.com", CONSOLE));
        when(signups.list()).thenReturn(List.of());
    }

    static Authentication platform() { return auth("PLATFORM_OPERATOR", Set.of()); }
    static Authentication rp() { return auth("RP_ADMIN", Set.of(UUID.randomUUID())); }

    private static Authentication auth(String role, Set<UUID> tenants) {
        AdminUserDetails p = new AdminUserDetails(UUID.randomUUID(), "a@x.com", "x", role, tenants, true, null, Clock.systemUTC());
        return new UsernamePasswordAuthenticationToken(p, "x", p.getAuthorities());
    }

    private MvcResult send(MockHttpServletRequestBuilder b, Authentication a, boolean confirm) throws Exception {
        MockHttpServletRequestBuilder r = b.with(authentication(a)).with(csrf()).header("HX-Request", "true");
        if (confirm) r.header("X-Confirm", "1");
        return mvc.perform(r).andReturn();
    }

    private static MockHttpServletRequestBuilder save(String idle, String mfa, String... cors) {
        MockHttpServletRequestBuilder b = put(SAVE).param("sessionIdleTimeoutMinutes", idle).param("mfaRequired", mfa);
        if (cors.length > 0) b.param("corsAllowlist", cors);
        return b;
    }

    private static Document html(MvcResult r) throws Exception {
        return Jsoup.parse(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static String trigger(MvcResult r) throws Exception {
        String h = r.getResponse().getHeader("HX-Trigger");
        return h == null ? null : new com.fasterxml.jackson.databind.ObjectMapper().readValue(h, Map.class).toString();
    }

    private Document tab() throws Exception {
        MvcResult r = send(get(SecurityPolicyTabController.TAB_PATH), platform(), false);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return html(r);
    }

    @Test
    @DisplayName("값 표시: idle(1–1440 안내)·MFA 스위치·CORS 칩, 카드 제목+캡션, 저장 바 라벨(저장·취소), PUT 폼")
    void rendersPolicy() throws Exception {
        Document d = tab();
        assertThat(d.selectFirst(".card__title").text()).isEqualTo("세션 & 인증 정책");
        assertThat(d.selectFirst(".card__cap").text()).isEqualTo("모든 콘솔 운영자에게 적용");
        Element idle = d.selectFirst("#sec-idle-timeout");
        assertThat(idle.val()).isEqualTo("30");
        assertThat(idle.attr("name")).isEqualTo("sessionIdleTimeoutMinutes");
        assertThat(idle.attr("min")).isEqualTo("1");
        assertThat(idle.attr("max")).isEqualTo("1440");
        assertThat(d.select("label[for=sec-idle-timeout]").text()).isEqualTo("세션 idle timeout (분)");
        assertThat(d.text()).contains("1–1440분 (최대 24시간)").contains("만료 직전 연장 모달이 뜹니다.");
        Element sw = d.selectFirst("input[role=switch]");
        assertThat(sw.attr("aria-checked")).isEqualTo("true");
        assertThat(d.selectFirst("input[type=hidden][name=mfaRequired]").val()).isEqualTo("true");
        assertThat(d.text()).contains("모든 운영자에게 MFA 필수");
        assertThat(d.select("[x-data*=chipInput]").attr("x-data")).contains("https://a.example.com").contains("origin-check");
        assertThat(d.text()).contains("https://호스트[:포트] 형식. 경로 · 와일드카드(*) 불가.");
        Element form = d.selectFirst("form#sec-form");
        assertThat(form.attr("hx-put")).isEqualTo(SAVE);
        assertThat(form.attr("hx-sync")).isEqualTo("this:drop");
        assertThat(form.attr("data-savebar-labels")).contains("세션 idle timeout").contains("MFA 필수").contains("CORS Allowlist");
        assertThat(form.attr("data-savebar-unordered")).isEqualTo("corsAllowlist");
        assertThat(d.selectFirst(".savebar").text()).contains("저장하지 않은 변경").contains("취소").contains("저장");
        assertThat(d.select(".savebar button").eachText()).containsExactly("취소", "저장");
        assertThat(d.selectFirst("#sec-confirm-slot")).isNotNull();
        // 서버가 정한 필드 오류 자리
        assertThat(d.select(".set")).hasSize(3);
        assertThat(d.select("[data-field-error]").eachAttr("id")).contains("sec-err-sessionIdleTimeoutMinutes", "sec-err-corsAllowlist", "sec-err-form");
    }

    @Test
    @DisplayName("콘솔 주소 경고(Alpine)의 기준: 저장된 목록에 콘솔 주소가 있을 때만 savedHasConsole, 저장된 표기(대소문자)를 되돌리기 값으로")
    void consoleWarningConfig() throws Exception {
        Document d0 = tab();
        assertThat(d0.select(".banner").text()).contains("은 지금 쓰고 있는 콘솔 주소입니다. 이 주소가 CORS 허용 목록에서 빠집니다.").contains("다른 도메인에서 이 콘솔 API를 호출하는 구성이라면 접속이 막힐 수 있습니다.")
                .doesNotContain("같은 출처");
        assertThat(d0.select(".banner button").eachText()).contains("삭제 되돌리기");
        String cfg = d0.selectFirst("form#sec-form").attr("x-data");
        assertThat(cfg).contains("\"savedHasConsole\":true").contains("\"consoleOrigin\":\"http://localhost\"").contains("\"consoleSaved\":\"http://localhost\"");
        when(policy.get()).thenReturn(view(30, true, "https://a.example.com"));
        assertThat(tab().selectFirst("form#sec-form").attr("x-data")).contains("\"savedHasConsole\":false");
        when(policy.get()).thenReturn(view(30, true, "http://LOCALHOST"));
        assertThat(tab().selectFirst("form#sec-form").attr("x-data")).contains("\"savedHasConsole\":true").contains("\"consoleSaved\":\"http://LOCALHOST\"");
    }

    @Test
    @DisplayName("불러오지 못하면 입력칸·저장 없이 LoadError(기본값처럼 보이지 않음), 원문 없음, 다시 시도 = 탭 다시 받기")
    void loadFailure() throws Exception {
        when(policy.get()).thenThrow(new IllegalStateException("boom-internal"));
        Document d = tab();
        assertThat(d.select("form, input#sec-idle-timeout, .savebar")).isEmpty();
        Element alert = d.selectFirst("[role=alert]");
        assertThat(alert.text()).contains("보안 정책을 불러오지 못했습니다").doesNotContain("boom-internal");
        Element retry = alert.select("button").stream().filter(b -> b.text().equals("다시 시도")).findFirst().orElseThrow();
        assertThat(retry.attr("hx-get")).isEqualTo(SecurityPolicyTabController.TAB_PATH);
        assertThat(retry.attr("hx-target")).isEqualTo("#settings-tab");
    }

    @Test
    @DisplayName("저장: SecurityPolicyController.update(바뀐 값), saved + 성공 토스트, 탭 전체를 새 값으로(HX-Retarget #settings-tab)")
    void savesAndRerendersTab() throws Exception {
        MvcResult r = send(save("45", "false", "https://a.example.com", CONSOLE), platform(), false);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        verify(policy).update(eq(new SecurityPolicyDto.UpdateRequest(45, false, List.of("https://a.example.com", CONSOLE))), any(Authentication.class));
        assertThat(trigger(r)).contains("saved=true").contains("보안 정책 저장됨").contains("success");
        assertThat(r.getResponse().getHeader("HX-Retarget")).isEqualTo("#settings-tab");
        assertThat(r.getResponse().getHeader("HX-Reswap")).isEqualTo("innerHTML");
        Document d = html(r);
        assertThat(d.selectFirst("#sec-idle-timeout").val()).isEqualTo("45"); // 응답(저장된 값)으로 다시 그림
        assertThat(d.selectFirst("input[role=switch]").attr("aria-checked")).isEqualTo("false");
    }

    @Test
    @DisplayName("저장 검증: idle 가 비었거나 숫자가 아니거나 0·1441 이면 422 + 필드 오류(OOB), 토스트 없음, REST 미호출")
    void idleValidation() throws Exception {
        for (String bad : new String[] {"", "abc", "0", "1441", "-5", "3.5", "99999999999999999999"}) {
            MvcResult r = send(save(bad, "true", "https://a.example.com", CONSOLE), platform(), false);
            assertThat(r.getResponse().getStatus()).as(bad).isEqualTo(422);
            assertThat(trigger(r)).as(bad).isNull();
            Element err = html(r).selectFirst("#sec-err-sessionIdleTimeoutMinutes");
            assertThat(err.text()).as(bad).isEqualTo(PolicyValidation.TIMEOUT_ERROR);
            assertThat(err.attr("hx-swap-oob")).isEqualTo("true");
            assertThat(err.attr("role")).isEqualTo("alert");
        }
        verify(policy, never()).update(any(), any());
    }

    @Test
    @DisplayName("idle 경계 1·1440 은 통과, 서버에 이미 저장된 값(상한 밖)을 손대지 않았으면 오류로 막지 않는다")
    void idleBoundariesAndUntouched() throws Exception {
        assertThat(send(save("1", "true", CONSOLE), platform(), false).getResponse().getStatus()).isEqualTo(200);
        assertThat(send(save("1440", "true", CONSOLE), platform(), false).getResponse().getStatus()).isEqualTo(200);
        when(policy.get()).thenReturn(view(2000, true, CONSOLE));
        assertThat(send(save("2000", "true", CONSOLE), platform(), false).getResponse().getStatus()).isEqualTo(200);
        verify(policy).update(eq(new SecurityPolicyDto.UpdateRequest(2000, true, List.of(CONSOLE))), any());
        // 고치려고 손대면 1–1440 을 요구한다
        assertThat(send(save("2001", "true", CONSOLE), platform(), false).getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    @DisplayName("저장 검증: CORS origin 형식·경로·와일드카드·기본 포트·길이·중복·65개 초과는 422 + corsAllowlist 오류, REST 미호출")
    void corsValidation() throws Exception {
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 65; i++) tooMany.add("https://h" + i + ".example.com");
        String[][] cases = {
                {PolicyValidation.ORIGIN_FORMAT_ERROR, "http://evil.example.com"},
                {PolicyValidation.ORIGIN_FORMAT_ERROR, "https://a.example.com/path"},
                {PolicyValidation.ORIGIN_FORMAT_ERROR, "https://*.example.com"},
                {PolicyValidation.ORIGIN_DEFAULT_PORT_ERROR, "https://a.example.com:443"},
                {PolicyValidation.ORIGIN_DUPLICATE_ERROR, "https://a.example.com", "https://A.example.com"},
        };
        for (String[] c : cases) {
            MvcResult r = send(save("30", "true", java.util.Arrays.copyOfRange(c, 1, c.length)), platform(), false);
            assertThat(r.getResponse().getStatus()).isEqualTo(422);
            assertThat(html(r).selectFirst("#sec-err-corsAllowlist").text()).isEqualTo(c[0]);
        }
        MvcResult many = send(save("30", "true", tooMany.toArray(new String[0])), platform(), false);
        assertThat(many.getResponse().getStatus()).isEqualTo(422);
        assertThat(html(many).selectFirst("#sec-err-corsAllowlist").text()).isEqualTo(PolicyValidation.ORIGIN_COUNT_ERROR);
        MvcResult longO = send(save("30", "true", "https://" + "a".repeat(260) + ".com"), platform(), false);
        assertThat(longO.getResponse().getStatus()).isEqualTo(422);
        assertThat(html(longO).selectFirst("#sec-err-corsAllowlist").text()).isEqualTo(PolicyValidation.ORIGIN_LENGTH_ERROR);
        verify(policy, never()).update(any(), any());
    }

    @Test
    @DisplayName("MFA 값이 true/false 가 아니면 422(일반 오류), 빈 허용 목록·localhost http 는 통과")
    void mfaAndEmptyList() throws Exception {
        MvcResult bad = send(save("30", "maybe", CONSOLE), platform(), false);
        assertThat(bad.getResponse().getStatus()).isEqualTo(422);
        assertThat(html(bad).selectFirst("#sec-err-form").text()).contains("저장할 수 없는 값이 있습니다");
        when(policy.get()).thenReturn(view(30, true));
        assertThat(send(save("30", "true"), platform(), false).getResponse().getStatus()).isEqualTo(200);
        verify(policy).update(eq(new SecurityPolicyDto.UpdateRequest(30, true, List.of())), any());
        assertThat(send(save("30", "true", "http://localhost:3000"), platform(), false).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("콘솔 주소를 목록에서 빼면 저장하지 않고 422 + L2 확인 다이얼로그(주소·안내, 비교한 값을 hidden, confirmLockout=1, 확인 = PUT)")
    void removingConsoleAsksConfirmation() throws Exception {
        MvcResult r = send(save("45", "false", "https://a.example.com"), platform(), false);
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
        verify(policy, never()).update(any(), any());
        assertThat(trigger(r)).isNull();
        Document d = html(r);
        Element form = d.selectFirst("form#sec-confirm-form");
        assertThat(form).isNotNull();
        assertThat(form.select("input[name=sessionIdleTimeoutMinutes]").val()).isEqualTo("45");
        assertThat(form.select("input[name=mfaRequired]").val()).isEqualTo("false");
        assertThat(form.select("input[name=corsAllowlist]").eachAttr("value")).containsExactly("https://a.example.com");
        assertThat(form.select("input[name=confirmLockout]").val()).isEqualTo("1");
        String text = form.html();
        assertThat(text).contains("현재 콘솔 주소를 빼고 저장할까요?").contains("http://localhost")
                .contains("이 CORS 허용 목록에서 빠집니다").contains("필요하면 이 주소를 다시 추가해 저장하세요.");
        Element confirm = form.selectFirst("button[data-confirm]");
        assertThat(confirm.attr("hx-put")).isEqualTo(SAVE);
        assertThat(confirm.attr("hx-target")).isEqualTo("#settings-tab");
        assertThat(confirm.text()).contains("저장");
        assertThat(confirm.hasClass("btn--danger-solid")).isFalse(); // danger=false → primary
        assertThat(d.selectFirst("#sec-err-form").text()).isEmpty(); // 오류 자리는 비워 둔다
    }

    @Test
    @DisplayName("확인(confirmLockout=1)이면 콘솔 주소를 뺀 채로 저장한다. 대소문자만 다른 콘솔 주소가 남아 있으면 확인 없이 저장")
    void confirmedAndCaseInsensitive() throws Exception {
        MvcResult ok = send(save("45", "false", "https://a.example.com").param("confirmLockout", "1"), platform(), true);
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        verify(policy).update(eq(new SecurityPolicyDto.UpdateRequest(45, false, List.of("https://a.example.com"))), any());
        assertThat(trigger(ok)).contains("saved=true");

        MvcResult kept = send(save("45", "false", "https://a.example.com", "HTTP://LOCALHOST"), platform(), false);
        assertThat(kept.getResponse().getStatus()).isEqualTo(200); // 대소문자만 다른 콘솔 주소가 남아 있으면 빠진 것이 아니다(확인 없이 저장)
        MvcResult kept2 = send(save("45", "false", "https://a.example.com", "http://localhost"), platform(), false);
        assertThat(kept2.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("저장된 목록에 콘솔 주소가 없었으면 확인 없이 저장")
    void noConsoleInSavedList() throws Exception {
        when(policy.get()).thenReturn(view(30, true, "https://a.example.com"));
        assertThat(send(save("45", "false", "https://a.example.com"), platform(), false).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("저장 실패: 저장 바 경로는 오류 토스트('…입력한 내용은 그대로 있습니다.'), 확인 경로는 확인창 안 인라인, 원문 없음")
    void saveFailure() throws Exception {
        when(policy.update(any(), any())).thenThrow(new IllegalStateException("db-secret"));
        MvcResult bar = send(save("45", "false", "https://a.example.com", CONSOLE), platform(), false);
        assertThat(bar.getResponse().getStatus()).isEqualTo(500);
        assertThat(trigger(bar)).contains("보안 정책을 저장하지 못했습니다").contains("입력한 내용은 그대로 있습니다.").contains("error").doesNotContain("db-secret");
        assertThat(bar.getResponse().getContentAsString()).isEmpty();

        MvcResult dlg = send(save("45", "false", "https://a.example.com").param("confirmLockout", "1"), platform(), true);
        assertThat(dlg.getResponse().getStatus()).isEqualTo(500);
        assertThat(trigger(dlg)).contains("confirmError").contains("보안 정책을 저장하지 못했습니다").doesNotContain("db-secret").doesNotContain("error=");
    }

    @Test
    @DisplayName("저장 전 현재 정책을 읽지 못하면 저장하지 않고 오류(REST update 미호출)")
    void currentPolicyUnreadable() throws Exception {
        when(policy.get()).thenThrow(new IllegalStateException("boom"));
        MvcResult r = send(save("45", "false", CONSOLE), platform(), false);
        assertThat(r.getResponse().getStatus()).isEqualTo(500);
        verify(policy, never()).update(any(), any());
    }

    @Test
    @DisplayName("origin-check: 통과하면 본문 비움 + X-Normalized-Value(소문자), 오류면 문구, 중복·잘못된 형식")
    void originCheck() throws Exception {
        MvcResult ok = send(get(SecurityPolicyTabController.ORIGIN_CHECK_PATH).param("value", " https://New.Example.com "), platform(), false);
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(ok.getResponse().getContentAsString()).isEmpty();
        assertThat(ok.getResponse().getHeader("X-Normalized-Value")).isEqualTo("https://new.example.com");
        MvcResult bad = send(get(SecurityPolicyTabController.ORIGIN_CHECK_PATH).param("value", "https://a.example.com/x"), platform(), false);
        assertThat(bad.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(PolicyValidation.ORIGIN_FORMAT_ERROR);
        assertThat(bad.getResponse().getHeader("X-Normalized-Value")).isNull();
        MvcResult dup = send(get(SecurityPolicyTabController.ORIGIN_CHECK_PATH).param("value", "https://A.example.com").param("existing", "https://a.example.com"), platform(), false);
        assertThat(dup.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(PolicyValidation.ORIGIN_DUPLICATE_ERROR);
    }

    @Test
    @DisplayName("모든 엔드포인트는 RP_ADMIN 에게 403 이고 REST 를 부르지 않는다")
    void rpAdminForbidden() throws Exception {
        assertThat(send(get(SecurityPolicyTabController.TAB_PATH), rp(), false).getResponse().getStatus()).isEqualTo(403);
        assertThat(send(save("45", "false", CONSOLE), rp(), false).getResponse().getStatus()).isEqualTo(403);
        assertThat(send(save("45", "false", CONSOLE).param("confirmLockout", "1"), rp(), true).getResponse().getStatus()).isEqualTo(403);
        assertThat(send(get(SecurityPolicyTabController.ORIGIN_CHECK_PATH).param("value", "https://x.example.com"), rp(), false).getResponse().getStatus()).isEqualTo(403);
        verify(policy, never()).get();
        verify(policy, never()).update(any(), any());
    }

    @Test
    @DisplayName("리터럴 …/tab/security 가 일반 탭 매핑보다 우선(PLATFORM 200, RP 는 계정 탭으로 떨어지지 않고 403)")
    void literalWinsOverGeneric() throws Exception {
        MvcResult r = send(get("/admin/_/settings/tab/security"), platform(), false);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(html(r).selectFirst("form#sec-form")).isNotNull();
        assertThat(send(get("/admin/_/settings/tab/security"), rp(), false).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("removesConsole 단위: 저장된 목록엔 있고 새 목록엔 없을 때만, 대소문자 무시")
    void removesConsoleUnit() {
        assertThat(SecurityPolicyTabController.removesConsole(List.of("http://localhost"), List.of(), "http://localhost")).isTrue();
        assertThat(SecurityPolicyTabController.removesConsole(List.of("http://LOCALHOST"), List.of("http://localhost"), "http://localhost")).isFalse();
        assertThat(SecurityPolicyTabController.removesConsole(List.of("https://a.example.com"), List.of(), "http://localhost")).isFalse();
    }
}
