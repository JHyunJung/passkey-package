package com.crosscert.passkey.sdk.idtoken

import com.crosscert.passkey.sdk.exception.PasskeyIdTokenException
import com.nimbusds.jose.jwk.JWKSet
import java.net.URI
import java.net.URL
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

class JwksCache(baseUrl: URI, private val ttl: Duration, private val clock: Clock) {

    private val jwksUrl: URL = try {
        baseUrl.resolve("/.well-known/jwks.json").toURL()
    } catch (e: Exception) {
        throw IllegalArgumentException("Bad baseUrl for JWKS", e)
    }

    private val snapshot = AtomicReference<Snapshot>()

    fun get(): JWKSet {
        val cur = snapshot.get()
        val now = clock.instant()
        if (cur != null && cur.fetchedAt.plus(ttl).isAfter(now)) {
            return cur.jwks
        }
        val fresh = fetch()
        snapshot.set(Snapshot(fresh, now))
        return fresh
    }

    private fun fetch(): JWKSet {
        return try {
            JWKSet.load(jwksUrl)
        } catch (e: java.io.IOException) {
            throw PasskeyIdTokenException("JWKS fetch failed: $jwksUrl", e)
        } catch (e: java.text.ParseException) {
            throw PasskeyIdTokenException("JWKS fetch failed: $jwksUrl", e)
        }
    }

    private data class Snapshot(val jwks: JWKSet, val fetchedAt: Instant)
}
