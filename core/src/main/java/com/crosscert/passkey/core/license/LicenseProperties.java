package com.crosscert.passkey.core.license;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Configuration for on-prem license validation. All properties are
 * unused when passkey.deployment.mode=saas (no @ConditionalOnProperty
 * here on purpose — we want the binding to fail loudly if the YAML
 * shape is wrong, even in SaaS mode).
 */
@ConfigurationProperties(prefix = "passkey.license")
public record LicenseProperties(
        Path path,
        Path cachePath,
        String issuer,
        String audience,
        String heartbeatUrl,
        Duration heartbeatInterval
) {
    public LicenseProperties {
        if (path == null) path = Path.of("/etc/passkey/license.jwt");
        if (cachePath == null) cachePath = Path.of("/var/lib/passkey/license-cache.jwt");
        if (issuer == null) issuer = "license.crosscert.com";
        if (audience == null) audience = "passkey-onprem";
        if (heartbeatUrl == null) heartbeatUrl = "https://license.crosscert.com/v1/license";
        if (heartbeatInterval == null) heartbeatInterval = Duration.ofHours(1);
    }
}
