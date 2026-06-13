package com.crosscert.passkey.admin.policy;

import com.crosscert.passkey.core.entity.SecurityPolicy;
import com.crosscert.passkey.core.repository.SecurityPolicyRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.List;

/**
 * P0-6: security_policy.cors_allowlist 를 실제 CORS 정책으로 적용.
 *
 * <p>Spring CorsFilter 는 health/asset 을 포함한 모든 요청마다
 * {@link #getCorsConfiguration} 을 호출한다. allowlist 는 거의 바뀌지 않으므로
 * 매 요청 DB 조회를 피하기 위해 짧은 TTL(10s) 의 in-memory 스냅샷을 둔다.
 * 만료 시에만 단일 행을 읽어 갱신하며, 동시성은 약간의 중복 읽기를 허용하는
 * 단순한 방식(volatile 스냅샷)으로 처리한다 — 정확성에는 영향이 없다.
 */
@Slf4j
@Component
public class DynamicCorsConfigurationSource implements CorsConfigurationSource {

    private static final Long SINGLETON_ID = 1L;
    private static final long TTL_NANOS = 10_000_000_000L; // 10s

    private final SecurityPolicyRepository repo;
    private final ObjectMapper mapper;

    /** Cached origin allowlist + when it expires (nanoTime). volatile = lock-free reads. */
    private volatile List<String> cachedAllowlist = List.of();
    private volatile long expiresAtNanos = 0L;
    /** Forces a load on the very first call, independent of nanoTime's sign/wraparound. */
    private volatile boolean loaded = false;

    public DynamicCorsConfigurationSource(SecurityPolicyRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    @Override
    public CorsConfiguration getCorsConfiguration(HttpServletRequest request) {
        List<String> allow = currentAllowlist();
        if (allow.isEmpty()) {
            return null; // CORS 비활성 (same-origin 만 허용)
        }
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(allow);
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setAllowCredentials(true);
        return cfg;
    }

    /**
     * Return the cached allowlist, refreshing from the DB once per TTL window.
     * Hot path: a cache hit takes two volatile reads and no DB / transaction.
     * On expiry one (or, under a brief race, a few) caller(s) reload — harmless.
     */
    private List<String> currentAllowlist() {
        long now = System.nanoTime();
        // now - expiresAtNanos < 0  → still within TTL window (overflow-safe compare).
        if (loaded && now - expiresAtNanos < 0) {
            return cachedAllowlist;
        }
        List<String> fresh = loadAllowlist();
        cachedAllowlist = fresh;
        expiresAtNanos = now + TTL_NANOS;
        loaded = true;
        return fresh;
    }

    /**
     * Single-row read on a cache miss. No {@code @Transactional} here on purpose:
     * Spring Data wraps the {@code findById} in its own transaction, and a
     * self-invoked {@code @Transactional} method would not be proxied anyway.
     */
    private List<String> loadAllowlist() {
        return repo.findById(SINGLETON_ID)
                .map(SecurityPolicy::getCorsAllowlistJson)
                .map(this::parse)
                .orElse(List.of());
    }

    private List<String> parse(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return mapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            // fail-closed (빈 목록 → CORS 비활성) 하되 silent 하지 않게 — raw blob 은
            // 로그에 남기지 않고 길이만 기록한다.
            log.warn("cors_allowlist parse 실패 (singletonId={} jsonLength={}) — CORS 비활성으로 처리: {}",
                    SINGLETON_ID, json.length(), e.getMessage());
            return List.of();
        }
    }
}
