package com.crosscert.passkey.admin.auth;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

/**
 * RFC 6238 TOTP (Time-based One-Time Password) implementation.
 *
 * <p>HMAC-SHA1, 6 digits, 30-second time step. Verification accepts the
 * current step plus ±1 adjacent steps to tolerate client/server clock skew
 * (RFC 6238 §5.2). Secrets are 160-bit (20 bytes) of {@link SecureRandom}
 * entropy, Base32-encoded (RFC 4648, no padding) so they can be typed into
 * standard authenticator apps (Google Authenticator, Authy, …).
 *
 * <p>No external TOTP/Base32 library is required — Base32 is hand-rolled
 * here to avoid adding a dependency to the admin-app module.
 */
@Service
public class TotpService {

    private static final int DIGITS = 6;
    private static final long STEP_MILLIS = 30_000L;
    private static final int SKEW_STEPS = 1; // ±1 window
    private static final int SECRET_BYTES = 20; // 160-bit
    private static final int[] POW10 = {1, 10, 100, 1_000, 10_000, 100_000, 1_000_000};

    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private final SecureRandom random = new SecureRandom();

    /** Generate a new Base32-encoded 160-bit secret suitable for an authenticator app. */
    public String newSecretBase32() {
        byte[] buf = new byte[SECRET_BYTES];
        random.nextBytes(buf);
        return base32Encode(buf);
    }

    /** Generate the 6-digit TOTP for {@code secret} at the given epoch-millis. */
    public String generate(String secretBase32, long epochMillis) {
        long counter = Math.floorDiv(epochMillis, STEP_MILLIS);
        return generateForCounter(base32Decode(secretBase32), counter);
    }

    /**
     * Verify {@code code} against {@code secret} at the given time, accepting
     * the current step and ±{@value #SKEW_STEPS} adjacent steps. Constant-time
     * comparison against each candidate to avoid leaking which window matched.
     */
    public boolean verifyAt(String secretBase32, String code, long epochMillis) {
        if (code == null) return false;
        String trimmed = code.trim();
        if (trimmed.length() != DIGITS) return false;
        byte[] key;
        try {
            key = base32Decode(secretBase32);
        } catch (RuntimeException ex) {
            return false;
        }
        long counter = Math.floorDiv(epochMillis, STEP_MILLIS);
        boolean match = false;
        for (long c = counter - SKEW_STEPS; c <= counter + SKEW_STEPS; c++) {
            String candidate = generateForCounter(key, c);
            // OR-accumulate so every window is evaluated (no early return → no timing oracle).
            match |= constantTimeEquals(candidate, trimmed);
        }
        return match;
    }

    /** Test-only hook: expose the decoded secret length to assert entropy size. */
    public byte[] decodeSecretForTest(String secretBase32) {
        return base32Decode(secretBase32);
    }

    private String generateForCounter(byte[] key, long counter) {
        byte[] msg = new byte[8];
        for (int i = 7; i >= 0; i--) {
            msg[i] = (byte) (counter & 0xff);
            counter >>>= 8;
        }
        byte[] hash = hmacSha1(key, msg);
        // Dynamic truncation (RFC 4226 §5.3).
        int offset = hash[hash.length - 1] & 0x0f;
        int binary = ((hash[offset] & 0x7f) << 24)
                | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8)
                | (hash[offset + 3] & 0xff);
        int otp = binary % POW10[DIGITS];
        return String.format("%0" + DIGITS + "d", otp);
    }

    private static byte[] hmacSha1(byte[] key, byte[] msg) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            return mac.doFinal(msg);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA1 unavailable", e);
        }
    }

    /** Length-independent constant-time string compare. */
    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] y = b.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        return java.security.MessageDigest.isEqual(x, y);
    }

    // --- Base32 (RFC 4648, no padding) ----------------------------------

    static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                int idx = (buffer >> (bitsLeft - 5)) & 0x1f;
                bitsLeft -= 5;
                sb.append(BASE32_ALPHABET.charAt(idx));
            }
        }
        if (bitsLeft > 0) {
            int idx = (buffer << (5 - bitsLeft)) & 0x1f;
            sb.append(BASE32_ALPHABET.charAt(idx));
        }
        return sb.toString();
    }

    static byte[] base32Decode(String s) {
        if (s == null) throw new IllegalArgumentException("null secret");
        String clean = s.trim().replace("=", "").replace(" ", "").toUpperCase(java.util.Locale.ROOT);
        if (clean.isEmpty()) throw new IllegalArgumentException("empty secret");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(clean.length() * 5 / 8 + 1);
        int buffer = 0;
        int bitsLeft = 0;
        for (int i = 0; i < clean.length(); i++) {
            int val = BASE32_ALPHABET.indexOf(clean.charAt(i));
            if (val < 0) throw new IllegalArgumentException("invalid base32 character");
            buffer = (buffer << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out.write((buffer >> (bitsLeft - 8)) & 0xff);
                bitsLeft -= 8;
            }
        }
        return out.toByteArray();
    }
}
