package com.crosscert.passkey.rpapp.web.relay;

import com.crosscert.passkey.rpapp.config.RelayProperties;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 등록 릴레이 토큰 코덱. {registrationToken, userHandle, username, displayName, exp} 를 HMAC-SHA256 으로
 * 서명해 "base64url(payloadJson).base64url(hmac)" 형태의 불투명 토큰을 만들고 검증한다.
 *
 * <p>서명이 맞아야 payload 를 신뢰하므로 클라이언트가 userHandle 을 조작할 수 없다. 토큰이 자기완결적이라
 * 서버에 pending 상태를 두지 않고도 finish 단계에서 사용자를 확정할 수 있다(무상태 설계).
 */
@Component
public class RegRelayCodec {

    /** 복원된 relay payload. */
    public record RegRelay(
            String registrationToken,
            String userHandle,
            String username,
            String displayName
    ) {}

    private static final String HMAC_ALG = "HmacSHA256";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final ObjectMapper mapper;
    private final byte[] key;
    private final long ttlSeconds;

    public RegRelayCodec(RelayProperties props, ObjectMapper mapper) {
        this.mapper = mapper;
        this.key = props.secret().getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = props.ttl().toSeconds();
    }

    /** {rt, uh, un, dn, exp} 를 서명한 relay 토큰 생성. */
    public String encode(String registrationToken, String userHandle, String username, String displayName) {
        long exp = Instant.now().getEpochSecond() + ttlSeconds;
        ObjectNodePayload p = new ObjectNodePayload(registrationToken, userHandle, username, displayName, exp);
        byte[] payload;
        try {
            payload = mapper.writeValueAsBytes(p);
        } catch (Exception e) {
            throw new IllegalStateException("relay encode failed", e);
        }
        String p64 = B64.encodeToString(payload);
        String sig = B64.encodeToString(hmac(p64));
        return p64 + "." + sig;
    }

    /** relay 토큰 검증·복원. 서명 불일치/만료/형식오류면 IllegalArgumentException. */
    public RegRelay decode(String token) {
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
        ObjectNodePayload p;
        try {
            p = mapper.readValue(B64D.decode(p64), ObjectNodePayload.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("relay token bad payload");
        }
        if (p.exp() < Instant.now().getEpochSecond()) {
            throw new IllegalArgumentException("relay token expired");
        }
        // 필수 4필드 검증. 값이 빠진 토큰을 upstream 호출 전에 거부해, 매핑 누락으로 인한 오류를 막고
        // 클라이언트가 등록을 처음부터 깨끗이 다시 시작하게 한다.
        if (p.rt() == null || p.uh() == null || p.un() == null || p.dn() == null) {
            throw new IllegalArgumentException("relay token incomplete payload");
        }
        return new RegRelay(p.rt(), p.uh(), p.un(), p.dn());
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

    /** 직렬화 payload. 필드명을 짧게(rt/uh/un/dn/exp) 유지하고 {@code @JsonProperty} 로 매핑을 명시한다. */
    record ObjectNodePayload(
            @JsonProperty("rt") String rt,
            @JsonProperty("uh") String uh,
            @JsonProperty("un") String un,
            @JsonProperty("dn") String dn,
            @JsonProperty("exp") long exp
    ) {
        @JsonCreator
        ObjectNodePayload {}
    }
}
