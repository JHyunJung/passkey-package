package com.crosscert.passkey.samplerp.common.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * X-Trace-Id 헤더가 있으면 사용, 없으면 새로 발급. MDC 키 "traceId" 는
 * SDK 의 PasskeyClientConfig.MDC_TRACE_ID_KEY 와 반드시 동일해야 한다 — SDK 의
 * 기본 traceIdProvider 가 같은 키를 읽음.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Trace-Id";
    private static final String MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String traceId = Optional.ofNullable(req.getHeader(HEADER))
                .filter(s -> !s.isBlank())
                .orElseGet(() -> UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        MDC.put(MDC_KEY, traceId);
        res.setHeader(HEADER, traceId);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
