package com.crosscert.passkey.admin.tenant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AllowedOriginFormatTest {

    private static final String HASH43 =
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // 43 chars

    @Test
    void acceptsHttpsOrigin() {
        assertTrue(AllowedOriginFormat.isValid("https://example.com"));
        assertTrue(AllowedOriginFormat.isValid("https://sub.example.com:8443"));
        assertTrue(AllowedOriginFormat.isValid("http://localhost:9090"));
    }

    @Test
    void acceptsAndroidApkKeyHash() {
        assertTrue(AllowedOriginFormat.isValid("android:apk-key-hash:" + HASH43));
    }

    @Test
    void rejectsWrongLengthApkKeyHash() {
        assertFalse(AllowedOriginFormat.isValid("android:apk-key-hash:TOOSHORT"));
        assertFalse(AllowedOriginFormat.isValid("android:apk-key-hash:" + HASH43 + "X")); // 44
    }

    @Test
    void rejectsApkKeyHashWithBadChars() {
        // '+' '/' '=' 는 base64url 이 아님
        String bad = "android:apk-key-hash:" + "+/=".concat("A".repeat(40));
        assertFalse(AllowedOriginFormat.isValid(bad));
    }

    @Test
    void rejectsOtherSchemes() {
        assertFalse(AllowedOriginFormat.isValid("ftp://example.com"));
        assertFalse(AllowedOriginFormat.isValid("ios:something"));
    }

    @Test
    void rejectsNullAndBlank() {
        assertFalse(AllowedOriginFormat.isValid(null));
        assertFalse(AllowedOriginFormat.isValid("   "));
    }
}
