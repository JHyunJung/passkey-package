package com.crosscert.passkey.admin.web.format;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** ← admin-ui/src/i18n/labels.test.ts */
class LabelsTest {

    @Test
    @DisplayName("maps known status enums to Korean")
    void mapsKnownStatusEnumsToKorean() {
        assertThat(Labels.statusLabel("ACTIVE")).isEqualTo("활성");
        assertThat(Labels.statusLabel("SUSPENDED")).isEqualTo("정지");
        assertThat(Labels.statusLabel("INTACT")).isEqualTo("정상");
        assertThat(Labels.statusLabel("TAMPERED")).isEqualTo("위변조");
        assertThat(Labels.statusLabel("OPEN")).isEqualTo("처리중");
        assertThat(Labels.statusLabel("RESOLVED")).isEqualTo("해결");
        assertThat(Labels.statusLabel("SUCCESS")).isEqualTo("성공");
        assertThat(Labels.statusLabel("FAILED")).isEqualTo("실패");
    }

    @Test
    @DisplayName("returns the original value for unmapped status (safe fallback)")
    void returnsTheOriginalValueForUnmappedStatus() {
        assertThat(Labels.statusLabel("UNKNOWN_FUTURE")).isEqualTo("UNKNOWN_FUTURE");
    }

    @Test
    @DisplayName("covers all spec-defined status enums")
    void coversAllSpecDefinedStatusEnums() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("ACTIVE", "활성"); expected.put("SUSPENDED", "정지"); expected.put("REVOKED", "회수"); expected.put("EXPIRED", "만료");
        expected.put("PENDING", "대기"); expected.put("INTACT", "정상"); expected.put("TAMPERED", "위변조"); expected.put("OPEN", "처리중");
        expected.put("RESOLVED", "해결"); expected.put("SUCCESS", "성공"); expected.put("FAILED", "실패"); expected.put("ROTATED", "교체됨");
        expected.put("SYNCED", "동기화됨"); expected.put("SKIPPED", "건너뜀");
        assertThat(Labels.STATUS_LABELS).isEqualTo(expected);
    }

    @Test
    @DisplayName("maps AAGUID policy modes to Korean")
    void mapsAaguidPolicyModesToKorean() {
        assertThat(Labels.AAGUID_MODE_LABELS).isEqualTo(Map.of(
                "ANY", "전체 허용", "ALLOWLIST", "허용 목록", "DENYLIST", "차단 목록"));
    }
}
