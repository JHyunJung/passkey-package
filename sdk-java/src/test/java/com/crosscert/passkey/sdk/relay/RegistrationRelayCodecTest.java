package com.crosscert.passkey.sdk.relay;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RegistrationRelayCodec 단위 테스트. HMAC-SHA256 무상태 relay 토큰의
 * round-trip·서명/페이로드 변조거부·만료거부·키불일치거부를 검증한다.
 * 만료는 주입형 Clock 으로 결정적으로 테스트한다.
 */
class RegistrationRelayCodecTest {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-06-30T00:00:00Z"), ZoneOffset.UTC);

    private static RegistrationRelayCodec codec(String secret, Duration ttl) {
        return new RegistrationRelayCodec(secret.getBytes(StandardCharsets.UTF_8), ttl, FIXED);
    }

    private static RegistrationRelayCodec codec(String secret, Duration ttl, Clock clock) {
        return new RegistrationRelayCodec(secret.getBytes(StandardCharsets.UTF_8), ttl, clock);
    }

    private static String hmacB64(String secret, String payloadB64) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return B64.encodeToString(mac.doFinal(payloadB64.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void encodeThenDecode_roundTrips() {
        RegistrationRelayCodec codec = codec("test-secret-1", Duration.ofMinutes(5));
        String token = codec.encode("reg-token-xyz", "user-handle-abc", "alice", "Alice Example");
        RegistrationRelayCodec.RegistrationRelay d = codec.decode(token);
        assertThat(d.registrationToken()).isEqualTo("reg-token-xyz");
        assertThat(d.userHandle()).isEqualTo("user-handle-abc");
        assertThat(d.username()).isEqualTo("alice");
        assertThat(d.displayName()).isEqualTo("Alice Example");
    }

    @Test
    void decode_rejectsTamperedSignature() {
        RegistrationRelayCodec codec = codec("test-secret-2", Duration.ofMinutes(5));
        String token = codec.encode("rt", "uh", "un", "dn");
        int dot = token.lastIndexOf('.');
        String p64 = token.substring(0, dot);
        byte[] sig = B64D.decode(token.substring(dot + 1));
        sig[0] ^= 0x01;
        String tampered = p64 + "." + B64.encodeToString(sig);
        assertThatThrownBy(() -> codec.decode(tampered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bad signature");
    }

    @Test
    void decode_rejectsTamperedPayloadWithOriginalSignature() {
        String secret = "test-secret-payload";
        RegistrationRelayCodec codec = codec(secret, Duration.ofMinutes(5));
        String token = codec.encode("rt", "victim-handle", "victim-user", "Victim");
        int dot = token.lastIndexOf('.');
        String origSig = token.substring(dot + 1);
        String payloadJson = new String(B64D.decode(token.substring(0, dot)), StandardCharsets.UTF_8);
        assertThat(payloadJson).contains("victim-handle");
        String tamperedJson = payloadJson.replace("victim-handle", "attacker-handl"); // 같은 길이
        String forged = B64.encodeToString(tamperedJson.getBytes(StandardCharsets.UTF_8)) + "." + origSig;
        assertThatThrownBy(() -> codec.decode(forged))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bad signature");
    }

    @Test
    void decode_rejectsTamperedUsernameWithOriginalSignature() {
        String secret = "test-secret-username";
        RegistrationRelayCodec codec = codec(secret, Duration.ofMinutes(5));
        String token = codec.encode("rt", "uh", "victim-user", "Victim");
        int dot = token.lastIndexOf('.');
        String origSig = token.substring(dot + 1);
        String payloadJson = new String(B64D.decode(token.substring(0, dot)), StandardCharsets.UTF_8);
        assertThat(payloadJson).contains("victim-user");
        String tamperedJson = payloadJson.replace("victim-user", "attackr-user"); // 같은 길이
        String forged = B64.encodeToString(tamperedJson.getBytes(StandardCharsets.UTF_8)) + "." + origSig;
        assertThatThrownBy(() -> codec.decode(forged))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bad signature");
    }

    @Test
    void decode_rejectsExpiredToken() {
        String secret = "test-secret-3";
        // exp 는 FIXED+5분. decode 를 FIXED+6분 시계로 수행해 만료를 결정적으로 검증.
        RegistrationRelayCodec encoder = codec(secret, Duration.ofMinutes(5), FIXED);
        String token = encoder.encode("rt", "uh", "un", "dn");
        Clock later = Clock.fixed(FIXED.instant().plusSeconds(360), ZoneOffset.UTC);
        RegistrationRelayCodec decoder = codec(secret, Duration.ofMinutes(5), later);
        assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void decode_rejectsLegacyPayloadMissingUsernameAndDisplayName() {
        String secret = "test-secret-legacy";
        RegistrationRelayCodec codec = codec(secret, Duration.ofMinutes(5));
        long futureExp = FIXED.instant().getEpochSecond() + 300;
        String payloadJson = "{\"rt\":\"rt\",\"uh\":\"uh\",\"exp\":" + futureExp + "}";
        String p64 = B64.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        String legacy = p64 + "." + hmacB64(secret, p64);
        assertThatThrownBy(() -> codec.decode(legacy))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("incomplete payload");
    }

    @Test
    void decode_rejectsTokenSignedWithDifferentKey() {
        RegistrationRelayCodec a = codec("secret-A", Duration.ofMinutes(5));
        RegistrationRelayCodec b = codec("secret-B", Duration.ofMinutes(5));
        String token = a.encode("rt", "uh", "un", "dn");
        assertThatThrownBy(() -> b.decode(token))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bad signature");
    }
}
