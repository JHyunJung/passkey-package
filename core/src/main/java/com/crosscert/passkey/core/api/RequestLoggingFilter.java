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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
 * <p>{@code actorEmail} MDC is populated in the finally block (after
 * Spring Security's filter chain has placed the {@code Authentication} in
 * the {@link SecurityContextHolder}) and removed immediately after the
 * log statement. This keeps the MDC visible to the request log line
 * itself but prevents leaking authenticated context into unrelated work
 * later on the same thread. {@link SecurityContextHolder} is on the core
 * classpath (spring-security-core); apps without form login (passkey-app)
 * simply observe {@code auth == null} and skip the MDC put.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10) // after TraceIdFilter (HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    private static final String MDC_ACTOR_EMAIL = "actorEmail";

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
            boolean actorPut = populateActorEmailMdc();
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
     * Read the current authenticated principal from Spring Security and
     * push its name onto MDC under {@code actorEmail}. Returns {@code true}
     * when MDC was modified so the caller can remove it on the same path.
     *
     * <p>Defensive: any failure (no Spring Security on classpath in a future
     * downstream module, ClassCastException on a strange principal, etc.)
     * is swallowed — request logging must never throw.
     */
    private boolean populateActorEmailMdc() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()) {
                return false;
            }
            String name = auth.getName();
            if (name == null || name.isBlank() || "anonymousUser".equals(name)) {
                return false;
            }
            MDC.put(MDC_ACTOR_EMAIL, name);
            return true;
        } catch (Throwable t) {
            // Spring Security absent or principal unreadable — ignore.
            return false;
        }
    }
}
