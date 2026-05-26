package com.crosscert.passkey.core.jwt;

import com.crosscert.passkey.core.entity.SigningKey;
import com.crosscert.passkey.core.repository.SigningKeyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SigningKeyProviderTest {

    private SigningKeyRepository repo;
    private KeyEnvelope envelope;
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
    private final ObjectMapper mapper = new ObjectMapper();
    private SigningKeyProvider provider;

    @BeforeEach
    void setUp() {
        repo = mock(SigningKeyRepository.class);
        envelope = new KeyEnvelope(
                Base64.getEncoder().encodeToString(new byte[32]),
                new SecureRandom());
        provider = new SigningKeyProvider(repo, envelope, mapper, clock);
    }

    @Test
    void initLoadsExistingActiveKey() throws Exception {
        SigningKey stored = freshActiveKey("kid-existing");
        when(repo.findFirstByStatusOrderByCreatedAtDesc("ACTIVE"))
                .thenReturn(Optional.of(stored));

        provider.init();
        RSAKey k = provider.signingKey();
        assertThat(k.getKeyID()).isEqualTo("kid-existing");
        assertThat(k.getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(k.getKeyUse()).isEqualTo(KeyUse.SIGNATURE);
        assertThat(k.isPrivate()).isTrue();
    }

    @Test
    void initGeneratesAndPersistsWhenNoActiveExists() {
        when(repo.findFirstByStatusOrderByCreatedAtDesc("ACTIVE"))
                .thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        provider.init();

        ArgumentCaptor<SigningKey> captor = ArgumentCaptor.forClass(SigningKey.class);
        verify(repo).save(captor.capture());
        SigningKey saved = captor.getValue();
        assertThat(saved.getAlg()).isEqualTo("RS256");
        assertThat(saved.getStatus()).isEqualTo("ACTIVE");
        assertThat(saved.getKid()).isNotBlank();
        assertThat(saved.getPublicJwk()).contains("\"kty\":\"RSA\"");
        assertThat(saved.getPrivatePkcs8()).isNotNull();
        byte[] pkcs8 = envelope.open(saved.getPrivatePkcs8());
        assertThat(pkcs8).isNotEmpty();
    }

    @Test
    void publicJwkSetIncludesActiveAndRotated() throws Exception {
        SigningKey active = freshActiveKey("active-kid");
        SigningKey rotated = freshActiveKey("rotated-kid");
        rotated.rotate(clock.instant());
        when(repo.findFirstByStatusOrderByCreatedAtDesc("ACTIVE"))
                .thenReturn(Optional.of(active));
        when(repo.findAllByStatusIn(List.of("ACTIVE", "ROTATED")))
                .thenReturn(List.of(active, rotated));

        provider.init();
        JWKSet set = provider.publicJwkSet();
        assertThat(set.getKeys()).extracting(j -> j.getKeyID())
                .containsExactlyInAnyOrder("active-kid", "rotated-kid");
        assertThat(set.getKeys()).allMatch(j -> !j.isPrivate());
    }

    @Test
    void publicJwkSetForcesPublicProjectionEvenIfStoredJwkLeaksPrivateFields() throws Exception {
        // Defense-in-depth: if a bad seed/admin import ever wrote a
        // private JWK into the public_jwk column, the JWKS endpoint
        // must NOT echo private RSA parameters to RPs.
        SigningKey active = freshActiveKey("active-kid");
        SigningKey leaky = leakyActiveKey("leaky-kid");
        when(repo.findFirstByStatusOrderByCreatedAtDesc("ACTIVE"))
                .thenReturn(Optional.of(active));
        when(repo.findAllByStatusIn(List.of("ACTIVE", "ROTATED")))
                .thenReturn(List.of(active, leaky));

        provider.init();
        JWKSet set = provider.publicJwkSet();
        assertThat(set.getKeys()).allMatch(j -> !j.isPrivate());
        // toJSONString() must not contain any private-only RSA fields.
        String json = set.toString();
        for (String privateField : new String[]{"\"d\"", "\"p\"", "\"q\"", "\"dp\"", "\"dq\"", "\"qi\""}) {
            assertThat(json)
                    .as("JWKS leaked private RSA field %s", privateField)
                    .doesNotContain(privateField);
        }
    }

    @Test
    void reloadRefreshesCachedActiveAfterRotation() throws Exception {
        SigningKey first = freshActiveKey("first");
        SigningKey second = freshActiveKey("second");
        when(repo.findFirstByStatusOrderByCreatedAtDesc("ACTIVE"))
                .thenReturn(Optional.of(first))
                .thenReturn(Optional.of(second));

        provider.init();
        assertThat(provider.signingKey().getKeyID()).isEqualTo("first");

        provider.reload();
        assertThat(provider.signingKey().getKeyID()).isEqualTo("second");
    }

    private SigningKey freshActiveKey(String kid) throws Exception {
        var gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        var pair = gen.generateKeyPair();
        RSAKey rsa = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey((RSAPrivateKey) pair.getPrivate())
                .keyID(kid)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .build();
        String publicJwk = rsa.toPublicJWK().toJSONString();
        byte[] sealed = envelope.seal(pair.getPrivate().getEncoded());
        return new SigningKey(kid, "RS256", publicJwk, sealed);
    }

    /**
     * Constructs a row where {@code public_jwk} INCORRECTLY contains
     * the full private RSA JWK (d, p, q, dp, dq, qi). Simulates an
     * admin import bug or a future rotation that forgets to project
     * to public — JWKS must not leak private fields anyway.
     */
    private SigningKey leakyActiveKey(String kid) throws Exception {
        var gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        var pair = gen.generateKeyPair();
        RSAKey rsa = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey((RSAPrivateKey) pair.getPrivate())
                .keyID(kid)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .build();
        // NOT toPublicJWK() — store the full private JWK on purpose.
        String fullJwk = rsa.toJSONString();
        byte[] sealed = envelope.seal(pair.getPrivate().getEncoded());
        return new SigningKey(kid, "RS256", fullJwk, sealed);
    }
}
