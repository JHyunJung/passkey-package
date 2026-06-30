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

    private static final java.util.regex.Pattern HEX32 = java.util.regex.Pattern.compile("(?i)[0-9a-f]{32}");

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

    /**
     * 서명·exp 검증(1-인자 verify) 후 iss/aud 시맨틱 검증까지 수행한다.
     *
     * <p>expectedIssuer 는 {@code <issuerBase>/<tenantId>} 전체 문자열이다. 마지막 '/'
     * 기준으로 issuerBase prefix(정확 일치 요구)와 tenant(UUID 정규화 비교)로 분해한다.
     * expectedAudience(tenantId)는 토큰 aud 와 UUID 정규화 비교한다. hex32↔대시 표기
     * 차이는 정규화로 동치 처리된다.
     */
    public IdTokenClaims verify(String compactJwt, String expectedIssuer, String expectedAudience) {
        IdTokenClaims claims = verify(compactJwt);

        if (expectedIssuer == null || expectedIssuer.isBlank()) {
            log.warn("id-token verify failed: reason=config expectedIssuer-blank");
            throw new PasskeyIdTokenException("expectedIssuer must not be blank");
        }
        if (expectedAudience == null || expectedAudience.isBlank()) {
            log.warn("id-token verify failed: reason=config expectedAudience-blank");
            throw new PasskeyIdTokenException("expectedAudience must not be blank");
        }

        // expectedIssuer 를 prefix(issuerBase) + tenant 로 분해.
        int slash = expectedIssuer.lastIndexOf('/');
        if (slash < 0) {
            throw new PasskeyIdTokenException("expectedIssuer must be <issuerBase>/<tenantId>");
        }
        String issuerBase = expectedIssuer.substring(0, slash);
        String expectedTenant = normalizeTenantId(expectedIssuer.substring(slash + 1));

        String tokenIss = claims.iss();
        String prefix = issuerBase + "/";
        boolean issOk = tokenIss != null
                && tokenIss.startsWith(prefix)
                && java.util.Objects.equals(
                        normalizeTenantId(tokenIss.substring(prefix.length())), expectedTenant);
        if (!issOk) {
            log.warn("id-token verify failed: reason=iss-mismatch expectedPrefix={} got={}", prefix, tokenIss);
            throw new PasskeyIdTokenException("iss mismatch");
        }

        if (!java.util.Objects.equals(normalizeTenantId(expectedAudience), normalizeTenantId(claims.aud()))) {
            log.warn("id-token verify failed: reason=aud-mismatch expected={} got={}",
                    expectedAudience, claims.aud());
            throw new PasskeyIdTokenException("aud mismatch");
        }
        return claims;
    }

    /**
     * tenantId 를 표준 UUID(소문자+대시)로 정규화한다. hex32(대시 없음) 또는 대시 UUID
     * 모두 허용. 파싱 불가하면 trim 된 입력을 그대로 반환(원본 비교에 맡김).
     */
    private static String normalizeTenantId(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (HEX32.matcher(s).matches()) {
            s = s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16)
                    + "-" + s.substring(16, 20) + "-" + s.substring(20);
        }
        try {
            return java.util.UUID.fromString(s).toString();
        } catch (IllegalArgumentException e) {
            return s;
        }
    }

    private static String truncate(String s, int n) {
        if (s == null) return null;
        return (s.length() <= n) ? s : s.substring(0, n) + "...";
    }
}
