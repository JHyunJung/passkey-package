package com.crosscert.passkey.admin.web.format;

import com.crosscert.passkey.admin.policy.SecurityPolicyDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static com.crosscert.passkey.admin.web.format.PolicyValidation.*;
import static org.assertj.core.api.Assertions.assertThat;

/** ← admin-ui/src/lib/policyValidation.test.ts (+ validate(UpdateRequest) 추가 검증) */
class PolicyValidationTest {

    private static final List<String> NONE = List.of();

    // ── validateOrigin ──
    @Test
    @DisplayName("https://호스트[:포트] 는 통과")
    void httpsHostPortPasses() {
        assertThat(validateOrigin("https://admin.example.com", NONE)).isNull();
        assertThat(validateOrigin("https://admin.example.com:8443", NONE)).isNull();
        assertThat(validateOrigin("  https://a.example.com  ", NONE)).isNull();
    }

    @Test
    @DisplayName("스킴 누락 · 경로 · 끝 슬래시 · 와일드카드 · 잘못된 포트는 형식 오류")
    void formatErrors() {
        for (String v : List.of("admin.example.com", "https://admin.example.com/login", "https://admin.example.com/", "https://*.example.com", "ftp://x.com", "https://", "https://a.com:99999", "https://a.com:0")) {
            assertThat(validateOrigin(v, NONE)).as(v).isEqualTo(ORIGIN_FORMAT_ERROR);
        }
    }

    @Test
    @DisplayName("http 는 localhost · 127.0.0.1 만 허용")
    void httpOnlyForLocalHosts() {
        assertThat(validateOrigin("http://localhost:5173", NONE)).isNull();
        assertThat(validateOrigin("http://127.0.0.1:8080", NONE)).isNull();
        assertThat(validateOrigin("http://admin.example.com", NONE)).isEqualTo(ORIGIN_FORMAT_ERROR);
    }

    @Test
    @DisplayName("이미 목록에 있으면 중복 오류")
    void duplicateIsAnError() {
        assertThat(validateOrigin("https://a.example.com", List.of("https://a.example.com"))).isEqualTo(ORIGIN_DUPLICATE_ERROR);
    }

    @Test
    @DisplayName("대소문자만 다른 중복도 중복 오류")
    void caseInsensitiveDuplicate() {
        assertThat(validateOrigin("https://A.Example.com", List.of("https://a.example.com"))).isEqualTo(ORIGIN_DUPLICATE_ERROR);
        assertThat(validateOrigin("https://a.example.com", List.of("https://A.EXAMPLE.com"))).isEqualTo(ORIGIN_DUPLICATE_ERROR);
    }

    @Test
    @DisplayName("대문자 스킴·호스트는 통과하고 normalizeOrigin 이 앞뒤 공백 제거 + 소문자로 맞춘다")
    void upperCaseSchemeAndHostPassAndNormalize() {
        assertThat(validateOrigin("HTTPS://Admin.Example.COM:8443", NONE)).isNull();
        assertThat(normalizeOrigin("  HTTPS://Admin.Example.COM:8443 ")).isEqualTo("https://admin.example.com:8443");
    }

    @Test
    @DisplayName("기본 포트(https :443, http :80)를 명시하면 오류")
    void explicitDefaultPortIsAnError() {
        assertThat(validateOrigin("https://a.example.com:443", NONE)).isEqualTo(ORIGIN_DEFAULT_PORT_ERROR);
        assertThat(validateOrigin("http://localhost:80", NONE)).isEqualTo(ORIGIN_DEFAULT_PORT_ERROR);
        assertThat(validateOrigin("https://a.example.com:80", NONE)).isNull();
        assertThat(validateOrigin("http://localhost:443", NONE)).isNull();
    }

    @Test
    @DisplayName("0 으로 시작하는 포트 · 범위 밖 포트 · 빈 포트는 형식 오류")
    void invalidPorts() {
        for (String v : List.of("https://a.com:08443", "https://a.com:0443", "https://a.com:65536", "https://a.com:", "https://a.com:00")) {
            assertThat(validateOrigin(v, NONE)).as(v).isEqualTo(ORIGIN_FORMAT_ERROR);
        }
        assertThat(validateOrigin("https://a.com:65535", NONE)).isNull();
        assertThat(validateOrigin("https://a.com:1", NONE)).isNull();
    }

