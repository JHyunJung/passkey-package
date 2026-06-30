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

    /** fetch 실패 후 직전 스냅샷이 있을 때 다음 재시도를 억제하는 짧은 백오프. */
    private static final Duration BACKOFF = Duration.ofSeconds(5);

    private final URL jwksUrl;
    private final Duration ttl;
    private final Clock clock;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();

    /** TTL 만료 시 동시 갱신을 1회로 직렬화(single-flight)하는 락. */
    private final Object refreshLock = new Object();
    /** 직전 fetch 실패 시 이 시각 이전에는 재시도하지 않고 stale 스냅샷을 반환. */
    private volatile Instant nextRetryAfter = Instant.MIN;

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
        // Happy path: 유효 스냅샷이 있으면 락 없이 그대로 반환(현행과 동일).
        if (cur != null && cur.fetchedAt().plus(ttl).isAfter(now)) {
            return cur.jwks();
        }
        // 만료 경로: single-flight — 한 스레드만 fetch, 나머지는 락 안에서 재확인.
        synchronized (refreshLock) {
            Snapshot again = snapshot.get();
            Instant n2 = clock.instant();
            // 다른 스레드가 락 대기 중 이미 갱신했으면 그 결과 사용(중복 fetch 제거).
            if (again != null && again.fetchedAt().plus(ttl).isAfter(n2)) {
                return again.jwks();
            }
            // 직전 실패로 백오프 중이고 폴백할 스냅샷이 있으면 fetch 없이 stale 반환.
            if (again != null && n2.isBefore(nextRetryAfter)) {
                return again.jwks();
            }
            try {
                JWKSet fresh = fetch();
                snapshot.set(new Snapshot(fresh, n2));
                return fresh;
            } catch (PasskeyIdTokenException e) {
                // 직전 유효 스냅샷이 있으면 가용성 보존을 위해 그것을 반환하고 백오프 기록.
                // 만료된 JWKS가 아니라 *직전 유효* JWKS이므로 토큰 검증 의미는 보존된다.
                if (again != null) {
                    nextRetryAfter = n2.plus(BACKOFF);
                    return again.jwks();
                }
                // 최초 부팅 실패(폴백 스냅샷 없음)는 기존대로 예외 전파(동작 보존).
                throw e;
            }
        }
    }

    protected JWKSet fetch() {
        try {
            return JWKSet.load(jwksUrl);
        } catch (IOException | ParseException e) {
            throw new PasskeyIdTokenException("JWKS fetch failed: " + jwksUrl, e);
        }
    }

    private record Snapshot(JWKSet jwks, Instant fetchedAt) {}
}
