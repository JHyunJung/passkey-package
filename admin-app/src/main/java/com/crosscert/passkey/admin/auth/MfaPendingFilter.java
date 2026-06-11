package com.crosscert.passkey.admin.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Second-factor gate for {@code /admin/api/**}.
 *
 * <p>After a primary (password) login succeeds for an MFA-enabled operator,
 * {@code AdminSecurityConfig#adminLoginSuccessHandler} sets the server-side
 * session attribute {@link #MFA_PENDING_ATTR} to {@code TRUE}. Until the
 * operator submits a valid TOTP code to {@code /admin/api/mfa/verify} (which
 * clears the attribute), this filter blocks every authenticated
 * {@code /admin/api/**} request with {@code 403 {"error":"mfa_required"}}.
 *
 * <p>Allow-listed while pending so the user can complete the second factor:
 * <ul>
 *   <li>{@code /admin/api/mfa/verify} — verify endpoint only.</li>
 *   <li>{@code /admin/api/me} — so the SPA can read its own session state
 *       (notably {@code mfaRequired}) and decide to show the MFA challenge.</li>
 *   <li>{@code /admin/logout} — let the operator abandon the half-finished login.</li>
 * </ul>
 *
 * <p><b>Why it cannot be bypassed:</b> the gate decision is read from the
 * server-side {@link HttpSession} (never a client header), and the filter is
 * wired via {@code addFilterAfter(.., AuthorizationFilter.class)} so it runs
 * only after Spring Security has authenticated the request and populated the
 * {@link SecurityContextHolder}. An unauthenticated request never reaches a
 * point where {@code MFA_PENDING} is true (the attribute is only set on login
 * success), and a pending session cannot reach any business endpoint because
 * every non-allow-listed {@code /admin/api/**} path is short-circuited here
 * before the dispatcher servlet runs.
 */
public class MfaPendingFilter extends OncePerRequestFilter {

    public static final String MFA_PENDING_ATTR = "MFA_PENDING";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        if (isMfaPending(req) && !isAllowedWhilePending(req)) {
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"mfa_required\"}");
            return; // do NOT continue the chain — request is fully handled.
        }
        chain.doFilter(req, res);
    }

    private boolean isMfaPending(HttpServletRequest req) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            return false;
        }
        HttpSession session = req.getSession(false);
        return session != null && Boolean.TRUE.equals(session.getAttribute(MFA_PENDING_ATTR));
    }

    /**
     * Paths reachable while a session is MFA-pending. Uses the servlet path
     * (full request URI including context path) and exact matching; query strings are ignored
     * by the servlet API for {@link HttpServletRequest#getRequestURI()}.
     */
    private boolean isAllowedWhilePending(HttpServletRequest req) {
        String uri = req.getRequestURI();
        if (uri == null) return false;
        // Only the second-factor completion endpoint is reachable while pending.
        // enroll/confirm/disable require a fully-authenticated (non-pending)
        // session — narrowing from the old "/admin/api/mfa/" prefix closes the
        // re-enroll bypass where a password-only session overwrote the TOTP secret.
        return uri.equals("/admin/api/mfa/verify")
                || uri.equals("/admin/api/me")
                || uri.equals("/admin/logout");
    }
}
