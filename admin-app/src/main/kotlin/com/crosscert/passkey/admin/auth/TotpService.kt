package com.crosscert.passkey.admin.auth

import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.OptionalLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val DIGITS = 6
private const val STEP_MILLIS = 30_000L
private const val SKEW_STEPS = 1 // ±1 window
private const val SECRET_BYTES = 20 // 160-bit
private val POW10 = intArrayOf(1, 10, 100, 1_000, 10_000, 100_000, 1_000_000)

private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

/**
 * RFC 6238 TOTP (Time-based One-Time Password) implementation.
 *
 * HMAC-SHA1, 6 digits, 30-second time step. Verification accepts the
 * current step plus ±1 adjacent steps to tolerate client/server clock skew
 * (RFC 6238 §5.2). Secrets are 160-bit (20 bytes) of [SecureRandom]
 * entropy, Base32-encoded (RFC 4648, no padding) so they can be typed into
 * standard authenticator apps (Google Authenticator, Authy, …).
 *
 * No external TOTP/Base32 library is required — Base32 is hand-rolled
 * here to avoid adding a dependency to the admin-app module.
 */
@Service
class TotpService {

    private val random = SecureRandom()

    /** Generate a new Base32-encoded 160-bit secret suitable for an authenticator app. */
    fun newSecretBase32(): String {
        val buf = ByteArray(SECRET_BYTES)
        random.nextBytes(buf)
        return base32Encode(buf)
    }

    /** Generate the 6-digit TOTP for `secret` at the given epoch-millis. */
    fun generate(secretBase32: String?, epochMillis: Long): String {
        val counter = Math.floorDiv(epochMillis, STEP_MILLIS)
        return generateForCounter(base32Decode(secretBase32), counter)
    }

    /**
     * Verify `code` against `secret` at the given time, accepting
     * the current step and ±1 (SKEW_STEPS) adjacent steps. Constant-time
     * comparison against each candidate to avoid leaking which window matched.
     */
    fun verifyAt(secretBase32: String?, code: String?, epochMillis: Long): Boolean =
        matchedCounter(secretBase32, code, epochMillis).isPresent

    /**
     * Verify `code` the same way as [verifyAt], but return the
     * matched time-step counter instead of a boolean so the caller can persist
     * it as a replay-guard high-water mark (RFC 6238 §5.2: "the validation
     * system should not accept the second attempt of the OTP after the first
     * successful validation").
     *
     * Every candidate window is still evaluated (no early return) to avoid
     * a timing oracle; when more than one window matches (only possible if
     * `code` were to collide across adjacent steps, which does not
     * happen for a well-formed HOTP), the highest counter is returned so the
     * stored high-water mark only ever advances.
     */
    fun matchedCounter(secretBase32: String?, code: String?, epochMillis: Long): OptionalLong {
        if (code == null) return OptionalLong.empty()
        // trim { it <= ' ' }: 원 Java String.trim 과 같은 판정(Kotlin trim() 은 유니코드 공백까지 자른다).
        val trimmed = code.trim { it <= ' ' }
        if (trimmed.length != DIGITS) return OptionalLong.empty()
        val key: ByteArray
        try {
            key = base32Decode(secretBase32)
        } catch (ex: RuntimeException) {
            return OptionalLong.empty()
        }
        val counter = Math.floorDiv(epochMillis, STEP_MILLIS)
        var matched = false
        var matchedStep = Long.MIN_VALUE
        var c = counter - SKEW_STEPS
        while (c <= counter + SKEW_STEPS) {
            val candidate = generateForCounter(key, c)
            // Evaluate every window regardless of prior matches (no early return → no timing oracle).
            if (constantTimeEquals(candidate, trimmed)) {
                matched = true
                matchedStep = Math.max(matchedStep, c)
            }
            c++
        }
        return if (matched) OptionalLong.of(matchedStep) else OptionalLong.empty()
    }

    /** Test-only hook: expose the decoded secret length to assert entropy size. */
    fun decodeSecretForTest(secretBase32: String?): ByteArray = base32Decode(secretBase32)

    private fun generateForCounter(key: ByteArray, counterIn: Long): String {
        var counter = counterIn
        val msg = ByteArray(8)
        for (i in 7 downTo 0) {
            msg[i] = (counter and 0xff).toByte()
            counter = counter ushr 8
        }
        val hash = hmacSha1(key, msg)
        // Dynamic truncation (RFC 4226 §5.3).
        val offset = hash[hash.size - 1].toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        val otp = binary % POW10[DIGITS]
        return String.format("%0" + DIGITS + "d", otp)
    }
}

private fun hmacSha1(key: ByteArray, msg: ByteArray): ByteArray {
    try {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        return mac.doFinal(msg)
    } catch (e: GeneralSecurityException) {
        throw IllegalStateException("HMAC-SHA1 unavailable", e)
    }
}

/** Length-independent constant-time string compare (MessageDigest.isEqual). */
private fun constantTimeEquals(a: String, b: String): Boolean {
    val x = a.toByteArray(StandardCharsets.US_ASCII)
    val y = b.toByteArray(StandardCharsets.US_ASCII)
    return MessageDigest.isEqual(x, y)
}

// --- Base32 (RFC 4648, no padding) ----------------------------------

private fun base32Encode(data: ByteArray): String {
    val sb = StringBuilder()
    var buffer = 0
    var bitsLeft = 0
    for (b in data) {
        buffer = (buffer shl 8) or (b.toInt() and 0xff)
        bitsLeft += 8
        while (bitsLeft >= 5) {
            val idx = (buffer shr (bitsLeft - 5)) and 0x1f
            bitsLeft -= 5
            sb.append(BASE32_ALPHABET[idx])
        }
    }
    if (bitsLeft > 0) {
        val idx = (buffer shl (5 - bitsLeft)) and 0x1f
        sb.append(BASE32_ALPHABET[idx])
    }
    return sb.toString()
}

private fun base32Decode(s: String?): ByteArray {
    if (s == null) throw IllegalArgumentException("null secret")
    // trim { it <= ' ' }: 원 Java String.trim 과 같은 판정.
    val clean = s.trim { it <= ' ' }.replace("=", "").replace(" ", "").uppercase(Locale.ROOT)
    if (clean.isEmpty()) throw IllegalArgumentException("empty secret")
    val out = ByteArrayOutputStream(clean.length * 5 / 8 + 1)
    var buffer = 0
    var bitsLeft = 0
    for (i in 0 until clean.length) {
        val v = BASE32_ALPHABET.indexOf(clean[i])
        if (v < 0) throw IllegalArgumentException("invalid base32 character")
        buffer = (buffer shl 5) or v
        bitsLeft += 5
        if (bitsLeft >= 8) {
            out.write((buffer shr (bitsLeft - 8)) and 0xff)
            bitsLeft -= 8
        }
    }
    return out.toByteArray()
}
