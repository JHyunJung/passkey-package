package com.crosscert.passkey.webauthn.attestation;

import java.security.MessageDigest;
import java.util.Arrays;

/**
 * android-key attestation extension(OID 1.3.6.1.4.1.11129.2.1.17, KeyDescription) 파서.
 *
 * <p>{@code X509Certificate.getExtensionValue}는 KeyDescription SEQUENCE를 한 겹 감싼
 * 바깥 DER OCTET STRING을 반환한다. 그 안의 KeyDescription SEQUENCE는 다음 형태다
 * (Android Keystore Attestation / WebAuthn §8.4):
 * <pre>
 *   KeyDescription ::= SEQUENCE {
 *       attestationVersion        INTEGER,
 *       attestationSecurityLevel  ENUMERATED,
 *       keymasterVersion          INTEGER,
 *       keymasterSecurityLevel    ENUMERATED,
 *       attestationChallenge      OCTET STRING,   -- == clientDataHash 이어야 함
 *       uniqueId                  OCTET STRING,
 *       softwareEnforced          AuthorizationList,
 *       teeEnforced               AuthorizationList }
 * </pre>
 *
 * <p>{@code attestationChallenge}는 KeyDescription SEQUENCE의 <b>5번째 요소</b>다. 앞 4개
 * 요소(version INTEGER, securityLevel ENUMERATED, kmVersion INTEGER, kmSecurityLevel
 * ENUMERATED)를 순서대로 건너뛴 뒤, 5번째 요소가 OCTET STRING(tag 0x04)인지 확인하고 그
 * 값을 challenge로 사용한다. ("첫 OCTET STRING 찾기" 휴리스틱은 너무 관대하므로 실제
 * 필드 순서를 따라 엄격히 파싱한다 — codex P2.)
 *
 * <p><b>모든 길이 검증은 뺄셈 형태({@code len > buf.length - off})로 한다</b> — 조작된
 * 4바이트 길이에서 {@code off + len}이 부호 있는 int 오버플로로 음수가 되어 검사를 통과하는
 * 일을 막기 위함이다. 잘못된 DER는 항상 {@link AttestationException}을 던진다
 * (IllegalArgumentException/AIOOBE 누출 금지).
 */
final class AndroidKeyExtension {

    private AndroidKeyExtension() {}

    /**
     * 바깥 OCTET STRING을 벗기고, KeyDescription SEQUENCE의 5번째 요소
     * (attestationChallenge OCTET STRING)가 clientDataHash와 같은지 검사한다.
     *
     * @param extensionValue {@code cert.getExtensionValue(oid)}의 반환값 (바깥 OCTET STRING DER)
     */
    static boolean challengeMatches(byte[] extensionValue, byte[] clientDataHash) {
        if (extensionValue == null) {
            throw new AttestationException("android-key: KeyDescription extension missing");
        }
        // 1) 바깥 OCTET STRING(0x04) 한 겹 언랩 → KeyDescription SEQUENCE 바이트
        byte[] keyDescription = unwrapOctetString(extensionValue);
        // 2) SEQUENCE 5번째 요소(attestationChallenge OCTET STRING) 추출
        byte[] challenge = attestationChallenge(keyDescription);
        // 타이밍-세이프 비교 (clientDataHash 비밀은 아니지만 일관성 유지)
        return challenge != null && MessageDigest.isEqual(challenge, clientDataHash);
    }

    /** OCTET STRING(tag 0x04) 한 겹 언랩 → value 바이트 반환. */
    private static byte[] unwrapOctetString(byte[] der) {
        if (der.length < 2 || (der[0] & 0xff) != 0x04) {
            throw new AttestationException("android-key: expected outer OCTET STRING");
        }
        int[] lv = readLen(der, 1);             // {length, valueOffset}
        int len = lv[0], off = lv[1];
        if (len > der.length - off) {           // 뺄셈 형태 (오버플로 방지)
            throw new AttestationException("android-key: outer OCTET STRING length overrun");
        }
        return Arrays.copyOfRange(der, off, off + len);
    }

