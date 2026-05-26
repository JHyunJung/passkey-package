package com.crosscert.passkey.core.jwt;

import com.crosscert.passkey.core.repository.SigningKeyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class IdTokenIssuerTest {

    private SigningKeyProvider signingKeys;
    private IdTokenIssuer issuer;
    private final Instant fixedNow = Instant.parse("2026-05-25T08:00:00Z");

    @BeforeEach
    void setUp() {
        // Phase 3: SigningKeyProvider is DB-backed. Stub the repo so init()
        // generates+saves a fresh in-memory key without touching JPA.
        SigningKeyRepository repo = Mockito.mock(SigningKeyRepository.class);
        KeyEnvelope envelope = new KeyEnvelope(
                Base64.getEncoder().encodeToString(new byte[32]),
                new SecureRandom());
        Clock providerClock = Clock.fixed(fixedNow, ZoneOffset.UTC);
        Mockito.when(repo.findFirstByStatusOrderByCreatedAtDesc("ACTIVE"))
                .thenReturn(Optional.empty());
        Mockito.when(repo.save(ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(0));
        signingKeys = new SigningKeyProvider(repo, envelope, new ObjectMapper(), providerClock);
        signingKeys.init();
        Clock clock = Clock.fixed(fixedNow, ZoneOffset.UTC);
        issuer = new IdTokenIssuer(
                signingKeys,
                "https://passkey.example.test",
                Duration.ofMinutes(15),
                clock);
    }

    @Test
    void issuesSignedJwtWithExpectedClaims() throws Exception {
        byte[] userHandle = new byte[]{1, 2, 3, 4};
        byte[] aaguid = new byte[]{(byte)0xab, (byte)0xcd};
        String jwt = issuer.issue("T_A", userHandle, 42L, aaguid);

        SignedJWT parsed = SignedJWT.parse(jwt);
        assertThat(parsed.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(parsed.getHeader().getKeyID()).isEqualTo(signingKeys.signingKey().getKeyID());

        assertThat(parsed.getJWTClaimsSet().getIssuer())
                .isEqualTo("https://passkey.example.test/T_A");
        assertThat(parsed.getJWTClaimsSet().getSubject())
                .isEqualTo(Base64.getUrlEncoder().withoutPadding().encodeToString(userHandle));
        assertThat(parsed.getJWTClaimsSet().getAudience()).containsExactly("T_A");
        assertThat(parsed.getJWTClaimsSet().getIssueTime().toInstant()).isEqualTo(fixedNow);
        assertThat(parsed.getJWTClaimsSet().getExpirationTime().toInstant())
                .isEqualTo(fixedNow.plus(Duration.ofMinutes(15)));
        assertThat(parsed.getJWTClaimsSet().getClaim("amr"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsExactly("webauthn");
        assertThat(parsed.getJWTClaimsSet().getClaim("aaguid")).isEqualTo("abcd");
        // cred_id is the 8-byte big-endian credentialId, base64url-no-pad.
        // 42L → bytes 00 00 00 00 00 00 00 2A → "AAAAAAAAACo".
        assertThat(parsed.getJWTClaimsSet().getClaim("cred_id")).isEqualTo("AAAAAAAAACo");
    }

    @Test
    void aaguidClaimIsOmittedWhenNull() throws Exception {
        String jwt = issuer.issue("T_A", new byte[]{0}, 1L, null);
        SignedJWT parsed = SignedJWT.parse(jwt);
        assertThat(parsed.getJWTClaimsSet().getClaim("aaguid")).isNull();
    }

    @Test
    void signatureVerifiesAgainstPublicJwk() throws Exception {
        String jwt = issuer.issue("T_A", new byte[]{0}, 1L, null);
        SignedJWT parsed = SignedJWT.parse(jwt);
        JWSVerifier verifier = new RSASSAVerifier(signingKeys.signingKey().toPublicJWK());
        assertThat(parsed.verify(verifier)).isTrue();
    }
}
