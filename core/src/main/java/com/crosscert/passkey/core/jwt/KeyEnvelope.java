package com.crosscert.passkey.core.jwt;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM envelope for sealed PKCS8 private keys.
 *
 * <p>Sealed format: {@code nonce(12) || ciphertext || tag(16)}.
 *
 * <p>Master key comes from {@code passkey.key-envelope.master-key}
 * property. admin-app's local profile hard-codes a dev value, and the dev
 * profile of both admin-app and passkey-app falls back to the same value
 * when the env var is unset; production sets
 * {@code PASSKEY_KEY_ENVELOPE_MASTER_KEY} env var.
 *
 * <p>prod·qa yml 은 이 환경변수의 기본값이 빈 문자열이라, 설정을 빠뜨리면 생성자의 32바이트 검사에서 기동이 실패한다.
 * 같은 master key 에 기대는 것: signing_key 의 봉인된 개인키({@link SigningKeyProvider}),
 * admin MFA secret(MfaSecretCipher 가 seal/open), admin recovery code 의 HMAC 키({@link #deriveSubkey}).
 * passkey-app·admin-app 이 같은 값을 써야 하고, 값을 바꾸면 기존 서명 키를 열지 못해
 * {@code SigningKeyProvider.init()} 에서 기동이 실패한다 — MFA secret 과 recovery code 도 다시 만들어야 한다.
 *
 * <p>The seal/open API never logs or throws the master key. Tag
 * mismatch (tamper or wrong key) becomes a generic
 * IllegalStateException("envelope authentication failed") so
 * callers cannot probe whether a wrong key or wrong ciphertext
 * caused the failure.
 */
@Component
public class KeyEnvelope {

    private static final int NONCE_LEN_BYTES = 12;
    private static final int TAG_LEN_BITS = 128;
    private static final int MASTER_KEY_BYTES = 32;

    private final SecretKey masterKey;
    private final SecureRandom random;

    public KeyEnvelope(@Value("${passkey.key-envelope.master-key}") String masterB64,
                       SecureRandom random) {
        byte[] keyBytes = Base64.getDecoder().decode(masterB64);
        if (keyBytes.length != MASTER_KEY_BYTES) {
            throw new IllegalStateException(
                    "master key must be 32 bytes (AES-256); got " + keyBytes.length);
        }
        this.masterKey = new SecretKeySpec(keyBytes, "AES");
        // SecretKeySpec copies the bytes; zero our local reference to
        // shorten the master key's residence in the heap.
        java.util.Arrays.fill(keyBytes, (byte) 0);
        this.random = random;
    }

    /**
     * master key 에서 용도별 하위 키(32바이트)를 파생한다: HMAC-SHA256(master, purpose).
     * 용도 문자열이 다르면 키가 분리되므로 암호화 키(master)를 다른 목적에 그대로 쓰지 않는다.
     * 예: 관리자 recovery code 저장용 HMAC 키(pepper) — DB 밖의 비밀이라 DB 만 유출되면 대입할 수 없다.
     */
    public byte[] deriveSubkey(String purpose) {
        byte[] master = masterKey.getEncoded();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(master, "HmacSHA256"));
            return mac.doFinal(purpose.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("subkey derivation failed", e);
        } finally {
            java.util.Arrays.fill(master, (byte) 0);
        }
    }

    public byte[] seal(byte[] pkcs8) {
        byte[] nonce = new byte[NONCE_LEN_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, masterKey,
                    new GCMParameterSpec(TAG_LEN_BITS, nonce));
            byte[] ctAndTag = c.doFinal(pkcs8);
            return ByteBuffer.allocate(NONCE_LEN_BYTES + ctAndTag.length)
                    .put(nonce).put(ctAndTag).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("envelope seal failed", e);
        }
    }

    public byte[] open(byte[] envelope) {
        if (envelope.length < NONCE_LEN_BYTES + (TAG_LEN_BITS / 8)) {
            throw new IllegalStateException("envelope authentication failed");
        }
        byte[] nonce = new byte[NONCE_LEN_BYTES];
        System.arraycopy(envelope, 0, nonce, 0, NONCE_LEN_BYTES);
        byte[] ctAndTag = new byte[envelope.length - NONCE_LEN_BYTES];
        System.arraycopy(envelope, NONCE_LEN_BYTES, ctAndTag, 0, ctAndTag.length);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, masterKey,
                    new GCMParameterSpec(TAG_LEN_BITS, nonce));
            return c.doFinal(ctAndTag);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("envelope authentication failed", e);
        }
    }
}
