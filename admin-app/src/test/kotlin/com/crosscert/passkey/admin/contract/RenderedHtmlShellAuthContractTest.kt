package com.crosscert.passkey.admin.contract

import com.crosscert.passkey.admin.audit.AuditChainOverview
import com.crosscert.passkey.admin.audit.AuditChainTenantOverview
import com.crosscert.passkey.admin.auth.ActiveTenantController
import com.crosscert.passkey.admin.auth.MfaController
import com.crosscert.passkey.admin.auth.MfaPendingFilter
import com.crosscert.passkey.admin.license.LicenseView
import com.crosscert.passkey.admin.operator.PasswordResetController
import com.crosscert.passkey.admin.operator.SignupRequestDto
import com.crosscert.passkey.admin.tenant.TenantAdminDto
import com.crosscert.passkey.core.api.ApiResponse
import jakarta.servlet.DispatcherType
import jakarta.servlet.RequestDispatcher
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

/**
 * 렌더 HTML 스냅샷 — 셸(Shell·CommandPalette)·인증 화면(Login·Password·Signup)·HomeRedirect·오류 화면(ScreenErrorViewResolver)
 * (Kotlin 전환 안전망, 동결). 커버리지 표는 [RenderedHtmlContractCoverage].
 */
@HtmlContractSupport.HtmlContractSlice
class RenderedHtmlShellAuthContractTest : HtmlContractSupport() {

    companion object {
        fun pendingSession(): MockHttpSession {
            val s = MockHttpSession()
            s.setAttribute(MfaPendingFilter.MFA_PENDING_ATTR, java.lang.Boolean.TRUE)
            return s
        }

        fun anon(b: MockHttpServletRequestBuilder): MockHttpServletRequestBuilder = b.with(csrf())
    }

    @BeforeEach
    fun shellStubs() {
        whenever(tenants.list()).thenReturn(
            ApiResponse.ok(
                listOf(
                    Stub.of(
                        TenantAdminDto.TenantView::class.java,
                        "id", TID, "slug", "alpha", "displayName", "Alpha Corp", "status", "ACTIVE",
                    ),
                    Stub.of(
                        TenantAdminDto.TenantView::class.java,
                        "id", TID2, "slug", "beta", "displayName", "베타", "status", "SUSPENDED",
                    ),
                ),
            ),
        )
        whenever(activeTenants.current()).thenReturn(
            ActiveTenantController.ActiveTenantView(
                TID, listOf(TID, TID2),
                listOf(ActiveTenantController.TenantRef(TID, "Alpha Corp"), ActiveTenantController.TenantRef(TID2, "베타")),
            ),
        )
        whenever(chain.overview(anyInt())).thenReturn(
            AuditChainOverview(
                NOW.minusSeconds(600), 24, 60,
                AuditChainOverview.Totals(1, 2, 1, 10L, 5L),
                listOf(
                    AuditChainTenantOverview(TID, "Alpha Corp", true, 7L, listOf(1L), null),
                    AuditChainTenantOverview(TID2, "베타", false, 3L, listOf(0L), null),
                ),
            ),
        )
        whenever(license.get()).thenReturn(
            ApiResponse.ok(
                LicenseView(
                    "onprem", "NETWORK_GRACE", "acme", "lic_1",
                    NOW.plusSeconds(86400L * 5), 5L, listOf(), NOW.minusSeconds(7200), 40L, NOW.plusSeconds(600),
                ),
            ),
        )
    }

    // ── ShellController ────────────────────────────────────

    @Test
    fun shellFragments() {
        snap("shell/license-banner.frag.grace.platform.html", frag("/admin/_/shell/license-banner", operator()))
        snap("shell/license-banner.frag.grace.rp-admin.html", frag("/admin/_/shell/license-banner", rpAdmin()))
        snap("shell/chain-card.frag.html", frag("/admin/_/shell/chain-card", operator()))
        snap("shell/tenant-switcher.frag.html", frag("/admin/_/shell/tenant-switcher", rpAdmin()))
        snap(
            "shell/active-tenant.switch.htmx.html",
            write(post("/admin/_/active-tenant").param("tenantId", TID2.toString()), rpAdmin()),
        )
        snap(
            "shell/active-tenant.switch.redirect.html",
            post("/admin/_/active-tenant").param("tenantId", TID2.toString())
                .header("Referer", "http://localhost/admin/tenants/$TID?tab=audit")
                .with(authentication(rpAdmin())).with(csrf()),
        )
        whenever(license.get()).thenReturn(
            ApiResponse.ok(
                LicenseView(
                    "onprem", "VALID", "acme", "lic_1",
                    NOW.plusSeconds(86400L * 300), 300L, listOf(), NOW, null, NOW.plusSeconds(600),
                ),
            ),
        )
        snap("shell/license-banner.frag.valid.html", frag("/admin/_/shell/license-banner", operator()))
    }

    // ── CommandPaletteController ───────────────────────────

    @Test
    fun palette() {
        snap("shell/palette.frag.platform.html", frag("/admin/_/palette", operator()))
        snap("shell/palette.frag.platform.q.html", frag("/admin/_/palette?q=al", operator()))
        snap("shell/palette.frag.rp-admin.html", frag("/admin/_/palette", rpAdmin()))
        snap("shell/palette.frag.no-match.html", frag("/admin/_/palette?q=zzzz", rpAdmin()))
    }

    // ── LoginPageController ────────────────────────────────

