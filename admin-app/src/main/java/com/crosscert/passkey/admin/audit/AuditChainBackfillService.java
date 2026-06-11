package com.crosscert.passkey.admin.audit;

import com.crosscert.passkey.core.entity.AuditLog;
import com.crosscert.passkey.core.repository.AuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 기존 audit_log row 의 tenant chain 컬럼 (V25 신규) 을 채우는 백필 서비스.
 *
 * <p>호출 흐름: PLATFORM_OPERATOR 가 POST /admin/api/audit/chain/backfill 호출 →
 *   {@link #backfill()} 실행 → tenant_id 별로 id ASC 순회 → tenant chain 재계산.
 *
 * <p>Idempotent: tenant_hash 가 이미 채워진 row 는 skip. 같은 트랜잭션 안에서
 *   AUDIT_CHAIN_LOCK 을 잡아 동시 append 와 직렬화.
 */
@Service
public class AuditChainBackfillService {

    private final AuditLogRepository repo;
    private final EntityManager em;
    private final ObjectMapper canonical;

    public AuditChainBackfillService(AuditLogRepository repo, EntityManager em, ObjectMapper baseMapper) {
        this.repo = repo;
        this.em = em;
        this.canonical = baseMapper.copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public record Summary(int tenantsProcessed, int rowsUpdated, int rowsSkipped) {}

    @Transactional
    public Summary backfill() {
        em.createNativeQuery(
                "SELECT 1 FROM APP_OWNER.scheduler_lease WHERE name = :n FOR UPDATE")
            .setParameter("n", AuditLogService.CHAIN_LOCK_NAME)
            .getSingleResult();

        List<UUID> tenantIds = repo.findDistinctTenantIds();
        int updated = 0;
        int skipped = 0;
        for (UUID tenantId : tenantIds) {
            List<AuditLog> rows = repo.findAllByTenantOrdered(tenantId);
            byte[] prev = null;
            for (AuditLog row : rows) {
                // Skip only when *both* tenantHash and tenantPrevHash are present —
                // a partial-completion row (hash set, prev_hash null) would otherwise
                // leave the chain broken on re-run.  Recomputing is idempotent.
                if (row.getTenantHash() != null
                        && (prev == null
                            ? row.getTenantPrevHash() == null
                            : java.util.Arrays.equals(prev, row.getTenantPrevHash()))) {
                    prev = row.getTenantHash();
                    skipped++;
                    continue;
                }
                Map<String, Object> payload;
                try {
                    payload = canonical.readValue(row.getPayload(),
                            new com.fasterxml.jackson.core.type.TypeReference<>() {});
                } catch (Exception e) {
                    payload = new HashMap<>();
                }
                AuditAppendRequest req = new AuditAppendRequest(
                        row.getActorId(), row.getActorEmail(), row.getAction(),
                        row.getTargetType(), row.getTargetId(),
                        row.getTenantId(),
                        payload);
                byte[] computed = AuditLogService.computeHash(prev, req, row.getPayload(), row.getCreatedAt());
                // Use native SQL UPDATE targeting only tenant_hash + tenant_prev_hash so that
                // we need only the column-level UPDATE grant on those two columns (V46),
                // not a full-row UPDATE. This avoids triggering ORA-01031 on the other columns
                // that APP_ADMIN intentionally cannot update (V10 append-only design).
                em.createNativeQuery(
                                "UPDATE APP_OWNER.audit_log"
                                + " SET tenant_prev_hash = :prevHash,"
                                + "     tenant_hash      = :hash"
                                + " WHERE id = :id")
                        .setParameter("prevHash", prev)
                        .setParameter("hash", computed)
                        .setParameter("id", uuidToBytes(row.getId()))
                        .executeUpdate();
                // Detach the entity so Hibernate does not attempt a full-row flush on commit.
                em.detach(row);
                prev = computed;
                updated++;
            }
        }
        return new Summary(tenantIds.size(), updated, skipped);
    }

    /** Convert UUID to 16-byte big-endian RAW(16) for Oracle JDBC binding. */
    private static byte[] uuidToBytes(UUID id) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(id.getMostSignificantBits());
        bb.putLong(id.getLeastSignificantBits());
        return bb.array();
    }
}
