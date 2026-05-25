package com.crosscert.passkey.app.security;

import com.crosscert.passkey.core.vpd.TenantContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

/**
 * Filters every {@code /api/v1/rp/**} request. Parses {@code X-API-Key},
 * looks up the row via {@link ApiKeyLookupService} (definer-rights
 * PL/SQL bypasses VPD safely), BCrypt-verifies the secret, and on
 * success sets {@link TenantContextHolder} for the duration of the
 * request.
 *
 * <p>Order: {@code HIGHEST_PRECEDENCE + 5} — runs after
 * {@code RateLimitFilter} and before the legacy
 * {@code DevTenantHeaderFilter} so a real {@code X-API-Key} always
 * wins over the dev header.
 *
 * <p>Security properties:
 * <ul>
 *   <li>All failure paths emit a generic 401 — no detail leaks about
 *       whether the prefix existed, the secret matched, or the key
 *       was revoked.</li>
 *   <li>Unknown-prefix path runs BCrypt against a fixed dummy hash to
 *       equalize timing with the wrong-secret path. Phase 1 acceptable
 *       trade-off: removes the simple "does this prefix exist" oracle
 *       at the cost of one BCrypt op per invalid request — already
 *       rate-limited by RateLimitFilter.</li>
 *   <li>Only headers starting with the canonical {@code "pk_"} prefix
 *       are processed; any other shape is rejected before any DB call.</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-API-Key";
    private static final String KEY_PREFIX = "pk_";
    private static final int PREFIX_LEN = 11; // "pk_" + 8 base64url chars

    /**
     * A fixed BCrypt hash of an unused secret. Used for timing
     * equalization on the unknown-prefix code path so an attacker
     * cannot distinguish "prefix exists, wrong secret" from "prefix
     * does not exist" by measuring response time.
     */
    private static final String DUMMY_HASH =
            "$2a$12$KIXxA7B0NQI8YzCq0RZ.7eN5lUucl0wH0G3jWvSEqcD9QYxIqUyqK";

    private final ApiKeyLookupService lookup;
    private final PasswordEncoder encoder;

    public ApiKeyAuthFilter(ApiKeyLookupService lookup,
                            PasswordEncoder encoder) {
        this.lookup = lookup;
        this.encoder = encoder;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // getServletPath() strips the servlet context path so the
        // match is robust under a non-root deployment context (e.g.
        // server.servlet.context-path=/passkey). getRequestURI()
        // would include that prefix and let an attacker bypass
        // authentication for /passkey/api/v1/rp/** paths.
        String path = request.getServletPath();
        return path == null || !path.startsWith("/api/v1/rp/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        String header = req.getHeader(HEADER);
        if (header == null || header.length() <= PREFIX_LEN
                || !header.startsWith(KEY_PREFIX)) {
            unauthorized(res);
            return;
        }

        String prefix = header.substring(0, PREFIX_LEN);
        String secret = header.substring(PREFIX_LEN);

        Optional<ApiKeyLookupService.ApiKeyAuthRow> opt = lookup.findByPrefix(prefix);
        if (opt.isEmpty()) {
            // Run BCrypt anyway so the timing of unknown-prefix matches
            // the timing of known-prefix-wrong-secret.
            encoder.matches(secret, DUMMY_HASH);
            unauthorized(res);
            return;
        }
        ApiKeyLookupService.ApiKeyAuthRow row = opt.get();

        Instant now = Instant.now();
        if (!row.isActive(now)) {
            // Same timing equalization for revoked/expired keys.
            encoder.matches(secret, DUMMY_HASH);
            unauthorized(res);
            return;
        }
        if (!encoder.matches(secret, row.keyHash())) {
            unauthorized(res);
            return;
        }

        TenantContextHolder.set(row.tenantId());
        try {
            // touchLastUsed runs WITH tenant context active so the
            // V8 package's WHERE tenant_id = SYS_CONTEXT predicate
            // matches the calling tenant exactly.
            lookup.touchLastUsed(row.id(), now);
            chain.doFilter(req, res);
        } finally {
            TenantContextHolder.clear();
        }
    }

    /** Single generic 401 — no detail leaks. */
    private void unauthorized(HttpServletResponse res) throws IOException {
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        res.setContentType("application/problem+json");
        res.getWriter().write(
                "{\"type\":\"about:blank\",\"status\":401,\"title\":\"Unauthorized\"}");
    }
}
