package com.crosscert.passkey.admin.tenant

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AllowedOriginFormatTest {

    private companion object {
        const val HASH43 =
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" // 43 chars
    }

    @Test
    fun acceptsHttpsOrigin() {
        assertTrue(AllowedOriginFormat.isValid("https://example.com"))
        assertTrue(AllowedOriginFormat.isValid("https://sub.example.com:8443"))
        assertTrue(AllowedOriginFormat.isValid("http://localhost:9090"))
    }

    @Test
    fun acceptsAndroidApkKeyHash() {
        assertTrue(AllowedOriginFormat.isValid("android:apk-key-hash:$HASH43"))
    }

    @Test
    fun rejectsWrongLengthApkKeyHash() {
        assertFalse(AllowedOriginFormat.isValid("android:apk-key-hash:TOOSHORT"))
        assertFalse(AllowedOriginFormat.isValid("android:apk-key-hash:" + HASH43 + "X")) // 44
    }

    @Test
    fun rejectsApkKeyHashWithBadChars() {
        // '+' '/' '=' 는 base64url 이 아님
        val bad = "android:apk-key-hash:" + "+/=" + "A".repeat(40)
        assertFalse(AllowedOriginFormat.isValid(bad))
    }

    @Test
    fun rejectsOtherSchemes() {
        assertFalse(AllowedOriginFormat.isValid("ftp://example.com"))
        assertFalse(AllowedOriginFormat.isValid("ios:something"))
    }

    @Test
    fun rejectsNullAndBlank() {
        assertFalse(AllowedOriginFormat.isValid(null))
        assertFalse(AllowedOriginFormat.isValid("   "))
    }
}
