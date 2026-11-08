package com.crosscert.passkey.admin.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.security.web.csrf.DefaultCsrfToken

/**
 * F-cookieSecure: the XSRF-TOKEN cookie must carry `SameSite=Lax`
 * (parity with the Spring Session cookie's default) and its `Secure`
 * flag must track `request.isSecure()` rather than being hardcoded —
 * that auto-detection is what makes prod (behind a TLS-terminating LB, once
 * `server.forward-headers-strategy` resolves `isSecure()`
 * correctly — see G13) get Secure cookies while dev/local plain-http logins
 * keep working.
 */
class AdminSecurityConfigCsrfCookieTest {

    @Test
    fun csrfCookie_hasSameSiteLax() {
        val repo = AdminSecurityConfig.buildCsrfTokenRepository()
        val req = MockHttpServletRequest()
        req.isSecure = false
        val res = MockHttpServletResponse()
        val token: CsrfToken = DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "token-value")

        repo.saveToken(token, req, res)

        val cookie = res.getCookie("XSRF-TOKEN")
        assertThat(cookie).isNotNull()
        assertThat(checkNotNull(cookie).getAttribute("SameSite")).isEqualTo("Lax")
    }

    @Test
    fun csrfCookie_isNotSecure_whenRequestIsPlainHttp() {
        val repo = AdminSecurityConfig.buildCsrfTokenRepository()
        val req = MockHttpServletRequest()
        req.isSecure = false // dev/local: no TLS, no forwarded-proto header
        val res = MockHttpServletResponse()
        val token: CsrfToken = DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "token-value")

        repo.saveToken(token, req, res)

        assertThat(checkNotNull(res.getCookie("XSRF-TOKEN")).secure).isFalse()
    }

    @Test
    fun csrfCookie_isSecure_whenRequestIsSecure() {
        // Simulates the prod topology once server.forward-headers-strategy
        // resolves X-Forwarded-Proto: https into request.isSecure()=true.
        val repo = AdminSecurityConfig.buildCsrfTokenRepository()
        val req = MockHttpServletRequest()
        req.isSecure = true
        val res = MockHttpServletResponse()
        val token: CsrfToken = DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "token-value")

        repo.saveToken(token, req, res)

        assertThat(checkNotNull(res.getCookie("XSRF-TOKEN")).secure).isTrue()
    }

    @Test
    fun csrfCookie_scopedToAdminPath() {
        val repo = AdminSecurityConfig.buildCsrfTokenRepository()
        val req = MockHttpServletRequest()
        val res = MockHttpServletResponse()
        val token: CsrfToken = DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "token-value")

        repo.saveToken(token, req, res)

        assertThat(checkNotNull(res.getCookie("XSRF-TOKEN")).path).isEqualTo("/admin")
    }
}
