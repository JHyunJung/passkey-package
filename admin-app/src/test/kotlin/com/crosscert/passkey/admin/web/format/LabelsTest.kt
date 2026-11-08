package com.crosscert.passkey.admin.web.format

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** ← admin-ui/src/i18n/labels.test.ts */
class LabelsTest {

    @Test
    @DisplayName("maps known status enums to Korean")
    fun mapsKnownStatusEnumsToKorean() {
        assertThat(Labels.statusLabel("ACTIVE")).isEqualTo("활성")
        assertThat(Labels.statusLabel("SUSPENDED")).isEqualTo("정지")
        assertThat(Labels.statusLabel("INTACT")).isEqualTo("정상")
        assertThat(Labels.statusLabel("TAMPERED")).isEqualTo("위변조")
        assertThat(Labels.statusLabel("OPEN")).isEqualTo("처리중")
        assertThat(Labels.statusLabel("RESOLVED")).isEqualTo("해결")
        assertThat(Labels.statusLabel("SUCCESS")).isEqualTo("성공")
        assertThat(Labels.statusLabel("FAILED")).isEqualTo("실패")
    }

    @Test
    @DisplayName("returns the original value for unmapped status (safe fallback)")
    fun returnsTheOriginalValueForUnmappedStatus() {
        assertThat(Labels.statusLabel("UNKNOWN_FUTURE")).isEqualTo("UNKNOWN_FUTURE")
    }

    @Test
    @DisplayName("covers all spec-defined status enums")
    fun coversAllSpecDefinedStatusEnums() {
        val expected = LinkedHashMap<String, String>()
        expected["ACTIVE"] = "활성"; expected["SUSPENDED"] = "정지"; expected["REVOKED"] = "회수"; expected["EXPIRED"] = "만료"
        expected["PENDING"] = "대기"; expected["INTACT"] = "정상"; expected["TAMPERED"] = "위변조"; expected["OPEN"] = "처리중"
        expected["RESOLVED"] = "해결"; expected["SUCCESS"] = "성공"; expected["FAILED"] = "실패"; expected["ROTATED"] = "교체됨"
        expected["SYNCED"] = "동기화됨"; expected["SKIPPED"] = "건너뜀"
        assertThat(Labels.STATUS_LABELS).isEqualTo(expected)
    }

    @Test
    @DisplayName("maps AAGUID policy modes to Korean")
    fun mapsAaguidPolicyModesToKorean() {
        assertThat(Labels.AAGUID_MODE_LABELS).isEqualTo(
            java.util.Map.of("ANY", "전체 허용", "ALLOWLIST", "허용 목록", "DENYLIST", "차단 목록"),
        )
    }
}
