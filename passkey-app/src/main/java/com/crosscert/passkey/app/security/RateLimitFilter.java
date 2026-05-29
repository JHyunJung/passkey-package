package com.crosscert.passkey.app.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

/**
 * Token-bucket rate limit over Redis (Bucket4j Lettuce backend).
 *
 * <p>Two buckets per endpoint per request — both must allow:
 * <ul>
 *   <li><b>IP bucket</b> {@code (endpoint, remoteAddr)} — drops floods
 *       BEFORE any expensive auth work. Attackers cannot bypass this
 *       by spraying random {@code pk_} prefixes (codex P1 fix).</li>
 *   <li><b>Key bucket</b> {@code (endpoint, X-API-Key prefix or "anon-IP")}
 *       — keeps a single API key from exhausting another tenant's
 *       share. Anonymous requests share the IP bucket only.</li>
 * </ul>
 *
 * <p>Limits per spec §9:
 * <ul>
 *   <li>{@code /api/v1/rp/registration/start}    — 60/min</li>
 *   <li>{@code /api/v1/rp/registration/finish}   — 60/min</li>
 *   <li>{@code /api/v1/rp/authentication/start}  — 300/min</li>
 *   <li>{@code /api/v1/rp/authentication/finish} — 300/min</li>
 * </ul>
 *
 * <p>Order: {@link Ordered#HIGHEST_PRECEDENCE} — runs BEFORE
 * {@link ApiKeyAuthFilter} (at {@code HIGHEST_PRECEDENCE + 5}) so
 * floods are dropped before the (slow) BCrypt verification path.
 *
 * <p>On rejection emits {@code 429 Too Many Requests} with
 * {@code Retry-After: 60} and a {@code application/problem+json}
 * body that intentionally omits any tenant-identifying detail.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /** {@code "pk_"} + 8 base64url chars — matches {@link ApiKeyAuthFilter}. */
    private static final int PREFIX_LEN = 11;
    private static final String KEY_PREFIX = "pk_";

    private static final Map<String, BucketConfiguration> LIMITS = Map.of(
            "/api/v1/rp/registration/start",
            tokenBucket(60, Duration.ofMinutes(1)),
            "/api/v1/rp/registration/finish",
            tokenBucket(60, Duration.ofMinutes(1)),
            "/api/v1/rp/authentication/start",
            tokenBucket(300, Duration.ofMinutes(1)),
            "/api/v1/rp/authentication/finish",
            tokenBucket(300, Duration.ofMinutes(1)));

    /**
     * P0-4: self-service credential endpoints live under path variables
     * ({@code /credentials/{credentialId}...}), so exact-match {@link #LIMITS}
     * cannot reach them. They are matched by prefix + HTTP method instead:
     * <ul>
     *   <li>{@code DELETE /api/v1/rp/credentials/{id}} — 20/min (destructive,
     *       lower cap to bound hard-delete enumeration with a stolen key).</li>
     *   <li>everything else under the prefix (GET list, POST label) — 60/min.</li>
     * </ul>
     */
    private static final String CREDENTIALS_PREFIX = "/api/v1/rp/credentials";
    private static final BucketConfiguration CREDENTIALS_DELETE_LIMIT =
            tokenBucket(20, Duration.ofMinutes(1));
    private static final BucketConfiguration CREDENTIALS_DEFAULT_LIMIT =
            tokenBucket(60, Duration.ofMinutes(1));

    private final ProxyManager<String> proxy;

    public RateLimitFilter(ProxyManager<String> proxy) {
        this.proxy = proxy;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // getServletPath() strips the deployment context path so the
        // rate-limit match is robust under e.g.
        // server.servlet.context-path=/passkey. getRequestURI() would
        // include that prefix and miss the LIMITS keys entirely,
        // silently disabling the limiter.
        String path = request.getServletPath();
        return resolve(path, request.getMethod()) == null;
    }

    /**
     * Resolve the rate-limit tier for a request, or {@code null} if the path
     * isn't limited. Exact-match {@link #LIMITS} is checked first so the
     * ceremony endpoints keep their existing behavior unchanged; only paths
     * that miss exact-match fall through to the credentials prefix rule. The
     * returned {@code scope} is used in the bucket key in place of the raw URI
     * so path-variable URIs ({@code /credentials/{id}}) collapse onto one
     * stable bucket per tier instead of spawning a bucket per credentialId.
     */
    private static RateTier resolve(String path, String method) {
        if (path == null) {
            return null;
        }
        BucketConfiguration exact = LIMITS.get(path);
        if (exact != null) {
            return new RateTier(path, exact);
        }
        if (path.equals(CREDENTIALS_PREFIX) || path.startsWith(CREDENTIALS_PREFIX + "/")) {
            if ("DELETE".equalsIgnoreCase(method)) {
                return new RateTier(CREDENTIALS_PREFIX + ":DELETE", CREDENTIALS_DELETE_LIMIT);
            }
            return new RateTier(CREDENTIALS_PREFIX, CREDENTIALS_DEFAULT_LIMIT);
        }
        return null;
    }

    /** A resolved limit: {@code scope} keys the bucket, {@code config} sizes it. */
    private record RateTier(String scope, BucketConfiguration config) {}

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        long startNs = System.nanoTime();
        // shouldNotFilter already guaranteed resolve(...) != null.
        RateTier tier = resolve(req.getServletPath(), req.getMethod());
        String uri = tier.scope();
        BucketConfiguration config = tier.config();

        // IP bucket — fired on EVERY request. Attackers spraying prefixes
        // get capped here before the key bucket can be circumvented.
        String ipBucketKey = "ratelimit:" + uri + ":ip:" + req.getRemoteAddr();
        var ipBucket = proxy.builder().build(ipBucketKey, () -> config);
        if (!ipBucket.tryConsume(1L)) {
            log.warn("rate limit exceeded: scope=ip path={} remoteAddr={} durMs={}",
                    uri, req.getRemoteAddr(),
                    (System.nanoTime() - startNs) / 1_000_000);
            tooManyRequests(res);
            return;
        }

        // Key bucket — caps a single tenant. Only well-formed pk_ keys
        // get their own bucket; malformed headers and missing headers
        // share the IP-anchored anon bucket so attackers can't bypass
        // by varying the header shape.
        String header = req.getHeader("X-API-Key");
        boolean wellFormedKey = header != null
                && header.length() >= PREFIX_LEN
                && header.startsWith(KEY_PREFIX);
        String keyPart = wellFormedKey
                ? header.substring(0, PREFIX_LEN)
                : "anon-" + req.getRemoteAddr();
        String keyBucketKey = "ratelimit:" + uri + ":key:" + keyPart;
        var keyBucket = proxy.builder().build(keyBucketKey, () -> config);

        if (keyBucket.tryConsume(1L)) {
            chain.doFilter(req, res);
        } else {
            log.warn("rate limit exceeded: scope=key path={} prefix={} durMs={}",
                    uri, keyPart,
                    (System.nanoTime() - startNs) / 1_000_000);
            tooManyRequests(res);
        }
    }

    private void tooManyRequests(HttpServletResponse res) throws IOException {
        res.setStatus(429);
        res.setHeader("Retry-After", "60");
        res.setContentType("application/problem+json");
        res.getWriter().write(
                "{\"type\":\"about:blank\",\"status\":429,\"title\":\"Too Many Requests\"}");
    }

    /**
     * Build a classic token bucket with greedy refill: {@code capacity}
     * tokens, replenished smoothly over {@code window}. Uses Bucket4j 8.18's
     * staged {@code Bandwidth.builder()} fluent API ({@code Bandwidth.classic}
     * / {@code Refill.greedy} are deprecated since 8.x).
     */
    private static BucketConfiguration tokenBucket(long capacity, Duration window) {
        Bandwidth bandwidth = Bandwidth.builder()
                .capacity(capacity)
                .refillGreedy(capacity, window)
                .build();
        return BucketConfiguration.builder()
                .addLimit(bandwidth)
                .build();
    }
}
