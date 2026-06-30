package com.crosscert.passkey.core.license;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.Optional;

/**
 * Drives one-time license bootstrap in onprem mode:
 *   1. Read the JWS token from disk (passkey.license.path)
 *   2. Verify it
 *   3. Reconcile with the cache (use whichever has the later exp)
 *   4. Construct LicenseStateMachine
 *
 * The licensed tenant is applied per-request by OnpremTenantPinFilter;
 * this bootstrap does not pin the booting thread (no boot-time work reads
 * the tenant context — see the note at the end of the @Bean method).
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

        // No boot-thread tenant pin: nothing executed synchronously after this
        // @Bean returns reads TenantContextHolder. Flyway migrations run via raw
        // JDBC and the signing-key bootstrap uses a definer-rights PL/SQL package
        // — neither consults the Hibernate tenantFilter. The only boot-time
        // @PostConstruct (SigningKeyProvider) reads the non-tenant signing_key
        // table. Per-request pinning is handled by OnpremTenantPinFilter, so
        // request threads always see the licensed tenant. Setting (and never
        // clearing) the ThreadLocal here would leak the tenant onto the boot
        // thread without any consumer needing it.
        log.info("onprem mode: licensed tenant {} (per-request pinning via OnpremTenantPinFilter)",
                effective.tenantId());

        return new LicenseStateMachine(effective, verifiedAt, clock::instant);
    }
}