    /**
     * KeyDescription SEQUENCE(0x30)에서 5번째 요소(attestationChallenge OCTET STRING)의
     * value를 반환한다. 앞 4개 요소를 순서대로 건너뛰고 5번째가 OCTET STRING인지 확인한다.
     * "첫 OCTET STRING 찾기"보다 엄격 — 실제 필드 순서를 따른다 (codex P2).
     */
    private static byte[] attestationChallenge(byte[] der) {
        if (der.length < 2 || (der[0] & 0xff) != 0x30) {
            throw new AttestationException("android-key: KeyDescription not a SEQUENCE");
        }
        int[] seq = readLen(der, 1);
        int pos = seq[1];
        int end = seq[1] + seq[0];
        if (seq[0] > der.length - seq[1]) {     // 뺄셈 형태 (오버플로 방지)
            throw new AttestationException("android-key: SEQUENCE length overrun");
        }
        // 앞 4개 요소(version INTEGER, secLevel ENUM, kmVersion INTEGER, kmSecLevel ENUM) skip.
        // 앞 4개는 모두 universal primitive(INTEGER 0x02 / ENUMERATED 0x0A)이므로 tag 무관 skip으로 충분.
        for (int i = 0; i < 4; i++) {
            pos = skipElement(der, pos, end);
        }
        // 5번째 = attestationChallenge, 반드시 OCTET STRING(0x04).
        if (pos >= end) {
            throw new AttestationException("android-key: KeyDescription too few elements");
        }
        int tag = der[pos] & 0xff;
        if (tag != 0x04) {
            throw new AttestationException(
                    "android-key: attestationChallenge not OCTET STRING (tag=" + tag + ")");
        }
        int[] tl = readLen(der, pos + 1);
        int len = tl[0], valOff = tl[1];
        if (len > end - valOff) {               // 뺄셈 형태, SEQUENCE end 경계 기준
            throw new AttestationException("android-key: attestationChallenge length overrun");
        }
        return Arrays.copyOfRange(der, valOff, valOff + len);
    }

    /** der[pos]의 한 요소(tag+len+value)를 건너뛰고 다음 요소 offset 반환. SEQUENCE end 경계 검증 포함. */
    private static int skipElement(byte[] der, int pos, int end) {
        if (pos + 1 >= end) {                   // tag 1바이트 + 최소 길이 1바이트 필요
            throw new AttestationException("android-key: KeyDescription truncated element");
        }
        int[] tl = readLen(der, pos + 1);
        int len = tl[0], valOff = tl[1];
        if (len > end - valOff) {               // 뺄셈 형태, SEQUENCE end 경계 기준
            throw new AttestationException("android-key: element length overrun");
        }
        return valOff + len;
    }

    /**
     * der[offset]부터 DER 길이를 읽어 {length, valueOffset}을 반환한다.
     * 단문(0x00~0x7f) 및 장문(0x81/0x82/0x83/0x84 = 1~4바이트) 형태 지원.
     * 모든 경계 검사는 뺄셈 형태로 한다.
     */
    private static int[] readLen(byte[] der, int offset) {
        if (offset >= der.length) {
            throw new AttestationException("android-key: truncated DER length");
        }
        int b = der[offset] & 0xff;
        if (b < 0x80) {
            return new int[]{b, offset + 1};
        }
        int n = b & 0x7f;
        // n == 0: 무기한(indefinite) 길이 — DER에서 금지. n > 4: int 범위 초과.
        if (n == 0 || n > 4 || n > der.length - (offset + 1)) {
            throw new AttestationException("android-key: bad DER length encoding");
        }
        int len = 0;
        for (int i = 0; i < n; i++) {
            len = (len << 8) | (der[offset + 1 + i] & 0xff);
        }
        if (len < 0) {  // 4바이트 길이가 최상위 비트를 세워 음수가 된 경우
            throw new AttestationException("android-key: DER length overflow");
        }
        return new int[]{len, offset + 1 + n};
    }
}
