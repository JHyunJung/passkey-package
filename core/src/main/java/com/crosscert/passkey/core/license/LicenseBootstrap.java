package com.crosscert.passkey.core.license;

import com.crosscert.passkey.core.vpd.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * Drives one-time license bootstrap in onprem mode:
 *   1. Read the JWS token from disk (passkey.license.path)
 *   2. Verify it
 *   3. Reconcile with the cache (use whichever has the later exp)
 *   4. Construct LicenseStateMachine
 *   5. Pin TenantContextHolder to the licensed tenantId for the
 *      booting thread (per-request pinning is handled by
 *      OnpremTenantPinFilter)
 *
 * Bootstrap failure -> Spring application context fails to start
 * (intentional: an onprem server with no valid license must not run).
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "passkey.deployment.mode", havingValue = "onprem")
public class LicenseBootstrap {

    @Bean
    @ConditionalOnMissingBean(LicenseStateMachine.class)
    public LicenseStateMachine licenseStateMachine(LicenseLoader loader,
                                                   LicenseVerifier verifier,
                                                   LicenseCache cache,
                                                   LicenseProperties props,
                                                   Clock clock) {
        String fromDisk = loader.load(props.path());
        LicenseToken diskToken = verifier.verify(fromDisk);
        log.info("license loaded from disk: jti={} expiresAt={} features={}",
                diskToken.jti(), diskToken.expiresAt(), diskToken.features());

        // Reconcile with cache: prefer whichever has the later exp.
        Optional<LicenseCache.Entry> cached = cache.read(props.cachePath());
        LicenseToken effective = diskToken;
        java.time.Instant verifiedAt = clock.instant();
        if (cached.isPresent()) {
            try {
                LicenseToken cachedToken = verifier.verify(cached.get().tokenJws());
                if (cachedToken.expiresAt().isAfter(diskToken.expiresAt())) {
                    effective = cachedToken;
                    verifiedAt = cached.get().lastVerifiedAt();
                    log.info("license cache supersedes disk: cached.exp={} disk.exp={}",
                            cachedToken.expiresAt(), diskToken.expiresAt());
                }
            } catch (LicenseVerificationException e) {
                log.warn("license cache invalid, ignoring: {}", e.getMessage());
            }
        }

        // Pin tenant for the booting thread (Liquibase, schema validation, etc.).
        // Per-request pinning is handled by OnpremTenantPinFilter so request
        // threads see the same tenant context.
        TenantContextHolder.set(UUID.fromString(effective.tenantId()));
        log.info("onprem mode: pinned VPD tenant to {} (per-request pinning via OnpremTenantPinFilter)",
                effective.tenantId());

        return new LicenseStateMachine(effective, verifiedAt, clock::instant);
    }
}
