package com.crosscert.passkey.admin.web.format

import java.util.Collections

/** 화면 표시 라벨(← admin-ui i18n/labels.ts). enum 값(영어)은 로직·API 에 그대로 쓰고 화면에 그릴 때만 변환한다. */
object Labels {

    /** 상태 배지 공용 라벨. */
    val STATUS_LABELS: Map<String, String> = ordered(
        "ACTIVE", "활성", "SUSPENDED", "정지", "REVOKED", "회수", "EXPIRED", "만료",
        "PENDING", "대기", "INTACT", "정상", "TAMPERED", "위변조", "OPEN", "처리중",
        "RESOLVED", "해결", "SUCCESS", "성공", "FAILED", "실패", "ROTATED", "교체됨",
        "SYNCED", "동기화됨", "SKIPPED", "건너뜀",
    )

    /** AAGUID 정책 모드 enum → 한국어. */
    val AAGUID_MODE_LABELS: Map<String, String> = ordered(
        "ANY", "전체 허용", "ALLOWLIST", "허용 목록", "DENYLIST", "차단 목록",
    )

    data class MdsTrustMode(val label: String?, val description: String?)

    /** MDS 신뢰 모드 enum → 사람이 읽는 라벨 + 설명. 모르는 값은 호출자가 원문 그대로 보인다. */
    val MDS_TRUST_MODES: Map<String, MdsTrustMode> = java.util.Map.of(
        "MDS_STRICT_OPTIONAL",
        MdsTrustMode(
            "MDS 확인 권장",
            "MDS 에 없는 인증기도 기본은 허용합니다. 테넌트에서 ‘MDS 엄격 모드’ 를 켠 경우에만 MDS 미등록 인증기를 거부합니다.",
        ),
    )

    /** 상태 enum 값을 한국어 표시 라벨로. 미매핑 값은 원본 그대로(안전한 fallback). */
    fun statusLabel(v: String?): String? = if (v == null) null else STATUS_LABELS.getOrDefault(v, v)

    fun aaguidMode(v: String?): String? = if (v == null) null else AAGUID_MODE_LABELS.getOrDefault(v, v)

    private fun ordered(vararg kv: String): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        var i = 0
        while (i < kv.size) {
            m[kv[i]] = kv[i + 1]
            i += 2
        }
        return Collections.unmodifiableMap(m)
    }
}
