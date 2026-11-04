package com.crosscert.passkey.admin.web.format;

import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Android 네이티브 WebAuthn 클라이언트의 clientData.origin 은
 * {@code android:apk-key-hash:<base64url(SHA-256(서명인증서))>}(padding 없는 43자) 형태다.
 * keytool 출력의 SHA-256 지문(hex)을 붙여넣으면 base64url 로 변환한다(← admin-ui lib/apkKeyHash.ts).
 */
public final class ApkKeyHash {

    public static final String APK_KEY_HASH_PREFIX = "android:apk-key-hash:";

    private static final Pattern STRIP = Pattern.compile("[\\s\\p{Z}:]");
    private static final Pattern HEX64 = Pattern.compile("[0-9a-fA-F]{64}");

    private ApkKeyHash() {}

    /** ok 이면 value, 아니면 error 가 채워진다. */
    public record Result(boolean ok, String value, String error) {}

    public static boolean isApkKeyHashOrigin(String s) {
        return s.startsWith(APK_KEY_HASH_PREFIX);
    }

    public static Result fromFingerprint(String input) {
        String hex = STRIP.matcher(input).replaceAll("");
        if (hex.isEmpty()) return fail("SHA-256 지문을 입력하세요.");
        if (hex.length() != 64) {
            return fail("SHA-256 지문은 32바이트(64 hex)여야 합니다. 입력 길이: " + hex.length());
        }
        if (!HEX64.matcher(hex).matches()) return fail("지문에 16진수가 아닌 문자가 있습니다.");
        byte[] bytes = new byte[32];
        for (int i = 0; i < 32; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return new Result(true, APK_KEY_HASH_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), null);
    }

    private static Result fail(String error) {
        return new Result(false, null, error);
    }
}
