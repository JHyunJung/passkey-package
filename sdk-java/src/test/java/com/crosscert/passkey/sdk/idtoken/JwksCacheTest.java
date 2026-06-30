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

        ProgrammableCache(Duration ttl, Clock clock) { super(BASE, ttl, clock); }

        @Override
        protected JWKSet fetch() {
            fetchCount.incrementAndGet();
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
}
