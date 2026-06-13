package com.crosscert.passkey.sdk.idtoken;

import com.crosscert.passkey.sdk.exception.PasskeyIdTokenException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Slf4j
public class IdTokenVerifier {
    private final JwksCache jwks;
    private final Clock clock;

    public IdTokenVerifier(JwksCache jwks, Clock clock) {
        this.jwks = jwks;
        this.clock = clock;
    }

    public IdTokenClaims verify(String compactJwt) {
        Instant started = clock.instant();
        try {
            SignedJWT jwt = SignedJWT.parse(compactJwt);
            // Pin alg to RS256 — the issuer signs exclusively with RS256, so this
            // rejects alg-confusion / "none" / HS* downgrade attempts without
            // affecting any legitimate token (sec-idtoken-alg-not-pinned).
            if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) {
                log.warn("id-token verify failed: reason=unexpected-alg alg={}",
                        jwt.getHeader().getAlgorithm());
                throw new PasskeyIdTokenException(
                        "Unexpected JWS algorithm: " + jwt.getHeader().getAlgorithm());
            }
            String kid = jwt.getHeader().getKeyID();
            JWK key = jwks.get().getKeyByKeyId(kid);
            if (key == null || !(key instanceof RSAKey rsa)) {
                log.warn("id-token verify failed: reason=unknown-kid kid={}", kid);
                throw new PasskeyIdTokenException("Unknown or non-RSA kid: " + kid);
            }
            JWSVerifier verifier = new RSASSAVerifier(rsa.toRSAPublicKey());
            if (!jwt.verify(verifier)) {
                log.warn("id-token verify failed: reason=signature kid={}", kid);
                throw new PasskeyIdTokenException("Signature verification failed");
            }
            JWTClaimsSet c = jwt.getJWTClaimsSet();
            Instant now = clock.instant();
            Instant exp = c.getExpirationTime() == null ? null : c.getExpirationTime().toInstant();
            if (exp == null || !exp.isAfter(now)) {
                log.warn("id-token verify failed: reason=expired exp={} now={}", exp, now);
                throw new PasskeyIdTokenException("ID Token expired (exp=" + exp + ", now=" + now + ")");
            }
            @SuppressWarnings("unchecked")
            List<String> amr = (List<String>) c.getClaim("amr");
            long durMs = Duration.between(started, clock.instant()).toMillis();
            // sub is the (b64url-encoded) opaque userHandle; truncate to a
            // short prefix so logs don't leak the full identifier across
            // RPs while still being join-able to backend logs.
            String subShort = truncate(c.getSubject(), 8);
            log.info("id-token verified: reason=success iss={} sub={} durMs={}",
                    c.getIssuer(), subShort, durMs);
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
            // Already logged at the precise reason branch above; rethrow.
            throw e;
        } catch (Exception e) {
            // Parse/structural failure (malformed JWT, JWK fetch error, etc.)
            // — neither signature nor expiry, so log as generic parse reason.
            log.warn("id-token verify failed: reason=parse cause={}", e.toString());
            throw new PasskeyIdTokenException("ID Token parsing failed", e);
        }
    }

    private static String truncate(String s, int n) {
        if (s == null) return null;
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }
}
