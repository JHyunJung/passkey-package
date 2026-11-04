package com.crosscert.passkey.admin.web.settings;

import com.crosscert.passkey.admin.audit.AuditChainMonitorController;
import com.crosscert.passkey.admin.auth.ActiveTenantController;
import com.crosscert.passkey.admin.auth.AdminUserDetails;
import com.crosscert.passkey.admin.auth.MfaController;
import com.crosscert.passkey.admin.license.LicenseController;
import com.crosscert.passkey.admin.operator.AdminUserController;
import com.crosscert.passkey.admin.operator.AdminUserDto;
import com.crosscert.passkey.admin.operator.SignupRequestController;
import com.crosscert.passkey.admin.operator.SignupRequestDto;
import com.crosscert.passkey.admin.system.SystemInfoController;
import com.crosscert.passkey.admin.tenant.TenantAdminController;
import com.crosscert.passkey.admin.tenant.TenantAdminDto;
import com.crosscert.passkey.admin.web.AdminWebSliceTest;
import com.crosscert.passkey.core.api.ApiResponse;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.server.ResponseStatusException;

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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 설정 › Admin 사용자 탭. 원본: AdminUsersTab.test.tsx. */
@AdminWebSliceTest(controllers = {AdminUsersTabController.class, SettingsPageController.class, AccountTabController.class})
class AdminUsersTabControllerTest {

    @Autowired MockMvc mvc;
    @Autowired Clock clock;
    @MockBean AdminUserController users;
    @MockBean SignupRequestController signups;
    @MockBean TenantAdminController tenants;
    @MockBean MfaController mfa;
    @MockBean SystemInfoController systemInfo;
    @MockBean LicenseController license;
    @MockBean AuditChainMonitorController chain;
    @MockBean ActiveTenantController activeTenants;

