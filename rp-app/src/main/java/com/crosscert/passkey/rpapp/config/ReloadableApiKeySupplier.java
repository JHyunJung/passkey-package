package com.crosscert.passkey.rpapp.config;

import lombok.extern.slf4j.Slf4j;

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
 *       에서 매 요청 디스크 IO 회피). 캐시 유무와 무관하게 폴링 주기로 throttle 하되
 *       최초 호출은 즉시 시도한다(파일 부재/blank 상태에서도 매 요청 IO·로그 폭주 방지).</li>
 *   <li>파일이 비었거나 공백뿐 → env 폴백.</li>
 *   <li>읽기 실패(삭제/권한) → 직전 유효 키 유지(fail-safe) + WARN. 단 한 번도 못
 *       읽었으면 env 폴백.</li>
 * </ul>
 *
 * <p>스레드 안전: 캐시 상태({@code cachedKey} + {@code lastModified})를 불변 holder 로
 * 묶어 단일 volatile 참조로 발행한다. 동시 reload 가 순서 뒤바뀌어 완료돼도 두 필드가
 * 항상 일관된 짝으로 보이며, 마지막 성공 reload 의 (mtime,key) 쌍만 관찰된다.
 * lastPollAt 의 race 는 무해(여분의 stat 한 번)하므로 별도 volatile 로 둔다.
 */
@Slf4j
public class ReloadableApiKeySupplier implements Supplier<String> {

    /** (mtime, key) 를 한 번에 발행하기 위한 불변 holder. key==null 이면 env 폴백 신호. */
    private record State(long lastModified, String cachedKey) {}

    private static final State EMPTY = new State(Long.MIN_VALUE, null);

    private final Path keyFile;
    private final long pollMillis;
    private final String envFallback;

    private volatile State state = EMPTY;
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
        String key = state.cachedKey();
        return key != null ? key : envFallback;
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
                // 빈/공백 파일 → 캐시 비워 env 폴백. mtime 은 갱신해 같은 빈 파일을
                // 반복 재읽기하지 않게 하되, cachedKey=null 로 폴백 신호.
                state = new State(mtime, null);
                log.warn("api-key file is empty/blank, falling back to env: {}", keyFile);
            } else {
                state = new State(mtime, raw);
            }
        } catch (IOException | RuntimeException e) {
            // 읽기 실패(삭제/권한/IO): 직전 유효 키 유지(fail-safe). 단 한 번도
            // 못 읽었으면 state.cachedKey==null → get() 이 env 폴백.
            log.warn("api-key file reload failed (keeping last good key): {} cause={}",
                    keyFile, e.toString());
        }
    }
}
