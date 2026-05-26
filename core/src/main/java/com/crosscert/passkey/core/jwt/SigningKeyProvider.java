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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Types;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.util.List;

/**
 * DB-backed signing-key provider (Phase 3 rewrite).
 *
 * <p>Boot: load the single ACTIVE row. If none exists (fresh install,
 * first boot of either app), generate a new RSA-2048 key, envelope-seal
 * the PKCS8 private bytes, and INSERT as ACTIVE. Subsequent boots find
 * the persisted row.
 *
 * <p>Concurrent first boots are race-tolerant via the definer-rights
 * PL/SQL package {@code APP_OWNER.signing_key_bootstrap_pkg} (V18),
 * which only allows INSERT when no ACTIVE row exists. The
 * function-based {@code signing_key_one_active_uix} unique index (V15)
 * is a belt-and-suspenders safety net at the storage layer.
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
    private final JdbcTemplate jdbc;
    private final JwksAssembler jwksAssembler;
    private volatile RSAKey cachedActive;

    @org.springframework.beans.factory.annotation.Autowired
    public SigningKeyProvider(SigningKeyRepository repo,
                              KeyEnvelope envelope,
                              ObjectMapper mapper,
                              Clock clock,
                              JdbcTemplate jdbc,
                              JwksAssembler jwksAssembler) {
        this.repo = repo;
        this.envelope = envelope;
        this.mapper = mapper;
        this.clock = clock;
        this.jdbc = jdbc;
        this.jwksAssembler = jwksAssembler;
    }

    /**
     * Backward-compatible 4-arg constructor for unit tests that don't
     * exercise the createInitialKey PL/SQL path. Tests construct with
     * a mocked repo that returns a pre-existing ACTIVE key, so jdbc is
     * never touched. Spring DI uses the @Autowired 6-arg variant.
     * Internally self-wires a {@link JwksAssembler} backed by the same repo.
     */
    public SigningKeyProvider(SigningKeyRepository repo,
                              KeyEnvelope envelope,
                              ObjectMapper mapper,
                              Clock clock) {
        this(repo, envelope, mapper, clock, null, new JwksAssembler(repo));
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
        return jwksAssembler.build();
    }

    /** Called by KeyRotationService after a successful rotation. */
    public void reload() {
        SigningKey row = repo.findFirstByStatusOrderByCreatedAtDesc("ACTIVE")
                .orElseThrow(() -> new IllegalStateException(
                        "no ACTIVE signing key after reload — rotation contract broken"));
        this.cachedActive = decodeWithPrivate(row);
    }

    private SigningKey createInitialKey() {
        if (jdbc == null) {
            throw new IllegalStateException(
                    "SigningKeyProvider was constructed without a JdbcTemplate. "
                            + "The 4-arg test constructor only works when an ACTIVE row "
                            + "already exists in the mocked repository.");
        }
        SigningKey generated = generate();
        // Call the definer-rights PL/SQL package APP_OWNER.signing_key_bootstrap_pkg
        // (V18). It allows the caller (APP_ADMIN or APP_RUNTIME) to
        // INSERT the first ACTIVE row ONLY when none exists. This
        // narrows the runtime DB principal's attack surface compared
        // to a plain GRANT INSERT (codex review T6-followup Medium).
        jdbc.execute((java.sql.Connection conn) -> {
            try (java.sql.CallableStatement cs = conn.prepareCall(
                    "{ call APP_OWNER.signing_key_bootstrap_pkg.bootstrap_active(?,?,?,?,?) }")) {
                cs.setString(1, generated.getKid());
                cs.setString(2, generated.getAlg());
                cs.setString(3, generated.getPublicJwk());
                cs.setBytes(4, generated.getPrivatePkcs8());
                cs.registerOutParameter(5, Types.NUMERIC);
                cs.execute();
                // p_inserted = 1 if we won, 0 if an ACTIVE row already existed.
                // Either way we re-read below to get the canonical row.
                return null;
            }
        });
        // Re-read whichever row is now ACTIVE (ours, or the one a
        // sibling app inserted while we were generating).
        return repo.findFirstByStatusOrderByCreatedAtDesc("ACTIVE")
                .orElseThrow(() -> new IllegalStateException(
                        "signing_key bootstrap completed but no ACTIVE row visible"));
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
