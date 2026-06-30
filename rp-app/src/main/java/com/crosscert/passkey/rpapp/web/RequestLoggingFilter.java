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
 * 요청 1건당 한 줄 요약(method/path/status/durMs)을 INFO/WARN/ERROR 로 남기는 필터.
 * 요청·응답 본문은 별도 payload 로거에 DEBUG 로만 남기므로 운영(qa/prod)에서는 자동 억제된다.
 * 본문은 2KB 로 자르고, {@link SecretMaskingConverter} 가 비밀값을 마스킹한다(다중 방어).
 *
 * <p>{@code TraceIdFilter} 가 먼저 traceId 를 MDC 에 넣고, SDK 호출 시 같은 X-Trace-Id 가
 * passkey-app 으로 전파돼 두 서버 로그를 한 추적 단위로 묶는다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
    private static final Logger payloadLog = LoggerFactory.getLogger("com.crosscert.passkey.payload");

    private static final String MDC_ACTOR_EMAIL = "actorEmail";

    /** 인증 필터가 요청 속성에 넣은 사용자 이메일을 읽어 MDC actorEmail 로 남길 때 쓰는 키(데모는 미설정). */
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
