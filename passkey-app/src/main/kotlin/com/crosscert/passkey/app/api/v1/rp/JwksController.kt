package com.crosscert.passkey.app.api.v1.rp

import com.crosscert.passkey.core.jwt.SigningKeyProvider
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

private val log = LoggerFactory.getLogger(JwksController::class.java)

/**
 * Exposes the public JWKS (RFC 7517) for RPs to verify ID Tokens issued
 * by this passkey-app. Intentionally NOT wrapped in ApiResponse —
 * RFC 7517 + OIDC Discovery wire format is mandatory; any envelope
 * breaks standard JWT libraries (Nimbus, jose4j, jsonwebtoken, jose).
 */
@RestController
class JwksController(private val keys: SigningKeyProvider) {

    @GetMapping(value = ["/.well-known/jwks.json"], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun jwks(): Map<String, Any?> {
        // publicJwkSet().toJSONObject() never includes the "d" private
        // exponent — Nimbus's toPublicJWK() in SigningKeyProvider drops
        // all private fields before this serializer runs.
        val body: Map<String, Any?> = keys.publicJwkSet().toJSONObject()
        if (log.isDebugEnabled) {
            // RequestLoggingFilter excludes /.well-known/jwks.json so this
            // is the only place operators see JWKS poll volume. Count + kid
            // only — public key bytes are never logged.
            val count = (body["keys"] as? List<*>)?.size ?: 0
            log.debug("jwks served: keys={} activeKid={}", count, keys.signingKey().keyID)
        }
        return body
    }
}
