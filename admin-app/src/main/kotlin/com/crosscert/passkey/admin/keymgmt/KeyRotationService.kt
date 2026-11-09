package com.crosscert.passkey.admin.keymgmt

import com.crosscert.passkey.admin.audit.AuditAppendRequest
import com.crosscert.passkey.admin.audit.AuditLogService
import com.crosscert.passkey.admin.scheduler.SchedulerLeaseService
import com.crosscert.passkey.core.api.BusinessException
import com.crosscert.passkey.core.api.ErrorCode
import com.crosscert.passkey.core.jwt.KeyEnvelope
import com.crosscert.passkey.core.jwt.SigningKeyFactory
import com.crosscert.passkey.core.jwt.SigningKeyProvider
import com.crosscert.passkey.core.repository.SigningKeyRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.lang.management.ManagementFactory
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.util.Objects
import java.util.UUID

private val log = LoggerFactory.getLogger(KeyRotationService::class.java)

private const val LEASE_NAME = "key-rotation"
private val LEASE_TTL: Duration = Duration.ofSeconds(30)

/**
 * Generates a new ACTIVE signing key and transitions the existing one
 * to ROTATED. Protected by a SchedulerLeaseService lease so concurrent
 * admin clicks don't double-rotate.
 *
 * Ordering (codex T17 review fixes): transition-old + insert-new
 * happen in one @Transactional, with an explicit `saveAndFlush`
 * between them so Hibernate emits the UPDATE-old before the INSERT-new
 * (otherwise Hibernate's default action ordering — inserts before
 * updates — would trip `signing_key_one_active_uix`).
 *
 * Cache reload contract: [SigningKeyProvider.reload] is
 * scheduled via [TransactionSynchronizationManager] to run
 * AFTER the transaction commits. If audit append or commit fails,
 * the rotation rolls back and the in-memory cache stays pointing at
 * the still-ACTIVE old key — never at an uncommitted new key.
 *
 * Lease holder uniqueness: each invocation uses a fresh UUID
 * holder so a second concurrent admin click in the SAME JVM cannot
 * piggy-back on a still-held lease (SchedulerLeaseService permits
 * `holder=?` re-acquisition for owner refresh).
 */
@Service
class KeyRotationService(
    private val repo: SigningKeyRepository,
    private val provider: SigningKeyProvider,
    private val leases: SchedulerLeaseService,
    private val audit: AuditLogService,
    private val envelope: KeyEnvelope,
    private val clock: Clock,
) {

    @Transactional
    fun rotate(actorId: UUID?, actorEmail: String?): RotateResult {
        // Per-attempt holder: hostname/PID + a UUID so SchedulerLeaseService's
        // owner-renewal path (holder=?) cannot let a second same-JVM caller
        // piggy-back on a still-held lease.
        val holder = ManagementFactory.getRuntimeMXBean().name + "#" + UUID.randomUUID()
        if (!leases.tryAcquire(LEASE_NAME, holder, LEASE_TTL)) {
            throw BusinessException(ErrorCode.KEY_ROTATION_CONFLICT)
        }
        val old = repo.findFirstByStatusOrderByCreatedAtDesc("ACTIVE")
            .orElseThrow { BusinessException(ErrorCode.KEY_NO_ACTIVE) }

        // Inside the @Transactional: transition old → ROTATED then INSERT
        // new ACTIVE. saveAndFlush forces Hibernate to emit the UPDATE
        // before the subsequent INSERT, so the function-based unique
        // index `signing_key_one_active_uix` sees a single ACTIVE at any
        // moment. Both writes commit together.
        old.rotate(OffsetDateTime.now(clock))
        repo.saveAndFlush(old)

        val fresh = SigningKeyFactory.newRsaSigningKey(envelope)
        repo.save(fresh)

        val payload = LinkedHashMap<String, Any>()
        payload["oldKid"] = old.kid
        payload["newKid"] = fresh.kid
        audit.append(
            AuditAppendRequest(
                actorId, actorEmail, "SIGNING_KEY_ROTATE",
                "SIGNING_KEY", Objects.toString(fresh.id),
                null,
                payload,
            ),
        )

        // Schedule cache reload to run AFTER commit. If audit append (above)
        // or commit fails, the synchronization's afterCommit() is never
        // invoked — the in-memory cache keeps pointing at the old ACTIVE,
        // matching the rolled-back DB state.
        scheduleProviderReloadAfterCommit()

        log.info("key rotation: oldKid={} newKid={}", old.kid, fresh.kid)

        return RotateResult(old.kid, fresh.kid)
    }

    private fun scheduleProviderReloadAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        provider.reload()
                    }
                },
            )
        } else {
            // No active TX (tests / unmanaged callers). Reload inline so
            // unit-test verification still observes the call.
            provider.reload()
        }
    }

    data class RotateResult(val oldKid: String?, val newKid: String?)
}
