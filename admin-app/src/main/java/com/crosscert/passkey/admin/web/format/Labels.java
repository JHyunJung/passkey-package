package com.crosscert.passkey.admin.web.format;

import java.util.LinkedHashMap;
import java.util.Map;

/** 화면 표시 라벨(← admin-ui i18n/labels.ts). enum 값(영어)은 로직·API 에 그대로 쓰고 화면에 그릴 때만 변환한다. */
public final class Labels {

    private Labels() {}

    /** 상태 배지 공용 라벨. */
    public static final Map<String, String> STATUS_LABELS = ordered(
            "ACTIVE", "활성", "SUSPENDED", "정지", "REVOKED", "회수", "EXPIRED", "만료",
            "PENDING", "대기", "INTACT", "정상", "TAMPERED", "위변조", "OPEN", "처리중",
            "RESOLVED", "해결", "SUCCESS", "성공", "FAILED", "실패", "ROTATED", "교체됨",
            "SYNCED", "동기화됨", "SKIPPED", "건너뜀");

    /** AAGUID 정책 모드 enum → 한국어. */
    public static final Map<String, String> AAGUID_MODE_LABELS = ordered(
            "ANY", "전체 허용", "ALLOWLIST", "허용 목록", "DENYLIST", "차단 목록");

    public record MdsTrustMode(String label, String description) {}

    /** MDS 신뢰 모드 enum → 사람이 읽는 라벨 + 설명. 모르는 값은 호출자가 원문 그대로 보인다. */
    public static final Map<String, MdsTrustMode> MDS_TRUST_MODES = Map.of(
            "MDS_STRICT_OPTIONAL", new MdsTrustMode("MDS 확인 권장",
                    "MDS 에 없는 인증기도 기본은 허용합니다. 테넌트에서 ‘MDS 엄격 모드’ 를 켠 경우에만 MDS 미등록 인증기를 거부합니다."));

    /** 상태 enum 값을 한국어 표시 라벨로. 미매핑 값은 원본 그대로(안전한 fallback). */
    public static String statusLabel(String v) {
        return v == null ? null : STATUS_LABELS.getOrDefault(v, v);
    }

    public static String aaguidMode(String v) {
        return v == null ? null : AAGUID_MODE_LABELS.getOrDefault(v, v);
    }

    private static Map<String, String> ordered(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return java.util.Collections.unmodifiableMap(m);
    }
}
