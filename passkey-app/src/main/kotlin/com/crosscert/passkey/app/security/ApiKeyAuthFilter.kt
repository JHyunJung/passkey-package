package com.crosscert.passkey.app.security

import com.crosscert.passkey.core.alert.SecurityAlertEvent
import com.crosscert.passkey.core.repository.ApiKeyScopeRepository
import com.crosscert.passkey.core.tenant.TenantContextHolder
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.context.ApplicationEventPublisher
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Clock

private val log = LoggerFactory.getLogger(ApiKeyAuthFilter::class.java)

private const val HEADER = "X-API-Key"
private const val KEY_PREFIX = "pk_"
private const val PREFIX_LEN = 11 // "pk_" + 8 base64url chars

private const val MDC_API_KEY_PREFIX = "apiKeyPrefix"
private const val MDC_TENANT_ID = "tenantId"

/**
 * A fixed BCrypt hash of an unused secret. Used for timing
 * equalization on the unknown-prefix code path so an attacker
 * cannot distinguish "prefix exists, wrong secret" from "prefix
 * does not exist" by measuring response time.
 */
private const val DUMMY_HASH = "\$2a\$12\$KIXxA7B0NQI8YzCq0RZ.7eN5lUucl0wH0G3jWvSEqcD9QYxIqUyqK"

private const val AUTH_COUNTER = "passkey_apikey_auth_total"

