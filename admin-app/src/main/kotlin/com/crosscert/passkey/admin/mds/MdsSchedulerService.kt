package com.crosscert.passkey.admin.mds

import com.crosscert.passkey.admin.audit.AuditAppendRequest
import com.crosscert.passkey.admin.audit.AuditLogService
import com.crosscert.passkey.admin.scheduler.SchedulerLeaseService
import com.crosscert.passkey.core.alert.SecurityAlertEvent
import com.crosscert.passkey.core.mds.MdsAaguidCache
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.lang.management.ManagementFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant

private val log = LoggerFactory.getLogger(MdsSchedulerService::class.java)

private const val LEASE_NAME = "mds-sync"
private const val SYNC_COUNTER = "passkey_mds_sync_total"

/**
 * Orchestrates a single MDS sync cycle: acquire lease → fetch BLOB →
 * persist → invalidate AAGUID cache → audit. Returns [SyncResult]
 * so the controller (admin force-sync) and the @Scheduled job can
 * both report status.
 *
 * T16 extends this to also write per-AAGUID entries back to Redis.
 */
@Service
class MdsSchedulerService(
    private val leases: SchedulerLeaseService,
    private val client: MdsBlobClient,
    private val store: MdsBlobStore,
    private val redis: StringRedisTemplate,
    private val audit: AuditLogService,
    private val historyService: MdsHistoryService,
    private val clock: Clock,
    private val meterRegistry: MeterRegistry,
    private val eventPublisher: ApplicationEventPublisher,
    @Value("\${passkey.mds.lease-holder:default}") configuredHolder: String,
) {

    // Default holder = PID@host (ManagementFactory), unique per JVM.
    private val holder: String =
        if ("default" == configuredHolder) ManagementFactory.getRuntimeMXBean().name else configuredHolder

    fun runOnce(): SyncResult {
        val scheduledAt = Instant.now()
        log.info("mds sync started: scheduledAt={}", scheduledAt)
        if (!leases.tryAcquire(LEASE_NAME, holder, Duration.ofMinutes(5))) {
            log.info("mds sync skipped: reason=lease-held")
            // SKIPPED: peer instance owns the lease and will record its own
            // history row. Recording here would double-count cycles.
            return SyncResult.skipped()
        }
        log.info("mds sync: lease acquired (holder={})", holder)
        // Capture start BEFORE any work so duration covers fetch + persist + cache.
        val started = scheduledAt
        val result: SyncResult = try {
            val fetched = client.fetch()
            val blob = fetched.blob
            // BLOB 메타데이터(version/next_update/fetched_at) 저장.
            store.store(blob)

            // G07: record the pre-cycle key set but do NOT delete it yet.
            // Deleting before repopulating opened a window where every
            // "mds:aaguid:*" key was absent from Redis — any lookup landing
            // in that window (registration/finish for an mdsRequired
            // tenant) missed the cache and failed closed. Populate-first,
            // stale-cleanup-after removes that window entirely: a key that
            // existed before the cycle either still holds its previous
            // (possibly stale-but-valid) value or has already been
            // overwritten with the fresh one — it is never simply gone.
            val previousKeys: Set<String>? = redis.keys("mds:aaguid:*")
            val previousCount = previousKeys?.size ?: 0

            // Populate per-AAGUID cache entries (mirrors passkey-app's
            // MdsAaguidCache key format: "mds:aaguid:<UUID>" → CSV of
            // status strings). Statuses are ordered as the MDS BLOB
            // returns them (most recent last); MdsVerifier only inspects
            // the last entry per FIDO MDS spec §5.4.
            //
            // TTL 7h > scheduler cadence of 6h: entries stay live until
            // the next sync cycle repopulates them. A 30min TTL would leave
            // a ~5.5h stale-miss window that fails closed.
            var populated = 0
            var skippedLegacy = 0
            val freshKeys = HashSet<String>()
            for (entry in blob.entries()) {
                if (entry.aaguid() == null) {
                    skippedLegacy++ // legacy U2F
                    continue
                }
                // Reuse MdsAaguidCache.canonicalAaguid so the Redis key UUID
                // matches passkey-app's lookup exactly.
                val uuid = MdsAaguidCache.canonicalAaguid(entry.aaguid()).toString()
                // Java String.isBlank 와 같은 판정(Character.isWhitespace) — Kotlin isBlank 는 NBSP 등도 공백으로 본다.
                val csv = entry.statusReports()
                    .map { it.status() }
                    .filter { s -> s != null && !s.all(Character::isWhitespace) }
                    .joinToString(",")
                if (!csv.all(Character::isWhitespace)) {
                    val key = "mds:aaguid:$uuid"
                    redis.opsForValue().set(key, csv, Duration.ofHours(7))
                    freshKeys.add(key)
                    populated++
                }
            }

            // Stale-key cleanup: only AAGUIDs present before this cycle but
            // absent from the new BLOB (revoked/dropped entries) are removed —
            // and only now, after every surviving/updated key has already
            // been (re)written above.
            if (previousKeys != null && previousKeys.isNotEmpty()) {
                val staleKeys = HashSet(previousKeys)
                staleKeys.removeAll(freshKeys)
                if (staleKeys.isNotEmpty()) {
                    redis.delete(staleKeys)
                }
            }

            // Diff approximation: previousCount = AAGUID keys present
            // before this cycle; populated = entries written this cycle.
            // Net delta surfaces churn even without a per-entry compare.
            log.info(
                "mds sync: entries diff populated={} previousCacheSize={} skippedLegacy={}",
                populated, previousCount, skippedLegacy,
            )

            val version = blob.no().toLong()
            val payload = LinkedHashMap<String, Any>()
            payload["version"] = version
            payload["fetchedAt"] = clock.instant().toString()
            // Actor null = scheduler (no human operator).
            audit.append(
                AuditAppendRequest(
                    null, "(scheduler)", "MDS_BLOB_SYNC", null, null,
                    null,
                    payload,
                ),
            )

            val durMs = Duration.between(started, Instant.now()).toMillis()
            log.info("mds sync ok: version={} durMs={}", version, durMs)
            meterRegistry.counter(SYNC_COUNTER, "result", "success").increment()
            SyncResult.synced(version)
        } catch (e: RuntimeException) {
            log.error("mds sync failed: cause={}", e.toString(), e)
            meterRegistry.counter(SYNC_COUNTER, "result", "failure").increment()
            eventPublisher.publishEvent(
                SecurityAlertEvent(
                    SecurityAlertEvent.AlertType.MDS_SYNC_FAILURE,
                    SecurityAlertEvent.Severity.HIGH,
                    "mds sync failed",
                    mapOf("cause" to e.toString()),
                ),
            )
            SyncResult.failed(e.message)
        }
        // Best-effort history append; never disrupts the sync result.
        // Pulls status/version/error from SyncResult so SYNCED + FAILED
        // paths share the same code (correct field order: version, status, error).
        safeRecord(started, result.version, result.status, null, result.error)
        return result
    }

    private fun safeRecord(
        startedAt: Instant,
        version: Long?,
        status: String?,
        changeSummary: String?,
        errorMessage: String?,
    ) {
        try {
            val finishedAt = Instant.now()
            val durMs = Duration.between(startedAt, finishedAt).toMillis()
            val durationMs: Int? = if (durMs > Int.MAX_VALUE) null else durMs.toInt()
            historyService.append(
                startedAt, finishedAt, version, status, changeSummary,
                durationMs, errorMessage,
            )
        } catch (logFailure: Exception) {
            // History append must not break sync; log and continue.
            log.warn("mds_sync_history append failed: {}", logFailure.toString())
        }
    }

    data class SyncResult(val status: String?, val version: Long?, val error: String?) {

        /** 원 Java record 의 toString 형식 유지 — MdsSyncJob 이 "MdsSyncJob result: {}" 로 로그에 남긴다. */
        override fun toString(): String = "SyncResult[status=$status, version=$version, error=$error]"

        companion object {
            @JvmStatic
            fun synced(version: Long): SyncResult = SyncResult("SYNCED", version, null)

            @JvmStatic
            fun skipped(): SyncResult = SyncResult("SKIPPED", null, null)

            @JvmStatic
            fun failed(error: String?): SyncResult = SyncResult("FAILED", null, error)
        }
    }
}
