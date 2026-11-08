package com.crosscert.passkey.admin.auth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TotpServiceTest {

    private val svc = TotpService()

    @Test
    fun verify_acceptsCodeGeneratedForSameTimestep() {
        val secret = svc.newSecretBase32()
        val fixedTime = 1_700_000_000_000L
        val code = svc.generate(secret, fixedTime)
        assertThat(svc.verifyAt(secret, code, fixedTime)).isTrue()
    }

    @Test
    fun verify_acceptsPreviousWindow_forClockSkew() {
        val secret = svc.newSecretBase32()
        val t = 1_700_000_000_000L
        val prevCode = svc.generate(secret, t - 30_000L)
        assertThat(svc.verifyAt(secret, prevCode, t)).isTrue()
    }

    @Test
    fun verify_rejectsWrongCode() {
        val secret = svc.newSecretBase32()
        assertThat(svc.verifyAt(secret, "000000", 1_700_000_000_000L)).isFalse()
    }

    @Test
    fun newSecret_decodesToAtLeast20Bytes() {
        val secret = svc.newSecretBase32()
        // verify it round-trips through your base32 decode to >= 20 bytes (160-bit)
        assertThat(svc.decodeSecretForTest(secret).size).isGreaterThanOrEqualTo(20)
    }

    // ---- matchedCounter (F02 replay-guard support) -----------------------

    @Test
    fun matchedCounter_returnsCounterForCurrentWindow() {
        val secret = svc.newSecretBase32()
        val t = 1_700_000_000_000L
        val code = svc.generate(secret, t)

        val expectedCounter = Math.floorDiv(t, 30_000L)
        assertThat(svc.matchedCounter(secret, code, t)).hasValue(expectedCounter)
    }

    @Test
    fun matchedCounter_returnsLowerCounterForPreviousWindow_forClockSkew() {
        val secret = svc.newSecretBase32()
        val t = 1_700_000_000_000L
        val prevCode = svc.generate(secret, t - 30_000L)

        val expectedCounter = Math.floorDiv(t, 30_000L) - 1
        assertThat(svc.matchedCounter(secret, prevCode, t)).hasValue(expectedCounter)
    }

    @Test
    fun matchedCounter_empty_forWrongCode() {
        val secret = svc.newSecretBase32()
        assertThat(svc.matchedCounter(secret, "000000", 1_700_000_000_000L)).isEmpty()
    }

    @Test
    fun matchedCounter_empty_forNullCode() {
        val secret = svc.newSecretBase32()
        assertThat(svc.matchedCounter(secret, null, 1_700_000_000_000L)).isEmpty()
    }

    @Test
    fun verifyAt_stillTrue_whenMatchedCounterPresent() {
        // verifyAt must stay a thin wrapper over matchedCounter (no call sites
        // regressed by the refactor to expose the matched step).
        val secret = svc.newSecretBase32()
        val t = 1_700_000_000_000L
        val code = svc.generate(secret, t)
        assertThat(svc.verifyAt(secret, code, t)).isTrue()
    }
}
