package com.crosscert.passkey.core.jwt;

import com.crosscert.passkey.core.entity.SigningKey;
import com.crosscert.passkey.core.repository.SigningKeyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * DB-backed signing-key provider (Phase 3 rewrite).
 *
 * <p>Boot: load the single ACTIVE row. If none exists (fresh install,
 * first boot of either app), generate a new RSA-2048 key, envelope-seal
 * the PKCS8 private bytes, and INSERT as ACTIVE. Subsequent boots find
 * the persisted row.
 *
 * <p>Concurrent first boots are race-tolerant: the
 * {@code signing_key_one_active_uix} unique index guarantees at most
 * one ACTIVE row at the DB level. If a parallel writer wins the race
 * we observe a {@link DataIntegrityViolationException} on save and
 * re-read the ACTIVE row instead of crashing.
 *
 * <p>JWKS: returns ACTIVE + ROTATED keys so RPs still verify JWTs
 * signed by a recently-rotated key during the grace window
 * (KeyExpirationJob transitions ROTATED &rarr; REVOKED after the configured
 * grace expires; REVOKED keys are excluded from JWKS). Public JWKS
 * construction never touches the envelope — it parses the stored
 * public JWK column directly so the JWKS endpoint stays available
 * even if envelope decryption is misconfigured.
 *
 * <p>Signing: always uses the cached ACTIVE key. KeyRotationService
 * calls {@link #reload()} after a rotation so the cache picks up the
 * new ACTIVE row.
 */
@Component
public class SigningKeyProvider {

    private final SigningKeyRepository repo;
    private final KeyEnvelope envelope;
    private final ObjectMapper mapper;
    private final Clock clock;
    private volatile RSAKey cachedActive;

    public SigningKeyProvider(SigningKeyRepository repo,
                              KeyEnvelope envelope,
                              ObjectMapper mapper,
                              Clock clock) {
        this.repo = repo;
        this.envelope = envelope;
        this.mapper = mapper;
        this.clock = clock;
    }

    @PostConstruct
    public void init() {
        SigningKey row = repo.findFirstByStatusOrderByCreatedAtDesc("ACTIVE")
                .orElseGet(this::createInitialKey);
        this.cachedActive = decodeWithPrivate(row);
    }

    public RSAKey signingKey() {
        return cachedActive;
    }

    public JWKSet publicJwkSet() {
        List<JWK> publics = new ArrayList<>();
        for (SigningKey row : repo.findAllByStatusIn(List.of("ACTIVE", "ROTATED"))) {
            publics.add(parsePublicJwk(row));
        }
        return new JWKSet(publics);
    }

    /** Called by KeyRotationService after a successful rotation. */
    public void reload() {
        SigningKey row = repo.findFirstByStatusOrderByCreatedAtDesc("ACTIVE")
                .orElseThrow(() -> new IllegalStateException(
                        "no ACTIVE signing key after reload — rotation contract broken"));
        this.cachedActive = decodeWithPrivate(row);
    }

    private SigningKey createInitialKey() {
        SigningKey generated = generate();
        try {
            return repo.save(generated);
        } catch (DataIntegrityViolationException e) {
            // Another writer (the sibling app booting in parallel) won
            // the race and inserted the ACTIVE row first. The unique
            // index on (CASE WHEN status='ACTIVE' THEN 1 END) blocked
            // us. Re-read and use the winning row.
            return repo.findFirstByStatusOrderByCreatedAtDesc("ACTIVE")
                    .orElseThrow(() -> new IllegalStateException(
                            "signing_key save failed with constraint violation "
                                    + "but no ACTIVE row visible on re-read", e));
        }
    }

    private SigningKey generate() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair pair = gen.generateKeyPair();

            RSAKey withoutKid = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
            String kid = withoutKid.computeThumbprint().toString();
            RSAKey rsa = new RSAKey.Builder(withoutKid).keyID(kid).build();
            String publicJwk = rsa.toPublicJWK().toJSONString();
            byte[] sealed = envelope.seal(pair.getPrivate().getEncoded());
            return new SigningKey(kid, "RS256", publicJwk, sealed);
        } catch (Exception e) {
            throw new IllegalStateException("initial signing key generation failed", e);
        }
    }

    /**
     * Build the public-only JWK from the stored {@code public_jwk}
     * column. No envelope decryption needed.
     *
     * <p>Defense-in-depth: forces {@link JWK#toPublicJWK()} projection
     * before returning, so even if the column ever contains private
     * RSA fields (rotation bug, admin import error, bad seed) the
     * JWKS endpoint cannot leak them.
     */
    private JWK parsePublicJwk(SigningKey row) {
        try {
            return JWK.parse(row.getPublicJwk()).toPublicJWK();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "failed to parse public_jwk kid=" + row.getKid(), e);
        }
    }

    /**
     * Build the full RSAKey (public + private) by combining the stored
     * public JWK with the envelope-opened PKCS8 private bytes. Used
     * only for the cached ACTIVE key the signer needs.
     */
    private RSAKey decodeWithPrivate(SigningKey row) {
        try {
            JWK publicOnly = JWK.parse(row.getPublicJwk());
            RSAPublicKey pub = ((RSAKey) publicOnly).toRSAPublicKey();
            byte[] pkcs8 = envelope.open(row.getPrivatePkcs8());
            RSAPrivateKey priv = (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            return new RSAKey.Builder(pub)
                    .privateKey(priv)
                    .keyID(row.getKid())
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "failed to decode signing key kid=" + row.getKid(), e);
        }
    }
}
