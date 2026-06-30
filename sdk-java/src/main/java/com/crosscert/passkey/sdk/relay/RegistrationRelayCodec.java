package com.crosscert.passkey.sdk.relay;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 등록 릴레이 토큰 코덱. {registrationToken, userHandle, username, displayName, exp} 를
 * HMAC-SHA256 으로 서명해 "base64url(payloadJson).base64url(hmac)" 형태의 불투명 토큰을
 * 만들고 검증한다.
 *
 * <p>서명이 맞아야 payload 를 신뢰하므로 클라이언트가 userHandle 을 조작할 수 없다. 토큰이
 * 자기완결적이라 서버에 pending 상태를 두지 않고도 finish 단계에서 사용자를 확정할 수 있다
 * (무상태 설계). passkey-app 의 challenge 만료(기본 5분)에 맞춰 ttl 을 설정하라.
 *
 * <p>Spring 비의존 순수 클래스다. secret 의 출처·보호(데모키 거부 등)는 호출자(RP) 책임이다.
 */
public final class RegistrationRelayCodec {

    /** 복원된 relay payload. */
    public record RegistrationRelay(
            String registrationToken,
            String userHandle,
            String username,
            String displayName
    ) {}

    private static final String HMAC_ALG = "HmacSHA256";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    // ObjectMapper 는 설정 완료 후 thread-safe 라 인스턴스마다 만들 필요 없이 공유한다(Jackson 권장).
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final byte[] key;
    private final long ttlSeconds;
    private final Clock clock;

    public RegistrationRelayCodec(byte[] secret, Duration ttl, Clock clock) {
        // 공개 SDK API — 잘못된 인자는 생성 시점에 fail-fast 시켜 지연된 NPE 를 막는다.
        this.key = Objects.requireNonNull(secret, "secret").clone();
        this.ttlSeconds = Objects.requireNonNull(ttl, "ttl").toSeconds();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** {rt, uh, un, dn, exp} 를 서명한 relay 토큰 생성. */
    public String encode(String registrationToken, String userHandle, String username, String displayName) {
        long exp = clock.instant().getEpochSecond() + ttlSeconds;
        Payload p = new Payload(registrationToken, userHandle, username, displayName, exp);
        byte[] payload;
        try {
            payload = MAPPER.writeValueAsBytes(p);
        } catch (Exception e) {
            throw new IllegalStateException("relay encode failed", e);
        }
        String p64 = B64.encodeToString(payload);
        String sig = B64.encodeToString(hmac(p64));
        return p64 + "." + sig;
    }

    /** relay 토큰 검증·복원. 서명 불일치/만료/형식오류면 IllegalArgumentException. */
    public RegistrationRelay decode(String token) {
        if (token == null) throw new IllegalArgumentException("relay token missing");
        int dot = token.indexOf('.');
        if (dot < 0) throw new IllegalArgumentException("relay token malformed");
        String p64 = token.substring(0, dot);
        String sig = token.substring(dot + 1);
        byte[] expected = hmac(p64);
        byte[] actual;
        try {
            actual = B64D.decode(sig);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("relay token bad signature encoding");
        }
        // 상수시간 비교(타이밍 공격 방지).
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new IllegalArgumentException("relay token bad signature");
        }
        Payload p;
        try {
            p = MAPPER.readValue(B64D.decode(p64), Payload.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("relay token bad payload");
        }
        if (p.exp() < clock.instant().getEpochSecond()) {
            throw new IllegalArgumentException("relay token expired");
        }
        // 필수 4필드 검증. 값이 빠진 토큰을 upstream 호출 전에 거부해 매핑 누락으로 인한 오류를 막는다.
        if (p.rt() == null || p.uh() == null || p.un() == null || p.dn() == null) {
            throw new IllegalArgumentException("relay token incomplete payload");
        }
        return new RegistrationRelay(p.rt(), p.uh(), p.un(), p.dn());
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(key, HMAC_ALG));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("relay hmac failed", e);
        }
    }

    /** 직렬화 payload. 필드명을 짧게(rt/uh/un/dn/exp) 유지하고 @JsonProperty 로 매핑을 명시한다. */
    record Payload(
            @JsonProperty("rt") String rt,
            @JsonProperty("uh") String uh,
            @JsonProperty("un") String un,
            @JsonProperty("dn") String dn,
            @JsonProperty("exp") long exp
    ) {
        @JsonCreator
        Payload {}
    }
}
