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

    /**
     * stale-if-error 허용 상한(grace). fetch 실패 시 직전 스냅샷을 무제한 반환하면, 키
     * compromise로 회전된 직후 JWKS 서버 장애가 겹칠 때 폐기된 키를 TTL 넘어 무한 신뢰
     * (폐기 윈도우 우회)하게 된다. 따라서 stale 허용을 {@code fetchedAt + ttl + STALE_GRACE}
     * 이내로 제한하고, 초과하면 fail-closed(예외).
     *
     * <p>운영 TTL이 5분일 때 grace 10분 → 장애 중 폐기 키 신뢰는 최대 TTL+grace=15분으로 bounded.
     * 짧은 장애는 가용성을 위해 stale 허용, 장기 장애는 보안(폐기 타이밍 보호)을 우선한다.
     */
    private static final Duration STALE_GRACE = Duration.ofMinutes(10);

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
            // 직전 실패로 백오프 중이면 fetch를 건너뛴다. 단 catch 경로와 동일한 stale-grace
            // 상한을 강제: 상한 이내면 stale 반환, 초과면 fail-closed(폐기 윈도우 우회 차단).
            // (이 가드가 없으면 경계 직전 실패로 설정된 backoff 윈도우 동안 상한을 몇 초 넘겨
            //  stale을 허용해 fail-closed 경계가 살짝 새어 나간다.)
            if (again != null && n2.isBefore(nextRetryAfter)) {
                if (withinStaleGrace(again, n2)) {
                    return again.jwks();
                }
                throw new PasskeyIdTokenException(
                        "JWKS fetch failure: stale-if-error max age exceeded (no refresh)");
            }
            try {
                JWKSet fresh = fetch();
                // fetchedAt 기준은 fetch 시작 전 시각(n2)이 아니라 *fetch 완료 시각*.
                // fetch(블로킹 I/O)가 TTL보다 오래 걸리면 n2+TTL이 이미 과거가 돼 방금
                // 갱신한 스냅샷이 즉시 만료로 판정 → 대기하던 스레드들이 각자 또 fetch
                // (single-flight 무력화). 완료 시각으로 찍어 TTL 윈도우를 정확히 시작한다.
                Instant fetchedAt = clock.instant();
                snapshot.set(new Snapshot(fresh, fetchedAt));
                return fresh;
            } catch (PasskeyIdTokenException e) {
                // 직전 유효 스냅샷이 있고 stale-if-error 상한(TTL+grace) 이내면 가용성 보존을
                // 위해 그것을 반환하고 백오프 기록. 만료된 JWKS가 아니라 *직전 유효* JWKS이므로
                // 토큰 검증 의미는 보존된다.
                Instant failedAt = clock.instant(); // 실패가 반환된 그 순간(P2와 동일 기준)
                if (again != null && withinStaleGrace(again, failedAt)) {
                    // 백오프 기준은 fetch 시작 전 시각(n2)이 아니라 실패 시각. fetch(블로킹 I/O)가
                    // BACKOFF보다 오래 걸려 실패하면 n2+BACKOFF는 이미 과거가 돼 negative-cache가
                    // 무력화(retry storm 재현)되므로, 실패 시각부터 센다.
                    nextRetryAfter = failedAt.plus(BACKOFF);
                    return again.jwks();
                }
                // 폴백 스냅샷이 없거나(최초 부팅 실패) stale 상한을 초과하면 fail-closed(예외).
                // 후자는 폐기된 키를 무한 신뢰하는 폐기 윈도우 우회를 차단한다.
                throw e;
            }
        }
    }

    /**
     * stale-if-error 상한 검사: 스냅샷이 {@code fetchedAt + ttl + STALE_GRACE} 이내인가.
     * catch(실제 fetch 실패)·backoff(fetch 생략) 두 경로가 공유해 상한 판정을 일관화하고
     * 드리프트를 막는다.
     */
    private boolean withinStaleGrace(Snapshot s, Instant now) {
        return s.fetchedAt().plus(ttl).plus(STALE_GRACE).isAfter(now);
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
