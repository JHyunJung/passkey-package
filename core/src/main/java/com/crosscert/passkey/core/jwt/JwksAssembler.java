package com.crosscert.passkey.core.jwt;

import com.crosscert.passkey.core.entity.SigningKey;
import com.crosscert.passkey.core.repository.SigningKeyRepository;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the JWKS set published at GET /.well-known/jwks.json.
 *
 * <p>Includes ACTIVE + ROTATED keys (so JWTs signed by the recently
 * rotated key can still verify during grace) but never REVOKED keys.
 * Private material is stripped via {@link JWK#toPublicJWK()}.
 */
@Component
public class JwksAssembler {

    private final SigningKeyRepository repo;

    public JwksAssembler(SigningKeyRepository repo) {
        this.repo = repo;
    }

    public JWKSet build() {
        List<JWK> publics = new ArrayList<>();
        for (SigningKey row : repo.findAllByStatusIn(List.of("ACTIVE", "ROTATED"))) {
            publics.add(parsePublicJwk(row));
        }
        return new JWKSet(publics);
    }

    private JWK parsePublicJwk(SigningKey row) {
        try {
            // Defense-in-depth: force toPublicJWK() projection so even if the
            // public_jwk column ever contains private RSA fields (rotation
            // bug, admin import error, bad seed) the JWKS endpoint cannot
            // leak them.
            return JWK.parse(row.getPublicJwk()).toPublicJWK();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "failed to parse public_jwk kid=" + row.getKid(), e);
        }
    }
}
