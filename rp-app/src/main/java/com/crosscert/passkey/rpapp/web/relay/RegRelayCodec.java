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
 * 등록 relay 토큰 코덱(spec §5). {registrationToken, userHandle, username, displayName, exp} 를
 * HMAC-SHA256 으로 서명한 불투명 토큰 "base64url(payloadJson).base64url(hmac)" 을 만들고 검증한다.
 * 서명이 맞아야 payload 를 신뢰 → 클라이언트가 userHandle 을 조작할 수 없다. 무상태(자기완결).
 * username/displayName 을 함께 봉인해 finish 가 pending 없이도 결정적으로 user 를 확정할 수 있다(P0-4).
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
        // 필수 4필드 검증. 배포 직전 발급된 레거시 토큰(un/dn 없음)이 TTL 내에 finish 되면
        // un/dn 이 null 로 역직렬화되는데, 그대로 두면 upstream finish 후 confirmRegistration 의
        // byUsername.put(null,..) 에서 NPE(500) → credential 은 생성됐는데 매핑 누락. upstream
        // 호출 전 여기서 거부해 클라이언트가 등록을 깨끗이 재시작하게 한다(P0-4 무상태 계약).
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

    /**
     * 직렬화 payload. 필드명을 짧게(rt/uh/un/dn/exp) 유지. @JsonProperty 로 생성자 바인딩을
     * 명시해 jackson-module-kotlin 유무와 무관하게 record 와 동일 복원.
     */
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
