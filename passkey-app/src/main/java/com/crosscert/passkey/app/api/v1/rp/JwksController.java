package com.crosscert.passkey.app.api.v1.rp;

import com.crosscert.passkey.core.jwt.SigningKeyProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Exposes the public JWKS (RFC 7517) for RPs to verify ID Tokens issued
 * by this passkey-app. Intentionally NOT wrapped in ApiResponse —
 * RFC 7517 + OIDC Discovery wire format is mandatory; any envelope
 * breaks standard JWT libraries (Nimbus, jose4j, jsonwebtoken, jose).
 */
@RestController
public class JwksController {

    private static final Logger log = LoggerFactory.getLogger(JwksController.class);

    private final SigningKeyProvider keys;

    public JwksController(SigningKeyProvider keys) {
        this.keys = keys;
    }

    @GetMapping(value = "/.well-known/jwks.json",
                produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> jwks() {
        // publicJwkSet().toJSONObject() never includes the "d" private
        // exponent — Nimbus's toPublicJWK() in SigningKeyProvider drops
        // all private fields before this serializer runs.
        Map<String, Object> body = keys.publicJwkSet().toJSONObject();
        if (log.isDebugEnabled()) {
            // RequestLoggingFilter excludes /.well-known/jwks.json so this
            // is the only place operators see JWKS poll volume. Count + kid
            // only — public key bytes are never logged.
            Object keysList = body.get("keys");
            int count = (keysList instanceof List<?> l) ? l.size() : 0;
            log.debug("jwks served: keys={} activeKid={}",
                    count, keys.signingKey().getKeyID());
        }
        return body;
    }
}
