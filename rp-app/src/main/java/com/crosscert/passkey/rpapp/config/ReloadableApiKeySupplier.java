package com.crosscert.passkey.rpapp.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * API Key 를 파일에서 핫리로드하는 Supplier. SDK 의 RedactingRequestInterceptor
 * 가 요청마다 get() 을 호출하므로, 운영자가 키 파일만 바꾸면 재기동 없이 다음 요청부터
 * 새 키가 반영된다.
 *
 * 스레드 안전: 캐시 상태(cachedKey + lastModified)를 불변 holder 로 묶어 단일 volatile
 * 참조로 발행한다. lastPollAt 의 race 는 무해하므로 별도 volatile.
 */
public class ReloadableApiKeySupplier implements Supplier<String> {

    private static final Logger log = LoggerFactory.getLogger(ReloadableApiKeySupplier.class);

    /** (mtime, key) 를 한 번에 발행하기 위한 불변 holder. key==null 이면 env 폴백 신호. */
    private record State(long lastModified, String cachedKey) {}

    private static final State EMPTY = new State(Long.MIN_VALUE, null);

    private final Path keyFile;
    private final String envFallback;
    private final long pollMillis;

    private volatile State state = EMPTY;
    private volatile long lastPollAt = Long.MIN_VALUE;

    public ReloadableApiKeySupplier(Path keyFile, Duration pollInterval, String envFallback) {
        this.keyFile = keyFile;
        this.envFallback = envFallback;
        this.pollMillis = (pollInterval == null) ? 0L : Math.max(0L, pollInterval.toMillis());
    }

    @Override
    public String get() {
        if (keyFile == null) {
            return envFallback;
        }
        maybeReload();
        String key = state.cachedKey();
        return (key != null) ? key : envFallback;
    }

    private void maybeReload() {
        long now = System.currentTimeMillis();
        // 폴링 throttle: 캐시 유무와 무관하게 poll 주기 내면 디스크 안 본다.
        // 최초 호출(lastPollAt==MIN_VALUE)은 항상 통과한다.
        if (lastPollAt != Long.MIN_VALUE && now - lastPollAt < pollMillis) {
            return;
        }
        lastPollAt = now;
        State current = state;
        try {
            long mtime = Files.getLastModifiedTime(keyFile).toMillis();
            if (mtime == current.lastModified() && current.cachedKey() != null) {
                return; // 안 바뀌었고 이미 읽은 캐시 있음
            }
            String raw = Files.readString(keyFile).trim();
            if (raw.isEmpty()) {
                // 빈/공백 파일 → 캐시 비워 env 폴백. mtime 갱신해 반복 재읽기 방지.
                state = new State(mtime, null);
                log.warn("api-key file is empty/blank, falling back to env: {}", keyFile);
            } else {
                state = new State(mtime, raw);
            }
        } catch (IOException e) {
            // 읽기 실패: 직전 유효 키 유지(fail-safe).
            log.warn("api-key file reload failed (keeping last good key): {} cause={}", keyFile, e.toString());
        } catch (RuntimeException e) {
            log.warn("api-key file reload failed (keeping last good key): {} cause={}", keyFile, e.toString());
        }
    }
}
