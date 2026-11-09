package com.crosscert.passkey.admin.policy

import com.crosscert.passkey.core.repository.SecurityPolicyRepository
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

private val log = LoggerFactory.getLogger(DynamicCorsConfigurationSource::class.java)

private const val SINGLETON_ID = 1L
private const val TTL_NANOS = 10_000_000_000L // 10s

/**
 * P0-6: security_policy.cors_allowlist 를 실제 CORS 정책으로 적용.
 *
 * Spring CorsFilter 는 health/asset 을 포함한 모든 요청마다
 * [getCorsConfiguration] 을 호출한다. allowlist 는 거의 바뀌지 않으므로
 * 매 요청 DB 조회를 피하기 위해 짧은 TTL(10s) 의 in-memory 스냅샷을 둔다.
 * 만료 시에만 단일 행을 읽어 갱신하며, 동시성은 약간의 중복 읽기를 허용하는
 * 단순한 방식(volatile 스냅샷)으로 처리한다 — 정확성에는 영향이 없다.
 */
@Component
class DynamicCorsConfigurationSource(
    private val repo: SecurityPolicyRepository,
    private val mapper: ObjectMapper,
) : CorsConfigurationSource {

    /** Cached origin allowlist + when it expires (nanoTime). volatile = lock-free reads. */
    @Volatile
    private var cachedAllowlist: List<String> = java.util.List.of()

    @Volatile
    private var expiresAtNanos: Long = 0L

    /** Forces a load on the very first call, independent of nanoTime's sign/wraparound. */
    @Volatile
    private var loaded: Boolean = false

    override fun getCorsConfiguration(request: HttpServletRequest): CorsConfiguration? {
        val allow = currentAllowlist()
        if (allow.isEmpty()) {
            return null // CORS 비활성 (same-origin 만 허용)
        }
        val cfg = CorsConfiguration()
        cfg.allowedOrigins = allow
        cfg.allowedMethods = java.util.List.of("GET", "POST", "PUT", "DELETE", "OPTIONS")
        cfg.allowedHeaders = java.util.List.of("*")
        cfg.allowCredentials = true
        return cfg
    }

    /**
     * Return the cached allowlist, refreshing from the DB once per TTL window.
     * Hot path: a cache hit takes two volatile reads and no DB / transaction.
     * On expiry one (or, under a brief race, a few) caller(s) reload — harmless.
     */
    private fun currentAllowlist(): List<String> {
        val now = System.nanoTime()
        // now - expiresAtNanos < 0  → still within TTL window (overflow-safe compare).
        if (loaded && now - expiresAtNanos < 0) {
            return cachedAllowlist
        }
        val fresh = loadAllowlist()
        cachedAllowlist = fresh
        expiresAtNanos = now + TTL_NANOS
        loaded = true
        return fresh
    }

    /**
     * Single-row read on a cache miss. No `@Transactional` here on purpose:
     * Spring Data wraps the `findById` in its own transaction, and a
     * self-invoked `@Transactional` method would not be proxied anyway.
     */
    private fun loadAllowlist(): List<String> =
        repo.findById(SINGLETON_ID)
            .map { it.corsAllowlistJson }
            .map<List<String>> { parse(it) }
            .orElse(java.util.List.of())

    /**
     * 반환형 nullable: 저장값이 JSON `null` 이면 readValue 가 null 을 돌려주고, 원 Java 처럼
     * loadAllowlist 의 Optional.map 이 이를 empty 로 바꿔 빈 목록(CORS 비활성)이 된다.
     */
    private fun parse(json: String?): List<String>? {
        // Java String.isBlank 와 같은 판정(Character.isWhitespace) — Kotlin isBlank 는 NBSP 등도 공백으로 본다.
        if (json == null || json.all(Character::isWhitespace)) return java.util.List.of()
        try {
            return mapper.readValue(json, object : TypeReference<List<String>>() {})
        } catch (e: Exception) {
            // fail-closed (빈 목록 → CORS 비활성) 하되 silent 하지 않게 — raw blob 은
            // 로그에 남기지 않고 길이만 기록한다.
            log.warn(
                "cors_allowlist parse 실패 (singletonId={} jsonLength={}) — CORS 비활성으로 처리: {}",
                SINGLETON_ID, json.length, e.message,
            )
            return java.util.List.of()
        }
    }
}