/**
 * Filters every `/api/v1/rp/` request. Parses `X-API-Key`,
 * looks up the row via [ApiKeyLookupService] (native SQL that runs
 * before any tenant context is set), BCrypt-verifies the secret, and on
 * success sets [TenantContextHolder] for the duration of the
 * request.
 *
 * Order: `HIGHEST_PRECEDENCE + 5` — runs after
 * `RateLimitFilter` and before the legacy
 * `DevTenantHeaderFilter` so a real `X-API-Key` always
 * wins over the dev header.
 *
 * Security properties:
 * - All failure paths emit a generic 401 — no detail leaks about
 *   whether the prefix existed, the secret matched, or the key
 *   was revoked.
 * - Unknown-prefix path runs BCrypt against a fixed dummy hash to
 *   equalize timing with the wrong-secret path. Phase 1 acceptable
 *   trade-off: removes the simple "does this prefix exist" oracle
 *   at the cost of one BCrypt op per invalid request — already
 *   rate-limited by RateLimitFilter.
 * - Only headers starting with the canonical `"pk_"` prefix
 *   are processed; any other shape is rejected before any DB call.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
class ApiKeyAuthFilter(
    private val lookup: ApiKeyLookupService,
    private val encoder: PasswordEncoder,
    private val scopeRepo: ApiKeyScopeRepository,
    private val scopeResolver: ApiKeyScopeResolver,
    meterRegistry: MeterRegistry,
    private val eventPublisher: ApplicationEventPublisher,
    private val clock: Clock,
) : OncePerRequestFilter() {

    // Pre-resolved result-tagged counters. Micrometer returns the same
    // Counter for a given name+tags, so hoisting the lookup out of the
    // hot path yields the identical metric series.
    private val cSuccess = meterRegistry.counter(AUTH_COUNTER, "result", "success")
    private val cUnknownPrefix = meterRegistry.counter(AUTH_COUNTER, "result", "unknown_prefix")
    private val cRevoked = meterRegistry.counter(AUTH_COUNTER, "result", "revoked")
    private val cExpired = meterRegistry.counter(AUTH_COUNTER, "result", "expired")
    private val cBadSecret = meterRegistry.counter(AUTH_COUNTER, "result", "bad_secret")
    private val cInsufficientScope = meterRegistry.counter(AUTH_COUNTER, "result", "insufficient_scope")

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        // getServletPath() strips the servlet context path so the
        // match is robust under a non-root deployment context (e.g.
        // server.servlet.context-path=/passkey). getRequestURI()
        // would include that prefix and let an attacker bypass
        // authentication for /passkey/api/v1/rp/** paths.
        val path: String? = request.servletPath
        return path == null || !path.startsWith("/api/v1/rp/")
    }

    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        // Defense-in-depth (sec-apikeyfilter-no-defensive-clear): clear any
        // stale ThreadLocal value before we read the request. Reuse of a
        // Tomcat worker thread that exited a prior request via an unusual
        // path should never leak the previous tenant into this one. Mirrors
        // DevTenantHeaderFilter's entry-point clear. The success path still
        // set()s the real tenant (below) and the finally clears it.
        TenantContextHolder.clear()

        val header: String? = req.getHeader(HEADER)
        if (header == null || header.length <= PREFIX_LEN || !header.startsWith(KEY_PREFIX)) {
            unauthorized(res)
            return
        }

        val prefix = header.substring(0, PREFIX_LEN)
        val secret = header.substring(PREFIX_LEN)

        val row = lookup.findByPrefix(prefix)
        if (row == null) {
            // Run BCrypt anyway so the timing of unknown-prefix matches
            // the timing of known-prefix-wrong-secret.
            encoder.matches(secret, DUMMY_HASH)
            log.warn("api-key auth failed: reason=unknown-prefix prefix={}", prefix)
            cUnknownPrefix.increment()
            unauthorized(res)
            return
        }

        val now = clock.instant()
        if (!row.isActive(now)) {
            // Same timing equalization for revoked/expired keys.
            encoder.matches(secret, DUMMY_HASH)
            val revoked = row.revokedAt != null
            log.warn("api-key auth failed: reason={} prefix={}", if (revoked) "revoked" else "expired", prefix)
            (if (revoked) cRevoked else cExpired).increment()
            unauthorized(res)
            return
        }
        if (!encoder.matches(secret, row.keyHash)) {
            log.warn("api-key auth failed: reason=bad-secret prefix={}", prefix)
            cBadSecret.increment()
            eventPublisher.publishEvent(
                SecurityAlertEvent(
                    SecurityAlertEvent.AlertType.API_KEY_BRUTE_FORCE,
                    SecurityAlertEvent.Severity.MEDIUM,
                    "api key auth failed (bad secret)",
                    mapOf("prefix" to prefix),
                ),
            )
            unauthorized(res)
            return
        }

        TenantContextHolder.set(row.tenantId)
        try {
            // P1-5: 경로가 scope 를 요구하면 키 보유 scope 와 대조. 키는 유효하나
            // 권한 부족이면 403(401 과 구분). 매핑 없는 경로는 scope 검사 생략.
            // 검사는 TenantContextHolder 가 설정된 상태에서 수행 — 앱 레벨 @Filter 조회가
            // 해당 키의 테넌트를 본다. getServletPath() 는 shouldNotFilter 의 인증
            // 게이트와 동일 기준이라 context-path 우회 위험이 없다.
            val required = scopeResolver.requiredScope(req.servletPath)
            if (required != null) {
                // scope 쿼리 예외는 fail-closed(전파→접근 거부)가 의도 —
                // touchLastUsed 와 달리 swallow 금지. 바깥 finally 가 context clear 보장.
                val held = scopeRepo.findScopeValuesByApiKeyId(row.id)
                if (required !in held) {
                    log.warn("api-key scope denied: prefix={} required={} held={}", prefix, required, held)
                    cInsufficientScope.increment()
                    forbidden(res)
                    return // 바깥 finally 가 TenantContextHolder.clear() 보장
                }
            }

            MDC.put(MDC_API_KEY_PREFIX, prefix)
            MDC.put(MDC_TENANT_ID, row.tenantId.toString())
            try {
                // 조회는 native SQL 로 명시 tenant_id 로 격리하므로
                // 인증된 row.tenantId 를 그대로 넘긴다.
                lookup.touchLastUsed(row.id, row.tenantId, now)
                if (log.isDebugEnabled) {
                    log.debug("api-key auth ok: prefix={} tenantId={}", prefix, row.tenantId)
                }
                cSuccess.increment()
                chain.doFilter(req, res)
            } finally {
                MDC.remove(MDC_API_KEY_PREFIX)
                MDC.remove(MDC_TENANT_ID)
            }
        } finally {
            TenantContextHolder.clear()
        }
    }

    /** Single generic 401 — no detail leaks. */
    private fun unauthorized(res: HttpServletResponse) {
        ProblemJson.write(res, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")
    }

    /** 403 — 키는 유효하나 요청 경로 scope 미보유. */
    private fun forbidden(res: HttpServletResponse) {
        ProblemJson.write(res, HttpServletResponse.SC_FORBIDDEN, "Forbidden", "insufficient_scope")
    }
}
