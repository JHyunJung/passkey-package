package com.crosscert.passkey.admin.mds;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.admin.scheduler.SchedulerLeaseService;
import com.webauthn4j.metadata.data.MetadataBLOB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Orchestrates a single MDS sync cycle: acquire lease → fetch BLOB →
 * persist → invalidate AAGUID cache → audit. Returns {@link SyncResult}
 * so the controller (admin force-sync) and the @Scheduled job can
 * both report status.
 *
 * <p>T16 extends this to also write per-AAGUID entries back to Redis.
 */
@Service
public class MdsSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(MdsSchedulerService.class);
    private static final String LEASE_NAME = "mds-sync";

    private final SchedulerLeaseService leases;
    private final MdsBlobClient client;
    private final MdsBlobStore store;
    private final StringRedisTemplate redis;
    private final AuditLogService audit;
    private final MdsHistoryService historyService;
    private final Clock clock;
    private final String holder;

    public MdsSchedulerService(SchedulerLeaseService leases,
                               MdsBlobClient client,
                               MdsBlobStore store,
                               StringRedisTemplate redis,
                               AuditLogService audit,
                               MdsHistoryService historyService,
                               Clock clock,
                               @Value("${passkey.mds.lease-holder:default}")
                               String configuredHolder) {
        this.leases = leases;
        this.client = client;
        this.store = store;
        this.redis = redis;
        this.audit = audit;
        this.historyService = historyService;
        this.clock = clock;
        // Default holder = PID@host (ManagementFactory), unique per JVM.
        this.holder = "default".equals(configuredHolder)
                ? ManagementFactory.getRuntimeMXBean().getName()
                : configuredHolder;
    }

    public SyncResult runOnce() {
        Instant scheduledAt = Instant.now();
        log.info("mds sync started: scheduledAt={}", scheduledAt);
        if (!leases.tryAcquire(LEASE_NAME, holder, Duration.ofMinutes(5))) {
            log.info("mds sync skipped: reason=lease-held");
            // SKIPPED: peer instance owns the lease and will record its own
            // history row. Recording here would double-count cycles.
            return SyncResult.skipped();
        }
        // Capture start BEFORE any work so duration covers fetch + persist + cache.
        Instant started = scheduledAt;
        SyncResult result;
        try {
            MetadataBLOB blob = client.fetch();
            // Phase 3: raw JWT bytes not surfaced by webauthn4j parsed
            // BLOB. Pass empty JSON placeholder; verifier consults parsed
            // entries via Redis cache (T16) not the blob_jwt column.
            String rawJwt = "{}";
            store.store(rawJwt, blob);

            // Invalidate AAGUID cache so passkey-app sees fresh data.
            // T16 immediately repopulates these keys with the new entries.
            Set<String> keys = redis.keys("mds:aaguid:*");
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }

            // Populate per-AAGUID cache entries (mirrors passkey-app's
            // MdsAaguidCache key format: "mds:aaguid:<UUID>" → CSV of
            // status strings). Statuses are ordered as the MDS BLOB
            // returns them (most recent last); MdsVerifier only inspects
            // the last entry per FIDO MDS spec §5.4.
            //
            // TTL 7h > scheduler cadence of 6h: entries stay live until
            // the next sync cycle invalidates + repopulates them. A 30min
            // TTL would leave a ~5.5h stale-miss window that fails closed.
            for (com.webauthn4j.metadata.data.MetadataBLOBPayloadEntry entry
                    : blob.getPayload().getEntries()) {
                if (entry.getAaguid() == null) continue; // legacy U2F entries
                String uuid = entry.getAaguid().getValue().toString();
                String csv = entry.getStatusReports().stream()
                        .map(sr -> sr.getStatus() == null ? "" : sr.getStatus().getValue())
                        .filter(s -> !s.isBlank())
                        .reduce((a, b) -> a + "," + b)
                        .orElse("");
                if (!csv.isBlank()) {
                    redis.opsForValue().set("mds:aaguid:" + uuid, csv,
                            java.time.Duration.ofHours(7));
                }
            }

            long version = blob.getPayload().getNo();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("version", version);
            payload.put("fetchedAt", clock.instant().toString());
            // Actor null = scheduler (no human operator).
            audit.append(new AuditAppendRequest(
                    null, "(scheduler)", "MDS_BLOB_SYNC", null, null,
                    null,
                    payload));

            long durMs = Duration.between(started, Instant.now()).toMillis();
            log.info("mds sync ok: version={} durMs={}", version, durMs);
            result = SyncResult.synced(version);
        } catch (RuntimeException e) {
            log.error("mds sync failed: cause={}", e.toString(), e);
            result = SyncResult.failed(e.getMessage());
        }
        // Best-effort history append; never disrupts the sync result.
        // Pulls status/version/error from SyncResult so SYNCED + FAILED
        // paths share the same code (correct field order: version, status, error).
        safeRecord(started, result.version(), result.status(), null, result.error());
        return result;
    }

    private void safeRecord(Instant startedAt, Long version, String status,
                            String changeSummary, String errorMessage) {
        try {
            Instant finishedAt = Instant.now();
            long durMs = Duration.between(startedAt, finishedAt).toMillis();
            Integer durationMs = durMs > Integer.MAX_VALUE ? null : (int) durMs;
            historyService.append(startedAt, finishedAt, version, status, changeSummary,
                    durationMs, errorMessage);
        } catch (Exception logFailure) {
            // History append must not break sync; log and continue.
            log.warn("mds_sync_history append failed: {}", logFailure.toString());
        }
    }

    public record SyncResult(String status, Long version, String error) {
        public static SyncResult synced(long version) {
            return new SyncResult("SYNCED", version, null);
        }
        public static SyncResult skipped() {
            return new SyncResult("SKIPPED", null, null);
        }
        public static SyncResult failed(String error) {
            return new SyncResult("FAILED", null, error);
        }
    }
}
