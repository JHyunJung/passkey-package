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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "abc\r\nFAKE LOG LINE",          // CRLF — 로그 줄 위조
            "abc\nx",
            "has space",
            "<script>",
            "한글추적",
            "a\u0000b",
    })
    void replacesUnsafeIncomingTraceId(String unsafe) throws Exception {
        assertReplaced(unsafe);
    }

    @Test
    void replacesOverlongIncomingTraceId() throws Exception {
        assertReplaced("a".repeat(65));
    }

    @Test
    void acceptsMaxLengthAndCommonFormats() throws Exception {
        for (String ok : new String[]{"a".repeat(64), "0af7651916cd43dd8448eb211c80319c",
                "123e4567-e89b-12d3-a456-426614174000", "req_1.2-x"}) {
            TraceIdFilter filter = new TraceIdFilter();
            HttpServletRequest req = mock(HttpServletRequest.class);
            HttpServletResponse res = mock(HttpServletResponse.class);
            when(req.getHeader("X-Trace-Id")).thenReturn(ok);
            filter.doFilterInternal(req, res, mock(FilterChain.class));
            verify(res).setHeader("X-Trace-Id", ok);
        }
    }

    private static void assertReplaced(String unsafe) throws Exception {
        TraceIdFilter filter = new TraceIdFilter();
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(req.getHeader("X-Trace-Id")).thenReturn(unsafe);
        String[] seen = new String[1];
        org.mockito.Mockito.doAnswer(inv -> { seen[0] = MDC.get("traceId"); return null; })
                .when(chain).doFilter(any(), any());

        filter.doFilterInternal(req, res, chain);

        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(res).setHeader(eq("X-Trace-Id"), cap.capture());
        assertThat(cap.getValue()).matches("[0-9a-f]{16}");
        assertThat(seen[0]).isEqualTo(cap.getValue());  // MDC 에도 원본이 아닌 새 ID
        verify(chain).doFilter(req, res);                // 요청은 거부하지 않는다
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
