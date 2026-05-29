package com.crosscert.passkey.core.license;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LicenseVerifierTest {

    private final LicensePublicKeyProvider keys =
            new LicensePublicKeyProvider("license-public.test.ed25519.pub");
    private final LicenseProperties props = new LicenseProperties(
            null, null, "license.crosscert.com", "passkey-onprem", null, null);
    private final LicenseVerifier verifier =
            new LicenseVerifier(keys, props, Clock.systemUTC());

    @Test
    void validToken_parsesAllClaims() throws Exception {
        String token = LicenseTestFixtures.issueValid(Duration.ofDays(90), List.of("mds", "audit-pdf"));

        LicenseToken result = verifier.verify(token);

        assertThat(result.features()).containsExactlyInAnyOrder("mds", "audit-pdf");
        assertThat(result.expiresAt()).isAfter(Instant.now());
        assertThat(result.tenantId()).isEqualTo("00000000-0000-0000-0000-000000000001");
        assertThat(result.limits().warningDaysBeforeExpiry()).isEqualTo(30);
        assertThat(result.limits().graceHoursWhenOffline()).isEqualTo(72);
    }

    @Test
    void expiredToken_rejected() {
        String token = LicenseTestFixtures.issueExpired();
        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(LicenseVerificationException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void badSignature_rejected() throws Exception {
        String token = LicenseTestFixtures.issueWithBadSignature();
        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(LicenseVerificationException.class)
                .hasMessageContaining("signature");
    }

    @Test
    void wrongAudience_rejected() {
        LicenseProperties strict = new LicenseProperties(
                null, null, "license.crosscert.com", "different-audience", null, null);
        LicenseVerifier strictVerifier = new LicenseVerifier(keys, strict, Clock.systemUTC());
        String token = LicenseTestFixtures.issueValid(Duration.ofDays(30), List.of("mds"));
        assertThatThrownBy(() -> strictVerifier.verify(token))
                .isInstanceOf(LicenseVerificationException.class)
                .hasMessageContaining("audience");
    }

    @Test
    void clockBeforeNbf_rejected() {
        Clock past = Clock.fixed(Instant.now().minus(Duration.ofDays(365)), ZoneOffset.UTC);
        LicenseVerifier earlyVerifier = new LicenseVerifier(keys, props, past);
        String token = LicenseTestFixtures.issueValid(Duration.ofDays(30), List.of("mds"));
        assertThatThrownBy(() -> earlyVerifier.verify(token))
                .isInstanceOf(LicenseVerificationException.class)
                .hasMessageContaining("not yet valid");
    }
}
