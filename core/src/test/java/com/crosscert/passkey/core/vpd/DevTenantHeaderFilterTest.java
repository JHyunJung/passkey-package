package com.crosscert.passkey.core.vpd;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DevTenantHeaderFilterTest {

    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

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
        // Phase 6: header must be a valid UUID string; operators pass UUID directly.
        when(req.getHeader("X-Tenant-Id")).thenReturn(TENANT_A.toString());

        // Capture the value visible inside the chain — proves the
        // header reached TenantContextHolder before downstream code ran.
        UUID[] visibleInChain = new UUID[1];
        doAnswer((InvocationOnMock i) -> {
            visibleInChain[0] = TenantContextHolder.get();
            return null;
        }).when(chain).doFilter(any(), any());

        filter.doFilter(req, res, chain);

        assertThat(visibleInChain[0]).isEqualTo(TENANT_A);
        // ThreadLocal must be cleared by the time control returns.
        assertThat(TenantContextHolder.get()).isNull();
    }

    @Test
    void doesNotSetTenantWhenHeaderBlank() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(req.getHeader("X-Tenant-Id")).thenReturn("   ");

        UUID[] visibleInChain = new UUID[1];
        doAnswer((InvocationOnMock i) -> {
            visibleInChain[0] = TenantContextHolder.get();
            return null;
        }).when(chain).doFilter(any(), any());

        filter.doFilter(req, res, chain);

        assertThat(visibleInChain[0]).isNull();
    }

    @Test
    void invalidUuidHeaderIsIgnoredWithWarning() throws Exception {
        // A slug-format value (e.g., "acme") is not a valid UUID.
        // Slug→UUID resolution is deferred to T13/T14. For now the filter
        // logs a warning and treats the request as if no tenant was supplied.
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(req.getHeader("X-Tenant-Id")).thenReturn("acme");

        UUID[] visibleInChain = new UUID[1];
        doAnswer((InvocationOnMock i) -> {
            visibleInChain[0] = TenantContextHolder.get();
            return null;
        }).when(chain).doFilter(any(), any());

        filter.doFilter(req, res, chain);

        // Invalid UUID header must not set any tenant context.
        assertThat(visibleInChain[0]).isNull();
        assertThat(TenantContextHolder.get()).isNull();
    }

    @Test
    void clearsStaleContextBeforeReadingHeader() throws Exception {
        // Simulate a leaked tenant from a prior request on this thread.
        TenantContextHolder.set(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));

        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(req.getHeader("X-Tenant-Id")).thenReturn(null);  // no header

        UUID[] visibleInChain = new UUID[1];
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
        when(req.getHeader("X-Tenant-Id")).thenReturn(TENANT_A.toString());
        doThrow(new ServletException("downstream boom"))
                .when(chain).doFilter(any(), any());

        assertThatThrownBy(() -> filter.doFilter(req, res, chain))
                .isInstanceOf(ServletException.class);
        assertThat(TenantContextHolder.get()).isNull();
    }
}
