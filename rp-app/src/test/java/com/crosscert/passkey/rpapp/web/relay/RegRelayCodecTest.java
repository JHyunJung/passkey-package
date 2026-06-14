package com.crosscert.passkey.rpapp.web.relay;

import com.crosscert.passkey.rpapp.config.RelayProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RegRelayCodec 단위 테스트. HMAC-SHA256 으로 서명한 무상태 relay 토큰의
 * round-trip·서명변조거부(서명/페이로드)·만료거부·키불일치거부를 검증한다(spec §5).
 */
class RegRelayCodecTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private static RegRelayCodec codec(String secret, Duration ttl) {
        return new RegRelayCodec(new RelayProperties(secret, ttl), MAPPER);
    }

    /** 테스트에서 직접 코덱과 동일한 방식으로 HMAC-SHA256 서명을 만든다(payload64 → sig64). */
    private static String hmacB64(String secret, String payloadB64) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return B64.encodeToString(mac.doFinal(payloadB64.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── 1. round-trip ─────────────────────────────────────────────

    @Test
    void encodeThenDecode_roundTrips() {
        RegRelayCodec codec = codec("test-secret-1", Duration.ofMinutes(5));

        String token = codec.encode("reg-token-xyz", "user-handle-abc", "alice", "Alice Example");
        RegRelayCodec.RegRelay decoded = codec.decode(token);

        assertThat(decoded.getRegistrationToken()).isEqualTo("reg-token-xyz");
        assertThat(decoded.getUserHandle()).isEqualTo("user-handle-abc");
        assertThat(decoded.getUsername()).isEqualTo("alice");
        assertThat(decoded.getDisplayName()).isEqualTo("Alice Example");
    }

    // ── 2. 서명 변조 거부 ──────────────────────────────────────────

    @Test
    void decode_rejectsTamperedSignature() {
        RegRelayCodec codec = codec("test-secret-2", Duration.ofMinutes(5));
        String token = codec.encode("rt", "uh", "un", "dn");

        // payload "." sig 에서 sig 부분의 첫 바이트를 디코드 레벨에서 1비트 뒤집는다.
        // (base64url 마지막 문자 치환은 패딩 비트 영향으로 flaky → 바이트 레벨 변조.)
        int dot = token.lastIndexOf('.');
        String p64 = token.substring(0, dot);
        byte[] sig = B64D.decode(token.substring(dot + 1));
        sig[0] ^= 0x01;
        String tampered = p64 + "." + B64.encodeToString(sig);

        assertThatThrownBy(() -> codec.decode(tampered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bad signature");
    }

    // ── 2b. payload 변조 거부 (서명은 그대로, userHandle 만 조작) ───────────
    //
    // 핵심 보안 계약: 클라이언트가 userHandle 을 바꿔치기할 수 없어야 한다(spec §5).
    // 서명을 그대로 둔 채 payload 의 uh 만 바꾸면 HMAC 이 어긋나 거부되어야 한다.
    // (서명-바이트 변조만으로는 "서명이 payload 에 실제로 바인딩됐는지"를 증명하지 못한다.)

    @Test
    void decode_rejectsTamperedPayloadWithOriginalSignature() {
        String secret = "test-secret-payload";
        RegRelayCodec codec = codec(secret, Duration.ofMinutes(5));
        String token = codec.encode("rt", "victim-handle", "victim-user", "Victim");

        int dot = token.lastIndexOf('.');
        String origSig = token.substring(dot + 1);

        // payload 를 디코드 → uh 를 공격자 값으로 교체 → 같은 길이/형식 유지(원본 서명 재사용 시도).
        String payloadJson = new String(B64D.decode(token.substring(0, dot)), StandardCharsets.UTF_8);
        assertThat(payloadJson).contains("victim-handle");
        String tamperedJson = payloadJson.replace("victim-handle", "attacker-handl");  // 같은 길이
        String tamperedP64 = B64.encodeToString(tamperedJson.getBytes(StandardCharsets.UTF_8));

        String forged = tamperedP64 + "." + origSig;

        assertThatThrownBy(() -> codec.decode(forged))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bad signature");
    }

    // ── 2c. username 변조 거부 (서명이 username 도 바인딩하는지) ──────────────
    //
    // username/displayName 은 finish 가 pending 없이 user 를 결정적으로 생성할 때 쓰이므로
    // (P0-4), 이 필드들도 서명으로 보호되어야 한다. username 만 바꿔치기하면 거부되어야 한다.

    @Test
    void decode_rejectsTamperedUsernameWithOriginalSignature() {
        String secret = "test-secret-username";
        RegRelayCodec codec = codec(secret, Duration.ofMinutes(5));
        String token = codec.encode("rt", "uh", "victim-user", "Victim");

        int dot = token.lastIndexOf('.');
        String origSig = token.substring(dot + 1);

        String payloadJson = new String(B64D.decode(token.substring(0, dot)), StandardCharsets.UTF_8);
        assertThat(payloadJson).contains("victim-user");
        String tamperedJson = payloadJson.replace("victim-user", "attackr-user");  // 같은 길이
        String tamperedP64 = B64.encodeToString(tamperedJson.getBytes(StandardCharsets.UTF_8));

        String forged = tamperedP64 + "." + origSig;

        assertThatThrownBy(() -> codec.decode(forged))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bad signature");
    }

    // ── 3. 만료 거부 (정상 TTL + 과거 exp 를 올바르게 서명) ─────────────────
    //
    // 음수 TTL 로 만료를 흉내내면 "음수 TTL 거부"인지 "exp 검사"인지 구분이 안 된다.
    // 그래서 정상(양수) TTL 코덱이 받아들이는 형식 그대로, exp 만 과거로 둔 *유효 서명*
    // 토큰을 손수 만들어 decode 가 exp 를 실제로 검사하는지 증명한다.

    @Test
    void decode_rejectsExpiredToken_withValidSignatureUnderPositiveTtl() {
        String secret = "test-secret-3";
        RegRelayCodec codec = codec(secret, Duration.ofMinutes(5));  // 정상 양수 TTL

        long pastExp = Instant.now().getEpochSecond() - 60;          // 이미 만료
        // ObjectNodePayload(rt, uh, un, dn, exp) 와 동일한 JSON 형식으로 직접 직렬화 후 유효 서명.
        String payloadJson = "{\"rt\":\"rt\",\"uh\":\"uh\",\"un\":\"un\",\"dn\":\"dn\",\"exp\":" + pastExp + "}";
        String p64 = B64.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        String expiredButValidlySigned = p64 + "." + hmacB64(secret, p64);

        assertThatThrownBy(() -> codec.decode(expiredButValidlySigned))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expired");
    }

    // ── 3b. 레거시/불완전 payload 거부 (un/dn 누락) ─────────────────────
    //
    // 배포 직전 발급된 레거시 토큰은 {rt, uh, exp} 만 가져 un/dn 이 null 로 역직렬화된다.
    // 서명·만료가 유효해도, upstream finish 전에 거부해야 confirmRegistration 의 NPE(500)·
    // 매핑 누락을 막는다(P0-4 무상태 계약).

    @Test
    void decode_rejectsLegacyPayloadMissingUsernameAndDisplayName() {
        String secret = "test-secret-legacy";
        RegRelayCodec codec = codec(secret, Duration.ofMinutes(5));

        long futureExp = Instant.now().getEpochSecond() + 300;        // 유효(미만료)
        // 레거시 형식: rt/uh/exp 만, un/dn 없음. 유효 서명을 붙여도 decode 가 거부해야 한다.
        String payloadJson = "{\"rt\":\"rt\",\"uh\":\"uh\",\"exp\":" + futureExp + "}";
        String p64 = B64.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        String legacyButValidlySigned = p64 + "." + hmacB64(secret, p64);

        assertThatThrownBy(() -> codec.decode(legacyButValidlySigned))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("incomplete payload");
    }

    // ── 4. 다른 키로 서명한 토큰 거부 ──────────────────────────────

    @Test
    void decode_rejectsTokenSignedWithDifferentKey() {
        RegRelayCodec codecA = codec("secret-A", Duration.ofMinutes(5));
        RegRelayCodec codecB = codec("secret-B", Duration.ofMinutes(5));

        String token = codecA.encode("rt", "uh", "un", "dn");

        assertThatThrownBy(() -> codecB.decode(token))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bad signature");
    }
}
