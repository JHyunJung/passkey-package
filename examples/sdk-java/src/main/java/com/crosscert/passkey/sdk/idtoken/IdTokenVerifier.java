package com.crosscert.passkey.sdk.idtoken;

import com.crosscert.passkey.sdk.exception.PasskeyIdTokenException;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

public class IdTokenVerifier {
    private final JwksCache jwks;
    private final Clock clock;

    public IdTokenVerifier(JwksCache jwks, Clock clock) {
        this.jwks = jwks;
        this.clock = clock;
    }

    public IdTokenClaims verify(String compactJwt) {
        try {
            SignedJWT jwt = SignedJWT.parse(compactJwt);
            String kid = jwt.getHeader().getKeyID();
            JWK key = jwks.get().getKeyByKeyId(kid);
            if (key == null || !(key instanceof RSAKey rsa)) {
                throw new PasskeyIdTokenException("Unknown or non-RSA kid: " + kid);
            }
            JWSVerifier verifier = new RSASSAVerifier(rsa.toRSAPublicKey());
            if (!jwt.verify(verifier)) {
                throw new PasskeyIdTokenException("Signature verification failed");
            }
            JWTClaimsSet c = jwt.getJWTClaimsSet();
            Instant now = clock.instant();
            Instant exp = c.getExpirationTime() == null ? null : c.getExpirationTime().toInstant();
            if (exp == null || !exp.isAfter(now)) {
                throw new PasskeyIdTokenException("ID Token expired (exp=" + exp + ", now=" + now + ")");
            }
            @SuppressWarnings("unchecked")
            List<String> amr = (List<String>) c.getClaim("amr");
            return new IdTokenClaims(
                    c.getIssuer(),
                    c.getSubject(),
                    c.getAudience().isEmpty() ? null : c.getAudience().get(0),
                    c.getIssueTime() == null ? null : c.getIssueTime().toInstant(),
                    exp,
                    amr == null ? List.of() : List.copyOf(amr),
                    (String) c.getClaim("cred_id"),
                    (String) c.getClaim("aaguid")
            );
        } catch (PasskeyIdTokenException e) {
            throw e;
        } catch (Exception e) {
            throw new PasskeyIdTokenException("ID Token parsing failed", e);
        }
    }
}
