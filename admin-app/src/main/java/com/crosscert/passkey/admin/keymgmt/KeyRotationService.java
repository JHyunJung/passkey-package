package com.crosscert.passkey.admin.keymgmt;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.admin.scheduler.SchedulerLeaseService;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import com.crosscert.passkey.core.entity.SigningKey;
import com.crosscert.passkey.core.jwt.KeyEnvelope;
import com.crosscert.passkey.core.jwt.SigningKeyProvider;
import com.crosscert.passkey.core.repository.SigningKeyRepository;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.management.ManagementFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Generates a new ACTIVE signing key and transitions the existing one
 * to ROTATED. Protected by a SchedulerLeaseService lease so concurrent
 * admin clicks don't double-rotate.
 *
 * <p>Ordering (codex T17 review fixes): transition-old + insert-new
 * happen in one @Transactional, with an explicit {@code saveAndFlush}
 * between them so Hibernate emits the UPDATE-old before the INSERT-new
 * (otherwise Hibernate's default action ordering — inserts before
 * updates — would trip {@code signing_key_one_active_uix}).
 *
 * <p>Cache reload contract: {@link SigningKeyProvider#reload()} is
 * scheduled via {@link TransactionSynchronizationManager} to run
 * AFTER the transaction commits. If audit append or commit fails,
 * the rotation rolls back and the in-memory cache stays pointing at
 * the still-ACTIVE old key — never at an uncommitted new key.
 *
 * <p>Lease holder uniqueness: each invocation uses a fresh UUID
 * holder so a second concurrent admin click in the SAME JVM cannot
 * piggy-back on a still-held lease (SchedulerLeaseService permits
 * {@code holder=?} re-acquisition for owner refresh).
 */
@Service
public class KeyRotationService {

    private static final String LEASE_NAME = "key-rotation";
    private static final Duration LEASE_TTL = Duration.ofSeconds(30);

    private final SigningKeyRepository repo;
    private final SigningKeyProvider provider;
    private final SchedulerLeaseService leases;
    private final AuditLogService audit;
    private final KeyEnvelope envelope;
    private final Clock clock;

    public KeyRotationService(SigningKeyRepository repo,
                              SigningKeyProvider provider,
                              SchedulerLeaseService leases,
                              AuditLogService audit,
                              KeyEnvelope envelope,
                              Clock clock) {
        this.repo = repo;
        this.provider = provider;
        this.leases = leases;
        this.audit = audit;
        this.envelope = envelope;
        this.clock = clock;
    }

    @Transactional
    public RotateResult rotate(UUID actorId, String actorEmail) {
        // Per-attempt holder: hostname/PID + a UUID so SchedulerLeaseService's
        // owner-renewal path (holder=?) cannot let a second same-JVM caller
        // piggy-back on a still-held lease.
        String holder = ManagementFactory.getRuntimeMXBean().getName()
                + "#" + UUID.randomUUID();
        if (!leases.tryAcquire(LEASE_NAME, holder, LEASE_TTL)) {
            throw new BusinessException(ErrorCode.KEY_ROTATION_CONFLICT);
        }
        SigningKey old = repo.findFirstByStatusOrderByCreatedAtDesc("ACTIVE")
                .orElseThrow(() -> new BusinessException(ErrorCode.KEY_NO_ACTIVE));

        // Inside the @Transactional: transition old → ROTATED then INSERT
        // new ACTIVE. saveAndFlush forces Hibernate to emit the UPDATE
        // before the subsequent INSERT, so the function-based unique
        // index `signing_key_one_active_uix` sees a single ACTIVE at any
        // moment. Both writes commit together.
        old.rotate(clock.instant());
        repo.saveAndFlush(old);

        SigningKey fresh = generateFreshActive();
        repo.save(fresh);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("oldKid", old.getKid());
        payload.put("newKid", fresh.getKid());
        audit.append(new AuditAppendRequest(
                actorId, actorEmail, "SIGNING_KEY_ROTATE",
                "SIGNING_KEY", String.valueOf(fresh.getId()), payload));

        // Schedule cache reload to run AFTER commit. If audit append (above)
        // or commit fails, the synchronization's afterCommit() is never
        // invoked — the in-memory cache keeps pointing at the old ACTIVE,
        // matching the rolled-back DB state.
        scheduleProviderReloadAfterCommit();

        return new RotateResult(old.getKid(), fresh.getKid());
    }

    private void scheduleProviderReloadAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            provider.reload();
                        }
                    });
        } else {
            // No active TX (tests / unmanaged callers). Reload inline so
            // unit-test verification still observes the call.
            provider.reload();
        }
    }

    private SigningKey generateFreshActive() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair pair = gen.generateKeyPair();
            RSAKey withoutKid = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
            String kid = withoutKid.computeThumbprint().toString();
            RSAKey rsa = new RSAKey.Builder(withoutKid).keyID(kid).build();
            return new SigningKey(kid, "RS256", rsa.toPublicJWK().toJSONString(),
                    envelope.seal(pair.getPrivate().getEncoded()));
        } catch (Exception e) {
            throw new IllegalStateException("rotation key generation failed", e);
        }
    }

    public record RotateResult(String oldKid, String newKid) {}
}
