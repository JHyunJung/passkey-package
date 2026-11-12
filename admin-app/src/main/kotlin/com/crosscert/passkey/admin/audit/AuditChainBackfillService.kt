package com.crosscert.passkey.admin.audit

import com.crosscert.passkey.core.config.DbSchemaProperties
import com.crosscert.passkey.core.repository.AuditLogRepository
import com.crosscert.passkey.core.util.CryptoUtils
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.util.Arrays
import java.util.UUID

/**
 * 기존 audit_log row 의 tenant chain 컬럼 (V25 신규) 을 채우는 백필 서비스.
 *
 * 호출 흐름: PLATFORM_OPERATOR 가 POST /admin/api/audit/chain/backfill 호출 →
 *   [backfill] 실행 → tenant_id 별로 id ASC 순회 → tenant chain 재계산.
 *
 * Idempotent: tenant_hash 가 이미 채워진 row 는 skip. 같은 트랜잭션 안에서
 *   AUDIT_CHAIN_LOCK 을 잡아 동시 append 와 직렬화.
 */
@Service
class AuditChainBackfillService(
    private val repo: AuditLogRepository,
    private val em: EntityManager,
    baseMapper: ObjectMapper,
    dbSchema: DbSchemaProperties,
) {

    // 원 Java 처럼 생성 시 null 검증을 하지 않는다(슬라이스 테스트 mock 은 null 을 준다).
    private val schema: String? = dbSchema.schema()

    private val canonical: ObjectMapper = canonicalCopy(baseMapper)

    data class Summary(val tenantsProcessed: Int, val rowsUpdated: Int, val rowsSkipped: Int)

    @Transactional
    fun backfill(): Summary {
        em.createNativeQuery(
            "SELECT 1 FROM " + schema + ".scheduler_lease WHERE name = :n FOR UPDATE",
        )
            .setParameter("n", AuditLogService.CHAIN_LOCK_NAME)
            .singleResult

        val tenantIds = repo.findDistinctTenantIds()
        var updated = 0
        var skipped = 0
        for (tenantId in tenantIds) {
            val rows = repo.findAllByTenantOrdered(tenantId)
            var prev: ByteArray? = null
            for (row in rows) {
                // Skip only when *both* tenantHash and tenantPrevHash are present —
                // a partial-completion row (hash set, prev_hash null) would otherwise
                // leave the chain broken on re-run.  Recomputing is idempotent.
                if (row.tenantHash != null &&
                    (if (prev == null) row.tenantPrevHash == null else Arrays.equals(prev, row.tenantPrevHash))
                ) {
                    prev = row.tenantHash
                    skipped++
                    continue
                }
                val payload: Map<String, Any?>? = try {
                    canonical.readValue(row.payload, PAYLOAD_TYPE)
                } catch (e: Exception) {
                    HashMap()
                }
                val req = AuditAppendRequest(
                    row.actorId, row.actorEmail, row.action,
                    row.targetType, row.targetId,
                    row.tenantId,
                    payload,
                )
                val computed = AuditLogService.computeHash(prev, req, row.payload, row.createdAt)
                // Use native SQL UPDATE targeting only tenant_hash + tenant_prev_hash so that
                // we need only the column-level UPDATE grant on those two columns (V46),
                // not a full-row UPDATE. This avoids triggering ORA-01031 on the other columns
                // that PSK_APP_ADMIN intentionally cannot update (V10 append-only design).
                em.createNativeQuery(
                    "UPDATE " + schema + ".audit_log" +
                        " SET tenant_prev_hash = :prevHash," +
                        "     tenant_hash      = :hash" +
                        " WHERE id = :id",
                )
                    .setParameter("prevHash", prev)
                    .setParameter("hash", computed)
                    .setParameter("id", CryptoUtils.uuidToBytes(row.id))
                    .executeUpdate()
                // Detach the entity so Hibernate does not attempt a full-row flush on commit.
                em.detach(row)
                prev = computed
                updated++
            }
        }
        return Summary(tenantIds.size, updated, skipped)
    }

    private companion object {
        val PAYLOAD_TYPE = object : TypeReference<Map<String, Any?>>() {}
    }
}
