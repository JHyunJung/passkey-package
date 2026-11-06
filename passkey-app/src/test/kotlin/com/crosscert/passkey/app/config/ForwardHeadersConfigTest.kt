package com.crosscert.passkey.app.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource

/**
 * F-ratelimitProxy: asserts the shipped base `application.yml` enables
 * `server.forward-headers-strategy=framework` for passkey-app — a
 * unit-level substitute for a full Spring context load (Testcontainers/prod
 * secrets are unavailable in this environment), parsing the exact file
 * Spring Boot loads at runtime.
 *
 * Without this, [com.crosscert.passkey.app.security.RateLimitFilter]
 * buckets every request by `request.getRemoteAddr()`, which behind a
 * reverse proxy (docs/single-instance-deployment.md §3.1) is always the
 * proxy's own loopback address — every client collapses onto one IP bucket
 * and the rate limiter can no longer distinguish clients (shared-throttle
 * DoS / broken per-IP defense). Enabling FRAMEWORK registers Spring's
 * `ForwardedHeaderFilter`, which wraps the request so
 * `getRemoteAddr()` reflects the trusted `X-Forwarded-For` first
 * hop — no RateLimitFilter code change needed. dev/local (no proxy, no
 * header) is an unaffected no-op, mirroring the admin-app fix
 * (commit 44cb1fe8, G13).
 */
class ForwardHeadersConfigTest {

    private fun prop(classpathYml: String, key: String): Any? =
        YamlPropertySourceLoader().load(classpathYml, ClassPathResource(classpathYml))
            .firstNotNullOfOrNull { it.getProperty(key) }

    @Test
    fun baseApplicationYml_enablesForwardHeadersStrategy() {
        assertThat(prop("application.yml", "server.forward-headers-strategy")).isEqualTo("framework")
    }
}
