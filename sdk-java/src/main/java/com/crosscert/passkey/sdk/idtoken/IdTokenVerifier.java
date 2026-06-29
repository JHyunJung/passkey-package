package com.crosscert.passkey.sdk.idtoken;

import com.crosscert.passkey.sdk.exception.PasskeyIdTokenException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

public class IdTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(IdTokenVerifier.class);

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
            // rejects alg-confusion / "none" / HS* downgrade attempts.
            if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) {
                log.warn("id-token verify failed: reason=unexpected-alg alg={}",
                        jwt.getHeader().getAlgorithm());
                throw new PasskeyIdTokenException(
                        "Unexpected JWS algorithm: " + jwt.getHeader().getAlgorithm());
            }
            String kid = jwt.getHeader().getKeyID();
            JWK key = jwks.get().getKeyByKeyId(kid);
            if (!(key instanceof RSAKey rsa)) {
                log.warn("id-token verify failed: reason=unknown-kid kid={}", kid);
                throw new PasskeyIdTokenException("Unknown or non-RSA kid: " + kid);
            }
            RSASSAVerifier verifier = new RSASSAVerifier(rsa.toRSAPublicKey());
            if (!jwt.verify(verifier)) {
                log.warn("id-token verify failed: reason=signature kid={}", kid);
                throw new PasskeyIdTokenException("Signature verification failed");
            }
            JWTClaimsSet c = jwt.getJWTClaimsSet();
            Instant now = clock.instant();
            Date expDate = c.getExpirationTime();
            Instant exp = (expDate == null) ? null : expDate.toInstant();
            if (exp == null || !exp.isAfter(now)) {
                log.warn("id-token verify failed: reason=expired exp={} now={}", exp, now);
                throw new PasskeyIdTokenException("ID Token expired (exp=" + exp + ", now=" + now + ")");
            }
            // 원본 Kotlin 의 `as? List<String>` 와 동일: amr 이 List 가 아니면(또는 없으면)
            // 예외 없이 null 로 폴백 → 아래에서 emptyList() 로 치환. 무조건 캐스트는
            // 비-List amr 에서 ClassCastException → parse 실패로 의미가 바뀌므로 금지.
            Object amrRaw = c.getClaim("amr");
            @SuppressWarnings("unchecked")
            List<String> amr = (amrRaw instanceof List<?>) ? (List<String>) amrRaw : null;
            long durMs = Duration.between(started, clock.instant()).toMillis();
            // sub is the opaque userHandle; truncate so logs don't leak the full id.
            String subShort = truncate(c.getSubject(), 8);
            log.info("id-token verified: reason=success iss={} sub={} durMs={}",
                    c.getIssuer(), subShort, durMs);
            Date iatDate = c.getIssueTime();
            return new IdTokenClaims(
                    c.getIssuer(),
                    c.getSubject(),
                    (c.getAudience() == null || c.getAudience().isEmpty()) ? null : c.getAudience().get(0),
                    (iatDate == null) ? null : iatDate.toInstant(),
                    exp,
                    (amr == null) ? List.of() : List.copyOf(amr),
                    (String) c.getClaim("cred_id"),
                    (String) c.getClaim("aaguid"));
        } catch (PasskeyIdTokenException e) {
            // Already logged at the precise reason branch above; rethrow.
            throw e;
        } catch (Exception e) {
            // Parse/structural failure (malformed JWT, JWK fetch error, etc.)
            log.warn("id-token verify failed: reason=parse cause={}", e.toString());
            throw new PasskeyIdTokenException("ID Token parsing failed", e);
        }
    }

    private static String truncate(String s, int n) {
        if (s == null) return null;
        return (s.length() <= n) ? s : s.substring(0, n) + "...";
    }
}
