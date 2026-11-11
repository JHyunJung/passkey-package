package com.crosscert.passkey.core.api;

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
import java.util.regex.Pattern;

/**
 * 요청마다 traceId 를 MDC 와 응답 헤더에 넣는다. 들어온 {@code X-Trace-Id} 는 클라이언트가 통제하는 값이라
 * 안전한 형식({@link #SAFE_TRACE_ID})일 때만 그대로 쓰고, 아니면 새로 발급한다 — CRLF 로 로그 줄을 위조하거나
 * 긴 값으로 로그를 부풀리는 것을 막는다. 형식이 나빠도 요청 자체는 거부하지 않는다(추적 ID 는 부가 정보).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String MDC_KEY = "traceId";
    /** UUID·W3C trace-id(32 hex)·일반 요청 ID 를 수용하는 최소 집합. 64자 상한. */
    static final Pattern SAFE_TRACE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String traceId = Optional.ofNullable(req.getHeader(TRACE_ID_HEADER))
                .filter(s -> SAFE_TRACE_ID.matcher(s).matches())
                .orElseGet(() -> UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        MDC.put(MDC_KEY, traceId);
        res.setHeader(TRACE_ID_HEADER, traceId);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
