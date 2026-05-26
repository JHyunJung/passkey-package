package com.crosscert.passkey.core.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TraceIdFilterTest {

    @AfterEach
    void clearMdc() { MDC.clear(); }

    @Test
    void generatesTraceIdWhenHeaderAbsent() throws Exception {
        TraceIdFilter filter = new TraceIdFilter();
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(req.getHeader("X-Trace-Id")).thenReturn(null);

        filter.doFilterInternal(req, res, chain);

        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(res).setHeader(eq("X-Trace-Id"), cap.capture());
        assertThat(cap.getValue()).hasSize(16);
        verify(chain).doFilter(req, res);
        assertThat(MDC.get("traceId")).isNull();  // cleared after chain
    }

    @Test
    void honorsIncomingTraceIdHeader() throws Exception {
        TraceIdFilter filter = new TraceIdFilter();
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(req.getHeader("X-Trace-Id")).thenReturn("incoming-id");

        filter.doFilterInternal(req, res, chain);

        verify(res).setHeader("X-Trace-Id", "incoming-id");
    }

    @Test
    void clearsMdcEvenIfChainThrows() throws Exception {
        TraceIdFilter filter = new TraceIdFilter();
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(req.getHeader("X-Trace-Id")).thenReturn("x");
        doThrow(new RuntimeException("boom")).when(chain).doFilter(any(), any());

        assertThatThrownBy(() -> filter.doFilterInternal(req, res, chain))
                .hasMessage("boom");
        assertThat(MDC.get("traceId")).isNull();
    }
}
