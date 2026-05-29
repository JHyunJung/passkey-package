package com.crosscert.passkey.core.license;

import com.crosscert.passkey.core.vpd.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *      JVM (booting thread; per-request inheritance depends on
 *      TenantContextHolder's thread-local semantics — see comment below)
 *
 * Bootstrap failure -> Spring application context fails to start
 * (intentional: an onprem server with no valid license must not run).
 */
@Configuration
@ConditionalOnProperty(name = "passkey.deployment.mode", havingValue = "onprem")
public class LicenseBootstrap {

    private static final Logger log = LoggerFactory.getLogger(LicenseBootstrap.class);

    @Bean
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

        // Pin tenant for VPD context on the booting thread.
        //
        // WARNING: TenantContextHolder uses a ThreadLocal<UUID>. Setting it here
        // only affects the Spring bootstrap thread. Per-request VPD isolation
        // requires a request filter/interceptor to call TenantContextHolder.set()
        // on each incoming thread. In onprem (single-tenant) mode the licensed
        // tenantId must be injected on every request thread — this is a follow-on
        // task (e.g. OnpremTenantPinFilter or similar). The boot-thread pin below
        // ensures any startup-time DB calls (schema init, liquibase, etc.) flow
        // through the correct VPD context.
        //
        // See: DONE_WITH_CONCERNS — per-request pinning is not yet wired.
        TenantContextHolder.set(UUID.fromString(effective.tenantId()));
        log.warn("onprem mode: VPD tenant pinned on boot thread to {} — " +
                "per-request pinning requires a request filter that calls " +
                "TenantContextHolder.set() on each thread; not yet implemented",
                effective.tenantId());

        return new LicenseStateMachine(effective, verifiedAt, clock::instant);
    }
}