    @Test
    fun loginPages() {
        snap("auth/login.page.html", get("/admin/login"))
        snap("auth/login.page.error.html", get("/admin/login?error"))
        snap("auth/login.page.authenticated.html", page("/admin/login", operator()))
        snap("auth/mfa.page.html", page("/admin/login", operator()).session(pendingSession()))
    }

    @Test
    fun loginMfaVerify() {
        doReturn(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "invalid_code")))
            .whenever(mfa).verify(any<MfaController.VerifyRequest>(), anyOrNull(), anyOrNull())
        snap(
            "auth/mfa.verify.form-error.html",
            write(post("/admin/login/mfa").param("code", "999999"), operator()).session(pendingSession()),
        )
        snap(
            "auth/mfa.verify.form-error.recovery.html",
            write(post("/admin/login/mfa").param("code", "x").param("mode", "recovery"), operator())
                .session(pendingSession()),
        )
        val ok = LinkedHashMap<String, Any>()
        ok["usedRecoveryCode"] = true
        ok["remaining"] = 7
        doReturn(ResponseEntity.ok(ok)).whenever(mfa).verify(any<MfaController.VerifyRequest>(), anyOrNull(), anyOrNull())
        snap(
            "auth/mfa.verify.ok-recovery.html",
            write(post("/admin/login/mfa").param("code", "aaaa-bbbb"), operator()).session(pendingSession()),
        )
        snap("auth/mfa.verify.not-pending.html", write(post("/admin/login/mfa").param("code", "123456"), operator()))
    }

    // ── PasswordPagesController ────────────────────────────

    @Test
    fun passwordPages() {
        snap("auth/forgot.page.html", get("/admin/forgot-password"))
        snap(
            "auth/forgot.form-error.html",
            anon(post("/admin/forgot-password").param("email", " ").header("HX-Request", "true")),
        )
        doReturn(ResponseEntity.accepted().build<Any>())
            .whenever(passwordReset).request(any<PasswordResetController.RequestBodyDto>())
        snap(
            "auth/forgot.sent.html",
            anon(post("/admin/forgot-password").param("email", "op@example.com").header("HX-Request", "true")),
        )
        snap("auth/reset-link.redirect.html", get("/reset-password?token=abc"))
        snap("auth/reset.page.form.html", get("/admin/reset-password?token=tok123"))
        snap("auth/reset.page.invalid.html", get("/admin/reset-password"))
        snap(
            "auth/reset.form-error.short.html",
            anon(
                post("/admin/reset-password").param("token", "tok123")
                    .param("password", "short").param("password2", "short").header("HX-Request", "true"),
            ),
        )
        snap(
            "auth/reset.form-error.mismatch.html",
            anon(
                post("/admin/reset-password").param("token", "tok123")
                    .param("password", "correct-horse-battery").param("password2", "correct-horse-batterz"),
            ),
        )
        doReturn(ResponseEntity.ok().build<Any>())
            .whenever(passwordReset).confirm(any<PasswordResetController.ConfirmBody>())
        snap(
            "auth/reset.ok.html",
            anon(
                post("/admin/reset-password").param("token", "tok123")
                    .param("password", "correct-horse-battery").param("password2", "correct-horse-battery")
                    .header("HX-Request", "true"),
            ),
        )
        whenever(passwordReset.confirm(any<PasswordResetController.ConfirmBody>()))
            .thenThrow(IllegalArgumentException("invalid token"))
        snap(
            "auth/reset.expired.html",
            anon(
                post("/admin/reset-password").param("token", "tok123")
                    .param("password", "correct-horse-battery").param("password2", "correct-horse-battery")
                    .header("HX-Request", "true"),
            ),
        )
    }

    // ── SignupPageController ───────────────────────────────

    @Test
    fun signupPages() {
        snap("auth/signup.page.html", get("/admin/signup"))
        snap(
            "auth/signup.form-error.html",
            anon(
                post("/admin/signup").param("email", "not-an-email")
                    .param("password", "short").param("password2", "other").header("HX-Request", "true"),
            ),
        )
        doReturn(ResponseEntity.accepted().body(mapOf("activated" to false)))
            .whenever(signups).request(any<SignupRequestDto.Create>())
        snap(
            "auth/signup.pending.html",
            anon(
                post("/admin/signup").param("email", "new@example.com")
                    .param("password", "correct-horse-battery").param("password2", "correct-horse-battery")
                    .param("reason", "RP 운영").header("HX-Request", "true"),
            ),
        )
    }

    // ── HomeRedirectController ─────────────────────────────

    @Test
    fun homeRedirects() {
        snap("shell/home.platform.html", page("/admin", operator()))
        snap("shell/home.rp-admin.html", page("/admin/", rpAdmin()))
        snap("shell/home.legacy-path.rp-admin.html", page("/admin/api-keys", rpAdmin()))
        snap("shell/home.anonymous.html", get("/admin"))
    }

    // ── ScreenErrorViewResolver ────────────────────────────

    @Test
    fun errorScreens() {
        snap("error/not-found.page.platform.html", page("/admin/nonexistent", operator()))
        snap("error/not-found.page.rp-admin.html", page("/admin/nonexistent", rpAdmin()))
        snap("error/not-found.frag.html", frag("/admin/nonexistent", rpAdmin()))
        snap(
            "error/dispatch-500.page.html",
            get("/error").accept("text/html").with { req ->
                req.dispatcherType = DispatcherType.ERROR
                req.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 500)
                req.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/admin/tenants")
                req
            }.with(authentication(operator())),
        )
    }
}
