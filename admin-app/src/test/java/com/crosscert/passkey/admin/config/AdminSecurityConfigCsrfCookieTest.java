package com.crosscert.passkey.admin.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-cookieSecure: the XSRF-TOKEN cookie must carry {@code SameSite=Lax}
 * (parity with the Spring Session cookie's default) and its {@code Secure}
 * flag must track {@code request.isSecure()} rather than being hardcoded —
 * that auto-detection is what makes prod (behind a TLS-terminating LB, once
 * {@code server.forward-headers-strategy} resolves {@code isSecure()}
 * correctly — see G13) get Secure cookies while dev/local plain-http logins
 * keep working.
 */
class AdminSecurityConfigCsrfCookieTest {

    @Test
    void csrfCookie_hasSameSiteLax() {
        var repo = AdminSecurityConfig.buildCsrfTokenRepository();
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setSecure(false);
        MockHttpServletResponse res = new MockHttpServletResponse();
        CsrfToken token = new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "token-value");

        repo.saveToken(token, req, res);

        var cookie = res.getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
    }

    @Test
    void csrfCookie_isNotSecure_whenRequestIsPlainHttp() {
        var repo = AdminSecurityConfig.buildCsrfTokenRepository();
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setSecure(false); // dev/local: no TLS, no forwarded-proto header
        MockHttpServletResponse res = new MockHttpServletResponse();
        CsrfToken token = new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "token-value");

        repo.saveToken(token, req, res);

        assertThat(res.getCookie("XSRF-TOKEN").getSecure()).isFalse();
    }

    @Test
    void csrfCookie_isSecure_whenRequestIsSecure() {
        // Simulates the prod topology once server.forward-headers-strategy
        // resolves X-Forwarded-Proto: https into request.isSecure()=true.
        var repo = AdminSecurityConfig.buildCsrfTokenRepository();
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setSecure(true);
        MockHttpServletResponse res = new MockHttpServletResponse();
        CsrfToken token = new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "token-value");

        repo.saveToken(token, req, res);

        assertThat(res.getCookie("XSRF-TOKEN").getSecure()).isTrue();
    }

    @Test
    void csrfCookie_scopedToAdminPath() {
        var repo = AdminSecurityConfig.buildCsrfTokenRepository();
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        CsrfToken token = new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "token-value");

        repo.saveToken(token, req, res);

        assertThat(res.getCookie("XSRF-TOKEN").getPath()).isEqualTo("/admin");
    }
}
