package com.crosscert.passkey.admin.tenant

import com.crosscert.passkey.core.entity.Tenant
import java.util.TreeSet

/**
 * Tenant 의 변경 추적용 스냅샷. update() before/after 비교에 쓰인다.
 * audit log payload 의 before/after 키로 직렬화 가능 (data class + 기본 타입).
 * 직렬화 필드 순서는 생성자 선언 순서 — 원 record 컴포넌트 순서와 같다(AuditPayloadCanonicalFormTest 가 고정).
 */
data class TenantSnapshot(
    val displayName: String?,
    val rpName: String?,
    val allowedOrigins: Set<String>?,
    val acceptedFormats: Set<String>?,
    val requireUserVerification: Boolean,
    val mdsRequired: Boolean,
    val attestationConveyance: String?,
    val webauthnTimeoutMs: Int,
) {

    /**
     * before vs after 비교해 변경된 필드 이름 리스트 반환.
     * audit payload 의 changedFields 키로 직렬화.
     */
    fun diff(other: TenantSnapshot): List<String> {
        val changed = ArrayList<String>()
        if (this.displayName != other.displayName) changed.add("displayName")
        if (this.rpName != other.rpName) changed.add("rpName")
        if (this.allowedOrigins != other.allowedOrigins) changed.add("allowedOrigins")
        if (this.acceptedFormats != other.acceptedFormats) changed.add("acceptedFormats")
        if (this.requireUserVerification != other.requireUserVerification) changed.add("requireUserVerification")
        if (this.mdsRequired != other.mdsRequired) changed.add("mdsRequired")
        if (this.attestationConveyance != other.attestationConveyance) changed.add("attestationConveyance")
        if (this.webauthnTimeoutMs != other.webauthnTimeoutMs) changed.add("webauthnTimeoutMs")
        return changed
    }

    companion object {
        fun of(t: Tenant): TenantSnapshot = TenantSnapshot(
            t.displayName,
            t.rpName,
            TreeSet(t.allowedOriginValues),
            TreeSet(t.acceptedFormatValues),
            t.isRequireUserVerification,
            t.isMdsRequired,
            t.attestationConveyance,
            t.webauthnTimeoutMs,
        )
    }
}
