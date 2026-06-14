package com.crosscert.passkey.sdk.idtoken

import com.crosscert.passkey.sdk.exception.PasskeyIdTokenException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant

class IdTokenVerifier(
    private val jwks: JwksCache,
    private val clock: Clock,
) {

    fun verify(compactJwt: String): IdTokenClaims {
        val started = clock.instant()
        try {
            val jwt = SignedJWT.parse(compactJwt)
            // Pin alg to RS256 — the issuer signs exclusively with RS256, so this
            // rejects alg-confusion / "none" / HS* downgrade attempts without
            // affecting any legitimate token (sec-idtoken-alg-not-pinned).
            if (JWSAlgorithm.RS256 != jwt.header.algorithm) {
                log.warn(
                    "id-token verify failed: reason=unexpected-alg alg={}",
                    jwt.header.algorithm,
                )
                throw PasskeyIdTokenException(
                    "Unexpected JWS algorithm: " + jwt.header.algorithm,
                )
            }
            val kid = jwt.header.keyID
            val key = jwks.get().getKeyByKeyId(kid)
            val rsa = key as? RSAKey
            if (rsa == null) {
                log.warn("id-token verify failed: reason=unknown-kid kid={}", kid)
                throw PasskeyIdTokenException("Unknown or non-RSA kid: $kid")
            }
            val verifier = RSASSAVerifier(rsa.toRSAPublicKey())
            if (!jwt.verify(verifier)) {
                log.warn("id-token verify failed: reason=signature kid={}", kid)
                throw PasskeyIdTokenException("Signature verification failed")
            }
            val c = jwt.jwtClaimsSet
            val now = clock.instant()
            val exp = c.expirationTime?.toInstant()
            if (exp == null || !exp.isAfter(now)) {
                log.warn("id-token verify failed: reason=expired exp={} now={}", exp, now)
                throw PasskeyIdTokenException("ID Token expired (exp=$exp, now=$now)")
            }
            @Suppress("UNCHECKED_CAST")
            val amr = c.getClaim("amr") as? List<String>
            val durMs = Duration.between(started, clock.instant()).toMillis()
            // sub is the (b64url-encoded) opaque userHandle; truncate to a
            // short prefix so logs don't leak the full identifier across
            // RPs while still being join-able to backend logs.
            val subShort = truncate(c.subject, 8)
            log.info(
                "id-token verified: reason=success iss={} sub={} durMs={}",
                c.issuer, subShort, durMs,
            )
            return IdTokenClaims(
                c.issuer,
                c.subject,
                if (c.audience.isEmpty()) null else c.audience[0],
                c.issueTime?.toInstant(),
                exp,
                if (amr == null) emptyList() else java.util.List.copyOf(amr),
                c.getClaim("cred_id") as String?,
                c.getClaim("aaguid") as String?,
            )
        } catch (e: PasskeyIdTokenException) {
            // Already logged at the precise reason branch above; rethrow.
            throw e
        } catch (e: Exception) {
            // Parse/structural failure (malformed JWT, JWK fetch error, etc.)
            // — neither signature nor expiry, so log as generic parse reason.
            log.warn("id-token verify failed: reason=parse cause={}", e.toString())
            throw PasskeyIdTokenException("ID Token parsing failed", e)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(IdTokenVerifier::class.java)

        private fun truncate(s: String?, n: Int): String? {
            if (s == null) return null
            return if (s.length <= n) s else s.substring(0, n) + "..."
        }
    }
}
