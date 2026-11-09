package com.crosscert.passkey.admin.audit

import com.crosscert.passkey.core.entity.AuditLog
import com.crosscert.passkey.core.repository.AuditLogRepository
import org.springframework.stereotype.Component
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.util.Arrays
import java.util.UUID

/**
 * On-demand tamper detector for the audit_log SHA-256 hash chain.
 *
 * Walks all rows returned by [AuditLogRepository.findAllOrdered]
 * (ascending by id) and, for each row, checks:
 * 1. The stored `prev_hash` matches the hash of the preceding row
 *    (or is null for the genesis row).
 * 2. The stored `hash` matches the value recomputed from the row's
 *    own fields using [AuditLogService.computeHash].
 *
 * Hash recomputation passes the stored payload string *directly* to
 * [AuditLogService.computeHash] — the same bytes that were hashed at
 * append time — so the result is always deterministic regardless of Jackson
 * number type widening. The payload is also parsed into a `Map` solely
 * to populate the [AuditAppendRequest] that `computeHash` requires;
 * the parsed `Map` itself is never re-serialized.
 *
 * This class is `@Component` so Spring wires it automatically; no
 * extra configuration is needed.
 */
@Component
class AuditChainVerifier(
    private val repo: AuditLogRepository,
    baseMapper: ObjectMapper,
) {

    private val canonical: ObjectMapper = canonicalCopy(baseMapper)

    /**
     * Verify the entire hash chain.
     *
     * @return [Result.valid] when the chain is intact, or
     *         [Result.broken] with the id of the first failing row.
     */
    fun verify(): Result {
        val rows = repo.findAllOrdered()
        var expectedPrev: ByteArray? = null
        for (row in rows) {
            // Check prev_hash linkage.
            if (!Arrays.equals(expectedPrev, row.prevHash)) {
                return Result.broken(row.id)
            }
            // Recompute hash from the stored fields. We pass the stored
            // payload string directly to computeHash — the same bytes that
            // were hashed on append — so the result is always deterministic.
            val expected = recomputeHash(row)
            if (!Arrays.equals(expected, row.hash)) {
                return Result.broken(row.id)
            }
            expectedPrev = row.hash
        }
        return Result.valid()
    }

    private fun recomputeHash(row: AuditLog): ByteArray {
        try {
            // Parse stored payload back to Map so we can build the request.
            // We do NOT re-serialize — we pass row.getPayload() directly to
            // computeHash, which is the exact string that was hashed on append.
            val payload: Map<String, Any?>? = canonical.readValue(row.payload, PAYLOAD_TYPE)
            val req = AuditAppendRequest(
                row.actorId, row.actorEmail, row.action,
                row.targetType, row.targetId,
                row.tenantId,
                payload,
            )
            return AuditLogService.computeHash(row.prevHash, req, row.payload, row.createdAt)
        } catch (e: Exception) {
            // Malformed JSON in the payload column is itself tamper evidence.
            return ByteArray(0)
        }
    }

    /**
     * Verify the hash chain for a specific tenant (or null for PLATFORM_OPERATOR rows).
     *
     * @param tenantId tenant UUID, or null for rows with no tenant
     * @return [TenantResult.valid] when the chain is intact, or
     *         [TenantResult.broken] with the id of the first failing row.
     */
    fun verifyTenant(tenantId: UUID?): TenantResult =
        verifyTenantRows(tenantId, repo.findAllByTenantOrdered(tenantId))

    /**
     * 이미 읽어 둔 tenant 행으로 체인을 검증한다. [rows] 는
     * [AuditLogRepository.findAllByTenantOrdered] 가 돌려준 그대로(id ASC)여야 하며,
     * 이 메서드는 리스트를 바꾸지 않는다. overview 가 같은 행을 버킷 집계에도 써서
     * audit_log 를 tenant 당 한 번만 읽기 위해 분리했다(P3).
     *
     * @param tenantId tenant UUID, or null for rows with no tenant
     * @param rows     the tenant's rows in chain order
     * @return [TenantResult.valid] or [TenantResult.broken] with the id of the first failing row
     */
    fun verifyTenantRows(tenantId: UUID?, rows: List<AuditLog>): TenantResult {
        var expectedPrev: ByteArray? = null
        for (row in rows) {
            if (!Arrays.equals(expectedPrev, row.tenantPrevHash)) {
                return TenantResult.broken(tenantId, row.id)
            }
            val expected = recomputeTenantHash(row)
            if (!Arrays.equals(expected, row.tenantHash)) {
                return TenantResult.broken(tenantId, row.id)
            }
            expectedPrev = row.tenantHash
        }
        return TenantResult.valid(tenantId)
    }

    /**
     * Verify the hash chain for every distinct tenant found in the repository.
     *
     * @return list of [TenantResult] — one entry per distinct tenant_id value
     */
    fun verifyAllTenants(): List<TenantResult> {
        val tenantIds = repo.findDistinctTenantIds()
        val out = ArrayList<TenantResult>(tenantIds.size)
        for (id in tenantIds) {
            out.add(verifyTenant(id))
        }
        return out
    }

    private fun recomputeTenantHash(row: AuditLog): ByteArray {
        try {
            val payload: Map<String, Any?>? = canonical.readValue(row.payload, PAYLOAD_TYPE)
            val req = AuditAppendRequest(
                row.actorId, row.actorEmail, row.action,
                row.targetType, row.targetId,
                row.tenantId,
                payload,
            )
            return AuditLogService.computeHash(row.tenantPrevHash, req, row.payload, row.createdAt)
        } catch (e: Exception) {
            return ByteArray(0)
        }
    }

    /**
     * Verification result.
     *
     * @param ok       true when the entire chain is intact
     * @param brokenAt id of the first row that failed verification, or null
     */
    data class Result(val ok: Boolean, val brokenAt: UUID?) {
        companion object {
            fun valid(): Result = Result(true, null)

            fun broken(id: UUID?): Result = Result(false, id)
        }
    }

    /**
     * Per-tenant verification result.
     *
     * @param tenantId the tenant that was verified (null for PLATFORM_OPERATOR rows)
     * @param ok       true when the tenant's chain is intact
     * @param brokenAt id of the first row that failed verification, or null
     */
    data class TenantResult(val tenantId: UUID?, val ok: Boolean, val brokenAt: UUID?) {
        companion object {
            fun valid(tenantId: UUID?): TenantResult = TenantResult(tenantId, true, null)

            fun broken(tenantId: UUID?, id: UUID?): TenantResult = TenantResult(tenantId, false, id)
        }
    }

    private companion object {
        val PAYLOAD_TYPE = object : TypeReference<Map<String, Any?>>() {}
    }
}
