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

/**
 * 기존 audit_log row 의 tenant chain 컬럼(tenant_prev_hash·tenant_hash)을 채우는 백필 서비스.
 *
 * 호출 흐름: PLATFORM_OPERATOR 가 POST /admin/api/audit/chain/backfill 호출 →
 *   [backfill] 실행 → tenant_id 별로 append 순서(createdAt ASC, id ASC) 순회 → tenant chain 재계산.
 *   화면(AuditChainPageController)은 위변조가 없음을 재검증한 뒤 AuditChainMonitorController.backfill 을 직접 부른다.
 *   이 재검증은 화면 경로에만 있다 — REST(POST …/backfill)는 검증 없이 바로 이 서비스를 실행한다.
 *
 * Idempotent: tenant_hash 가 있고 tenant_prev_hash 가 직전 row 의 tenant_hash 와 이어지는 row 만 skip
 *   (연결이 끊긴 row 는 다시 계산한다). 같은 트랜잭션 안에서
 *   AUDIT_CHAIN_LOCK 을 잡아 동시 append 와 직렬화.
 */
@Service
class AuditChainBackfillService(
    private val repo: AuditLogRepository,
    private val em: EntityManager,
    baseMapper: ObjectMapper,
    dbSchema: DbSchemaProperties,
) {

    // String?: 생성 시 null 검사를 하지 않는다(mock 한 DbSchemaProperties 는 null 을 준다).
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
                // Skip only when tenantHash is set and tenantPrevHash links to the previous
                // row's hash (null for the tenant's first row) — a row whose prev_hash does not
                // link (e.g. hash set, prev_hash null on a later row) would otherwise leave the
                // chain broken on re-run.  Recomputing is idempotent.
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
                // we need only the column-level UPDATE grant on those two columns (schema SQL),
                // not a full-row UPDATE. This avoids triggering ORA-01031 on the other columns
                // that PSK_APP_ADMIN intentionally cannot update (append-only design).
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
