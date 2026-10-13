package com.crosscert.passkey.core.license;

import com.crosscert.passkey.core.api.ApiResponse;
import com.crosscert.passkey.core.api.ErrorCode;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * Filters every HTTP request against the current LicenseState.
 *  - DEAD     -> 503 JSON (except for admin license/health/login paths)
 *  - WARNING  -> pass through + X-License-Warning header
 *  - NETWORK_GRACE -> pass through + X-License-Warning header
 *  - VALID    -> pass through
 *
 * Registered as @Component so passkey-app picks it up via Spring Boot
 * servlet filter auto-registration. admin-app's Spring Security chain
 * adds it explicitly via addFilterBefore() (see AdminSecurityConfig).
 *
 * Order = HIGHEST_PRECEDENCE + 1: runs after OnpremTenantPinFilter
 * (HIGHEST_PRECEDENCE) but before any auth filter, so license check
 * happens before any auth work but after the tenant context is set.
 */
@Component
@ConditionalOnProperty(name = "passkey.deployment.mode", havingValue = "onprem")
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@RequiredArgsConstructor
public class LicenseGuardFilter extends OncePerRequestFilter {

    /**
     * Paths exempt from the DEAD-state block. Keep operators able to
     * read /license to diagnose the outage, and keep /actuator/health
     * + login functional so the system surfaces clearly as dead rather
     * than silently dropping all traffic.
     *
     * <p>Matched by {@link #isExempt}: exact path or {@code path + "/"} prefix,
     * against BOTH the raw request URI and the container-normalized
     * {@code servletPath + pathInfo}. See {@link #isExempt} for why both.
     */
    private static final Set<String> DEAD_EXEMPT = Set.of(
            "/admin/api/license",
            "/admin/api/system/info",
            "/admin/login",
            "/admin/logout",
            "/actuator/health",
            "/actuator/info");

    private final LicenseStateMachine stateMachine;
    private final ObjectMapper mapper;

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        // Refresh state on every request — cheap, makes WARNING/DEAD transitions
        // visible without waiting for the next heartbeat.
        stateMachine.recompute();
        LicenseStateMachine.Snapshot snap = stateMachine.snapshot();

        switch (snap.state()) {
            case DEAD -> {
                if (isExempt(req)) {
                    chain.doFilter(req, res);
                    return;
                }
                writeDeadResponse(res);
                return;
            }
            case WARNING -> {
                long days = Math.max(0,
                        Duration.between(Instant.now(), snap.token().expiresAt()).toDays());
                res.setHeader("X-License-Warning", "expires in " + days + " day(s)");
            }
            case NETWORK_GRACE -> {
                long graceLeftHours = computeGraceLeftHours(snap);
                res.setHeader("X-License-Warning",
                        "license server unreachable; " + graceLeftHours + " hour(s) remaining");
            }
            case VALID -> { /* no-op */ }
        }
        chain.doFilter(req, res);
    }

    /**
     * A request is exempt only when all of these hold:
     * <ol>
     *   <li>the raw request URI contains neither {@code ".."} nor {@code ";"}
     *       — path traversal / path parameters (e.g. {@code ;jsessionid}) are
     *       never treated as exempt (a non-segment {@code ".."} such as
     *       {@code a..b} is rejected too; no exempt route has such segments);</li>
     *   <li>the raw request URI matches {@link #DEAD_EXEMPT} (the original rule —
     *       so the exempt set can only shrink, never grow, e.g. under a
     *       non-root context path or an {@code X-Forwarded-Prefix} it stays
     *       non-exempt as before);</li>
     *   <li>the container-normalized {@code servletPath + pathInfo} — the path
     *       the request is actually dispatched to after Tomcat decodes
     *       {@code %2e}, strips {@code ;params} and resolves {@code ..}, and
     *       which {@code ForwardedHeaderFilter} does not rewrite — matches
     *       {@link #DEAD_EXEMPT} too, so
     *       {@code /actuator/health/%2e%2e/%2e%2e/api/v1/rp/...} or a forged
     *       {@code X-Forwarded-Prefix: /actuator/health} cannot ride an exempt
     *       prefix past the DEAD block.</li>
     * </ol>
     * For a plain request (no dot-segments, path params, escapes or forwarded
     * prefix, root context) the raw URI equals {@code servletPath + pathInfo},
     * so the decision is identical to the previous raw-URI-only rule.
     */
    private boolean isExempt(HttpServletRequest req) {
        String raw = req.getRequestURI();
        if (raw == null || raw.contains("..") || raw.contains(";")) {
            return false;
        }
        String servletPath = req.getServletPath();
        String pathInfo = req.getPathInfo();
        String normalized = (servletPath == null ? "" : servletPath)
                + (pathInfo == null ? "" : pathInfo);
        return matchesExempt(raw) && matchesExempt(normalized);
    }

    private static boolean matchesExempt(String path) {
        for (String p : DEAD_EXEMPT) {
            if (path.equals(p) || path.startsWith(p + "/")) return true;
        }
        return false;
    }

    private void writeDeadResponse(HttpServletResponse res) throws IOException {
        res.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiResponse<Void> body = ApiResponse.error(ErrorCode.LICENSE_EXPIRED,
                "License has expired. Contact your administrator.");
        mapper.writeValue(res.getWriter(), body);
    }

    private long computeGraceLeftHours(LicenseStateMachine.Snapshot snap) {
        long graceMax = snap.token().limits().graceHoursWhenOffline();
        long elapsed = Duration.between(snap.lastVerifiedAt(), Instant.now()).toHours();
        return Math.max(0, graceMax - elapsed);
    }
}
