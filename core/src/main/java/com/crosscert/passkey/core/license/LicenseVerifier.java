package com.crosscert.passkey.core.license;

import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.Ed25519Verifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Component;

import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses + verifies a license JWS. Stateless. Bean lifecycle is
 * unconditional — even SaaS mode constructs this so tests pass without
 * the deployment.mode flag. Actual on-prem usage is gated by
 * LicenseBootstrap, which itself is @ConditionalOnProperty.
 */
@Component
public class LicenseVerifier {

    /**
     * Known feature whitelist — narrows what the JWS claims can grant.
     * Add new features here, not in the JWS payload alone.
     */
    private static final Set<String> KNOWN_FEATURES = Set.of(
            "mds", "audit-pdf", "security-policy-advanced");

    private final LicensePublicKeyProvider keys;
    private final LicenseProperties props;
    private final Clock clock;

    public LicenseVerifier(LicensePublicKeyProvider keys,
                           LicenseProperties props,
                           Clock clock) {
        this.keys = keys;
        this.props = props;
        this.clock = clock;
    }

    public LicenseToken verify(String compactJws) {
        SignedJWT jwt;
        try {
            jwt = SignedJWT.parse(compactJws);
        } catch (ParseException e) {
            throw new LicenseVerificationException("Malformed license token", e);
        }
        try {
            JWSVerifier v = new Ed25519Verifier(keys.publicKey());
            if (!jwt.verify(v)) {
                throw new LicenseVerificationException("License signature invalid");
            }
        } catch (LicenseVerificationException e) {
            throw e;
        } catch (Exception e) {
            throw new LicenseVerificationException("License signature invalid", e);
        }

        JWTClaimsSet claims;
        try {
            claims = jwt.getJWTClaimsSet();
        } catch (ParseException e) {
            throw new LicenseVerificationException("Malformed license claims", e);
        }

        if (!props.issuer().equals(claims.getIssuer())) {
            throw new LicenseVerificationException(
                    "License issuer mismatch: " + claims.getIssuer());
        }
        if (claims.getAudience() == null || !claims.getAudience().contains(props.audience())) {
            throw new LicenseVerificationException(
                    "License audience mismatch: " + claims.getAudience());
        }

        Instant now = clock.instant();
        Date nbf = claims.getNotBeforeTime();
        if (nbf != null && now.isBefore(nbf.toInstant())) {
            throw new LicenseVerificationException("License not yet valid: nbf=" + nbf);
        }
        Date exp = claims.getExpirationTime();
        if (exp == null || !now.isBefore(exp.toInstant())) {
            throw new LicenseVerificationException("License expired: exp=" + exp);
        }

        Set<String> features = filterKnown(rawFeatures(claims));
        LicenseLimits limits = readLimits(claims);
        String tenantId = (String) claims.getClaim("tenantId");
        if (tenantId == null) {
            throw new LicenseVerificationException("License missing required claim: tenantId");
        }
        try {
            java.util.UUID.fromString(tenantId);
        } catch (IllegalArgumentException e) {
            throw new LicenseVerificationException("License tenantId is not a valid UUID: " + tenantId);
        }

        return new LicenseToken(
                claims.getSubject(),
                claims.getJWTID(),
                claims.getIssueTime() == null ? null : claims.getIssueTime().toInstant(),
                nbf == null ? null : nbf.toInstant(),
                exp.toInstant(),
                tenantId,
                features,
                limits);
    }

    @SuppressWarnings("unchecked")
    private static List<String> rawFeatures(JWTClaimsSet claims) {
        Object raw = claims.getClaim("features");
        if (raw instanceof List<?> l) return (List<String>) l;
        return List.of();
    }

    private static Set<String> filterKnown(List<String> declared) {
        Set<String> out = new HashSet<>();
        for (String f : declared) {
            if (KNOWN_FEATURES.contains(f)) out.add(f);
        }
        return Set.copyOf(out);
    }

    @SuppressWarnings("unchecked")
    private static LicenseLimits readLimits(JWTClaimsSet claims) {
        Object raw = claims.getClaim("limits");
        if (!(raw instanceof Map<?, ?> m)) return LicenseLimits.DEFAULTS;
        int warn = intOrDefault(m, "warningDaysBeforeExpiry", LicenseLimits.DEFAULTS.warningDaysBeforeExpiry());
        int grace = intOrDefault(m, "graceHoursWhenOffline", LicenseLimits.DEFAULTS.graceHoursWhenOffline());
        return new LicenseLimits(warn, grace);
    }

    private static int intOrDefault(Map<?, ?> m, String k, int def) {
        Object v = m.get(k);
        if (v instanceof Number n) return n.intValue();
        return def;
    }
}
