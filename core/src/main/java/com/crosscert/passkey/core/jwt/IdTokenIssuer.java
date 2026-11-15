package com.crosscert.passkey.core.jwt;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.crosscert.passkey.core.util.CryptoUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 인증 성공 시 RP 에 돌려주는 ID Token(RS256 JWS)을 만든다. 호출자는 passkey-app AuthenticationFinishService.
 * iss = issuer-base + "/" + tenantId, aud = tenantId, sub = userHandle(base64url), 그 밖에 amr·cred_id·aaguid.
 * 헤더 kid 는 {@link SigningKeyProvider} 가 캐시한 ACTIVE 키 — RP(sdk-java IdTokenVerifier)는
 * /.well-known/jwks.json({@link JwksAssembler})의 공개키로 서명을 검증하고 iss·aud 를 자기 설정과 맞춰 본다.
 */
@Slf4j
@Component
public class IdTokenIssuer {

    private final SigningKeyProvider signingKeys;
    private final String issuerBase;
    private final Duration tokenTtl;
    private final Clock clock;

    public IdTokenIssuer(SigningKeyProvider signingKeys,
                         @Value("${passkey.id-token.issuer-base:https://dev-passkey.crosscert.com}")
                         String issuerBase,
                         @Value("${passkey.id-token.ttl:PT15M}")
                         Duration tokenTtl,
                         Clock clock) {
        // Fail-fast: prod/qa yml define this key with an empty env-var default
        // (${PASSKEY_ID_TOKEN_ISSUER_BASE:}) so an unset env var resolves to "".
        // Without this guard the app boots successfully and issues ID Tokens with
        // iss="/{tenantId}" — a structurally invalid issuer (no scheme/host) that
        // either slips past a symmetrically-misconfigured SDK verifier (fail-open)
        // or silently breaks auth relay against a correctly-configured RP.
        if (issuerBase == null || issuerBase.isBlank()) {
            throw new IllegalStateException(
                    "passkey.id-token.issuer-base must not be blank — "
                            + "set PASSKEY_ID_TOKEN_ISSUER_BASE to the absolute "
                            + "scheme://host issuer URL for this deployment");
        }
        this.signingKeys = signingKeys;
        this.issuerBase = issuerBase;
        this.tokenTtl = tokenTtl;
        this.clock = clock;
    }

    /** 발급하는 id-token 의 수명(초) — {@code passkey.id-token.ttl}. 응답 expiresIn 이 JWT exp 와 어긋나지 않도록 이 값을 쓴다. */
    public long tokenTtlSeconds() {
        return tokenTtl.toSeconds();
    }

    public String issue(String tenantId, byte[] userHandle, UUID credentialId, byte[] aaguid) {
        Instant now = clock.instant();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuerBase + "/" + tenantId)
                .subject(Base64.getUrlEncoder().withoutPadding().encodeToString(userHandle))
                .audience(tenantId)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(tokenTtl)))
                .claim("amr", List.of("webauthn"))
                .claim("cred_id",
                        Base64.getUrlEncoder().withoutPadding()
                                .encodeToString(CryptoUtils.uuidToBytes(credentialId)));
        if (aaguid != null) {
            claims.claim("aaguid", HexFormat.of().formatHex(aaguid));
        }

        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(signingKeys.signingKey().getKeyID())
                .build();
        SignedJWT jwt = new SignedJWT(header, claims.build());
        try {
            jwt.sign(new RSASSASigner(signingKeys.signingKey()));
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign ID Token", e);
        }
        if (log.isDebugEnabled()) {
            // Metadata only — no JWT body, no subject, no userHandle.
            // The sub tail and aud are INFO-logged at the call site
            // (AuthenticationFinishService).
            log.debug("id-token issued: kid={} alg=RS256",
                    signingKeys.signingKey().getKeyID());
        }
        return jwt.serialize();
    }

}
