package com.crosscert.passkey.app.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
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

    private final ProxyManager<String> proxy;

    public RateLimitFilter(ProxyManager<String> proxy) {
        this.proxy = proxy;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !LIMITS.containsKey(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        String uri = req.getRequestURI();
        BucketConfiguration config = LIMITS.get(uri);

        // IP bucket — fired on EVERY request. Attackers spraying prefixes
        // get capped here before the key bucket can be circumvented.
        String ipBucketKey = "ratelimit:" + uri + ":ip:" + req.getRemoteAddr();
        var ipBucket = proxy.builder().build(ipBucketKey, () -> config);
        if (!ipBucket.tryConsume(1L)) {
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
