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

    /**
     * G20: nbf/iat 미래-시각 검증에 허용하는 clock skew. exp 검증(zero-leeway, 기존
     * 동작 보존)과 달리 nbf/iat는 "미래 시각"을 판정하는 검사이므로, 발급자·검증자
     * 간 정상적인 시계 오차로 인한 오탐(false reject)을 흡수하기 위한 소액의 여유가
     * 필요하다.
     */
    private static final Duration CLOCK_SKEW_LEEWAY = Duration.ofSeconds(60);

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
            if (key == null) {
                // F26: kid-miss는 캐시된 JWKS가 TTL 이내라도 (키 회전 직후처럼)
                // 최신이 아닐 수 있다는 신호다. 1회 강제 refetch 후 재조회한다.
                // JwksCache.getForceRefresh() 자체에 쿨다운이 있어 unknown-kid를
                // 미끼로 한 refetch storm은 캐시 레벨에서 억제된다.
                // action=force-refresh-attempt: 쿨다운 중이면 JwksCache가 내부적으로
                // 실제 refetch를 생략하고 스냅샷을 그대로 반환할 수 있어(F26 쿨다운),
                // 이 로그 시점에는 아직 실제 refetch 여부가 확정되지 않는다.
                log.warn("id-token verify failed: reason=kid-miss kid={} action=force-refresh-attempt", kid);
                key = jwks.getForceRefresh().getKeyByKeyId(kid);
            }
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
            // G20: nbf(있으면)가 아직 도래하지 않았으면 거부 — not-before 미검증 시
            // pre-minted(미래에 유효화되도록 서명된) 토큰이 조기 수용될 수 있다.
            Date nbfDate = c.getNotBeforeTime();
            if (nbfDate != null) {
                Instant nbf = nbfDate.toInstant();
                if (nbf.isAfter(now.plus(CLOCK_SKEW_LEEWAY))) {
                    log.warn("id-token verify failed: reason=nbf-future nbf={} now={}", nbf, now);
                    throw new PasskeyIdTokenException("ID Token not yet valid (nbf=" + nbf + ", now=" + now + ")");
                }
            }
            // G20: iat가 (허용 clock skew를 넘어) 미래면 거부 — future-issuance 토큰은
            // 발급자 시계 조작 또는 손상된 서명 키에 의한 사전 발급 가능성을 시사한다.
            Date iatDateForCheck = c.getIssueTime();
            if (iatDateForCheck != null) {
                Instant iat = iatDateForCheck.toInstant();
                if (iat.isAfter(now.plus(CLOCK_SKEW_LEEWAY))) {
                    log.warn("id-token verify failed: reason=iat-future iat={} now={}", iat, now);
                    throw new PasskeyIdTokenException("ID Token issued in the future (iat=" + iat + ", now=" + now + ")");
                }
            }
            // 원본 Kotlin 의 `as? List<String>` 와 동일한 lenient 의도: amr 이 List 가
            // 아니면(또는 없으면) 예외 없이 null 로 폴백 → 아래에서 emptyList() 로 치환.
            // List 인 경우 각 원소를 String 으로 정규화한다. 무조건 캐스트는 (1) 비-List
            // amr 에서 ClassCastException → parse 실패로 의미가 바뀌고, (2) List 라도
            // 비-String 원소(숫자 등)가 섞이면 heap pollution → 첫 String 사용 지점에서
            // 지연 ClassCastException 이 터지므로 금지.
            Object amrRaw = c.getClaim("amr");
            List<String> amr = null;
            if (amrRaw instanceof List<?> rawList) {
                amr = rawList.stream()
                        .filter(java.util.Objects::nonNull)
                        .map(String::valueOf)
                        .toList();
            }
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
        String tenantPart = expectedIssuer.substring(slash + 1);
        // tenant segment 가 비어 있으면(issuerBase 만 넘어온 오설정) fail-closed.
        // 또한 issuerBase 가 scheme 슬래시까지만 남는 경우(예: "https:/")를 막아,
        // "https://issuer" 처럼 tenant 없는 expectedIssuer 가 잘못 통과하지 않게 한다.
        if (tenantPart.isBlank() || issuerBase.endsWith(":/") || issuerBase.endsWith(":")) {
            log.warn("id-token verify failed: reason=config expectedIssuer-no-tenant got={}", expectedIssuer);
            throw new PasskeyIdTokenException("expectedIssuer must be <issuerBase>/<tenantId> with a tenant segment");
        }
        String expectedTenant = normalizeTenantId(tenantPart);

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
