package com.crosscert.passkey.admin.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-cookieSecure / G13: asserts the actual shipped YAML resources carry the
 * session/CSRF cookie hardening and forward-headers wiring — a unit-level
 * substitute for a full Spring context load (Testcontainers/prod secrets are
 * unavailable in this environment), parsing the exact files Spring Boot
 * loads at runtime.
 */
class CookieSecurityProfileConfigTest {

    private Object prop(String classpathYml, String key) throws Exception {
        var loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load(classpathYml, new ClassPathResource(classpathYml));
        for (PropertySource<?> src : sources) {
            Object v = src.getProperty(key);
            if (v != null) return v;
        }
        return null;
    }

    @Test
    void baseApplicationYml_enablesForwardHeadersStrategy() throws Exception {
        // G13: without this, request.getRemoteAddr()/isSecure() only ever see
        // the LB's TCP peer address/plain-http scheme behind a reverse proxy.
        assertThat(prop("application.yml", "server.forward-headers-strategy"))
                .isEqualTo("framework");
    }

    @Test
    void prodYml_forcesSecureSessionCookie() throws Exception {
        assertThat(prop("application-prod.yml", "server.servlet.session.cookie.secure"))
                .isEqualTo(true);
        assertThat(prop("application-prod.yml", "server.servlet.session.cookie.same-site"))
                .isEqualTo("lax");
    }

    @Test
    void qaYml_forcesSecureSessionCookie() throws Exception {
        assertThat(prop("application-qa.yml", "server.servlet.session.cookie.secure"))
                .isEqualTo(true);
        assertThat(prop("application-qa.yml", "server.servlet.session.cookie.same-site"))
                .isEqualTo("lax");
    }

    @Test
    void devYml_doesNotForceSecureCookie() throws Exception {
        // dev/local run over plain http with no TLS-terminating proxy in front —
        // forcing Secure here would silently drop the session cookie and break
        // local login. Must stay unset (auto-detects to false via isSecure()).
        assertThat(prop("application-dev.yml", "server.servlet.session.cookie.secure")).isNull();
    }

    @Test
    void localYml_doesNotForceSecureCookie() throws Exception {
        assertThat(prop("application-local.yml", "server.servlet.session.cookie.secure")).isNull();
    }
}
