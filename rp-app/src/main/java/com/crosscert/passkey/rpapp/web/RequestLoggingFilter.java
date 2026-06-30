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
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * rp-app local twin of core's {@code RequestLoggingFilter}. rp-app
 * does not depend on core (separate webapp), so the logic is duplicated.
 *
 * <p>한 줄 요약(method/path/status/durMs)은 항상 INFO/WARN/ERROR 로 남기고,
 * request/response 본문은 전용 payload 로거에 DEBUG 로 남긴다. payload 로거는
 * logback 에서 local/dev 만 DEBUG 라 qa/prod 에선 자동 억제된다(레벨 게이트).
 * 본문은 2KB 로 캡하고, %msg SecretMaskingConverter 가 비밀값을 마스킹한다(삼중 방어).
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
    private static final Logger payloadLog = LoggerFactory.getLogger("com.crosscert.passkey.payload");

    private static final String MDC_ACTOR_EMAIL = "actorEmail";

    /** Mirror of core's RequestLoggingFilter.ACTOR_EMAIL_ATTR. */
    public static final String ACTOR_EMAIL_ATTR = "com.crosscert.passkey.actorEmail";

    private static final Set<String> EXCLUDED_PATHS = Set.of("/actuator/health");
    private static final int MAX_BODY = 2048;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        if (EXCLUDED_PATHS.contains(path)) {
            chain.doFilter(req, res);
            return;
        }
        boolean debugBody = payloadLog.isDebugEnabled();
        HttpServletRequest wrappedReq = debugBody ? new ContentCachingRequestWrapper(req) : req;
        HttpServletResponse wrappedRes = debugBody ? new ContentCachingResponseWrapper(res) : res;

        long startNs = System.nanoTime();
        try {
            chain.doFilter(wrappedReq, wrappedRes);
        } finally {
            long durMs = (System.nanoTime() - startNs) / 1_000_000L;
            int status = wrappedRes.getStatus();
            boolean actorPut = populateActorEmailMdc(req);
            try {
                String msg = String.format(
                        "request: method=%s path=%s status=%d durMs=%d",
                        req.getMethod(), path, status, durMs);
                if (status >= 500) log.error(msg);
                else if (status >= 400) log.warn(msg);
                else log.info(msg);

                if (debugBody) {
                    logBody((ContentCachingRequestWrapper) wrappedReq, (ContentCachingResponseWrapper) wrappedRes);
                }
            } finally {
                if (debugBody) {
                    ((ContentCachingResponseWrapper) wrappedRes).copyBodyToResponse();
                }
                if (actorPut) MDC.remove(MDC_ACTOR_EMAIL);
            }
        }
    }

    private void logBody(ContentCachingRequestWrapper req, ContentCachingResponseWrapper res) {
        String reqBody = cap(new String(req.getContentAsByteArray(), StandardCharsets.UTF_8));
        String resBody = cap(new String(res.getContentAsByteArray(), StandardCharsets.UTF_8));
        if (!reqBody.isEmpty()) payloadLog.debug("req body: {}", reqBody);
        if (!resBody.isEmpty()) payloadLog.debug("resp body: {}", resBody);
    }

    private String cap(String s) {
        return s.length() <= MAX_BODY ? s : s.substring(0, MAX_BODY) + "…[truncated " + s.length() + "]";
    }

    private boolean populateActorEmailMdc(HttpServletRequest req) {
        try {
            Object v = req.getAttribute(ACTOR_EMAIL_ATTR);
            if (v == null) return false;
            String name = v.toString();
            if (name.isBlank()) return false;
            MDC.put(MDC_ACTOR_EMAIL, name);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
