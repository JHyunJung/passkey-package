package com.crosscert.passkey.admin.web.format;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.crosscert.passkey.admin.web.format.ApkKeyHash.APK_KEY_HASH_PREFIX;
import static org.assertj.core.api.Assertions.assertThat;

/** ← admin-ui/src/lib/apkKeyHash.test.ts */
class ApkKeyHashTest {

    // 32바이트 0x00 → base64url(32x 0x00) = 43 'A'.
    private static final String ALL_ZERO_HEX = "00:".repeat(31) + "00";
    private static final String EXPECTED_ZERO = APK_KEY_HASH_PREFIX + "A".repeat(43);

    @Test
    @DisplayName("콜론 구분 64 hex 를 apk-key-hash 로 변환한다")
    void convertsColonSeparated64Hex() {
        assertThat(ApkKeyHash.fromFingerprint(ALL_ZERO_HEX)).isEqualTo(new ApkKeyHash.Result(true, EXPECTED_ZERO, null));
    }

    @Test
    @DisplayName("콜론 없는 64 hex 도 허용한다")
    void allows64HexWithoutColons() {
        assertThat(ApkKeyHash.fromFingerprint("00".repeat(32))).isEqualTo(new ApkKeyHash.Result(true, EXPECTED_ZERO, null));
    }

    @Test
    @DisplayName("대문자/소문자 hex 를 모두 허용한다")
    void allowsUpperAndLowerCaseHex() {
        ApkKeyHash.Result upper = ApkKeyHash.fromFingerprint("AB".repeat(32));
        ApkKeyHash.Result lower = ApkKeyHash.fromFingerprint("ab".repeat(32));
        assertThat(upper.ok()).isTrue();
        assertThat(upper).isEqualTo(lower);
    }

    @Test
    @DisplayName("변환 결과는 항상 43자 base64url 이다")
    void resultIsAlways43CharBase64Url() {
        ApkKeyHash.Result r = ApkKeyHash.fromFingerprint("1F".repeat(32));
        assertThat(r.ok()).isTrue();
        assertThat(r.value().substring(APK_KEY_HASH_PREFIX.length())).matches("[A-Za-z0-9_-]{43}");
    }

    @Test
    @DisplayName("hex 길이가 64 가 아니면 거부한다")
    void rejectsWrongLength() {
        assertThat(ApkKeyHash.fromFingerprint("00".repeat(20)).ok()).isFalse(); // 40 hex
        assertThat(ApkKeyHash.fromFingerprint("00".repeat(40)).ok()).isFalse(); // 80 hex
    }

    @Test
    @DisplayName("hex 가 아닌 문자가 있으면 거부한다")
    void rejectsNonHex() {
        assertThat(ApkKeyHash.fromFingerprint("ZZ".repeat(32)).ok()).isFalse();
    }

    @Test
    @DisplayName("빈 입력은 거부한다")
    void rejectsBlank() {
        assertThat(ApkKeyHash.fromFingerprint("   ").ok()).isFalse();
    }

    @Test
    @DisplayName("android prefix 면 true")
    void androidPrefixIsTrue() {
        assertThat(ApkKeyHash.isApkKeyHashOrigin(APK_KEY_HASH_PREFIX + "A".repeat(43))).isTrue();
    }

    @Test
    @DisplayName("https origin 이면 false")
    void httpsOriginIsFalse() {
        assertThat(ApkKeyHash.isApkKeyHashOrigin("https://example.com")).isFalse();
    }
}
