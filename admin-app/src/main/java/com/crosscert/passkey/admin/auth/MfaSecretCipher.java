package com.crosscert.passkey.admin.auth;

import com.crosscert.passkey.core.jwt.KeyEnvelope;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * admin_user.mfa_secret 의 at-rest 봉투 암호화 (P0 잔여 B).
 *
 * <p>저장 형식: {@code "enc:v1:" + base64(KeyEnvelope.seal(utf8(base32)))}.
 * {@link #open}은 프리픽스가 없으면 평문으로 간주해 그대로 반환 — V37 이전에
 * 평문으로 저장된 기존 secret 을 무중단으로 읽기 위한 마이그레이션 경로다.
 * 평문→암호문 전환은 다음 enroll/confirm 시 seal 된 값을 저장하며 자연 발생한다.
 *
 * <p>실패는 KeyEnvelope 와 동일하게 generic IllegalStateException 으로
 * 표면화하여 secret/키 내용이 로그·예외에 새지 않도록 한다.
 */
@Component
public class MfaSecretCipher {

    private static final String PREFIX = "enc:v1:";

    private final KeyEnvelope envelope;

    public MfaSecretCipher(KeyEnvelope envelope) {
        this.envelope = envelope;
    }

    /** Base32 secret → sealed 저장 문자열. */
    public String seal(String base32Secret) {
        if (base32Secret == null) return null;
        byte[] sealed = envelope.seal(base32Secret.getBytes(StandardCharsets.UTF_8));
        return PREFIX + Base64.getEncoder().encodeToString(sealed);
    }

    /** 저장 문자열 → Base32 secret. 프리픽스 없으면 평문으로 간주해 그대로 반환. */
    public String open(String stored) {
        if (stored == null) return null;
        if (!stored.startsWith(PREFIX)) return stored; // legacy 평문
        try {
            byte[] sealed = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            return new String(envelope.open(sealed), StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            throw new IllegalStateException("mfa secret open failed");
        }
    }
}
