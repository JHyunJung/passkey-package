package com.crosscert.passkey.admin.tenant

import com.crosscert.passkey.admin.auth.TenantBoundary
import com.crosscert.passkey.core.repository.TenantRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class WebauthnDiffService(
    private val tenantRepo: TenantRepository,
    private val tenantBoundary: TenantBoundary,
) {

    /**
     * tenantId nullable: 호출자(TenantAdminController.diff)가 TenantView.id(영속 엔티티 id 에서 온 nullable DTO 필드)를
     * 그대로 넘긴다 — 정상 경로에선 null 이 오지 않는다. null 이면 원 Java 처럼 boundary 검사 후, 리포지토리 호출 전에
     * SimpleJpaRepository 와 같은 문구의 IllegalArgumentException. 원 Java 는 `findById(null)` 안의 IAE 라 실제 Spring
     * 컨텍스트에선 리포지토리 프록시가 `InvalidDataAccessApiUsageException` 으로 번역했다 — 도달 불가 경로라 그대로 둔다.
     */
    @Transactional(readOnly = true)
    fun diff(tenantId: UUID?, proposed: WebauthnDiffRequest): WebauthnConfigDiff {
        // Self-guard (sec-webauthndiff-no-boundary-fragile): this method must
        // enforce tenant isolation itself, not rely on the controller having
        // resolved tenantId via a boundary-checked path. Current sole caller
        // (TenantAdminController.diff → service.get()) already passes a
        // boundary-checked UUID, so this is a no-op for the normal flow and
        // only blocks future callers that pass an arbitrary tenantId.
        tenantBoundary.assertCanAccessTenant(tenantId)

        val t = tenantRepo.findById(tenantId ?: throw IllegalArgumentException("The given id must not be null"))
            .orElseThrow { IllegalStateException("tenant not found $tenantId") }

        // 현재 값
        val curOrigins = t.allowedOriginValues
        val curFormats = ArrayList(t.acceptedFormatValues)
        val cur = WebauthnConfigDiff.Current(
            t.rpId, t.rpName,
            curOrigins,
            curFormats,
            t.isRequireUserVerification, t.isMdsRequired,
        )

        // 제안 (null → 현재 값 유지, @NotNull/@NotBlank 이지만 diff 미리보기 시 partial 허용)
        val propOrigins = if (proposed.allowedOrigins != null) proposed.allowedOrigins else curOrigins
        val propFormats = if (proposed.acceptedFormats != null) ArrayList(proposed.acceptedFormats) else curFormats
        val prop = WebauthnConfigDiff.Proposed(
            if (proposed.rpId != null) proposed.rpId else cur.rpId,
            if (proposed.rpName != null) proposed.rpName else cur.rpName,
            propOrigins,
            propFormats,
            if (proposed.requireUserVerification != null) proposed.requireUserVerification else cur.requireUserVerification,
            if (proposed.mdsRequired != null) proposed.mdsRequired else cur.mdsRequired,
        )

        val changes = ArrayList<WebauthnConfigDiff.FieldChange>()
        val warnings = ArrayList<String>()

        if (cur.rpId != prop.rpId) {
            changes.add(WebauthnConfigDiff.FieldChange("rpId", cur.rpId, prop.rpId, null, null))
            warnings.add("RP_ID_CHANGED")
        }
        if (cur.rpName != prop.rpName) {
            changes.add(WebauthnConfigDiff.FieldChange("rpName", cur.rpName, prop.rpName, null, null))
        }

        val originsAdded = ArrayList(propOrigins)
        originsAdded.removeAll(curOrigins)
        val originsRemoved = ArrayList(curOrigins)
        originsRemoved.removeAll(propOrigins)
        if (!originsAdded.isEmpty() || !originsRemoved.isEmpty()) {
            changes.add(WebauthnConfigDiff.FieldChange("origins", null, null, originsAdded, originsRemoved))
            if (!originsRemoved.isEmpty()) warnings.add("ORIGIN_REMOVED")
        }

        val formatsAdded = ArrayList(propFormats)
        formatsAdded.removeAll(curFormats)
        val formatsRemoved = ArrayList(curFormats)
        formatsRemoved.removeAll(propFormats)
        if (!formatsAdded.isEmpty() || !formatsRemoved.isEmpty()) {
            changes.add(WebauthnConfigDiff.FieldChange("formats", null, null, formatsAdded, formatsRemoved))
        }

        if (cur.requireUserVerification != prop.requireUserVerification) {
            changes.add(
                WebauthnConfigDiff.FieldChange(
                    "requireUserVerification",
                    cur.requireUserVerification, prop.requireUserVerification, null, null,
                ),
            )
            if (prop.requireUserVerification && !cur.requireUserVerification) {
                warnings.add("UV_RAISED_TO_REQUIRED")
            }
        }

        if (cur.mdsRequired != prop.mdsRequired) {
            changes.add(
                WebauthnConfigDiff.FieldChange(
                    "mdsRequired",
                    cur.mdsRequired, prop.mdsRequired, null, null,
                ),
            )
            if (prop.mdsRequired && !cur.mdsRequired) {
                warnings.add("MDS_RAISED_TO_REQUIRED")
            }
        }

        return WebauthnConfigDiff(cur, prop, changes, warnings)
    }
}
