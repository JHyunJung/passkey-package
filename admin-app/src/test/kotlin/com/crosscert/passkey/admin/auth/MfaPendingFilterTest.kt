package com.crosscert.passkey.admin.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpSession
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.security.core.context.SecurityContextHolder

class MfaPendingFilterTest {

    private val filter = MfaPendingFilter()

    @AfterEach
    fun clearContext() {
        SecurityContextHolder.clearContext()
    }

    private fun authenticate() {
        SecurityContextHolder.getContext().authentication =
            TestingAuthenticationToken("alice@example.com", "x", "ROLE_PLATFORM_OPERATOR")
    }

    private fun requestWithSession(uri: String, pending: Boolean): HttpServletRequest {
        val req: HttpServletRequest = mock()
        val session: HttpSession = mock()
        whenever(req.requestURI).thenReturn(uri)
        whenever(req.getSession(false)).thenReturn(session)
        whenever(session.getAttribute(MfaPendingFilter.MFA_PENDING_ATTR))
            .thenReturn(if (pending) java.lang.Boolean.TRUE else null)
        return req
    }

    @Test
    fun pending_nonMfaPath_is403_andChainNotCalled() {
        authenticate()
        val req = requestWithSession("/admin/api/tenants", true)
        val res = MockHttpServletResponse()
        val chain: FilterChain = mock()

        filter.doFilter(req, res, chain)

        assertThat(res.status).isEqualTo(HttpServletResponse.SC_FORBIDDEN)
        assertThat(res.contentAsString).contains("mfa_required")
        verify(chain, never()).doFilter(req, res)
    }

    @Test
    fun pending_mfaVerifyPath_passesThrough() {
        authenticate()
        val req = requestWithSession("/admin/api/mfa/verify", true)
        val res = MockHttpServletResponse()
        val chain: FilterChain = mock()

        filter.doFilter(req, res, chain)

        verify(chain, times(1)).doFilter(req, res)
        assertThat(res.status).isEqualTo(HttpServletResponse.SC_OK)
    }

    @Test
    fun pending_logoutPath_passesThrough() {
        authenticate()
        val req = requestWithSession("/admin/logout", true)
        val res = MockHttpServletResponse()
        val chain: FilterChain = mock()

        filter.doFilter(req, res, chain)

        verify(chain, times(1)).doFilter(req, res)
    }

    @Test
    fun pending_mePath_passesThrough() {
        // The SPA must read /admin/api/me even while MFA-pending so it can
        // discover it needs to show the MFA challenge.
        authenticate()
        val req = requestWithSession("/admin/api/me", true)
        val res = MockHttpServletResponse()
        val chain: FilterChain = mock()

        filter.doFilter(req, res, chain)

        verify(chain, times(1)).doFilter(req, res)
        assertThat(res.status).isEqualTo(HttpServletResponse.SC_OK)
    }

    @Test
    fun notPending_passesThrough() {
        authenticate()
        val req = requestWithSession("/admin/api/tenants", false)
        val res = MockHttpServletResponse()
        val chain: FilterChain = mock()

        filter.doFilter(req, res, chain)

        verify(chain, times(1)).doFilter(req, res)
    }

    @Test
    fun noSession_passesThrough() {
        authenticate()
        val req: HttpServletRequest = mock()
        whenever(req.requestURI).thenReturn("/admin/api/tenants")
        whenever(req.getSession(false)).thenReturn(null)
        val res = MockHttpServletResponse()
        val chain: FilterChain = mock()

        filter.doFilter(req, res, chain)

        verify(chain, times(1)).doFilter(req, res)
    }

    @Test
    fun pending_mfaEnrollPath_is403() {
        authenticate()
        val req = requestWithSession("/admin/api/mfa/enroll", true)
        val res = MockHttpServletResponse()
        val chain: FilterChain = mock()

        filter.doFilter(req, res, chain)

        assertThat(res.status).isEqualTo(HttpServletResponse.SC_FORBIDDEN)
        assertThat(res.contentAsString).contains("mfa_required")
        verify(chain, never()).doFilter(req, res)
    }

    @Test
    fun pending_mfaDisablePath_is403() {
        authenticate()
        val req = requestWithSession("/admin/api/mfa/disable", true)
        val res = MockHttpServletResponse()
        val chain: FilterChain = mock()

        filter.doFilter(req, res, chain)

        assertThat(res.status).isEqualTo(HttpServletResponse.SC_FORBIDDEN)
        assertThat(res.contentAsString).contains("mfa_required")
        verify(chain, never()).doFilter(req, res)
    }

    @Test
    fun pending_mfaConfirmPath_is403() {
        authenticate()
        val req = requestWithSession("/admin/api/mfa/confirm", true)
        val res = MockHttpServletResponse()
        val chain: FilterChain = mock()

        filter.doFilter(req, res, chain)

        assertThat(res.status).isEqualTo(HttpServletResponse.SC_FORBIDDEN)
        assertThat(res.contentAsString).contains("mfa_required")
        verify(chain, never()).doFilter(req, res)
    }

    @Test
    fun anonymous_evenWithPendingAttr_passesThrough() {
        // Defensive: an unauthenticated principal must never be gated (the
        // attribute should never be set in that case, but verify the guard).
        SecurityContextHolder.getContext().authentication =
            AnonymousAuthenticationToken(
                "key", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"),
            )
        val req = requestWithSession("/admin/api/tenants", true)
        val res = MockHttpServletResponse()
        val chain: FilterChain = mock()

        filter.doFilter(req, res, chain)

        verify(chain, times(1)).doFilter(req, res)
    }
}
