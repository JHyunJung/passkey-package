package com.crosscert.passkey.admin.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MfaPendingFilterTest {

    private final MfaPendingFilter filter = new MfaPendingFilter();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("alice@example.com", "x", "ROLE_PLATFORM_OPERATOR"));
    }

    private HttpServletRequest requestWithSession(String uri, boolean pending) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpSession session = mock(HttpSession.class);
        when(req.getRequestURI()).thenReturn(uri);
        when(req.getSession(false)).thenReturn(session);
        when(session.getAttribute(MfaPendingFilter.MFA_PENDING_ATTR))
                .thenReturn(pending ? Boolean.TRUE : null);
        return req;
    }

    @Test
    void pending_nonMfaPath_is403_andChainNotCalled() throws Exception {
        authenticate();
        HttpServletRequest req = requestWithSession("/admin/api/tenants", true);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(res.getContentAsString()).contains("mfa_required");
        verify(chain, never()).doFilter(req, res);
    }

    @Test
    void pending_mfaVerifyPath_passesThrough() throws Exception {
        authenticate();
        HttpServletRequest req = requestWithSession("/admin/api/mfa/verify", true);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    void pending_logoutPath_passesThrough() throws Exception {
        authenticate();
        HttpServletRequest req = requestWithSession("/admin/logout", true);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
    }

    @Test
    void pending_mePath_passesThrough() throws Exception {
        // The SPA must read /admin/api/me even while MFA-pending so it can
        // discover it needs to show the MFA challenge.
        authenticate();
        HttpServletRequest req = requestWithSession("/admin/api/me", true);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    void notPending_passesThrough() throws Exception {
        authenticate();
        HttpServletRequest req = requestWithSession("/admin/api/tenants", false);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
    }

    @Test
    void noSession_passesThrough() throws Exception {
        authenticate();
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/admin/api/tenants");
        when(req.getSession(false)).thenReturn(null);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
    }

    @Test
    void pending_mfaEnrollPath_is403() throws Exception {
        authenticate();
        HttpServletRequest req = requestWithSession("/admin/api/mfa/enroll", true);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(res.getContentAsString()).contains("mfa_required");
        verify(chain, never()).doFilter(req, res);
    }

    @Test
    void pending_mfaDisablePath_is403() throws Exception {
        authenticate();
        HttpServletRequest req = requestWithSession("/admin/api/mfa/disable", true);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(res.getContentAsString()).contains("mfa_required");
        verify(chain, never()).doFilter(req, res);
    }

    @Test
    void pending_mfaConfirmPath_is403() throws Exception {
        authenticate();
        HttpServletRequest req = requestWithSession("/admin/api/mfa/confirm", true);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(res.getContentAsString()).contains("mfa_required");
        verify(chain, never()).doFilter(req, res);
    }

    @Test
    void anonymous_evenWithPendingAttr_passesThrough() throws Exception {
        // Defensive: an unauthenticated principal must never be gated (the
        // attribute should never be set in that case, but verify the guard).
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymousUser",
                        AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        HttpServletRequest req = requestWithSession("/admin/api/tenants", true);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
    }
}
