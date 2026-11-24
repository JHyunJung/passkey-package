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

    private fun requestWithSession(
        uri: String,
        pending: Boolean,
        attr: String = MfaPendingFilter.MFA_PENDING_ATTR,
        method: String? = null,
        htmx: Boolean = false,
    ): HttpServletRequest {
        val req: HttpServletRequest = mock()
        val session: HttpSession = mock()
        whenever(req.requestURI).thenReturn(uri)
        whenever(req.method).thenReturn(method)
        whenever(req.getHeader("HX-Request")).thenReturn(if (htmx) "true" else null)
        whenever(req.getSession(false)).thenReturn(session)
        whenever(session.getAttribute(attr))
            .thenReturn(if (pending) java.lang.Boolean.TRUE else null)
        return req
    }

    private fun enrollRequiredRequest(uri: String, method: String? = null, htmx: Boolean = false) =
        requestWithSession(uri, true, MfaSessionState.ENROLL_REQUIRED_ATTR, method, htmx)

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
        // The console must be able to read /admin/api/me even while MFA-pending so it can
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

    @Test
    fun pending_loginMfaEnrollPath_isRedirected() {
        authenticate()
        val req = requestWithSession("/admin/login/mfa/enroll", true, method = "POST")
        val res = MockHttpServletResponse()
        val chain: FilterChain = mock()

        filter.doFilter(req, res, chain)

        assertThat(res.redirectedUrl).isEqualTo("/admin/login")
        verify(chain, never()).doFilter(req, res)
    }

    @Test
    fun enrollRequired_allowList_passesThrough() {
        val allowed = listOf(
            "GET" to "/admin/login",
            "POST" to "/admin/login/mfa/enroll",
            "POST" to "/admin/login/mfa/confirm",
            "GET" to "/admin/api/me",
            "POST" to "/admin/logout",
            "GET" to "/admin/css/app.css",
            "GET" to "/admin/js/app.js",
            "GET" to "/admin/fonts/a.woff2",
            "GET" to "/admin/img/a.png",
        )
        for ((method, uri) in allowed) {
            authenticate()
            val req = enrollRequiredRequest(uri, method)
            val res = MockHttpServletResponse()
            val chain: FilterChain = mock()

            filter.doFilter(req, res, chain)

            verify(chain, times(1)).doFilter(req, res)
        }
    }

    @Test
    fun enrollRequired_api_is403_mfaEnrollRequired() {
        for (uri in listOf(
            "/admin/api/tenants", "/admin/api/mfa/enroll", "/admin/api/mfa/verify", "/admin/api/mfa/confirm",
        )) {
            authenticate()
            val req = enrollRequiredRequest(uri, "POST")
            val res = MockHttpServletResponse()
            val chain: FilterChain = mock()

            filter.doFilter(req, res, chain)

            assertThat(res.status).isEqualTo(HttpServletResponse.SC_FORBIDDEN)
            assertThat(res.contentAsString).isEqualTo("{\"error\":\"mfa_enroll_required\"}")
            verify(chain, never()).doFilter(req, res)
        }
    }

    @Test
    fun enrollRequired_screen_redirectsToLogin() {
        authenticate()
        val req1 = enrollRequiredRequest("/admin", "GET")
        val res1 = MockHttpServletResponse()
        val chain1: FilterChain = mock()
        filter.doFilter(req1, res1, chain1)
        assertThat(res1.redirectedUrl).isEqualTo("/admin/login")
        verify(chain1, never()).doFilter(req1, res1)

        val req2 = enrollRequiredRequest("/admin/_/settings/tab/account", "GET", htmx = true)
        val res2 = MockHttpServletResponse()
        val chain2: FilterChain = mock()
        filter.doFilter(req2, res2, chain2)
        assertThat(res2.getHeader("HX-Redirect")).isEqualTo("/admin/login")
        verify(chain2, never()).doFilter(req2, res2)

        // 대기(pending) 전용 제출 경로는 등록 필요 세션에선 막힌다.
        val req3 = enrollRequiredRequest("/admin/login/mfa", "POST")
        val res3 = MockHttpServletResponse()
        val chain3: FilterChain = mock()
        filter.doFilter(req3, res3, chain3)
        assertThat(res3.redirectedUrl).isEqualTo("/admin/login")
        verify(chain3, never()).doFilter(req3, res3)
    }
}
