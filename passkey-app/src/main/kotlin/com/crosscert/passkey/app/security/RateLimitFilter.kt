package com.crosscert.passkey.app.security

import io.github.bucket4j.Bandwidth
import io.github.bucket4j.BucketConfiguration
import io.github.bucket4j.distributed.proxy.ProxyManager
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Duration

private val log = LoggerFactory.getLogger(RateLimitFilter::class.java)

/** `"pk_"` + 8 base64url chars — matches [ApiKeyAuthFilter]. */
private const val PREFIX_LEN = 11
private const val KEY_PREFIX = "pk_"

/**
 * P0-4: self-service credential endpoints live under path variables
 * (`/credentials/{credentialId}...`), so exact-match [LIMITS]
 * cannot reach them. They are matched by prefix + HTTP method instead:
 * - `DELETE /api/v1/rp/credentials/{id}` — 20/min (destructive,
 *   lower cap to bound hard-delete enumeration with a stolen key).
 * - everything else under the prefix (GET list, POST label) — 60/min.
 */
private const val CREDENTIALS_PREFIX = "/api/v1/rp/credentials"

/**
 * Build a classic token bucket with greedy refill: `capacity`
 * tokens, replenished smoothly over `window`. Uses Bucket4j 8.18's
 * staged `Bandwidth.builder()` fluent API (`Bandwidth.classic`
 * / `Refill.greedy` are deprecated since 8.x).
 */
private fun tokenBucket(capacity: Long, window: Duration): BucketConfiguration =
    BucketConfiguration.builder()
        .addLimit(
            Bandwidth.builder()
                .capacity(capacity)
                .refillGreedy(capacity, window)
                .build(),
        )
        .build()

private val LIMITS: Map<String, BucketConfiguration> = mapOf(
    "/api/v1/rp/registration/start" to tokenBucket(60, Duration.ofMinutes(1)),
    "/api/v1/rp/registration/finish" to tokenBucket(60, Duration.ofMinutes(1)),
    "/api/v1/rp/authentication/start" to tokenBucket(300, Duration.ofMinutes(1)),
    "/api/v1/rp/authentication/finish" to tokenBucket(300, Duration.ofMinutes(1)),
)
private val CREDENTIALS_DELETE_LIMIT = tokenBucket(20, Duration.ofMinutes(1))
private val CREDENTIALS_DEFAULT_LIMIT = tokenBucket(60, Duration.ofMinutes(1))

/** A resolved limit: [scope] keys the bucket, [config] sizes it. */
private data class RateTier(val scope: String, val config: BucketConfiguration)

/**
 * Resolve the rate-limit tier for a request, or `null` if the path
 * isn't limited. Exact-match [LIMITS] is checked first so the
 * ceremony endpoints keep their existing behavior unchanged; only paths
 * that miss exact-match fall through to the credentials prefix rule. The
 * returned `scope` is used in the bucket key in place of the raw URI
 * so path-variable URIs (`/credentials/{id}`) collapse onto one
 * stable bucket per tier instead of spawning a bucket per credentialId.
 */
private fun resolveTier(path: String?, method: String?): RateTier? {
    if (path == null) return null
    LIMITS[path]?.let { return RateTier(path, it) }
    if (path == CREDENTIALS_PREFIX || path.startsWith("$CREDENTIALS_PREFIX/")) {
        return if ("DELETE".equals(method, ignoreCase = true)) {
            RateTier("$CREDENTIALS_PREFIX:DELETE", CREDENTIALS_DELETE_LIMIT)
        } else {
            RateTier(CREDENTIALS_PREFIX, CREDENTIALS_DEFAULT_LIMIT)
        }
    }
    return null
}

/**
 * Token-bucket rate limit over Redis (Bucket4j Lettuce backend).
 *
 * Two buckets per endpoint per request — both must allow:
 * - **IP bucket** `(endpoint, remoteAddr)` — drops floods
 *   BEFORE any expensive auth work. Attackers cannot bypass this
 *   by spraying random `pk_` prefixes (codex P1 fix).
 * - **Key bucket** `(endpoint, X-API-Key prefix or "anon-IP")`
 *   — keeps a single API key from exhausting another tenant's
 *   share. Anonymous requests share the IP bucket only.
 *
 * Limits per spec §9:
 * - `/api/v1/rp/registration/start`    — 60/min
 * - `/api/v1/rp/registration/finish`   — 60/min
 * - `/api/v1/rp/authentication/start`  — 300/min
 * - `/api/v1/rp/authentication/finish` — 300/min
 *
 * Order: [Ordered.HIGHEST_PRECEDENCE] — runs BEFORE
 * [ApiKeyAuthFilter] (at `HIGHEST_PRECEDENCE + 5`) so
 * floods are dropped before the (slow) BCrypt verification path.
 *
 * On rejection emits `429 Too Many Requests` with
 * `Retry-After: 60` and a `application/problem+json`
 * body that intentionally omits any tenant-identifying detail.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RateLimitFilter(private val proxy: ProxyManager<String>) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        // getServletPath() strips the deployment context path so the
        // rate-limit match is robust under e.g.
        // server.servlet.context-path=/passkey. getRequestURI() would
        // include that prefix and miss the LIMITS keys entirely,
        // silently disabling the limiter.
        return resolveTier(request.servletPath, request.method) == null
    }

    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        val startNs = System.nanoTime()
        // shouldNotFilter already guaranteed resolveTier(...) != null.
        val (uri, config) = checkNotNull(resolveTier(req.servletPath, req.method)) {
            "rate-limit tier vanished after shouldNotFilter"
        }

        // IP bucket — fired on EVERY request. Attackers spraying prefixes
        // get capped here before the key bucket can be circumvented.
        val ipBucket = proxy.builder().build("ratelimit:$uri:ip:${req.remoteAddr}") { config }
        if (!ipBucket.tryConsume(1L)) {
            log.warn(
                "rate limit exceeded: scope=ip path={} remoteAddr={} durMs={}",
                uri, req.remoteAddr, (System.nanoTime() - startNs) / 1_000_000,
            )
            tooManyRequests(res)
            return
        }

        // Key bucket — caps a single tenant. Only well-formed pk_ keys
        // get their own bucket; malformed headers and missing headers
        // share the IP-anchored anon bucket so attackers can't bypass
        // by varying the header shape.
        val header: String? = req.getHeader("X-API-Key")
        val keyPart = if (header != null && header.length >= PREFIX_LEN && header.startsWith(KEY_PREFIX)) {
            header.substring(0, PREFIX_LEN)
        } else {
            "anon-${req.remoteAddr}"
        }
        val keyBucket = proxy.builder().build("ratelimit:$uri:key:$keyPart") { config }

        if (keyBucket.tryConsume(1L)) {
            chain.doFilter(req, res)
        } else {
            log.warn(
                "rate limit exceeded: scope=key path={} prefix={} durMs={}",
                uri, keyPart, (System.nanoTime() - startNs) / 1_000_000,
            )
            tooManyRequests(res)
        }
    }

    private fun tooManyRequests(res: HttpServletResponse) {
        res.setHeader("Retry-After", "60")
        ProblemJson.write(res, 429, "Too Many Requests")
    }
}
