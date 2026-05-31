package com.crosscert.passkey.core.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 공유 암호/해시/마스킹 유틸. admin-app 의 sha256Hex/hex/maskEmail/토큰 생성 중복을 통합.
 * 모든 메서드는 순수 함수(maskEmail) 또는 stateless(해시)이며 RNG 는 thread-safe SecureRandom 단일 인스턴스.
 */
public final class CryptoUtils {

    private static final SecureRandom RNG = new SecureRandom();

    private CryptoUtils() {}

    /** SHA-256 해시를 소문자 hex 문자열로. */
    public static String sha256Hex(String plaintext) {
        try {
            return hex(MessageDigest.getInstance("SHA-256")
                    .digest(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** byte[] 를 소문자 hex 문자열로. */
    public static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    /** 로깅용 이메일 마스킹: 첫 글자 + "***" + "@domain". null/blank→"(unknown)", @ 없음→"***". */
    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) return "(unknown)";
        int at = email.indexOf('@');
        if (at <= 0) return "***";
        return email.charAt(0) + "***" + email.substring(at);
    }

    /** prefix + (32 random bytes 의 hex). self-service 토큰 발급용. */
    public static String randomToken(String prefix) {
        byte[] raw = new byte[32];
        RNG.nextBytes(raw);
        return prefix + hex(raw);
    }
}
