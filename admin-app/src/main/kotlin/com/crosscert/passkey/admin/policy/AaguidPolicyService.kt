package com.crosscert.passkey.admin.policy

import com.crosscert.passkey.admin.auth.TenantBoundary
import com.crosscert.passkey.core.config.KstTime
import com.crosscert.passkey.core.mds.MdsAaguidCache
import com.crosscert.passkey.core.repository.TenantAaguidPolicyRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

private val log = LoggerFactory.getLogger(AaguidPolicyService::class.java)

@Service
class AaguidPolicyService(
    private val repo: TenantAaguidPolicyRepository,
    private val mdsCache: MdsAaguidCache,
    private val tenantBoundary: TenantBoundary,
) {

    @Transactional(readOnly = true)
    fun get(tenantId: UUID): AaguidPolicyDto.View {
        // 테넌트 경계 검사를 조회보다 먼저 — RP_ADMIN 이 다른 테넌트 정책 존재 여부를
        // 엿보지 못하도록(정보 노출 방지). PLATFORM_OPERATOR 는 무조건 통과.
        tenantBoundary.assertCanAccessTenant(tenantId)
        val p = repo.findById(tenantId)
            .orElseThrow { IllegalStateException("aaguid policy not found for tenant $tenantId") }
        val entries = p.entries.map { e ->
            // MdsAaguidCache.Entry only stores statuses — no name field available
            // mdsName is reserved for future expansion when full MetadataStatement is cached
            val mdsName: String? = null
            AaguidPolicyDto.Entry(e.aaguid, e.note, mdsName)
        }
        return AaguidPolicyDto.View(
            p.tenantId, p.mode, p.isMdsStrict,
            entries, p.updatedAt, p.updatedBy,
        )
    }

    @Transactional
    fun update(tenantId: UUID, req: AaguidPolicyDto.UpdateRequest, updatedBy: String?): AaguidPolicyDto.View {
        // 경계 검사를 변경보다 먼저 — RP_ADMIN 은 자기 테넌트만 수정 가능.
        tenantBoundary.assertCanAccessTenant(tenantId)
        val p = repo.findById(tenantId)
            .orElseThrow { IllegalStateException("aaguid policy not found for tenant $tenantId") }
        p.mode = req.mode
        p.isMdsStrict = req.mdsStrict
        p.clearEntries()
        val inputs = req.entries
        if (inputs != null) {
            for (e in inputs) {
                p.addEntry(e.aaguid, e.note)
            }
        }
        p.updatedAt = OffsetDateTime.now(KstTime.ZONE)
        p.updatedBy = updatedBy
        repo.save(p)

        val entryCount = if (inputs == null) 0 else inputs.size
        log.info(
            "aaguid policy updated: tenantId={} mode={} mdsStrict={} entries={}",
            tenantId, req.mode, req.mdsStrict, entryCount,
        )

        return get(tenantId)
    }
}
