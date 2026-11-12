package com.crosscert.passkey.admin.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource

/**
 * F-cookieSecure / G13: asserts the actual shipped YAML resources carry the
 * session/CSRF cookie hardening and forward-headers wiring — a unit-level
 * substitute for a full Spring context load (Testcontainers/prod secrets are
 * unavailable in this environment), parsing the exact files Spring Boot
 * loads at runtime.
 */
class CookieSecurityProfileConfigTest {

    private fun prop(classpathYml: String, key: String): Any? {
        val loader = YamlPropertySourceLoader()
        val sources = loader.load(classpathYml, ClassPathResource(classpathYml))
        for (src in sources) {
            val v = src.getProperty(key)
            if (v != null) return v
        }
        return null
    }

    @Test
    fun baseApplicationYml_enablesForwardHeadersStrategy() {
        // G13: without this, request.getRemoteAddr()/isSecure() only ever see
        // the LB's TCP peer address/plain-http scheme behind a reverse proxy.
        assertThat(prop("application.yml", "server.forward-headers-strategy"))
            .isEqualTo("native")
    }

    @Test
    fun prodYml_forcesSecureSessionCookie() {
        assertThat(prop("application-prod.yml", "server.servlet.session.cookie.secure"))
            .isEqualTo(true)
        assertThat(prop("application-prod.yml", "server.servlet.session.cookie.same-site"))
            .isEqualTo("lax")
    }

    @Test
    fun qaYml_forcesSecureSessionCookie() {
        assertThat(prop("application-qa.yml", "server.servlet.session.cookie.secure"))
            .isEqualTo(true)
        assertThat(prop("application-qa.yml", "server.servlet.session.cookie.same-site"))
            .isEqualTo("lax")
    }

    @Test
    fun devYml_doesNotForceSecureCookie() {
        // dev/local run over plain http with no TLS-terminating proxy in front —
        // forcing Secure here would silently drop the session cookie and break
        // local login. Must stay unset (auto-detects to false via isSecure()).
        assertThat(prop("application-dev.yml", "server.servlet.session.cookie.secure")).isNull()
    }

    @Test
    fun localYml_doesNotForceSecureCookie() {
        assertThat(prop("application-local.yml", "server.servlet.session.cookie.secure")).isNull()
    }
}
