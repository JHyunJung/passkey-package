package com.crosscert.passkey.admin.contract;

import com.crosscert.passkey.admin.auth.MfaController;
import com.crosscert.passkey.admin.mds.MdsHistoryView;
import com.crosscert.passkey.admin.mds.MdsSchedulerService;
import com.crosscert.passkey.admin.mds.MdsStatusView;
import com.crosscert.passkey.admin.operator.AdminUserDto;
import com.crosscert.passkey.admin.operator.SignupRequestDto;
import com.crosscert.passkey.admin.policy.SecurityPolicyDto;
import com.crosscert.passkey.admin.system.SystemInfoView;
import com.crosscert.passkey.admin.tenant.TenantAdminDto;
import com.crosscert.passkey.core.api.ApiResponse;
import com.crosscert.passkey.core.entity.AdminUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 렌더 HTML 스냅샷 — 설정 화면과 탭 컨트롤러 6개 (Kotlin 전환 안전망, 동결). 커버리지 표는 {@link RenderedHtmlContractCoverage}.
 */
@HtmlContractSupport.HtmlContractSlice
class RenderedHtmlSettingsContractTest extends HtmlContractSupport {

    static final UUID SIGNUP_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    static final UUID USER_RP = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
    static final UUID USER_SUSP = UUID.fromString("00000000-0000-0000-0000-0000000000d3");

    @BeforeEach
    void settingsStubs() {
        when(signups.list()).thenReturn(List.of(
                new SignupRequestDto.View(SIGNUP_ID, "new@example.com", "RP 운영 담당입니다", T0)));
        when(adminUsers.list()).thenReturn(List.of(
                Stub.of(AdminUserDto.View.class, "id", OP_ID, "email", OP_EMAIL, "role", "PLATFORM_OPERATOR", "status", "ACTIVE",
                        "tenantIds", List.of(), "createdAt", T0.minusDays(90), "lastLoginAt", T0, "suspendedAt", null,
                        "createdBy", "system", "mfaEnabled", true),
                Stub.of(AdminUserDto.View.class, "id", USER_RP, "email", RP_EMAIL, "role", "RP_ADMIN", "status", "ACTIVE",
                        "tenantIds", List.of(TID, TID2), "createdAt", T0.minusDays(10), "lastLoginAt", null, "suspendedAt", null,
                        "createdBy", OP_EMAIL, "mfaEnabled", false),
                Stub.of(AdminUserDto.View.class, "id", USER_SUSP, "email", "gone@example.com", "role", "RP_ADMIN", "status", "SUSPENDED",
                        "tenantIds", List.of(TID), "createdAt", T0.minusDays(40), "lastLoginAt", T0.minusDays(20),
                        "suspendedAt", T0.minusDays(5), "createdBy", OP_EMAIL, "mfaEnabled", false)));
        when(tenants.list()).thenReturn(ApiResponse.ok(List.of(
                Stub.of(TenantAdminDto.TenantView.class, "id", TID, "slug", "alpha", "displayName", "Alpha Corp", "status", "ACTIVE"),
                Stub.of(TenantAdminDto.TenantView.class, "id", TID2, "slug", "beta", "displayName", "베타", "status", "SUSPENDED"))));
        when(securityPolicy.get()).thenReturn(new SecurityPolicyDto.View(30, false,
                List.of("https://console.example.com", "http://localhost"), T0, OP_EMAIL));
        when(mds.status()).thenReturn(ApiResponse.ok(new MdsStatusView(42L, "2026-11-01", "2026-10-11T03:00:00+09:00", 3,
                "PINNED", new MdsStatusView.SuccessRate(28, 30))));
        when(mds.history(anyInt())).thenReturn(ApiResponse.ok(List.of(
                new MdsHistoryView(3L, "2026-10-13T03:00:00+09:00", "2026-10-13T03:00:02+09:00", 42L, "SYNCED", "+2 / -0 entries", 1834, null),
                new MdsHistoryView(2L, "2026-10-12T03:00:00+09:00", "2026-10-12T03:00:05+09:00", null, "FAILED", null, 5012,
                        "BLOB signature\n  verification failed"),
                new MdsHistoryView(1L, "2026-10-11T03:00:00+09:00", null, 41L, "SKIPPED", null, null, null))));
        when(systemInfo.get()).thenReturn(ApiResponse.ok(new SystemInfoView("1.4.2", "2026-10-01T09:00:00+09:00", 12L,
                new SystemInfoView.Host("prod"),
                List.of(new SystemInfoView.Component("Oracle", "19c", "UP", null),
                        new SystemInfoView.Component("Redis", "7.2", "DOWN", "연결 거부")))));
    }