    @Test
    @DisplayName("IPv6 리터럴 · IDN(유니코드) 호스트는 형식 오류, 퓨니코드는 통과")
    void ipv6AndIdnHosts() {
        assertThat(validateOrigin("https://[::1]:8443", NONE)).isEqualTo(ORIGIN_FORMAT_ERROR);
        assertThat(validateOrigin("http://[::1]:8443", NONE)).isEqualTo(ORIGIN_FORMAT_ERROR);
        assertThat(validateOrigin("https://한글.example.com", NONE)).isEqualTo(ORIGIN_FORMAT_ERROR);
        assertThat(validateOrigin("https://xn--bj0bj06e.example.com", NONE)).isNull();
    }

    @Test
    @DisplayName("백엔드 한도: origin 256자 이하, 목록 64개 이하")
    void backendLimits() {
        String tooLong = "https://" + "a".repeat(240) + ".example.com"; // 8 + 240 + 12 = 260
        assertThat(validateOrigin(tooLong, NONE)).isEqualTo(ORIGIN_LENGTH_ERROR);
        String ok = "https://" + "a".repeat(50) + ".example.com";
        assertThat(validateOrigin(ok, NONE)).isNull();
        List<String> full = IntStream.range(0, 64).mapToObj(i -> "https://h" + i + ".example.com").toList();
        assertThat(validateOrigin("https://new.example.com", full)).isEqualTo(ORIGIN_COUNT_ERROR);
        assertThat(validateOrigin("https://new.example.com", full.subList(0, 63))).isNull();
        assertThat(validateOrigin(full.get(0), full)).isEqualTo(ORIGIN_DUPLICATE_ERROR);
    }

    @Test
    @DisplayName("형식 오류 문구는 예시를 담는다")
    void formatErrorMessageHasExample() {
        assertThat(ORIGIN_FORMAT_ERROR).isEqualTo("형식이 올바르지 않습니다. https:// 로 시작하고 경로 없이 입력하세요. 예: https://admin.example.com");
    }

    // ── timeoutError ──
    @Test
    @DisplayName("1–1440 정수만 통과")
    void onlyIntegers1To1440Pass() {
        for (String v : List.of("1", "30", "1440", " 45 ")) assertThat(timeoutError(v)).as(v).isNull();
        for (String v : List.of("0", "1441", "", "-5", "1.5", "abc")) assertThat(timeoutError(v)).as(v).isEqualTo(TIMEOUT_ERROR);
    }

    @Test
    @DisplayName("오류 문구")
    void timeoutErrorMessage() {
        assertThat(TIMEOUT_ERROR).isEqualTo("1–1440 사이의 분을 입력하세요. 비워 둘 수 없습니다.");
    }

    // ── validate(UpdateRequest) — Java 쪽 추가 ──
    @Test
    @DisplayName("validate: 필드명을 키로 한 오류 맵(통과하면 빈 맵)")
    void validateReturnsErrorsByFieldName() {
        assertThat(validate(new SecurityPolicyDto.UpdateRequest(30, true, List.of("https://a.example.com")))).isEmpty();
        Map<String, String> errors = validate(new SecurityPolicyDto.UpdateRequest(0, true,
                List.of("https://a.example.com", "https://A.example.com")));
        assertThat(errors).containsEntry("sessionIdleTimeoutMinutes", TIMEOUT_ERROR)
                .containsEntry("corsAllowlist", ORIGIN_DUPLICATE_ERROR);
        assertThat(validate(new SecurityPolicyDto.UpdateRequest(1441, false, List.of("http://x.com"))))
                .containsEntry("sessionIdleTimeoutMinutes", TIMEOUT_ERROR)
                .containsEntry("corsAllowlist", ORIGIN_FORMAT_ERROR);
    }
}
