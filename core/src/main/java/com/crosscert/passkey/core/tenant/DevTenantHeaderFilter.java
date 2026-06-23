package com.crosscert.passkey.core.tenant;

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
import java.util.UUID;
import java.util.logging.Logger;

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
    private static final Logger LOG = Logger.getLogger(DevTenantHeaderFilter.class.getName());

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

        // X-Tenant-Id is a UUID string only. This dev/test-only filter
        // (@Profile local/dev/test) deliberately does NOT resolve slugs:
        // production traffic authenticates via X-API-Key (ApiKeyAuthFilter),
        // which sets tenant context from the verified key, so a slug
        // fallback here would be dead code. Non-UUID values are rejected
        // with a warning below.
        String headerValue = req.getHeader(HEADER);
        UUID tenantId = null;
        if (headerValue != null && !headerValue.isBlank()) {
            try {
                tenantId = UUID.fromString(headerValue);
            } catch (IllegalArgumentException e) {
                // Reject non-UUID X-Tenant-Id (slug resolution intentionally
                // unsupported in this dev-only filter — see header note).
                LOG.warning("X-Tenant-Id is not a valid UUID: " + headerValue);
            }
        }
        TenantContextHolder.set(tenantId);
        try {
            chain.doFilter(req, res);
        } finally {
            TenantContextHolder.clear();
        }
    }
}
