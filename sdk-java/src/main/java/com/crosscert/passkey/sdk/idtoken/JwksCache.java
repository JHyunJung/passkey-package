package com.crosscert.passkey.sdk.idtoken;

import com.crosscert.passkey.sdk.exception.PasskeyIdTokenException;
import com.nimbusds.jose.jwk.JWKSet;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

public class JwksCache {

    private final URL jwksUrl;
    private final Duration ttl;
    private final Clock clock;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();

    public JwksCache(URI baseUrl, Duration ttl, Clock clock) {
        this.ttl = ttl;
        this.clock = clock;
        try {
            this.jwksUrl = baseUrl.resolve("/.well-known/jwks.json").toURL();
        } catch (Exception e) {
            throw new IllegalArgumentException("Bad baseUrl for JWKS", e);
        }
    }

    public JWKSet get() {
        Snapshot cur = snapshot.get();
        Instant now = clock.instant();
        if (cur != null && cur.fetchedAt().plus(ttl).isAfter(now)) {
            return cur.jwks();
        }
        JWKSet fresh = fetch();
        snapshot.set(new Snapshot(fresh, now));
        return fresh;
    }

    private JWKSet fetch() {
        try {
            return JWKSet.load(jwksUrl);
        } catch (IOException | ParseException e) {
            throw new PasskeyIdTokenException("JWKS fetch failed: " + jwksUrl, e);
        }
    }

    private record Snapshot(JWKSet jwks, Instant fetchedAt) {}
}
