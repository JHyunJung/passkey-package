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
 * 가 요청마다 {@link #get()} 을 호출하므로, 운영자가 키 파일만 바꾸면 재기동 없이
 * 다음 요청부터 새 키가 반영된다. 서버의 grace rotation 과 짝지으면 무중단 교체.
 *
 * <p>동작:
 * <ul>
 *   <li>파일 미설정({@code keyFile == null}) → 항상 env 폴백({@code envFallback}).</li>
 *   <li>파일 설정 + 폴링 주기 경과 시에만 mtime 검사 → 변경됐을 때만 재읽기(hot path
 *       에서 매 요청 디스크 IO 회피).</li>
 *   <li>파일이 비었거나 공백뿐 → env 폴백.</li>
 *   <li>읽기 실패(삭제/권한) → 직전 유효 키 유지(fail-safe) + WARN. 단 한 번도 못
 *       읽었으면 env 폴백.</li>
 * </ul>
 *
 * <p>스레드 안전: 캐시 필드는 volatile. 여러 worker 스레드가 동시에 재읽기를
 * 시도할 수 있으나 결과가 동일하므로 lost-update 가 무해(idempotent reload).
 */
public class ReloadableApiKeySupplier implements Supplier<String> {

    private static final Logger log = LoggerFactory.getLogger(ReloadableApiKeySupplier.class);

    private final Path keyFile;
    private final long pollMillis;
    private final String envFallback;

    private volatile String cachedKey;
    private volatile long lastModified = Long.MIN_VALUE;
    private volatile long lastPollAt = Long.MIN_VALUE;

    public ReloadableApiKeySupplier(Path keyFile, Duration pollInterval, String envFallback) {
        this.keyFile = keyFile;
        this.pollMillis = pollInterval == null ? 0L : Math.max(0L, pollInterval.toMillis());
        this.envFallback = envFallback;
    }

    @Override
    public String get() {
        if (keyFile == null) {
            return envFallback;
        }
        maybeReload();
        return cachedKey != null ? cachedKey : envFallback;
    }

    private void maybeReload() {
        long now = System.currentTimeMillis();
        if (now - lastPollAt < pollMillis && cachedKey != null) {
            return;
        }
        lastPollAt = now;
        try {
            long mtime = Files.getLastModifiedTime(keyFile).toMillis();
            if (mtime == lastModified && cachedKey != null) {
                return;
            }
            String raw = Files.readString(keyFile).trim();
            lastModified = mtime;
            if (raw.isEmpty()) {
                cachedKey = null;
                log.warn("api-key file is empty/blank, falling back to env: {}", keyFile);
            } else {
                cachedKey = raw;
            }
        } catch (IOException | RuntimeException e) {
            log.warn("api-key file reload failed (keeping last good key): {} cause={}",
                    keyFile, e.toString());
        }
    }
}
