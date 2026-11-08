package com.crosscert.passkey.admin.contract;

import com.crosscert.passkey.admin.audit.AuditChainOverview;
import com.crosscert.passkey.admin.audit.AuditChainTenantOverview;
import com.crosscert.passkey.admin.auth.ActiveTenantController;
import com.crosscert.passkey.admin.auth.MfaController;
import com.crosscert.passkey.admin.auth.MfaPendingFilter;
import com.crosscert.passkey.admin.license.LicenseView;
import com.crosscert.passkey.admin.operator.PasswordResetController;
import com.crosscert.passkey.admin.operator.SignupRequestDto;
import com.crosscert.passkey.admin.tenant.TenantAdminDto;
import com.crosscert.passkey.core.api.ApiResponse;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 렌더 HTML 스냅샷 — 셸(Shell·CommandPalette)·인증 화면(Login·Password·Signup)·HomeRedirect·오류 화면(ScreenErrorViewResolver)
 * (Kotlin 전환 안전망, 동결). 커버리지 표는 {@link RenderedHtmlContractCoverage}.
 */
@HtmlContractSupport.HtmlContractSlice
class RenderedHtmlShellAuthContractTest extends HtmlContractSupport {

    @BeforeEach
    void shellStubs() {
        when(tenants.list()).thenReturn(ApiResponse.ok(List.of(
                Stub.of(TenantAdminDto.TenantView.class, "id", TID, "slug", "alpha", "displayName", "Alpha Corp", "status", "ACTIVE"),
                Stub.of(TenantAdminDto.TenantView.class, "id", TID2, "slug", "beta", "displayName", "베타", "status", "SUSPENDED"))));
        when(activeTenants.current()).thenReturn(new ActiveTenantController.ActiveTenantView(TID, List.of(TID, TID2),
                List.of(new ActiveTenantController.TenantRef(TID, "Alpha Corp"), new ActiveTenantController.TenantRef(TID2, "베타"))));
        when(chain.overview(anyInt())).thenReturn(new AuditChainOverview(NOW.minusSeconds(600), 24, 60,
                new AuditChainOverview.Totals(1, 2, 1, 10L, 5L),
                List.of(new AuditChainTenantOverview(TID, "Alpha Corp", true, 7L, List.of(1L), null),
                        new AuditChainTenantOverview(TID2, "베타", false, 3L, List.of(0L), null))));
        when(license.get()).thenReturn(ApiResponse.ok(new LicenseView("onprem", "NETWORK_GRACE", "acme", "lic_1",
                NOW.plusSeconds(86400L * 5), 5L, List.of(), NOW.minusSeconds(7200), 40L, NOW.plusSeconds(600))));
    }

