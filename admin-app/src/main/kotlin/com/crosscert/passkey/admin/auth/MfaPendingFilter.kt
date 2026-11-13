package com.crosscert.passkey.admin.auth

import com.crosscert.passkey.admin.web.support.Htmx
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Second-factor gate for `/admin/api/\*\*` and the server-rendered screens.
 *
 * After a primary (password) login succeeds for an MFA-enabled operator,
 * `AdminSecurityConfig#adminLoginSuccessHandler` sets the server-side
 * session attribute [MFA_PENDING_ATTR] to `TRUE`. Until the
 * operator submits a valid TOTP code to `/admin/api/mfa/verify` (which
 * clears the attribute), this filter blocks every authenticated
 * `/admin/api/\*\*` request with `403 {"error":"mfa_required"}`.
 *
 * Allow-listed while pending so the user can complete the second factor:
 * - `/admin/api/mfa/verify` — verify endpoint only.
 * - `/admin/api/me` — so the SPA can read its own session state
 *   (notably `mfaRequired`) and decide to show the MFA challenge.
 * - `/admin/logout` — let the operator abandon the half-finished login.
 * - `GET /admin/login`, `POST /admin/login/mfa` — the server-rendered MFA step.
 * - `/admin/css/\*\*`, `/admin/js/\*\*`, `/admin/fonts/\*\*`, `/admin/img/\*\*` — static assets.
 *
 * A session carrying [MfaSessionState.ENROLL_REQUIRED_ATTR] (policy requires MFA but the operator has
 * not enrolled) is gated the same way but with a narrower allow-list and `{"error":"mfa_enroll_required"}`:
 * `GET /admin/login`, `POST /admin/login/mfa/enroll`, `POST /admin/login/mfa/confirm`,
 * `/admin/api/me`, `/admin/logout`, static assets. `POST /admin/login/mfa` is blocked there, and
 * the enroll/confirm screen paths are blocked for pending sessions.
 *
 * Blocked `/admin/api/\*\*` requests get `403 {"error":"mfa_required"}`; any other
 * (screen) path is redirected to `/admin/login` (`HX-Redirect` for htmx requests).
 *
 * **Why it cannot be bypassed:** the gate decision is read from the
 * server-side [HttpSession] (never a client header), and the filter is
 * wired via `addFilterAfter(.., AuthorizationFilter.class)` so it runs
 * only after Spring Security has authenticated the request and populated the
 * [SecurityContextHolder]. An unauthenticated request never reaches a
 * point where `MFA_PENDING` is true (the attribute is only set on login
 * success), and a pending session cannot reach any business endpoint because
 * every non-allow-listed `/admin/api/\*\*` path is short-circuited here
 * before the dispatcher servlet runs.
 */
class MfaPendingFilter : OncePerRequestFilter() {

    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        if (isAuthenticated()) {
            if (MfaSessionState.isPending(req) && !isAllowedWhilePending(req)) {
                block(req, res, "mfa_required")
                return
            }
            if (MfaSessionState.isEnrollRequired(req) && !isAllowedWhileEnrollRequired(req)) {
                block(req, res, "mfa_enroll_required")
                return
            }
        }
        chain.doFilter(req, res)
    }

    /** 차단 응답: `/admin/api/` 하위는 403 JSON, 화면 경로는 로그인 화면으로(htmx 는 HX-Redirect). */
    private fun block(req: HttpServletRequest, res: HttpServletResponse, error: String) {
        val uri: String? = req.requestURI
        if (uri == null || !uri.startsWith("/admin/api/")) {
            // 화면 경로: 403 JSON 대신 로그인 화면(MFA 단계/등록 단계)으로 보낸다.
            Htmx.redirect(req, res, "/admin/login")
            return
        }
        res.status = HttpServletResponse.SC_FORBIDDEN
        res.contentType = "application/json"
        res.writer.write("{\"error\":\"$error\"}")
        // do NOT continue the chain — request is fully handled.
    }

    private fun isAuthenticated(): Boolean {
        val auth = SecurityContextHolder.getContext().authentication
        return auth != null && auth.isAuthenticated && "anonymousUser" != auth.name
    }

    /**
     * MFA 등록 필요 세션에서 닿을 수 있는 경로: 로그인 화면(GET /admin/login — 등록 단계 화면),
     * 등록 시작/확정 제출, `/admin/api/me`, 로그아웃, 정적 에셋. 그 외는 모두 차단.
     */
    private fun isAllowedWhileEnrollRequired(req: HttpServletRequest): Boolean {
        val uri: String = req.requestURI ?: return false
        if (uri == "/admin/api/me" || uri == "/admin/logout") return true
        val method: String? = req.method
        if ("GET" == method && uri == "/admin/login") return true
        if ("POST" == method && (uri == "/admin/login/mfa/enroll" || uri == "/admin/login/mfa/confirm")) return true
        return isStaticAsset(uri)
    }

    private fun isStaticAsset(uri: String): Boolean =
        uri.startsWith("/admin/css/") ||
            uri.startsWith("/admin/js/") ||
            uri.startsWith("/admin/fonts/") ||
            uri.startsWith("/admin/img/")

    /**
     * Paths reachable while a session is MFA-pending. Uses the servlet path
     * (full request URI including context path) and exact matching; query strings are ignored
     * by the servlet API for [HttpServletRequest.getRequestURI].
     */
    private fun isAllowedWhilePending(req: HttpServletRequest): Boolean {
        val uri: String = req.requestURI ?: return false
        // Only the second-factor completion endpoint is reachable while pending.
        // enroll/confirm/disable require a fully-authenticated (non-pending)
        // session — narrowing from the old "/admin/api/mfa/" prefix closes the
        // re-enroll bypass where a password-only session overwrote the TOTP secret.
        if (uri == "/admin/api/mfa/verify" ||
            uri == "/admin/api/me" ||
            uri == "/admin/logout"
        ) {
            return true
        }
        // 화면: MFA 단계 화면(GET /admin/login)과 그 제출(POST /admin/login/mfa), 정적 에셋.
        val method: String? = req.method
        if ("GET" == method && uri == "/admin/login") return true
        if ("POST" == method && uri == "/admin/login/mfa") return true
        return isStaticAsset(uri)
    }

    companion object {
        const val MFA_PENDING_ATTR = "MFA_PENDING"
    }
}