    private void mfaOn() {
        AdminUser u = new AdminUser(OP_EMAIL, "x", "PLATFORM_OPERATOR");
        u.setMfaEnabled(true);
        when(admins.findByEmail(anyString())).thenReturn(Optional.of(u));
    }

    // ── SettingsPageController ─────────────────────────────

    @Test
    void settingsPage() throws Exception {
        snap("settings/page.account.platform.html", page("/admin/settings", operator()));
        snap("settings/page.admins.platform.html", page("/admin/settings?tab=admins", operator()));
        snap("settings/page.account.rp-admin.html", page("/admin/settings?tab=admins", rpAdmin()));
        snap("settings/tab.unknown.frag.html", frag("/admin/_/settings/tab/nope", operator()));
        // 각 탭을 주소로 연 전체 페이지(선택된 탭 표시 + 지연 로드 속성 — 탭 본문은 페이지가 조각으로 불러온다)
        for (String tab : List.of("mds", "security", "system")) {
            snap("settings/page." + tab + ".platform.html", page("/admin/settings?tab=" + tab, operator()));
        }
    }

    // ── AccountTabController ───────────────────────────────

    @Test
    void accountTab() throws Exception {
        snap("settings/account.tab.frag.mfa-off.html", frag("/admin/_/settings/tab/account", operator()));
        mfaOn();
        snap("settings/account.tab.frag.mfa-on.rp-admin.html", frag("/admin/_/settings/tab/account", rpAdmin()));
    }

