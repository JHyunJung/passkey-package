package com.crosscert.passkey.rpapp.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * rp-app local twin of core's {@code RequestLoggingFilter}. rp-app
 * does not depend on core (separate webapp), so the logic is duplicated.
 *
 * <p>Drift check: keep status-mapping + format + actorEmail handling
 * identical to core. Intentional diffs only in excluded-paths set
 * (rp-app does not expose {@code /.well-known/jwks.json}).
 *
 * <p>rp-app's own {@code TraceIdFilter} (under {@code common.filter})
 * runs at {@code HIGHEST_PRECEDENCE} and populates {@code traceId} MDC
 * before this filter logs. When rp-app calls passkey-app via the SDK,
 * {@code TraceIdPropagationInterceptor} carries the same {@code X-Trace-Id}
 * forward so the two servers' logs share one trace.
 *
 * <p>actorEmail is read from a request attribute set by an in-security-chain
 * filter (rp-app does not yet have one — all routes are permitAll —
 * so this slot stays empty, matching the demo's no-auth posture).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    private static final String MDC_ACTOR_EMAIL = "actorEmail";

    /** Mirror of core's RequestLoggingFilter.ACTOR_EMAIL_ATTR. */
    public static final String ACTOR_EMAIL_ATTR = "com.crosscert.passkey.actorEmail";

    private static final Set<String> EXCLUDED_PATHS = Set.of("/actuator/health");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        if (EXCLUDED_PATHS.contains(path)) {
            chain.doFilter(req, res);
            return;
        }
        long startNs = System.nanoTime();
        try {
            chain.doFilter(req, res);
        } finally {
            long durMs = (System.nanoTime() - startNs) / 1_000_000L;
            int status = res.getStatus();
            boolean actorPut = populateActorEmailMdc(req);
            try {
                String msg = String.format("request: method=%s path=%s status=%d durMs=%d",
                        req.getMethod(), path, status, durMs);
                if (status >= 500) {
                    log.error(msg);
                } else if (status >= 400) {
                    log.warn(msg);
                } else {
                    log.info(msg);
                }
            } finally {
                if (actorPut) {
                    MDC.remove(MDC_ACTOR_EMAIL);
                }
            }
        }
    }

    private boolean populateActorEmailMdc(HttpServletRequest req) {
        try {
            Object v = req.getAttribute(ACTOR_EMAIL_ATTR);
            if (v == null) {
                return false;
            }
            String name = v.toString();
            if (name.isBlank()) {
                return false;
            }
            MDC.put(MDC_ACTOR_EMAIL, name);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
