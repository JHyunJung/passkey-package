package com.crosscert.passkey.core.vpd;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Phase 0 / dev-only request filter that copies the {@code X-Tenant-Id}
 * header into {@link TenantContextHolder} for downstream JDBC use.
 *
 * <p>Registered ONLY in known dev / test profiles (allow-list, not
 * "anything not prod") so that an unconfigured deployment without an
 * active profile cannot accidentally accept caller-supplied tenant
 * identifiers.
 *
 * <p>Phase 1 codex P1: this filter MUST skip the RP API paths
 * ({@code /api/v1/rp/**}) so it does not clear or overwrite the tenant
 * context that {@link com.crosscert.passkey.app.security.ApiKeyAuthFilter}
 * has just set from a validated X-API-Key. Without the skip, a valid
 * RP API request would either lose its tenant (if no X-Tenant-Id is
 * sent) or be silently switched to a caller-supplied tenant (if one
 * is sent) — both fatal for VPD isolation.
 */
@Component
@Profile({"local", "dev", "test"})
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class DevTenantHeaderFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Tenant-Id";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Use getServletPath() to be robust against a non-root context
        // path — getRequestURI() includes the context path and would
        // mismatch under e.g. server.servlet.context-path=/passkey.
        String path = request.getServletPath();
        return path != null && path.startsWith("/api/v1/rp/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        // Defense-in-depth: clear any stale ThreadLocal value before we
        // read the request. Reuse of a Tomcat worker thread that exited
        // a prior request via an unusual path should never leak the
        // previous tenant into this one.
        TenantContextHolder.clear();

        String tenant = req.getHeader(HEADER);
        if (tenant != null && !tenant.isBlank()) {
            TenantContextHolder.set(tenant);
        }
        try {
            chain.doFilter(req, res);
        } finally {
            TenantContextHolder.clear();
        }
    }
}
