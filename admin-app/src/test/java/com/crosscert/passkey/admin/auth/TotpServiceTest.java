package com.crosscert.passkey.admin.auth;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TotpServiceTest {

    private final TotpService svc = new TotpService();

    @Test
    void verify_acceptsCodeGeneratedForSameTimestep() {
        String secret = svc.newSecretBase32();
        long fixedTime = 1_700_000_000_000L;
        String code = svc.generate(secret, fixedTime);
        assertThat(svc.verifyAt(secret, code, fixedTime)).isTrue();
    }

    @Test
    void verify_acceptsPreviousWindow_forClockSkew() {
        String secret = svc.newSecretBase32();
        long t = 1_700_000_000_000L;
        String prevCode = svc.generate(secret, t - 30_000L);
        assertThat(svc.verifyAt(secret, prevCode, t)).isTrue();
    }

    @Test
    void verify_rejectsWrongCode() {
        String secret = svc.newSecretBase32();
        assertThat(svc.verifyAt(secret, "000000", 1_700_000_000_000L)).isFalse();
    }

    @Test
    void newSecret_decodesToAtLeast20Bytes() {
        String secret = svc.newSecretBase32();
        // verify it round-trips through your base32 decode to >= 20 bytes (160-bit)
        assertThat(svc.decodeSecretForTest(secret).length).isGreaterThanOrEqualTo(20);
    }

    // ---- matchedCounter (F02 replay-guard support) -----------------------

    @Test
    void matchedCounter_returnsCounterForCurrentWindow() {
        String secret = svc.newSecretBase32();
        long t = 1_700_000_000_000L;
        String code = svc.generate(secret, t);

        long expectedCounter = Math.floorDiv(t, 30_000L);
        assertThat(svc.matchedCounter(secret, code, t)).hasValue(expectedCounter);
    }

    @Test
    void matchedCounter_returnsLowerCounterForPreviousWindow_forClockSkew() {
        String secret = svc.newSecretBase32();
        long t = 1_700_000_000_000L;
        String prevCode = svc.generate(secret, t - 30_000L);

        long expectedCounter = Math.floorDiv(t, 30_000L) - 1;
        assertThat(svc.matchedCounter(secret, prevCode, t)).hasValue(expectedCounter);
    }

    @Test
    void matchedCounter_empty_forWrongCode() {
        String secret = svc.newSecretBase32();
        assertThat(svc.matchedCounter(secret, "000000", 1_700_000_000_000L)).isEmpty();
    }

    @Test
    void matchedCounter_empty_forNullCode() {
        String secret = svc.newSecretBase32();
        assertThat(svc.matchedCounter(secret, null, 1_700_000_000_000L)).isEmpty();
    }

    @Test
    void verifyAt_stillTrue_whenMatchedCounterPresent() {
        // verifyAt must stay a thin wrapper over matchedCounter (no call sites
        // regressed by the refactor to expose the matched step).
        String secret = svc.newSecretBase32();
        long t = 1_700_000_000_000L;
        String code = svc.generate(secret, t);
        assertThat(svc.verifyAt(secret, code, t)).isTrue();
    }
}