    static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");
    static final String BASE = "/admin/_/settings";
    static final UUID ME = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final UUID OTHER_PLAT = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    static final UUID RP_USER = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
    static final UUID SUSPENDED = UUID.fromString("00000000-0000-0000-0000-0000000000a4");
    static final UUID T1 = UUID.fromString("00000000-0000-0000-0000-000000000b01");
    static final UUID T2 = UUID.fromString("00000000-0000-0000-0000-000000000b02");
    static final UUID REQ1 = UUID.fromString("00000000-0000-0000-0000-000000000c01");
    static final UUID REQ2 = UUID.fromString("00000000-0000-0000-0000-000000000c02");
    static final String ME_EMAIL = "me@x.com";

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(users.list()).thenReturn(List.of(
                op(ME, ME_EMAIL, "PLATFORM_OPERATOR", "ACTIVE", List.of(), true),
                op(OTHER_PLAT, "plat2@x.com", "PLATFORM_OPERATOR", "ACTIVE", List.of(), false),
                op(RP_USER, "rp@x.com", "RP_ADMIN", "ACTIVE", List.of(T1, T2), true),
                op(SUSPENDED, "gone@x.com", "RP_ADMIN", "SUSPENDED", List.of(UUID.fromString("00000000-0000-0000-0000-000000000bff")), false)));
        when(signups.list()).thenReturn(List.of(
                new SignupRequestDto.View(REQ1, "new1@x.com", "<b>팀 합류</b>", OffsetDateTime.parse("2026-10-10T23:55:00Z")),
                new SignupRequestDto.View(REQ2, "new2@x.com", null, OffsetDateTime.parse("2026-10-10T20:00:00Z"))));
        when(tenants.list()).thenReturn(ApiResponse.ok(List.of(tenant(T1, "Acme", "acme", "ACTIVE"), tenant(T2, "Beta", "beta", "ACTIVE"),
                tenant(UUID.randomUUID(), "Old", "old", "SUSPENDED"))));
    }

    static AdminUserDto.View op(UUID id, String email, String role, String status, List<UUID> tenants, boolean mfa) {
        return new AdminUserDto.View(id, email, role, status, tenants, OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                mfa ? OffsetDateTime.parse("2026-10-10T23:00:00Z") : null, null, null, mfa);
    }

    static TenantAdminDto.TenantView tenant(UUID id, String name, String slug, String status) {
        return new TenantAdminDto.TenantView(id, slug, name, status, slug + ".example.com", name, List.of(), Set.of(), true, false, "NONE", 60000, 0, 0, null, null, null);
    }

    static Authentication platform() { return auth("PLATFORM_OPERATOR", ME_EMAIL, Set.of()); }
    static Authentication rp() { return auth("RP_ADMIN", "rp@x.com", Set.of(T1)); }

    private static Authentication auth(String role, String email, Set<UUID> tenants) {
        AdminUserDetails p = new AdminUserDetails(ME, email, "x", role, tenants, true, null, Clock.systemUTC());
        return new UsernamePasswordAuthenticationToken(p, "x", p.getAuthorities());
    }

    private MvcResult send(MockHttpServletRequestBuilder b, Authentication a, boolean confirm) throws Exception {
        MockHttpServletRequestBuilder r = b.with(authentication(a)).with(csrf()).header("HX-Request", "true");
        if (confirm) r.header("X-Confirm", "1");
        return mvc.perform(r).andReturn();
    }

    private Document tab() throws Exception {
        MvcResult r = send(get(AdminUsersTabController.TAB_PATH), platform(), false);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return html(r);
    }

    private static Document html(MvcResult r) throws Exception {
        return Jsoup.parse(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** HX-Trigger 헤더(JSON, 한글은 \\u 이스케이프)를 풀어 문자열로. 없으면 null. */
    private static String trigger(MvcResult r) throws Exception {
        String h = r.getResponse().getHeader("HX-Trigger");
        return h == null ? null : new com.fasterxml.jackson.databind.ObjectMapper().readValue(h, java.util.Map.class).toString();
    }

    private static Element row(Document d, String email) {
        return d.select("tr[data-op]").stream().filter(t -> t.text().contains(email)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("가입 요청 카드: 대기 N건, 사유는 평문(th:text), 사유 없음 문구, 승인·거절 버튼")
    void signupRequestsCard() throws Exception {
        Document d = tab();
        Element card = d.selectFirst("#au-signups");
        assertThat(card.text()).contains("가입 요청").contains("대기 2건").contains("승인 시 역할과 tenant 를 지정합니다");
        assertThat(card.select("li")).hasSize(2);
        assertThat(card.select("li").get(0).text()).contains("new1@x.com").contains("<b>팀 합류</b>").contains("5분 전");
        assertThat(card.select("li b")).isEmpty(); // 사용자 입력은 마크업으로 해석되지 않는다
        assertThat(card.select("li").get(1).text()).contains("요청 사유 없음");
        Element approve = card.select("li").get(0).select("button").stream().filter(b -> b.text().equals("승인…")).findFirst().orElseThrow();
        assertThat(approve.attr("hx-get")).isEqualTo(BASE + "/signups/" + REQ1 + "/approve");
        assertThat(approve.attr("hx-target")).isEqualTo("#au-dialog-slot");
        assertThat(card.select("button").eachText()).contains("거절…");
        assertThat(d.selectFirst("#au-dialog-slot")).isNotNull();
        assertThat(d.text()).doesNotContain("초대"); // 초대 버튼은 없다(원본 'no invite button remains')
    }

    @Test
    @DisplayName("대기 요청 0건이면 '대기 0건' + 안내, 가입 요청 목록을 못 읽으면 '대기 —' + LoadError(없음 아님)")
    void signupEmptyAndFailure() throws Exception {
        when(signups.list()).thenReturn(List.of());
        Element card = tab().selectFirst("#au-signups");
        assertThat(card.text()).contains("대기 0건").contains("대기 중인 요청이 없습니다");
        assertThat(card.select("li")).isEmpty();

        when(signups.list()).thenThrow(new IllegalStateException("boom-internal"));
        Document d = tab();
        Element failed = d.selectFirst("#au-signups");
        assertThat(failed.text()).contains("대기 —").contains("가입 요청을 불러오지 못했습니다").doesNotContain("boom-internal");
        assertThat(failed.selectFirst("[role=alert]")).isNotNull();
        assertThat(d.select("tr[data-op]")).hasSize(4); // 운영자 카드는 그대로
    }

    @Test
    @DisplayName("콘솔 운영자 표: 7열, 역할·tenant(이름·외 N개·id 꼬리·전체)·MFA·마지막 로그인·상태, 요약 '4명 · 활성 3명'")
    void operatorsTable() throws Exception {
        Document d = tab();
        Element card = d.selectFirst("#au-users");
        assertThat(card.selectFirst(".card__head").text()).contains("콘솔 운영자").contains("4명 · 활성 3명");
        assertThat(card.select("thead th").eachText()).containsExactly("운영자", "역할", "Tenant", "MFA", "마지막 로그인", "상태", "액션");
        assertThat(row(d, ME_EMAIL).text()).contains("PLATFORM").contains("전체 tenant").contains("ON").contains("1시간 전").contains("활성");
        Element rpRow = row(d, "rp@x.com");
        assertThat(rpRow.text()).contains("RP_ADMIN").contains("Acme").contains("외 1개");
        assertThat(rpRow.selectFirst("td:eq(2) .truncate").attr("title")).isEqualTo(T1.toString());
        Element gone = row(d, "gone@x.com");
        assertThat(gone.text()).contains("…0000000bff".substring(0, 1)).contains("미접속").contains("OFF").contains("정지");
        assertThat(gone.selectFirst("td:eq(2) .mono").text()).startsWith("…");
        assertThat(card.select("[role=group][aria-label=역할] button").eachText()).containsExactly("전체", "PLATFORM", "RP_ADMIN", "정지");
        assertThat(card.selectFirst("input[aria-label=운영자 검색]").attr("placeholder")).isEqualTo("이메일 · ID");
        // 필터용 데이터 속성
        assertThat(rpRow.attr("data-role")).isEqualTo("RP_ADMIN");
        assertThat(gone.attr("data-status")).isEqualTo("SUSPENDED");
        assertThat(rpRow.attr("data-hay")).contains("rp@x.com");
    }

    @Test
    @DisplayName("운영자 목록을 못 읽으면 LoadError(다시 시도 = 탭 다시 받기), 가입 요청 카드는 그대로")
    void usersFailure() throws Exception {
        when(users.list()).thenThrow(new IllegalStateException("boom-internal"));
        Document d = tab();
        Element card = d.selectFirst("#au-users");
        assertThat(card.text()).contains("운영자 목록을 불러오지 못했습니다").doesNotContain("boom-internal");
        assertThat(card.select("tr[data-op]")).isEmpty();
        Element retry = card.select("button").stream().filter(b -> b.text().equals("다시 시도")).findFirst().orElseThrow();
        assertThat(retry.attr("hx-get")).isEqualTo(AdminUsersTabController.TAB_PATH);
        assertThat(d.select("#au-signups li")).hasSize(2);
    }

    @Test
    @DisplayName("행 동작(승인·거절·정지·활성화)은 카드의 stale 상태에 묶여 비활성 + title=STALE_REASON(다시 읽기 실패 때만 켜짐)")
    void rowActionsBoundToStale() throws Exception {
        Document d = tab();
        String title = "stale ? '" + AdminUsersTabController.STALE_REASON + "' : null";
        for (Element b : d.select("#au-signups li button")) {
            assertThat(b.attr("x-bind:disabled")).isEqualTo("stale");
            assertThat(b.attr("x-bind:title")).isEqualTo(title);
        }
        assertThat(d.select("#au-signups li button")).hasSize(4);
        Element suspend = row(d, "rp@x.com").select("button").stream().filter(b -> b.text().equals("정지…")).findFirst().orElseThrow();
        Element activate = row(d, "gone@x.com").selectFirst("button");
        for (Element b : List.of(suspend, activate)) {
            assertThat(b.attr("x-bind:disabled")).isEqualTo("stale");
            assertThat(b.attr("x-bind:title")).isEqualTo(title);
        }
        assertThat(d.selectFirst("#au-signups").attr("x-data")).contains("stale");
        assertThat(d.selectFirst("#au-users").attr("x-data")).contains("operatorFilter");
        assertThat(d.select("[x-init=\"stale = true\"]")).isEmpty(); // 정상 렌더엔 stale 표시 없음
    }

    @Test
    @DisplayName("본인·마지막 활성 PLATFORM 은 정지 버튼이 막히고 이유가 보이며 확인 다이얼로그도 없다. 활성화/정지 버튼 속성")
    void selfProtectionAndActions() throws Exception {
        Document d = tab();
        Element me = row(d, ME_EMAIL);
        assertThat(me.select("button").stream().filter(b -> b.text().equals("정지…")).findFirst().orElseThrow().hasAttr("disabled")).isTrue();
        assertThat(me.text()).contains("본인 계정은 정지할 수 없음");
        assertThat(d.select("#au-suspend-" + ME)).isEmpty();
        assertThat(d.select("[id^=au-suspend-" + ME + "]")).isEmpty();

        // 활성 PLATFORM 이 둘 이상이면 다른 PLATFORM 도 정지할 수 있다
        assertThat(row(d, "plat2@x.com").select("button").stream().filter(b -> b.text().equals("정지…")).findFirst().orElseThrow().hasAttr("disabled")).isFalse();

        Element rp = row(d, "rp@x.com");
        Element suspend = rp.select("button").stream().filter(b -> b.text().equals("정지…")).findFirst().orElseThrow();
        assertThat(suspend.hasAttr("disabled")).isFalse();
        assertThat(suspend.hasClass("btn--danger")).isTrue();
        assertThat(suspend.attr("data-dialog")).isEqualTo("au-suspend-" + RP_USER);

        Element gone = row(d, "gone@x.com");
        Element activate = gone.selectFirst("button");
        assertThat(activate.text()).isEqualTo("활성화");
        assertThat(activate.attr("hx-post")).isEqualTo(BASE + "/admins/" + SUSPENDED + "/activate");
        assertThat(activate.attr("hx-sync")).isEqualTo("this:drop");
        assertThat(activate.attr("hx-disabled-elt")).isEqualTo("this");
    }

    @Test
    @DisplayName("마지막 활성 PLATFORM 운영자만 남으면 그 행이 막힌다(본인이 아니어도)")
    void lastPlatformBlocked() throws Exception {
        when(users.list()).thenReturn(List.of(op(OTHER_PLAT, "plat2@x.com", "PLATFORM_OPERATOR", "ACTIVE", List.of(), false),
                op(RP_USER, "rp@x.com", "RP_ADMIN", "ACTIVE", List.of(T1), true)));
        Document d = tab();
        assertThat(row(d, "plat2@x.com").text()).contains("마지막 활성 PLATFORM 운영자");
        assertThat(d.select("[id=au-suspend-" + OTHER_PLAT + "-title]")).isEmpty();
    }

    @Test
    @DisplayName("정지 확인 다이얼로그: L1(확인 절차 없음), 제목 조사, 이메일·역할·안내, 확인 버튼이 정지 POST(#au-users 교체)")
    void suspendDialog() throws Exception {
        Document d = tab();
        Element dlg = d.selectFirst("[x-data*=\"au-suspend-" + RP_USER + "\"]");
        assertThat(dlg).isNotNull();
        String html = dlg.html();
        assertThat(html).contains("rp@x.com을 정지할까요?").contains("다음 요청부터 콘솔에 접근할 수 없고, 다시 로그인할 수도 없습니다.")
                .contains("계정과 기록은 남습니다.");
        Element confirm = dlg.selectFirst("button[data-confirm]");
        assertThat(confirm.attr("hx-post")).isEqualTo(BASE + "/admins/" + RP_USER + "/suspend");
        assertThat(confirm.attr("hx-target")).isEqualTo("#au-users");
        assertThat(confirm.attr("hx-swap")).isEqualTo("outerHTML");
        assertThat(confirm.attr("hx-headers")).contains("X-Confirm");
        assertThat(dlg.select("input[type=checkbox], input[type=text]")).isEmpty(); // L1: 입력·체크 없음
    }

    @Test
    @DisplayName("정지: AdminUserController.suspend 호출, 성공 토스트, #au-users 갱신")
    void suspendCallsRest() throws Exception {
        MvcResult r = send(post(BASE + "/admins/" + RP_USER + "/suspend"), platform(), true);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        verify(users).suspend(eq(RP_USER), any(Authentication.class));
        assertThat(trigger(r)).contains("rp@x.com 운영자를 정지했습니다.").contains("다음 요청부터 콘솔에 접근할 수 없습니다.").contains("success")
                .doesNotContain("목록의").contains("label=되돌리기").contains("event=au-undo-suspend")
                .contains("url=" + BASE + "/admins/" + RP_USER + "/activate?undo=1");
        Document d = html(r);
        assertThat(d.selectFirst("section#au-users")).isNotNull();
        assertThat(d.select("tr[data-op]")).hasSize(4);
    }

    @Test
    @DisplayName("정지: 본인·마지막 PLATFORM·이미 정지된 대상은 REST 를 부르지 않고 409(확인창 안 사유)")
    void suspendBlockedNoRest() throws Exception {
        MvcResult self = send(post(BASE + "/admins/" + ME + "/suspend"), platform(), true);
        assertThat(self.getResponse().getStatus()).isEqualTo(409);
        assertThat(trigger(self)).contains("confirmError").contains("요청을 처리하지 못했습니다. 본인 계정은 정지할 수 없음");
        assertThat(self.getResponse().getContentAsString()).isEmpty();

        MvcResult stale = send(post(BASE + "/admins/" + SUSPENDED + "/suspend"), platform(), true);
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(trigger(stale)).contains("운영자 상태가 바뀌었습니다").contains("목록을 새로 고친 뒤 다시 시도하세요");

        MvcResult missing = send(post(BASE + "/admins/" + UUID.randomUUID() + "/suspend"), platform(), true);
        assertThat(missing.getResponse().getStatus()).isEqualTo(409);

        when(users.list()).thenReturn(List.of(op(OTHER_PLAT, "plat2@x.com", "PLATFORM_OPERATOR", "ACTIVE", List.of(), false)));
        MvcResult last = send(post(BASE + "/admins/" + OTHER_PLAT + "/suspend"), platform(), true);
        assertThat(last.getResponse().getStatus()).isEqualTo(409);
        assertThat(trigger(last)).contains("마지막 활성 PLATFORM 운영자");
        verify(users, never()).suspend(any(), any());
    }

    @Test
    @DisplayName("정지 REST 실패는 확인창 안 인라인(confirmError, 5xx 문구), 알 수 없는 예외 원문 없음")
    void suspendRestFailure() throws Exception {
        doThrow(new IllegalStateException("db-secret")).when(users).suspend(eq(RP_USER), any());
        MvcResult r = send(post(BASE + "/admins/" + RP_USER + "/suspend"), platform(), true);
        assertThat(r.getResponse().getStatus()).isEqualTo(500);
        assertThat(trigger(r)).contains("confirmError").doesNotContain("db-secret");
        assertThat(r.getResponse().getContentAsString()).isEmpty();
    }

    @Test
    @DisplayName("활성화: 확인 없이 AdminUserController.activate, 토스트, #au-users 갱신. 실패는 '활성화하지 못했습니다' 오류 토스트")
    void activate() throws Exception {
        MvcResult r = send(post(BASE + "/admins/" + SUSPENDED + "/activate"), platform(), false);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        verify(users).activate(eq(SUSPENDED), any(Authentication.class));
        verify(users, times(1)).list(); // 활성화 전에 목록을 읽지 않는다(재조회 한 번뿐)
        assertThat(trigger(r)).contains("운영자를 다시 활성화했습니다.").contains("gone@x.com");
        assertThat(html(r).selectFirst("section#au-users")).isNotNull();

        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "이미 활성")).when(users).activate(eq(SUSPENDED), any());
        MvcResult fail = send(post(BASE + "/admins/" + SUSPENDED + "/activate"), platform(), false);
        assertThat(fail.getResponse().getStatus()).isEqualTo(409);
        assertThat(trigger(fail)).contains("활성화하지 못했습니다").contains("error");
        assertThat(fail.getResponse().getContentAsString()).isEmpty();
    }

    @Test
    @DisplayName("승인 대화상자: 요청 이메일·사유(평문), 역할 카드 2개(RP Admin 선택), ACTIVE tenant 만 체크 목록, 폼은 승인 POST")
    void approveDialog() throws Exception {
        MvcResult r = send(get(BASE + "/signups/" + REQ1 + "/approve"), platform(), false);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        Document d = html(r);
        assertThat(d.selectFirst("#au-approve-title").text()).isEqualTo("가입 요청 승인");
        assertThat(d.text()).contains("new1@x.com").contains("<b>팀 합류</b>");
        assertThat(d.select("b")).isEmpty();
        assertThat(d.select("[role=radiogroup] [role=radio]").eachText()).hasSize(2);
        assertThat(d.select("[role=radiogroup]").text()).contains("Platform Operator").contains("RP Admin");
        assertThat(d.select("input[name=tenantId]").eachAttr("value")).containsExactly(T1.toString(), T2.toString());
        assertThat(d.select("label[data-tenant]").eachText()).anyMatch(t -> t.contains("Acme") && t.contains("acme"));
        assertThat(d.text()).doesNotContain("Old");
        Element form = d.selectFirst("form#au-approve-form");
        assertThat(form.attr("hx-post")).isEqualTo(BASE + "/signups/" + REQ1 + "/approve");
        assertThat(form.attr("hx-target")).isEqualTo("#au-content");
        assertThat(form.attr("hx-sync")).isEqualTo("this:drop");
        assertThat(d.select("button[type=submit]").text()).contains("승인하고 계정 생성");
        assertThat(d.text()).contains("Tenant를 최소 1개 선택해야 합니다.");
    }

    @Test
    @DisplayName("승인 대화상자: 없는 요청은 404(본문 없음), 활성 tenant 가 없으면 '활성 tenant 없음'")
    void approveDialogMissingAndNoTenants() throws Exception {
        assertThat(send(get(BASE + "/signups/" + UUID.randomUUID() + "/approve"), platform(), false).getResponse().getStatus()).isEqualTo(404);
        when(tenants.list()).thenReturn(ApiResponse.ok(List.of()));
        assertThat(html(send(get(BASE + "/signups/" + REQ1 + "/approve"), platform(), false)).text()).contains("활성 tenant 없음");
    }

    @Test
    @DisplayName("승인(RP Admin): SignupRequestController.approve(role, tenantIds), 성공 토스트, 목록·배지·자리 OOB")
    void approveRp() throws Exception {
        when(signups.approve(eq(REQ1), any(), any())).thenReturn(op(UUID.randomUUID(), "new1@x.com", "RP_ADMIN", "ACTIVE", List.of(T1), false));
        when(signups.list()).thenReturn(List.of(new SignupRequestDto.View(REQ2, "new2@x.com", null, OffsetDateTime.parse("2026-10-10T20:00:00Z"))));
        MvcResult r = send(post(BASE + "/signups/" + REQ1 + "/approve").param("role", "RP_ADMIN").param("tenantId", T1.toString(), T2.toString()), platform(), false);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        verify(signups).approve(eq(REQ1), eq(new SignupRequestDto.Approve("RP_ADMIN", List.of(T1, T2))), any(Authentication.class));
        assertThat(trigger(r)).contains("가입 요청을 승인했습니다.").contains("new1@x.com · 즉시 로그인 가능");
        Document d = html(r);
        assertThat(d.selectFirst("#au-content")).isNotNull();
        assertThat(d.select("#au-signups li")).hasSize(1);
        Element slot = d.selectFirst("#au-dialog-slot");
        assertThat(slot.attr("hx-swap-oob")).isEqualTo("true");
        assertThat(slot.children()).isEmpty();
        Element count = d.selectFirst("#settings-tab-admins-count");
        assertThat(count.attr("hx-swap-oob")).isEqualTo("true");
        assertThat(count.text()).isEqualTo("1");
    }

    @Test
    @DisplayName("승인(Platform Operator): tenant 를 보내도 무시하고 빈 목록으로 호출")
    void approvePlatformIgnoresTenants() throws Exception {
        when(signups.approve(eq(REQ1), any(), any())).thenReturn(op(UUID.randomUUID(), "new1@x.com", "PLATFORM_OPERATOR", "ACTIVE", List.of(), false));
        MvcResult r = send(post(BASE + "/signups/" + REQ1 + "/approve").param("role", "PLATFORM_OPERATOR").param("tenantId", T1.toString()), platform(), false);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        verify(signups).approve(eq(REQ1), eq(new SignupRequestDto.Approve("PLATFORM_OPERATOR", List.of())), any());
    }

    @Test
    @DisplayName("승인 검증: RP Admin 인데 tenant 가 없거나 모르는 역할이면 422 + 대화상자 안 오류(HX-Retarget), REST 미호출, 토스트 없음")
    void approveValidation() throws Exception {
        MvcResult none = send(post(BASE + "/signups/" + REQ1 + "/approve").param("role", "RP_ADMIN"), platform(), false);
        assertThat(none.getResponse().getStatus()).isEqualTo(422);
        assertThat(none.getResponse().getHeader("HX-Retarget")).isEqualTo("#au-approve-err");
        assertThat(none.getResponse().getHeader("HX-Reswap")).isEqualTo("innerHTML");
        assertThat(none.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("Tenant를 최소 1개 선택해야 합니다.");
        assertThat(trigger(none)).isNull();
        MvcResult badRole = send(post(BASE + "/signups/" + REQ1 + "/approve").param("role", "ROOT").param("tenantId", T1.toString()), platform(), false);
        assertThat(badRole.getResponse().getStatus()).isEqualTo(422);
        verify(signups, never()).approve(any(), any(), any());
    }

    @Test
    @DisplayName("승인 409(다른 관리자가 먼저)는 경고 토스트 + 목록 갱신(200), 그 밖의 실패는 '승인 실패' 오류 토스트 + 본문 없음")
    void approveConflictAndFailure() throws Exception {
        when(signups.approve(eq(REQ1), any(), any())).thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "done"));
        MvcResult conflict = send(post(BASE + "/signups/" + REQ1 + "/approve").param("role", "PLATFORM_OPERATOR"), platform(), false);
        assertThat(conflict.getResponse().getStatus()).isEqualTo(200);
        assertThat(trigger(conflict)).contains("이미 처리된 요청입니다.").contains("다른 관리자가 먼저 처리했습니다.").contains("warn");
        assertThat(html(conflict).selectFirst("#au-content")).isNotNull();

        when(signups.approve(eq(REQ2), any(), any())).thenThrow(new IllegalStateException("db-secret"));
        MvcResult fail = send(post(BASE + "/signups/" + REQ2 + "/approve").param("role", "PLATFORM_OPERATOR"), platform(), false);
        assertThat(fail.getResponse().getStatus()).isEqualTo(500);
        assertThat(trigger(fail)).contains("승인 실패").doesNotContain("db-secret");
        assertThat(fail.getResponse().getContentAsString()).isEmpty();
    }

    @Test
    @DisplayName("거절 확인 다이얼로그: L1, 제목·본문, 확인 버튼이 거절 POST(#au-signups 교체)")
    void rejectDialog() throws Exception {
        Element dlg = tab().selectFirst("[x-data*=\"au-reject-" + REQ1 + "\"]");
        assertThat(dlg.html()).contains("가입 요청을 거절할까요?").contains("new1@x.com").contains("요청자는 다시 가입 요청을 보낼 수 있습니다.");
        Element confirm = dlg.selectFirst("button[data-confirm]");
        assertThat(confirm.attr("hx-post")).isEqualTo(BASE + "/signups/" + REQ1 + "/reject");
        assertThat(confirm.attr("hx-target")).isEqualTo("#au-signups");
        assertThat(confirm.text()).contains("거절");
    }

    @Test
    @DisplayName("거절: SignupRequestController.reject 호출, 토스트, 가입 요청 카드 + 탭 줄 배지 OOB 갱신")
    void reject() throws Exception {
        when(signups.list()).thenReturn(List.of(new SignupRequestDto.View(REQ1, "new1@x.com", "x", OffsetDateTime.parse("2026-10-10T23:55:00Z"))))
                .thenReturn(List.of());
        MvcResult r = send(post(BASE + "/signups/" + REQ1 + "/reject"), platform(), true);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        verify(signups).reject(eq(REQ1), any(Authentication.class));
        assertThat(trigger(r)).contains("가입 요청을 거절했습니다.").contains("new1@x.com");
        Document d = html(r);
        assertThat(d.selectFirst("section#au-signups").text()).contains("대기 0건");
        Element count = d.selectFirst("#settings-tab-admins-count");
        assertThat(count.attr("hx-swap-oob")).isEqualTo("true");
        assertThat(count.select(".tabs__count")).isEmpty(); // 0 이면 배지 없음
    }

    @Test
    @DisplayName("거절 409 는 경고 토스트 + 갱신, 그 밖의 실패는 확인창 안 인라인(confirmError)")
    void rejectConflictAndFailure() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "done")).when(signups).reject(eq(REQ1), any());
        MvcResult conflict = send(post(BASE + "/signups/" + REQ1 + "/reject"), platform(), true);
        assertThat(conflict.getResponse().getStatus()).isEqualTo(200);
        assertThat(trigger(conflict)).contains("이미 처리된 요청입니다.").contains("warn");

        doThrow(new IllegalStateException("db-secret")).when(signups).reject(eq(REQ2), any());
        MvcResult fail = send(post(BASE + "/signups/" + REQ2 + "/reject"), platform(), true);
        assertThat(fail.getResponse().getStatus()).isEqualTo(500);
        assertThat(trigger(fail)).contains("confirmError").doesNotContain("db-secret");
    }

    @Test
    @DisplayName("모든 엔드포인트는 RP_ADMIN 에게 403 이고 REST 를 부르지 않는다")
    void rpAdminForbiddenEverywhere() throws Exception {
        List<MockHttpServletRequestBuilder> all = List.of(
                get(AdminUsersTabController.TAB_PATH),
                get(BASE + "/signups/" + REQ1 + "/approve"),
                post(BASE + "/admins/" + RP_USER + "/suspend"),
                post(BASE + "/admins/" + SUSPENDED + "/activate"),
                post(BASE + "/signups/" + REQ1 + "/approve").param("role", "PLATFORM_OPERATOR"),
                post(BASE + "/signups/" + REQ1 + "/reject"));
        for (MockHttpServletRequestBuilder b : all) {
            MvcResult r = send(b, rp(), true);
            assertThat(r.getResponse().getStatus()).isEqualTo(403);
        }
        verify(users, never()).list();
        verify(users, never()).suspend(any(), any());
        verify(users, never()).activate(any(), any());
        verify(signups, never()).list();
        verify(signups, never()).approve(any(), any(), any());
        verify(signups, never()).reject(any(), any());
        verify(tenants, never()).list();
    }

    @Test
    @DisplayName("리터럴 …/tab/admins 가 일반 탭 매핑보다 우선(PLATFORM 200 본문, RP 는 계정 탭으로 떨어지지 않고 403)")
    void literalWinsOverGeneric() throws Exception {
        MvcResult r = send(get("/admin/_/settings/tab/admins"), platform(), false);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(html(r).selectFirst("#au-content")).isNotNull();
        assertThat(send(get("/admin/_/settings/tab/admins"), rp(), false).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("설정 화면 탭 줄: Admin 사용자 탭에 개수 자리(#settings-tab-admins-count)와 대기 수")
    void tabStripCountSlot() throws Exception {
        MvcResult r = mvc.perform(get("/admin/settings").with(authentication(platform()))).andReturn();
        Document d = html(r);
        Element slot = d.selectFirst("#settings-tab-admins-count");
        assertThat(slot).isNotNull();
        assertThat(slot.text()).isEqualTo("2");
        // 각 *-count / *-badge 자리는 정확히 한 번(중첩·중복 id 없음)
        for (String suffix : new String[] {"-count", "-badge"}) {
            java.util.Map<String, Integer> seen = new java.util.HashMap<>();
            d.select("[id$=" + suffix + "]").forEach(e -> seen.merge(e.id(), 1, Integer::sum));
            assertThat(seen).isNotEmpty();
            assertThat(seen.values()).allMatch(n -> n == 1);
        }
        assertThat(d.select("#settings-tab-admins-count [id$=-count]")).isEmpty();
        assertThat(d.select("#settings-tab-admins-count .tabs__count")).hasSize(1);
        assertThat(d.select("[role=tablist] .tabs__count")).hasSize(1);
        assertThat(d.selectFirst("#settings-tab-admins").attr("hx-get")).isEqualTo(AdminUsersTabController.TAB_PATH);
    }

    @Test
    @DisplayName("tenants 목록을 못 읽으면 오류 없이 id 꼬리(…마지막 10자)로 표시, 전체 id 는 title")
    void tenantNamesUnavailable() throws Exception {
        when(tenants.list()).thenThrow(new IllegalStateException("boom"));
        Document d = tab();
        Element cell = row(d, "rp@x.com").selectFirst("td:eq(2)");
        assertThat(cell.selectFirst(".mono").text()).isEqualTo("…" + T1.toString().substring(T1.toString().length() - 10));
        assertThat(cell.selectFirst(".mono").attr("title")).isEqualTo(T1.toString());
        assertThat(cell.text()).contains("외 1개");
        assertThat(d.select(".empty[role=alert]")).isEmpty();
    }

    @Test
    @DisplayName("정지 뒤 목록 다시 읽기가 실패하면 화면을 바꾸지 않고(HX-Reswap none) 운영자 카드의 경고 자리만 OOB, 성공 토스트는 나감")
    void suspendThenReloadFailsKeepsDom() throws Exception {
        when(users.list()).thenReturn(List.of(
                op(ME, ME_EMAIL, "PLATFORM_OPERATOR", "ACTIVE", List.of(), true),
                op(RP_USER, "rp@x.com", "RP_ADMIN", "ACTIVE", List.of(T1), true)))
                .thenThrow(new IllegalStateException("boom-internal"));
        MvcResult r = send(post(BASE + "/admins/" + RP_USER + "/suspend"), platform(), true);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getHeader("HX-Reswap")).isEqualTo("none");
        assertThat(trigger(r)).contains("rp@x.com 운영자를 정지했습니다.");
        Document d = html(r);
        Element warn = d.selectFirst("#au-users-warn");
        assertThat(warn.attr("hx-swap-oob")).isEqualTo("true");
        assertThat(warn.text()).contains("다시 확인하지 못했습니다").contains("아래는 마지막으로 읽은 운영자 목록입니다. 목록을 새로 고치기 전에는 처리할 수 없습니다.").doesNotContain("boom-internal");
        assertThat(warn.select("button").eachText()).contains("다시 시도");
        assertThat(warn.attr("data-stale")).isEqualTo("true");
        assertThat(warn.attr("x-init")).isEqualTo("stale = true"); // 카드의 행 동작을 STALE_REASON 으로 비활성(다음 성공 렌더가 카드를 새로 그리며 해제)
        assertThat(d.select("section#au-users, tr[data-op]")).isEmpty();
    }

    @Test
    @DisplayName("거절 뒤 가입 요청을 다시 읽지 못하면 화면 유지 + 가입 요청 경고 자리 OOB(탭 개수는 건드리지 않음)")
    void rejectThenReloadFailsKeepsDom() throws Exception {
        when(signups.list()).thenReturn(List.of(new SignupRequestDto.View(REQ1, "new1@x.com", "x", OffsetDateTime.parse("2026-10-10T23:55:00Z"))))
                .thenThrow(new IllegalStateException("boom-internal"));
        MvcResult r = send(post(BASE + "/signups/" + REQ1 + "/reject"), platform(), true);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getHeader("HX-Reswap")).isEqualTo("none");
        assertThat(trigger(r)).contains("가입 요청을 거절했습니다.");
        Document d = html(r);
        assertThat(d.selectFirst("#au-signups-warn").text()).contains("아래는 마지막으로 읽은 가입 요청입니다");
        assertThat(d.selectFirst("#au-signups-warn").attr("x-init")).isEqualTo("stale = true");
        assertThat(d.select("#settings-tab-admins-count, section#au-signups")).isEmpty();
    }

    @Test
    @DisplayName("승인 뒤 운영자 목록만 못 읽으면: 가입 요청 카드는 OOB 로 새로, 운영자 카드는 경고 OOB, 대화상자 자리는 비움, 본 대상은 스왑 안 함")
    void approveThenPartialReloadFailure() throws Exception {
        when(signups.approve(eq(REQ1), any(), any())).thenReturn(op(UUID.randomUUID(), "new1@x.com", "PLATFORM_OPERATOR", "ACTIVE", List.of(), false));
        when(users.list()).thenThrow(new IllegalStateException("boom-internal"));
        MvcResult r = send(post(BASE + "/signups/" + REQ1 + "/approve").param("role", "PLATFORM_OPERATOR"), platform(), false);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getHeader("HX-Reswap")).isEqualTo("none");
        Document d = html(r);
        assertThat(d.selectFirst("section#au-signups").attr("hx-swap-oob")).isEqualTo("true");
        assertThat(d.selectFirst("#au-users-warn").attr("hx-swap-oob")).isEqualTo("true");
        assertThat(d.selectFirst("#au-users-warn").attr("x-init")).isEqualTo("stale = true");
        assertThat(d.select("#au-signups-warn [x-init], section#au-signups #au-signups-warn[x-init]")).isEmpty();
        assertThat(d.selectFirst("#au-dialog-slot").attr("hx-swap-oob")).isEqualTo("true");
        assertThat(d.select("#au-content")).isEmpty();
    }

    @Test
    @DisplayName("정지 되돌리기(?undo=1): 정지 상태일 때만 activate, 원본 문구('정지를 되돌렸습니다'), 상태가 바뀌었으면 REST 미호출 + 경고, 실패는 원본 안내")
    void undoSuspend() throws Exception {
        MvcResult ok = send(post(BASE + "/admins/" + SUSPENDED + "/activate").param("undo", "1"), platform(), false);
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        verify(users).activate(eq(SUSPENDED), any(Authentication.class));
        assertThat(trigger(ok)).contains("gone@x.com 운영자의 정지를 되돌렸습니다.");
        assertThat(html(ok).selectFirst("section#au-users")).isNotNull();

        MvcResult stale = send(post(BASE + "/admins/" + RP_USER + "/activate").param("undo", "1"), platform(), false);
        assertThat(stale.getResponse().getStatus()).isEqualTo(200);
        assertThat(stale.getResponse().getHeader("HX-Reswap")).isEqualTo("none");
        assertThat(trigger(stale)).contains("되돌릴 수 없습니다").contains("운영자 상태가 바뀌었습니다. 목록을 새로 고친 뒤 다시 시도하세요").contains("warn");
        verify(users, never()).activate(eq(RP_USER), any());

        doThrow(new IllegalStateException("db-secret")).when(users).activate(eq(SUSPENDED), any());
        MvcResult fail = send(post(BASE + "/admins/" + SUSPENDED + "/activate").param("undo", "1"), platform(), false);
        // 원본은 실패 뒤에도 목록을 다시 읽는다: 오류 토스트 + 갱신된 운영자 카드(200)
        assertThat(fail.getResponse().getStatus()).isEqualTo(200);
        assertThat(trigger(fail)).contains("정지를 되돌리지 못했습니다").contains("목록에서 “활성화”를 눌러 다시 시도하세요.").contains("error").doesNotContain("db-secret");
        assertThat(html(fail).selectFirst("section#au-users")).isNotNull();
    }

    @Test
    @DisplayName("되돌리기 사전 확인(목록 읽기) 실패도 같은 실패 처리(오류 토스트), REST activate 미호출. 이미 아는 이메일을 쓴다(재조회 실패여도 빈 이메일·앞 공백 없음)")
    void undoPrecheckFailureAndKnownEmail() throws Exception {
        when(users.list()).thenThrow(new IllegalStateException("boom-internal"));
        MvcResult pre = send(post(BASE + "/admins/" + SUSPENDED + "/activate").param("undo", "1"), platform(), false);
        assertThat(trigger(pre)).contains("정지를 되돌리지 못했습니다").contains("error").doesNotContain("boom-internal");
        verify(users, never()).activate(any(), any());

        // 사전 확인은 성공, 재조회는 실패: 토스트에 이메일이 있고(앞 공백 없음) 화면은 그대로 + 경고
        org.mockito.Mockito.doReturn(List.of(op(SUSPENDED, "gone@x.com", "RP_ADMIN", "SUSPENDED", List.of(T1), false)))
                .doThrow(new IllegalStateException("boom-internal")).when(users).list();
        MvcResult r = send(post(BASE + "/admins/" + SUSPENDED + "/activate").param("undo", "1"), platform(), false);
        assertThat(trigger(r)).contains("title=gone@x.com 운영자의 정지를 되돌렸습니다.");
        assertThat(r.getResponse().getHeader("HX-Reswap")).isEqualTo("none");
        assertThat(html(r).selectFirst("#au-users-warn").attr("x-init")).isEqualTo("stale = true");
    }

    @Test
    @DisplayName("일반 활성화 뒤 재조회 실패: 이메일을 모르면 메시지는 비우고(앞 공백·null 없음) 화면 유지 + 경고")
    void activateReloadFailureNoBlankEmail() throws Exception {
        when(users.list()).thenThrow(new IllegalStateException("boom-internal"));
        MvcResult r = send(post(BASE + "/admins/" + SUSPENDED + "/activate"), platform(), false);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getHeader("HX-Reswap")).isEqualTo("none");
        assertThat(trigger(r)).contains("운영자를 다시 활성화했습니다.").doesNotContain("null").doesNotContain("message= ");
        verify(users).activate(eq(SUSPENDED), any());
    }

    @Test
    @DisplayName("일반 활성화 토스트의 이메일은 행 버튼이 함께 보낸 그 행의 이메일(원본 u.email) — 재조회가 실패해도 남는다")
    void activateToastUsesRowEmailWhenReloadFails() throws Exception {
        Element btn = row(html(send(get(BASE + "/tab/admins"), platform(), false)), "gone@x.com").selectFirst("button");
        assertThat(btn.attr("name")).isEqualTo("email");
        assertThat(btn.attr("value")).isEqualTo("gone@x.com");
        when(users.list()).thenThrow(new IllegalStateException("boom-internal"));
        MvcResult r = send(post(BASE + "/admins/" + SUSPENDED + "/activate").param("email", "gone@x.com"), platform(), false);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(trigger(r)).contains("운영자를 다시 활성화했습니다.").contains("gone@x.com");
        verify(users).activate(eq(SUSPENDED), any());
    }

    @Test
    @DisplayName("클라이언트 검증은 htmx 요청 전에 취소한다(x-on:submit 이 아니라 htmx:before-request)")
    void approveFormCancelsInBeforeRequest() throws Exception {
        Element form = html(send(get(BASE + "/signups/" + REQ1 + "/approve"), platform(), false)).selectFirst("form#au-approve-form");
        assertThat(form.hasAttr("x-on:submit")).isFalse();
        assertThat(form.attr("x-on:htmx:before-request")).isEqualTo("onBefore($event)");
    }

    @Test
    @DisplayName("suspendBlock / tenantCell 단위: 대소문자 무시 본인 판정, id 꼬리 10자")
    void units() {
        List<AdminUserDto.View> all = List.of(op(ME, "ME@X.com", "PLATFORM_OPERATOR", "ACTIVE", List.of(), true), op(OTHER_PLAT, "p2@x.com", "PLATFORM_OPERATOR", "ACTIVE", List.of(), true));
        assertThat(AdminUsersTabController.suspendBlock(all.get(0), all, "me@x.com")).isEqualTo("본인 계정은 정지할 수 없음");
        assertThat(AdminUsersTabController.suspendBlock(all.get(1), all, "me@x.com")).isNull();
        assertThat(AdminUsersTabController.tail("abcdefghijklmnop", 10)).isEqualTo("ghijklmnop");
        assertThat(AdminUsersTabController.initial("zed@x.com")).isEqualTo("Z");
    }
}
