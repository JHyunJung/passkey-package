package com.crosscert.passkey.admin.web.format

/**
 * 액션 코드 → 한글 라벨, 피드 행을 사람이 읽는 문장으로(← pages/activityLabels.ts, 1:1).
 * 매핑에 없는 코드는 원문 그대로 노출(fallback).
 */
object ActivityLabels {

    private val ACTION_LABELS: Map<String, String> = java.util.Map.ofEntries(
        java.util.Map.entry("TENANT_CREATE", "테넌트 생성"),
        java.util.Map.entry("TENANT_UPDATE", "테넌트 수정"),
        java.util.Map.entry("TENANT_SUSPEND", "테넌트 정지"),
        java.util.Map.entry("TENANT_ACTIVATE", "테넌트 활성화"),
        java.util.Map.entry("CREDENTIAL_REVOKE", "Credential 폐기"),
        java.util.Map.entry("CREDENTIAL_REVOKED", "Credential 폐기"),
        java.util.Map.entry("CREDENTIAL_REGISTERED", "Credential 등록"),
        java.util.Map.entry("CREDENTIAL_AUTHENTICATED", "인증 성공"),
        java.util.Map.entry("API_KEY_ISSUE", "API 키 발급"),
        java.util.Map.entry("API_KEY_ISSUED", "API 키 발급"),
        java.util.Map.entry("API_KEY_REVOKE", "API 키 회수"),
        java.util.Map.entry("API_KEY_REVOKED", "API 키 회수"),
        java.util.Map.entry("API_KEY_ROTATED", "API 키 회전"),
        java.util.Map.entry("SIGNING_KEY_ROTATE", "서명키 회전"),
        java.util.Map.entry("SIGNING_KEY_REVOKE", "서명키 폐기"),
        java.util.Map.entry("WEBAUTHN_CONFIG_UPDATED", "설정 변경"),
        java.util.Map.entry("ATTESTATION_POLICY_UPDATED", "AAGUID 정책 변경"),
        java.util.Map.entry("ADMIN_LOGIN", "관리자 로그인"),
        java.util.Map.entry("ADMIN_LOGIN_FAILED", "로그인 실패"),
        java.util.Map.entry("SIGNATURE_COUNTER_REGRESSION", "서명 카운터 이상"),
        java.util.Map.entry("SIGNATURE_INVALID", "서명 검증 실패"),
        java.util.Map.entry("ATTESTATION_TRUST_FAILED", "Attestation 신뢰 실패"),
        java.util.Map.entry("MDS_BLOB_SYNC", "MDS 동기화"),
        java.util.Map.entry("RETENTION_PURGE", "보존기간 정리"),
    )

    @JvmStatic
    fun actionLabel(action: String?): String? {
        val l = if (action == null) null else ACTION_LABELS[action]
        return l ?: action
    }

    /** `EventSentenceInput`. */
    data class EventSentenceInput(
        val action: String?,
        val actorEmail: String?,
        val tenantSlug: String?,
        val targetType: String?,
        val targetId: String?,
    )

    private fun truthy(s: String?): Boolean = s != null && s.isNotEmpty()

    /** "{행위자} 님이 {테넌트} 테넌트에 {대상} {액션}" 형태의 한 줄 문장. */
    @JvmStatic
    fun eventSentence(e: EventSentenceInput): String {
        val actorEmail = e.actorEmail
        val actor = if (actorEmail != null && truthy(actorEmail) && !actorEmail.javaIsBlank()) actorEmail else "system"
        val label = actionLabel(e.action)
        val tenant = if (truthy(e.tenantSlug)) e.tenantSlug + " 테넌트" else "플랫폼"
        // e.targetType && e.targetId ? `${type} ${id}` : e.targetId ?? ''
        val target = if (truthy(e.targetType) && truthy(e.targetId)) {
            e.targetType + " " + e.targetId
        } else {
            e.targetId ?: ""
        }
        return if (truthy(target)) {
            "$actor 님이 ${tenant}에 $target · $label"
        } else {
            "$actor · $tenant · $label"
        }
    }
}