    @Test
    void accountMfaFlows() throws Exception {
        Map<String, Object> enroll = new LinkedHashMap<>();
        enroll.put("secret", "JBSWY3DPEHPK3PXP");
        enroll.put("otpauthUri", "otpauth://totp/Passkey%20Admin:op%40example.com?secret=JBSWY3DPEHPK3PXP&issuer=Passkey%20Admin");
        doReturn(ResponseEntity.ok(enroll)).when(mfa).enroll(any(), any());
        snap("settings/account.enroll.html", write(post("/admin/_/settings/mfa/enroll"), operator()));

        doReturn(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid_code")))
                .when(mfa).confirm(any(MfaController.VerifyRequest.class), any(), any());
        snap("settings/account.confirm.form-error.html", write(post("/admin/_/settings/mfa/confirm").param("code", "000000"), operator()));
        Map<String, Object> confirmed = new LinkedHashMap<>();
        confirmed.put("confirmed", true);
        confirmed.put("recoveryCodes", List.of("aaaa-bbbb-cccc", "dddd-eeee-ffff"));
        doReturn(ResponseEntity.ok(confirmed)).when(mfa).confirm(any(MfaController.VerifyRequest.class), any(), any());
        snap("settings/account.confirm.ok.html", write(post("/admin/_/settings/mfa/confirm").param("code", "123456"), operator()));

        mfaOn();
        doReturn(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid_code")))
                .when(mfa).disable(any(MfaController.VerifyRequest.class), any(), any());
        snap("settings/account.disable.form-error.html", write(post("/admin/_/settings/mfa/disable").param("code", "1"), operator()));
        doReturn(ResponseEntity.ok(Map.of("disabled", true))).when(mfa).disable(any(MfaController.VerifyRequest.class), any(), any());
        snap("settings/account.disable.ok.html", write(post("/admin/_/settings/mfa/disable").param("code", "123456"), operator()));
    }

    // ── AdminUsersTabController ────────────────────────────

    @Test
    void adminUsersTab() throws Exception {
        snap("settings/admins.tab.frag.html", frag("/admin/_/settings/tab/admins", operator()));
        snap("settings/admins.tab.frag.rp-admin-denied.html", frag("/admin/_/settings/tab/admins", rpAdmin()));
        snap("settings/admins.approve-dialog.frag.html", frag("/admin/_/settings/signups/" + SIGNUP_ID + "/approve", operator()));
    }

    @Test
    void adminUsersWrites() throws Exception {
        snap("settings/admins.approve.form-error.role.html", write(post("/admin/_/settings/signups/" + SIGNUP_ID + "/approve"), operator()));
        snap("settings/admins.approve.form-error.tenant.html", write(post("/admin/_/settings/signups/" + SIGNUP_ID + "/approve")
                .param("role", "RP_ADMIN"), operator()));
        when(signups.approve(any(), any(), any())).thenReturn(Stub.of(AdminUserDto.View.class, "id", SIGNUP_ID,
                "email", "new@example.com", "role", "RP_ADMIN", "status", "ACTIVE", "tenantIds", List.of(TID), "mfaEnabled", false));
        snap("settings/admins.approve.ok.html", write(post("/admin/_/settings/signups/" + SIGNUP_ID + "/approve")
                .param("role", "RP_ADMIN").param("tenantId", TID.toString()), operator()));
        doReturn(ResponseEntity.noContent().build()).when(signups).reject(any(), any());
        snap("settings/admins.reject.html", write(post("/admin/_/settings/signups/" + SIGNUP_ID + "/reject").header("X-Confirm", "1"), operator()));
        snap("settings/admins.suspend.html", write(post("/admin/_/settings/admins/" + USER_RP + "/suspend").header("X-Confirm", "1"), operator()));
        snap("settings/admins.suspend.stale.html", write(post("/admin/_/settings/admins/" + USER_SUSP + "/suspend").header("X-Confirm", "1"), operator()));
        snap("settings/admins.activate.undo.html", write(post("/admin/_/settings/admins/" + USER_SUSP + "/activate")
                .param("undo", "1").param("email", "gone@example.com"), operator()));
    }

    // ── MdsTabController ───────────────────────────────────

    @Test
    void mdsTab() throws Exception {
        snap("settings/mds.tab.frag.html", frag("/admin/_/settings/tab/mds", operator()));
        snap("settings/mds.tab.frag.rp-admin-denied.html", frag("/admin/_/settings/tab/mds", rpAdmin()));
        when(mds.sync()).thenReturn(ApiResponse.ok(new MdsSchedulerService.SyncResult("SYNCED", 43L, null)));
        snap("settings/mds.sync.ok.html", write(post("/admin/_/settings/mds/sync"), operator()));
        when(mds.sync()).thenReturn(ApiResponse.ok(new MdsSchedulerService.SyncResult("FAILED", null, "timeout")));
        snap("settings/mds.sync.failed.html", write(post("/admin/_/settings/mds/sync"), operator()));
        when(mds.history(anyInt())).thenReturn(ApiResponse.ok(List.of()));
        when(mds.status()).thenReturn(ApiResponse.ok(new MdsStatusView(0L, null, null, 0, "PINNED", null)));
        snap("settings/mds.tab.frag.never-synced.html", frag("/admin/_/settings/tab/mds", operator()));
    }

    // ── SecurityPolicyTabController ────────────────────────

    @Test
    void securityTab() throws Exception {
        snap("settings/security.tab.frag.html", frag("/admin/_/settings/tab/security", operator()));
        snap("settings/security.tab.frag.rp-admin-denied.html", frag("/admin/_/settings/tab/security", rpAdmin()));
        when(securityPolicy.get()).thenReturn(new SecurityPolicyDto.View(15, true, List.of(), null, null));
        snap("settings/security.tab.frag.mfa-required.html", frag("/admin/_/settings/tab/security?revert=1", operator()));
        snap("settings/security.origin-check.bad.html", frag("/admin/_/settings/security/origin-check?value=not-a-url", operator()));
    }

    @Test
    void securityWrites() throws Exception {
        snap("settings/security.save.form-error.html", write(put("/admin/_/settings/security")
                .param("sessionIdleTimeoutMinutes", "abc").param("mfaRequired", "maybe").param("corsAllowlist", "ftp://x"), operator()));
        snap("settings/security.save.lockout-dialog.html", write(put("/admin/_/settings/security")
                .param("sessionIdleTimeoutMinutes", "30").param("mfaRequired", "true")
                .param("corsAllowlist", "https://other.example.com"), operator()));
        when(securityPolicy.update(any(), any())).thenReturn(new SecurityPolicyDto.View(45, true,
                List.of("https://console.example.com", "http://localhost"), T0, OP_EMAIL));
        snap("settings/security.save.ok.html", write(put("/admin/_/settings/security")
                .param("sessionIdleTimeoutMinutes", "45").param("mfaRequired", "true")
                .param("corsAllowlist", "https://console.example.com", "http://localhost"), operator()));
    }

    // ── SystemInfoTabController ────────────────────────────

    @Test
    void systemTab() throws Exception {
        snap("settings/system.tab.frag.html", frag("/admin/_/settings/tab/system", operator()));
        snap("settings/system.tab.frag.rp-admin-denied.html", frag("/admin/_/settings/tab/system", rpAdmin()));
    }
}
