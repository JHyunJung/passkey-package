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
    private final Clock clock;
    private final String holder;

    public MdsSchedulerService(SchedulerLeaseService leases,
                               MdsBlobClient client,
                               MdsBlobStore store,
                               StringRedisTemplate redis,
                               AuditLogService audit,
                               Clock clock,
                               @Value("${passkey.mds.lease-holder:default}")
                               String configuredHolder) {
        this.leases = leases;
        this.client = client;
        this.store = store;
        this.redis = redis;
        this.audit = audit;
        this.clock = clock;
        // Default holder = PID@host (ManagementFactory), unique per JVM.
        this.holder = "default".equals(configuredHolder)
                ? ManagementFactory.getRuntimeMXBean().getName()
                : configuredHolder;
    }

    public SyncResult runOnce() {
        if (!leases.tryAcquire(LEASE_NAME, holder, Duration.ofMinutes(5))) {
            log.info("MDS sync skipped — another instance holds the lease");
            return SyncResult.skipped();
        }
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

            long version = blob.getPayload().getNo();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("version", version);
            payload.put("fetchedAt", clock.instant().toString());
            // Actor 0 = scheduler (no human operator).
            audit.append(new AuditAppendRequest(
                    0L, "(scheduler)", "MDS_BLOB_SYNC", null, null, payload));

            log.info("MDS sync complete — version={}", version);
            return SyncResult.synced(version);
        } catch (RuntimeException e) {
            log.warn("MDS sync failed: {}", e.toString());
            return SyncResult.failed(e.getMessage());
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
