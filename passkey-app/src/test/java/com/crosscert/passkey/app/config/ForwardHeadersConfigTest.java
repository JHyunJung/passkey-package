package com.crosscert.passkey.app.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-ratelimitProxy: asserts the shipped base {@code application.yml} enables
 * {@code server.forward-headers-strategy=framework} for passkey-app — a
 * unit-level substitute for a full Spring context load (Testcontainers/prod
 * secrets are unavailable in this environment), parsing the exact file
 * Spring Boot loads at runtime.
 *
 * <p>Without this, {@link com.crosscert.passkey.app.security.RateLimitFilter}
 * buckets every request by {@code request.getRemoteAddr()}, which behind a
 * reverse proxy (docs/single-instance-deployment.md §3.1) is always the
 * proxy's own loopback address — every client collapses onto one IP bucket
 * and the rate limiter can no longer distinguish clients (shared-throttle
 * DoS / broken per-IP defense). Enabling FRAMEWORK registers Spring's
 * {@code ForwardedHeaderFilter}, which wraps the request so
 * {@code getRemoteAddr()} reflects the trusted {@code X-Forwarded-For} first
 * hop — no RateLimitFilter code change needed. dev/local (no proxy, no
 * header) is an unaffected no-op, mirroring the admin-app fix
 * (commit 44cb1fe8, G13).
 */
class ForwardHeadersConfigTest {

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
        assertThat(prop("application.yml", "server.forward-headers-strategy"))
                .isEqualTo("framework");
    }
}
