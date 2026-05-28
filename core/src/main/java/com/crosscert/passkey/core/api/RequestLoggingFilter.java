package com.crosscert.passkey.core.api;

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
 * Logs one line per HTTP request after the response has been written. The
 * level depends on HTTP status: >=500 ERROR, >=400 WARN, else INFO.
 *
 * <p>Order is just after {@link TraceIdFilter} so {@code traceId} MDC is
 * already populated by the time this filter runs and emits its log line.
 *
 * <p>{@code actorEmail} MDC is populated in the finally block from the
 * request attribute {@link #ACTOR_EMAIL_ATTR} (set by an in-Spring-Security
 * filter — see {@code AdminMdcFilter} in admin-app), not directly from
 * {@link SecurityContextHolder}. By the time this filter's finally block
 * runs, Spring Security's outermost filter has already cleared the
 * {@link SecurityContextHolder}, so reading it here would always see
 * {@code null}. Request attributes survive into the finally because they
 * are attached to {@code HttpServletRequest}, not a thread-local.
 *
 * <p>This indirection keeps core decoupled from {@code spring-security-config}
 * (which would be required to register a filter inside the security chain)
 * while still letting the log pattern include {@code actorEmail}. Apps
 * without authenticated sessions (passkey-app, sample-rp's public pages)
 * simply do not set the attribute, and the MDC slot stays empty.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10) // after TraceIdFilter (HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    private static final String MDC_ACTOR_EMAIL = "actorEmail";

    /**
     * Request attribute carrying the authenticated principal's name (set
     * by an in-security-chain filter in each app). Public so app-side
     * filters can use the same key without copy-paste of the constant.
     */
    public static final String ACTOR_EMAIL_ATTR = "com.crosscert.passkey.actorEmail";

    private static final Set<String> EXCLUDED_PATHS = Set.of(
            "/actuator/health",
            "/.well-known/jwks.json"
    );

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
            String method = req.getMethod();
            int status = res.getStatus();
            boolean actorPut = populateActorEmailMdc(req);
            try {
                String msg = String.format("request: method=%s path=%s status=%d durMs=%d",
                        method, path, status, durMs);
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

    /**
     * Pull the actorEmail from the request attribute (set by an
     * in-security-chain filter) and push it onto MDC for the duration of
     * the request log statement. Returns true if MDC was modified.
     *
     * <p>Defensive: any failure is swallowed — request logging must never
     * throw because of an unexpected attribute type.
     */
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
