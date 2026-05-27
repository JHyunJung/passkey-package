package com.crosscert.passkey.admin.audit;

import com.crosscert.passkey.core.entity.AuditLog;
import com.crosscert.passkey.core.repository.AuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
                if (row.getTenantHash() != null) {
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
                row.setTenantPrevHash(prev);
                row.setTenantHash(computed);
                prev = computed;
                updated++;
            }
        }
        return new Summary(tenantIds.size(), updated, skipped);
    }
}
