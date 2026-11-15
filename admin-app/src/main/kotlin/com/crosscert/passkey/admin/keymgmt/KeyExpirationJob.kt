package com.crosscert.passkey.admin.keymgmt

import com.crosscert.passkey.admin.audit.AuditAppendRequest
import com.crosscert.passkey.admin.audit.AuditLogService
import com.crosscert.passkey.admin.scheduler.SchedulerLeaseService
import com.crosscert.passkey.core.repository.SigningKeyRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.lang.management.ManagementFactory
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.util.Objects

private val log = LoggerFactory.getLogger(KeyExpirationJob::class.java)

private const val LEASE_NAME = "key-expiration"

/**
 * Periodic job that transitions ROTATED signing keys to REVOKED once
 * the grace period elapses. Default grace is PT30M
 * (`passkey.key-rotation.grace`); during grace, JWKS continues
 * to expose the ROTATED key so RPs holding JWTs signed by it can still
 * verify. After grace, the key is hidden from JWKS and any remaining
 * JWT signed by it fails verification.
 */
@Component
class KeyExpirationJob(
    private val repo: SigningKeyRepository,
    private val leases: SchedulerLeaseService,
    private val audit: AuditLogService,
    private val clock: Clock,
    @Value("\${passkey.key-rotation.grace:PT30M}") private val grace: Duration,
) {

    @Scheduled(
        fixedDelayString = "\${passkey.key-rotation.expiration-job.fixed-delay:PT1M}",
        initialDelayString = "\${passkey.key-rotation.expiration-job.initial-delay:PT45S}",
    )
    fun runOnce() {
        val holder = ManagementFactory.getRuntimeMXBean().name
        // release 하지 않는다 — 상태 기반(ROTATED 키를 REVOKED 로 바꾸는) 잡이라 해제해도 얻는 것이 없다.
        // TTL 30초가 기본 주기(1분)보다 짧아 다음 tick 에는 어느 인스턴스든 다시 잡을 수 있다.
        if (!leases.tryAcquire(LEASE_NAME, holder, Duration.ofSeconds(30))) {
            log.debug("KeyExpirationJob skipped — another instance holds the lease")
            return
        }
        val cutoff = OffsetDateTime.now(clock).minus(grace)
        val expired = repo.findAllByStatusAndRotatedAtBefore("ROTATED", cutoff)
        // 1분마다 돌므로 정리할 키가 없는 tick 은 DEBUG 로 낮춰 INFO 로그를 덮지 않게 한다.
        if (expired.isEmpty()) {
            log.debug("key expiration tick: candidates=0")
        } else {
            log.info("key expiration tick: candidates={}", expired.size)
        }
        val now = OffsetDateTime.now(clock)
        for (k in expired) {
            val ageMs = Duration.between(k.rotatedAt, now).toMillis()
            k.revoke(now)
            repo.save(k)
            val payload = LinkedHashMap<String, Any>()
            payload["kid"] = k.kid
            payload["rotatedAt"] = k.rotatedAt.toString()
            audit.append(
                AuditAppendRequest(
                    null, "(scheduler)", "SIGNING_KEY_REVOKE",
                    "SIGNING_KEY", Objects.toString(k.id),
                    null,
                    payload,
                ),
            )
            // SigningKey is global (no tenant column) — emit tenantId=global
            // to keep field shape consistent with the tenantId-keyed log lines.
            log.warn("key expired: tenantId=global kid={} ageMs={}", k.kid, ageMs)
        }
    }
}
