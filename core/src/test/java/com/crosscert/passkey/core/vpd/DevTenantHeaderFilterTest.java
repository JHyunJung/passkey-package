package com.crosscert.passkey.core.vpd;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DevTenantHeaderFilterTest {

    private final DevTenantHeaderFilter filter = new DevTenantHeaderFilter();

    @AfterEach
    void cleanup() {
        TenantContextHolder.clear();
    }

    @Test
    void setsTenantFromHeaderDuringChain() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(req.getHeader("X-Tenant-Id")).thenReturn("T_A");

        // Capture the value visible inside the chain — proves the
        // header reached TenantContextHolder before downstream code ran.
        String[] visibleInChain = new String[1];
        doAnswer((InvocationOnMock i) -> {
            visibleInChain[0] = TenantContextHolder.get();
            return null;
        }).when(chain).doFilter(any(), any());

        filter.doFilter(req, res, chain);

        assertThat(visibleInChain[0]).isEqualTo("T_A");
        // ThreadLocal must be cleared by the time control returns.
        assertThat(TenantContextHolder.get()).isNull();
    }

    @Test
    void doesNotSetTenantWhenHeaderBlank() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(req.getHeader("X-Tenant-Id")).thenReturn("   ");

        String[] visibleInChain = new String[1];
        doAnswer((InvocationOnMock i) -> {
            visibleInChain[0] = TenantContextHolder.get();
            return null;
        }).when(chain).doFilter(any(), any());

        filter.doFilter(req, res, chain);

        assertThat(visibleInChain[0]).isNull();
    }

    @Test
    void clearsStaleContextBeforeReadingHeader() throws Exception {
        // Simulate a leaked tenant from a prior request on this thread.
        TenantContextHolder.set("T_STALE");

        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(req.getHeader("X-Tenant-Id")).thenReturn(null);  // no header

        String[] visibleInChain = new String[1];
        doAnswer((InvocationOnMock i) -> {
            visibleInChain[0] = TenantContextHolder.get();
            return null;
        }).when(chain).doFilter(any(), any());

        filter.doFilter(req, res, chain);

        // The stale value must NOT leak into the chain. Pre-read clear wins.
        assertThat(visibleInChain[0]).isNull();
    }

    @Test
    void clearsContextEvenWhenChainThrows() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(req.getHeader("X-Tenant-Id")).thenReturn("T_A");
        doThrow(new ServletException("downstream boom"))
                .when(chain).doFilter(any(), any());

        assertThatThrownBy(() -> filter.doFilter(req, res, chain))
                .isInstanceOf(ServletException.class);
        assertThat(TenantContextHolder.get()).isNull();
    }
}
