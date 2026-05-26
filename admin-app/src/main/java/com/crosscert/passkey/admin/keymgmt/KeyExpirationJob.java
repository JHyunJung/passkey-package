package com.crosscert.passkey.admin.keymgmt;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.admin.scheduler.SchedulerLeaseService;
import com.crosscert.passkey.core.entity.SigningKey;
import com.crosscert.passkey.core.repository.SigningKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Periodic job that transitions ROTATED signing keys to REVOKED once
 * the grace period elapses. Default grace is PT30M
 * ({@code passkey.key-rotation.grace}); during grace, JWKS continues
 * to expose the ROTATED key so RPs holding JWTs signed by it can still
 * verify. After grace, the key is hidden from JWKS and any remaining
 * JWT signed by it fails verification.
 */
@Component
public class KeyExpirationJob {

    private static final Logger log = LoggerFactory.getLogger(KeyExpirationJob.class);
    private static final String LEASE_NAME = "key-expiration";

    private final SigningKeyRepository repo;
    private final SchedulerLeaseService leases;
    private final AuditLogService audit;
    private final Clock clock;
    private final Duration grace;

    public KeyExpirationJob(SigningKeyRepository repo,
                            SchedulerLeaseService leases,
                            AuditLogService audit,
                            Clock clock,
                            @Value("${passkey.key-rotation.grace:PT30M}") Duration grace) {
        this.repo = repo;
        this.leases = leases;
        this.audit = audit;
        this.clock = clock;
        this.grace = grace;
    }

    @Scheduled(
            fixedDelayString = "${passkey.key-rotation.expiration-job.fixed-delay:PT1M}",
            initialDelayString = "${passkey.key-rotation.expiration-job.initial-delay:PT45S}")
    public void runOnce() {
        String holder = ManagementFactory.getRuntimeMXBean().getName();
        if (!leases.tryAcquire(LEASE_NAME, holder, Duration.ofSeconds(30))) {
            log.debug("KeyExpirationJob skipped — another instance holds the lease");
            return;
        }
        Instant cutoff = clock.instant().minus(grace);
        List<SigningKey> expired = repo.findAllByStatusAndRotatedAtBefore("ROTATED", cutoff);
        for (SigningKey k : expired) {
            k.revoke(clock.instant());
            repo.save(k);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("kid", k.getKid());
            payload.put("rotatedAt", k.getRotatedAt().toString());
            audit.append(new AuditAppendRequest(
                    0L, "(scheduler)", "SIGNING_KEY_REVOKE",
                    "SIGNING_KEY", String.valueOf(k.getId()), payload));
            log.info("Revoked signing key kid={} after grace period", k.getKid());
        }
    }
}
