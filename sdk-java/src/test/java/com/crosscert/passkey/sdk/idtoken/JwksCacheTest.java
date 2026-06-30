package com.crosscert.passkey.sdk.idtoken;

import com.crosscert.passkey.sdk.exception.PasskeyIdTokenException;
import com.nimbusds.jose.jwk.JWKSet;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * F27 — JWKS 갱신 single-flight + fetch 실패 시 직전 스냅샷 폴백·백오프 검증.
 *
 * <p>네트워크 의존 없이 {@code protected fetch()}를 서브클래스에서 오버라이드해 fetch 동작
 * (성공/IOException 환산 예외)을 결정적으로 제어한다. {@link MutableClock}로 시간을 전진시켜
 * TTL 만료·백오프 경계를 정확히 재현한다.
 */
class JwksCacheTest {

    private static final URI BASE = URI.create("https://issuer.example.com");

    /** 테스트가 시간을 임의로 전진시킬 수 있는 Clock. */
    static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant start) { this.now = start; }
        void advance(Duration d) { this.now = this.now.plus(d); }
        @Override public Instant instant() { return now; }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }

    /**
     * fetch 동작을 호출별로 프로그래밍할 수 있는 테스트용 캐시.
     * {@code failNext}가 true면 다음 fetch는 IOException → PasskeyIdTokenException으로 환산해 던진다.
     */
    static final class ProgrammableCache extends JwksCache {
        final AtomicInteger fetchCount = new AtomicInteger();
        volatile boolean failNext = false;
        volatile JWKSet nextResult = new JWKSet();
        /** fetch가 반환/예외 전 소모하는 시간(블로킹 I/O 시뮬레이션). null이면 0. */
        private final MutableClock clock;
        volatile Duration fetchDuration = Duration.ZERO;

        ProgrammableCache(Duration ttl, MutableClock clock) {
            super(BASE, ttl, clock);
            this.clock = clock;
        }

        @Override
        protected JWKSet fetch() {
            fetchCount.incrementAndGet();
            // fetch가 시간을 소모하도록 시계를 전진(느린 네트워크 I/O 모사).
            if (!fetchDuration.isZero()) {
                clock.advance(fetchDuration);
            }
            if (failNext) {
                // 실 fetch()가 IOException을 환산하는 것과 동일한 예외 타입을 던진다.
                throw new PasskeyIdTokenException("simulated JWKS fetch failure",
                        new IOException("boom"));
            }
            return nextResult;
        }
    }

    @Test
    void get_fetchFailureAfterSuccess_returnsStaleSnapshotNotThrow() {
        // given: 1회 성공으로 스냅샷 보유.
        MutableClock clock = new MutableClock(Instant.parse("2026-07-01T00:00:00Z"));
        ProgrammableCache cache = new ProgrammableCache(Duration.ofMinutes(5), clock);
        JWKSet first = new JWKSet();
        cache.nextResult = first;

        JWKSet got1 = cache.get();
        assertThat(got1).isSameAs(first);
        assertThat(cache.fetchCount.get()).isEqualTo(1);

        // when: TTL 만료 후 다음 fetch가 실패.
        clock.advance(Duration.ofMinutes(6)); // 5분 TTL 경과
        cache.failNext = true;

        // then: 예외를 던지지 않고 직전 유효 스냅샷을 반환한다(가용성 보존).
        JWKSet got2 = cache.get();
        assertThat(got2).isSameAs(first);
        assertThat(cache.fetchCount.get()).isEqualTo(2); // 실패 fetch 1회 시도

        // and: 백오프 동안(짧은 시간 경과)에는 fetch 재시도 없이 stale 반환.
        clock.advance(Duration.ofSeconds(1));
        JWKSet got3 = cache.get();
        assertThat(got3).isSameAs(first);
        assertThat(cache.fetchCount.get()).isEqualTo(2); // 재시도 안 함
    }

    @Test
    void get_fetchFailureOnFirstBoot_throws() {
        // given: 스냅샷이 전혀 없는 최초 부팅 상태에서 fetch가 실패.
        MutableClock clock = new MutableClock(Instant.parse("2026-07-01T00:00:00Z"));
        ProgrammableCache cache = new ProgrammableCache(Duration.ofMinutes(5), clock);
        cache.failNext = true;

        // then: 폴백할 스냅샷이 없으므로 기존대로 예외 전파(동작 보존).
        assertThatThrownBy(cache::get)
                .isInstanceOf(PasskeyIdTokenException.class)
                .hasMessageContaining("JWKS fetch failure");
    }

    @Test
    void get_validSnapshot_happyPathUnchanged() {
        // given: 1회 성공으로 스냅샷 보유.
        MutableClock clock = new MutableClock(Instant.parse("2026-07-01T00:00:00Z"));
        ProgrammableCache cache = new ProgrammableCache(Duration.ofMinutes(5), clock);
        JWKSet first = new JWKSet();
        cache.nextResult = first;

        JWKSet got1 = cache.get();
        assertThat(got1).isSameAs(first);
        assertThat(cache.fetchCount.get()).isEqualTo(1);

        // when: TTL 이내 반복 호출 — fetch 추가 호출 없이 동일 스냅샷 반환(현행과 동일).
        clock.advance(Duration.ofMinutes(4)); // TTL(5분) 이내
        JWKSet got2 = cache.get();
        assertThat(got2).isSameAs(first);
        assertThat(cache.fetchCount.get()).isEqualTo(1); // 재 fetch 없음
    }

    @Test
    void get_backoffExpires_refetchesAndRecovers() {
        // given: 성공→실패(stale 폴백, 백오프 진입) 상태.
        MutableClock clock = new MutableClock(Instant.parse("2026-07-01T00:00:00Z"));
        ProgrammableCache cache = new ProgrammableCache(Duration.ofMinutes(5), clock);
        JWKSet first = new JWKSet();
        cache.nextResult = first;
        cache.get(); // fetch 1

        clock.advance(Duration.ofMinutes(6));
        cache.failNext = true;
        assertThat(cache.get()).isSameAs(first); // fetch 2 (실패, stale 폴백 + 백오프)
        assertThat(cache.fetchCount.get()).isEqualTo(2);

        // when: 백오프 경과 후 다시 회복(fetch 성공).
        clock.advance(Duration.ofSeconds(10)); // BACKOFF(5s) 초과
        cache.failNext = false;
        JWKSet second = new JWKSet();
        cache.nextResult = second;

        // then: 재 fetch가 일어나고 새 스냅샷으로 갱신된다.
        JWKSet got = cache.get();
        assertThat(got).isSameAs(second); // fetch 3
        assertThat(cache.fetchCount.get()).isEqualTo(3);
    }

    @Test
    void get_slowFetchFailure_backoffMeasuredFromFailureMomentNotFetchStart() {
        // given: 1회 성공으로 스냅샷 보유.
        MutableClock clock = new MutableClock(Instant.parse("2026-07-01T00:00:00Z"));
        ProgrammableCache cache = new ProgrammableCache(Duration.ofMinutes(5), clock);
        JWKSet first = new JWKSet();
        cache.nextResult = first;
        cache.get(); // fetch 1
        assertThat(cache.fetchCount.get()).isEqualTo(1);

        // when: TTL 만료 후, fetch가 BACKOFF(5s)보다 오래(10s) 걸린 뒤 실패.
        //   회귀 버그(n2 기준)였다면 nextRetryAfter = (fetch 시작시각)+5s 가 이미 과거가 돼
        //   백오프가 무력화된다. 수정본은 *실패 시각* 기준이라 백오프가 유효해야 한다.
        clock.advance(Duration.ofMinutes(6));
        cache.failNext = true;
        cache.fetchDuration = Duration.ofSeconds(10); // BACKOFF(5s)보다 긴 느린 실패

        JWKSet staleA = cache.get(); // fetch 2 (느린 실패 → stale 폴백 + 백오프)
        assertThat(staleA).isSameAs(first);
        assertThat(cache.fetchCount.get()).isEqualTo(2);

        // then: 실패 직후 짧은 시간(BACKOFF 미만) 경과 시 즉시 재시도하지 않고 stale 반환.
        //   (회귀 버그였다면 여기서 fetchCount가 3으로 늘어 retry storm을 재현했을 것)
        cache.fetchDuration = Duration.ZERO;
        clock.advance(Duration.ofSeconds(2)); // 실패 시각 + 2s < BACKOFF(5s)
        JWKSet staleB = cache.get();
        assertThat(staleB).isSameAs(first);
        assertThat(cache.fetchCount.get()).isEqualTo(2); // 백오프 honor — 재시도 없음

        // and: 실패 시각 기준 BACKOFF 경과 후에는 다시 재시도가 허용된다.
        clock.advance(Duration.ofSeconds(4)); // 누적 실패+6s > BACKOFF(5s)
        cache.failNext = false;
        JWKSet second = new JWKSet();
        cache.nextResult = second;
        JWKSet recovered = cache.get(); // fetch 3
        assertThat(recovered).isSameAs(second);
        assertThat(cache.fetchCount.get()).isEqualTo(3);
    }

    @Test
    void get_slowSuccessfulFetch_fetchedAtMeasuredFromCompletionNotStart() {
        // given: 짧은 TTL(10s), 그런데 fetch(성공)가 TTL보다 오래(20s) 걸리는 stall 상황.
        //   회귀 버그(n2 기준)였다면 갱신 직후 스냅샷이 fetchedAt(=fetch 시작시각)+10s 가
        //   이미 과거라 즉시 만료로 판정 → 다음 호출/대기 스레드가 또 fetch(single-flight 무력화).
        //   수정본은 *완료 시각* 기준이라 갱신 직후 TTL 윈도우가 온전히 살아 있어야 한다.
        MutableClock clock = new MutableClock(Instant.parse("2026-07-01T00:00:00Z"));
        ProgrammableCache cache = new ProgrammableCache(Duration.ofSeconds(10), clock);
        JWKSet first = new JWKSet();
        cache.nextResult = first;
        cache.fetchDuration = Duration.ofSeconds(20); // TTL(10s)보다 긴 느린 성공

        JWKSet got1 = cache.get(); // fetch 1 (완료 시점에 시계가 +20s)
        assertThat(got1).isSameAs(first);
        assertThat(cache.fetchCount.get()).isEqualTo(1);

        // when: 완료 직후(TTL 이내) 다음 호출.
        cache.fetchDuration = Duration.ZERO;
        clock.advance(Duration.ofSeconds(3)); // 완료시각 + 3s < TTL(10s)
        JWKSet got2 = cache.get();

        // then: 동일 스냅샷 반환 + 재 fetch 없음(single-flight·TTL 윈도우 보존).
        //   (회귀 버그였다면 fetchCount가 2로 늘어 갱신 직후 재fetch storm을 재현했을 것)
        assertThat(got2).isSameAs(first);
        assertThat(cache.fetchCount.get()).isEqualTo(1);
    }

    @Test
    void get_fetchFailureWithinStaleGrace_returnsStale() {
        // given: 1회 성공으로 스냅샷 보유(TTL 5분).
        MutableClock clock = new MutableClock(Instant.parse("2026-07-01T00:00:00Z"));
        ProgrammableCache cache = new ProgrammableCache(Duration.ofMinutes(5), clock);
        JWKSet first = new JWKSet();
        cache.nextResult = first;
        cache.get(); // fetch 1
        assertThat(cache.fetchCount.get()).isEqualTo(1);

        // when: 장애가 stale 상한(TTL 5분 + grace 10분 = 15분) 이내(총 14분 경과)에서 발생.
        clock.advance(Duration.ofMinutes(14));
        cache.failNext = true;

        // then: grace 이내이므로 가용성 보존을 위해 stale 스냅샷 반환(예외 X).
        JWKSet got = cache.get(); // fetch 2 (실패, 상한 이내 → stale)
        assertThat(got).isSameAs(first);
        assertThat(cache.fetchCount.get()).isEqualTo(2);
    }

    @Test
    void get_fetchFailureBeyondStaleGrace_failsClosed() {
        // given: 1회 성공으로 스냅샷 보유(TTL 5분).
        MutableClock clock = new MutableClock(Instant.parse("2026-07-01T00:00:00Z"));
        ProgrammableCache cache = new ProgrammableCache(Duration.ofMinutes(5), clock);
        JWKSet first = new JWKSet();
        cache.nextResult = first;
        cache.get(); // fetch 1

        // when: 장애가 stale 상한(TTL 5분 + grace 10분 = 15분)을 초과(총 16분 경과)해서 지속.
        clock.advance(Duration.ofMinutes(16));
        cache.failNext = true;

        // then: 상한 초과 → 무한 stale을 막기 위해 fail-closed(예외).
        //   폐기된 키를 TTL 넘어 무한 신뢰하는 폐기 윈도우 우회를 차단한다.
        assertThatThrownBy(cache::get)
                .isInstanceOf(PasskeyIdTokenException.class)
                .hasMessageContaining("JWKS fetch failure");
    }

    @Test
    void get_backoffWindowBeyondStaleGrace_failsClosed() {
        // given: 1회 성공(TTL 5분, stale 상한 = 5+10 = 15분).
        MutableClock clock = new MutableClock(Instant.parse("2026-07-01T00:00:00Z"));
        ProgrammableCache cache = new ProgrammableCache(Duration.ofMinutes(5), clock);
        JWKSet first = new JWKSet();
        cache.nextResult = first;
        cache.get(); // fetch 1
        assertThat(cache.fetchCount.get()).isEqualTo(1);

        // when: 상한 직전(14분59초)에 실패 → catch 경로는 아직 grace 이내라 stale 반환하고
        //   nextRetryAfter = 14:59 + BACKOFF(5s) = 15:04 로 backoff 윈도우를 연다.
        clock.advance(Duration.ofSeconds(14 * 60 + 59));
        cache.failNext = true;
        JWKSet stale = cache.get(); // fetch 2 (상한 이내 → stale, backoff 진입)
        assertThat(stale).isSameAs(first);
        assertThat(cache.fetchCount.get()).isEqualTo(2);

        // and: 그 backoff 윈도우 안(15:01 < nextRetryAfter 15:04)이지만 stale 상한(15:00)은
        //   이미 초과한 시점에 호출. 가드 없는 회귀 버전이라면 fetch를 건너뛰고 stale을 반환해
        //   상한을 몇 초 새어 나갔을 것. 수정본은 backoff 경로도 상한을 강제해 fail-closed.
        clock.advance(Duration.ofSeconds(2)); // 15:01
        assertThatThrownBy(cache::get)
                .isInstanceOf(PasskeyIdTokenException.class)
                .hasMessageContaining("stale-if-error max age exceeded");
        // fetch는 backoff 윈도우라 시도조차 안 함(상한 초과로 곧장 fail-closed).
        assertThat(cache.fetchCount.get()).isEqualTo(2);
    }
}
