package com.crosscert.passkey.admin.web.format;

import java.util.Map;

/**
 * 액션 코드 → 한글 라벨, 피드 행을 사람이 읽는 문장으로(← pages/activityLabels.ts, 1:1).
 * 매핑에 없는 코드는 원문 그대로 노출(fallback).
 */
public final class ActivityLabels {

    private static final Map<String, String> ACTION_LABELS = Map.ofEntries(
            Map.entry("TENANT_CREATE", "테넌트 생성"),
            Map.entry("TENANT_UPDATE", "테넌트 수정"),
            Map.entry("TENANT_SUSPEND", "테넌트 정지"),
            Map.entry("TENANT_ACTIVATE", "테넌트 활성화"),
            Map.entry("CREDENTIAL_REVOKE", "Credential 폐기"),
            Map.entry("CREDENTIAL_REVOKED", "Credential 폐기"),
            Map.entry("CREDENTIAL_REGISTERED", "Credential 등록"),
            Map.entry("CREDENTIAL_AUTHENTICATED", "인증 성공"),
            Map.entry("API_KEY_ISSUE", "API 키 발급"),
            Map.entry("API_KEY_ISSUED", "API 키 발급"),
            Map.entry("API_KEY_REVOKE", "API 키 회수"),
            Map.entry("API_KEY_REVOKED", "API 키 회수"),
            Map.entry("API_KEY_ROTATED", "API 키 회전"),
            Map.entry("SIGNING_KEY_ROTATE", "서명키 회전"),
            Map.entry("SIGNING_KEY_REVOKE", "서명키 폐기"),
            Map.entry("WEBAUTHN_CONFIG_UPDATED", "설정 변경"),
            Map.entry("ATTESTATION_POLICY_UPDATED", "AAGUID 정책 변경"),
            Map.entry("ADMIN_LOGIN", "관리자 로그인"),
            Map.entry("ADMIN_LOGIN_FAILED", "로그인 실패"),
            Map.entry("SIGNATURE_COUNTER_REGRESSION", "서명 카운터 이상"),
            Map.entry("SIGNATURE_INVALID", "서명 검증 실패"),
            Map.entry("ATTESTATION_TRUST_FAILED", "Attestation 신뢰 실패"),
            Map.entry("MDS_BLOB_SYNC", "MDS 동기화"),
            Map.entry("RETENTION_PURGE", "보존기간 정리"));

    private ActivityLabels() {}

    public static String actionLabel(String action) {
        String l = action == null ? null : ACTION_LABELS.get(action);
        return l != null ? l : action;
    }

    /** {@code EventSentenceInput}. */
    public record EventSentenceInput(String action, String actorEmail, String tenantSlug, String targetType, String targetId) {}

    private static boolean truthy(String s) {
        return s != null && !s.isEmpty();
    }

    /** "{행위자} 님이 {테넌트} 테넌트에 {대상} {액션}" 형태의 한 줄 문장. */
    public static String eventSentence(EventSentenceInput e) {
        String actor = truthy(e.actorEmail()) && !e.actorEmail().strip().isEmpty() ? e.actorEmail() : "system";
        String label = actionLabel(e.action());
        String tenant = truthy(e.tenantSlug()) ? e.tenantSlug() + " 테넌트" : "플랫폼";
        // e.targetType && e.targetId ? `${type} ${id}` : e.targetId ?? ''
        String target = truthy(e.targetType()) && truthy(e.targetId())
                ? e.targetType() + " " + e.targetId()
                : (e.targetId() == null ? "" : e.targetId());
        return truthy(target)
                ? actor + " 님이 " + tenant + "에 " + target + " · " + label
                : actor + " · " + tenant + " · " + label;
    }
}
