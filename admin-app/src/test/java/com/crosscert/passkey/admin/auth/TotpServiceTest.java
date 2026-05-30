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
}
