package com.crosscert.passkey.core.jwt;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Component
public class IdTokenIssuer {

    private static final Logger log = LoggerFactory.getLogger(IdTokenIssuer.class);

    private final SigningKeyProvider signingKeys;
    private final String issuerBase;
    private final Duration tokenTtl;
    private final Clock clock;

    public IdTokenIssuer(SigningKeyProvider signingKeys,
                         @Value("${passkey.id-token.issuer-base:https://passkey.crosscert.com}")
                         String issuerBase,
                         @Value("${passkey.id-token.ttl:PT15M}")
                         Duration tokenTtl,
                         Clock clock) {
        this.signingKeys = signingKeys;
        this.issuerBase = issuerBase;
        this.tokenTtl = tokenTtl;
        this.clock = clock;
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
                                .encodeToString(uuidToBytes(credentialId)));
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
            // sub/aud are already INFO-logged at the call site
            // (AuthenticationFinishService) per Phase G2.
            log.debug("id-token issued: kid={} alg=RS256",
                    signingKeys.signingKey().getKeyID());
        }
        return jwt.serialize();
    }

    private static byte[] uuidToBytes(UUID uuid) {
        ByteBuffer buf = ByteBuffer.wrap(new byte[16]);
        buf.putLong(uuid.getMostSignificantBits());
        buf.putLong(uuid.getLeastSignificantBits());
        return buf.array();
    }
}
