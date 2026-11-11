package com.crosscert.passkey.rpapp.common.filter;

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
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 요청마다 추적 ID 를 준비하는 필터. X-Trace-Id 헤더가 안전한 형식(영숫자·{@code . _ -}, 64자 이하)이면
 * 그대로 쓰고, 없거나 형식이 맞지 않으면 새로 발급해 MDC "traceId" 와 응답 헤더에 넣는다. 형식 검사는
 * CRLF 로 로그 줄을 위조하거나 긴 값으로 로그를 부풀리는 것을 막기 위한 것이며, 요청 자체는 거부하지 않는다.
 * 이 MDC 키는 SDK 가 passkey-app 으로 전파하는 키와 같아야 한다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Trace-Id";
    private static final String MDC_KEY = "traceId";
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader(HEADER);
        String traceId = (header != null && SAFE_TRACE_ID.matcher(header).matches())
                ? header
                : UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        MDC.put(MDC_KEY, traceId);
        res.setHeader(HEADER, traceId);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