    static MockHttpSession pendingSession() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute(MfaPendingFilter.MFA_PENDING_ATTR, Boolean.TRUE);
        return s;
    }

    static MockHttpServletRequestBuilder anon(MockHttpServletRequestBuilder b) {
        return b.with(csrf());
    }

    // ── ShellController ────────────────────────────────────

    @Test
    void shellFragments() throws Exception {
        snap("shell/license-banner.frag.grace.platform.html", frag("/admin/_/shell/license-banner", operator()));
        snap("shell/license-banner.frag.grace.rp-admin.html", frag("/admin/_/shell/license-banner", rpAdmin()));
        snap("shell/chain-card.frag.html", frag("/admin/_/shell/chain-card", operator()));
        snap("shell/tenant-switcher.frag.html", frag("/admin/_/shell/tenant-switcher", rpAdmin()));
        snap("shell/active-tenant.switch.htmx.html", write(post("/admin/_/active-tenant").param("tenantId", TID2.toString()), rpAdmin()));
        snap("shell/active-tenant.switch.redirect.html", post("/admin/_/active-tenant").param("tenantId", TID2.toString())
                .header("Referer", "http://localhost/admin/tenants/" + TID + "?tab=audit").with(authentication(rpAdmin())).with(csrf()));
        when(license.get()).thenReturn(ApiResponse.ok(new LicenseView("onprem", "VALID", "acme", "lic_1",
                NOW.plusSeconds(86400L * 300), 300L, List.of(), NOW, null, NOW.plusSeconds(600))));
        snap("shell/license-banner.frag.valid.html", frag("/admin/_/shell/license-banner", operator()));
    }

    // ── CommandPaletteController ───────────────────────────

    @Test
    void palette() throws Exception {
        snap("shell/palette.frag.platform.html", frag("/admin/_/palette", operator()));
        snap("shell/palette.frag.platform.q.html", frag("/admin/_/palette?q=al", operator()));
        snap("shell/palette.frag.rp-admin.html", frag("/admin/_/palette", rpAdmin()));
        snap("shell/palette.frag.no-match.html", frag("/admin/_/palette?q=zzzz", rpAdmin()));
    }

    // ── LoginPageController ────────────────────────────────

    @Test
    void loginPages() throws Exception {
        snap("auth/login.page.html", get("/admin/login"));
        snap("auth/login.page.error.html", get("/admin/login?error"));
        snap("auth/login.page.authenticated.html", page("/admin/login", operator()));
        snap("auth/mfa.page.html", page("/admin/login", operator()).session(pendingSession()));
    }

    @Test
    void loginMfaVerify() throws Exception {
        doReturn(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid_code")))
                .when(mfa).verify(any(MfaController.VerifyRequest.class), any(), any());
        snap("auth/mfa.verify.form-error.html", write(post("/admin/login/mfa").param("code", "999999"), operator()).session(pendingSession()));
        snap("auth/mfa.verify.form-error.recovery.html", write(post("/admin/login/mfa").param("code", "x").param("mode", "recovery"), operator())
                .session(pendingSession()));
        Map<String, Object> ok = new java.util.LinkedHashMap<>();
        ok.put("usedRecoveryCode", true);
        ok.put("remaining", 7);
        doReturn(ResponseEntity.ok(ok)).when(mfa).verify(any(MfaController.VerifyRequest.class), any(), any());
        snap("auth/mfa.verify.ok-recovery.html", write(post("/admin/login/mfa").param("code", "aaaa-bbbb"), operator()).session(pendingSession()));
        snap("auth/mfa.verify.not-pending.html", write(post("/admin/login/mfa").param("code", "123456"), operator()));
    }

    // ── PasswordPagesController ────────────────────────────

    @Test
    void passwordPages() throws Exception {
        snap("auth/forgot.page.html", get("/admin/forgot-password"));
        snap("auth/forgot.form-error.html", anon(post("/admin/forgot-password").param("email", " ").header("HX-Request", "true")));
        doReturn(ResponseEntity.accepted().build()).when(passwordReset).request(any(PasswordResetController.RequestBodyDto.class));
        snap("auth/forgot.sent.html", anon(post("/admin/forgot-password").param("email", "op@example.com").header("HX-Request", "true")));
        snap("auth/reset-link.redirect.html", get("/reset-password?token=abc"));
        snap("auth/reset.page.form.html", get("/admin/reset-password?token=tok123"));
        snap("auth/reset.page.invalid.html", get("/admin/reset-password"));
        snap("auth/reset.form-error.short.html", anon(post("/admin/reset-password").param("token", "tok123")
                .param("password", "short").param("password2", "short").header("HX-Request", "true")));
        snap("auth/reset.form-error.mismatch.html", anon(post("/admin/reset-password").param("token", "tok123")
                .param("password", "correct-horse-battery").param("password2", "correct-horse-batterz")));
        doReturn(ResponseEntity.ok().build()).when(passwordReset).confirm(any(PasswordResetController.ConfirmBody.class));
        snap("auth/reset.ok.html", anon(post("/admin/reset-password").param("token", "tok123")
                .param("password", "correct-horse-battery").param("password2", "correct-horse-battery").header("HX-Request", "true")));
        when(passwordReset.confirm(any(PasswordResetController.ConfirmBody.class))).thenThrow(new IllegalArgumentException("invalid token"));
        snap("auth/reset.expired.html", anon(post("/admin/reset-password").param("token", "tok123")
                .param("password", "correct-horse-battery").param("password2", "correct-horse-battery").header("HX-Request", "true")));
    }

    // ── SignupPageController ───────────────────────────────

    @Test
    void signupPages() throws Exception {
        snap("auth/signup.page.html", get("/admin/signup"));
        snap("auth/signup.form-error.html", anon(post("/admin/signup").param("email", "not-an-email")
                .param("password", "short").param("password2", "other").header("HX-Request", "true")));
        doReturn(ResponseEntity.accepted().body(Map.of("activated", false))).when(signups).request(any(SignupRequestDto.Create.class));
        snap("auth/signup.pending.html", anon(post("/admin/signup").param("email", "new@example.com")
                .param("password", "correct-horse-battery").param("password2", "correct-horse-battery")
                .param("reason", "RP 운영").header("HX-Request", "true")));
    }

    // ── HomeRedirectController ─────────────────────────────

    @Test
    void homeRedirects() throws Exception {
        snap("shell/home.platform.html", page("/admin", operator()));
        snap("shell/home.rp-admin.html", page("/admin/", rpAdmin()));
        snap("shell/home.legacy-path.rp-admin.html", page("/admin/api-keys", rpAdmin()));
        snap("shell/home.anonymous.html", get("/admin"));
    }

    // ── ScreenErrorViewResolver ────────────────────────────

    @Test
    void errorScreens() throws Exception {
        snap("error/not-found.page.platform.html", page("/admin/nonexistent", operator()));
        snap("error/not-found.page.rp-admin.html", page("/admin/nonexistent", rpAdmin()));
        snap("error/not-found.frag.html", frag("/admin/nonexistent", rpAdmin()));
        snap("error/dispatch-500.page.html", get("/error").accept("text/html").with(req -> {
            req.setDispatcherType(DispatcherType.ERROR);
            req.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 500);
            req.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/admin/tenants");
            return req;
        }).with(authentication(operator())));
    }
}
