package com.crosscert.passkey.app.security;

import com.crosscert.passkey.core.alert.SecurityAlertEvent;
import com.crosscert.passkey.core.repository.ApiKeyScopeRepository;
import com.crosscert.passkey.core.tenant.TenantContextHolder;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Filters every {@code /api/v1/rp/**} request. Parses {@code X-API-Key},
 * looks up the row via {@link ApiKeyLookupService} (native SQL that runs
 * before any tenant context is set), BCrypt-verifies the secret, and on
 * success sets {@link TenantContextHolder} for the duration of the
 * request.
 *
 * <p>Order: {@code HIGHEST_PRECEDENCE + 5} — runs after
 * {@code RateLimitFilter} and before the legacy
 * {@code DevTenantHeaderFilter} so a real {@code X-API-Key} always
 * wins over the dev header.
 *
 * <p>Security properties:
 * <ul>
 *   <li>All failure paths emit a generic 401 — no detail leaks about
 *       whether the prefix existed, the secret matched, or the key
 *       was revoked.</li>
 *   <li>Unknown-prefix path runs BCrypt against a fixed dummy hash to
 *       equalize timing with the wrong-secret path. Phase 1 acceptable
 *       trade-off: removes the simple "does this prefix exist" oracle
 *       at the cost of one BCrypt op per invalid request — already
 *       rate-limited by RateLimitFilter.</li>
 *   <li>Only headers starting with the canonical {@code "pk_"} prefix
 *       are processed; any other shape is rejected before any DB call.</li>
 * </ul>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-API-Key";
    private static final String KEY_PREFIX = "pk_";
    private static final int PREFIX_LEN = 11; // "pk_" + 8 base64url chars

    private static final String MDC_API_KEY_PREFIX = "apiKeyPrefix";
    private static final String MDC_TENANT_ID = "tenantId";

    /**
     * A fixed BCrypt hash of an unused secret. Used for timing
     * equalization on the unknown-prefix code path so an attacker
     * cannot distinguish "prefix exists, wrong secret" from "prefix
     * does not exist" by measuring response time.
     */
    private static final String DUMMY_HASH =
            "$2a$12$KIXxA7B0NQI8YzCq0RZ.7eN5lUucl0wH0G3jWvSEqcD9QYxIqUyqK";

    private static final String AUTH_COUNTER = "passkey_apikey_auth_total";

    private final ApiKeyLookupService lookup;
    private final PasswordEncoder encoder;
    private final ApiKeyScopeRepository scopeRepo;
    private final ApiKeyScopeResolver scopeResolver;
    private final MeterRegistry meterRegistry;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    // Pre-resolved result-tagged counters. Micrometer returns the same
    // Counter for a given name+tags, so hoisting the lookup out of the
    // hot path yields the identical metric series.
    private final io.micrometer.core.instrument.Counter cSuccess;
    private final io.micrometer.core.instrument.Counter cUnknownPrefix;
    private final io.micrometer.core.instrument.Counter cRevoked;
    private final io.micrometer.core.instrument.Counter cExpired;
    private final io.micrometer.core.instrument.Counter cBadSecret;
    private final io.micrometer.core.instrument.Counter cInsufficientScope;

    public ApiKeyAuthFilter(ApiKeyLookupService lookup,
                            PasswordEncoder encoder,
                            ApiKeyScopeRepository scopeRepo,
                            ApiKeyScopeResolver scopeResolver,
                            MeterRegistry meterRegistry,
                            ApplicationEventPublisher eventPublisher,
                            Clock clock) {
        this.lookup = lookup;
        this.encoder = encoder;
        this.scopeRepo = scopeRepo;
        this.scopeResolver = scopeResolver;
        this.meterRegistry = meterRegistry;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.cSuccess = meterRegistry.counter(AUTH_COUNTER, "result", "success");
        this.cUnknownPrefix = meterRegistry.counter(AUTH_COUNTER, "result", "unknown_prefix");
        this.cRevoked = meterRegistry.counter(AUTH_COUNTER, "result", "revoked");
        this.cExpired = meterRegistry.counter(AUTH_COUNTER, "result", "expired");
        this.cBadSecret = meterRegistry.counter(AUTH_COUNTER, "result", "bad_secret");
        this.cInsufficientScope = meterRegistry.counter(AUTH_COUNTER, "result", "insufficient_scope");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // getServletPath() strips the servlet context path so the
        // match is robust under a non-root deployment context (e.g.
        // server.servlet.context-path=/passkey). getRequestURI()
        // would include that prefix and let an attacker bypass
        // authentication for /passkey/api/v1/rp/** paths.
        String path = request.getServletPath();
        return path == null || !path.startsWith("/api/v1/rp/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        // Defense-in-depth (sec-apikeyfilter-no-defensive-clear): clear any
        // stale ThreadLocal value before we read the request. Reuse of a
        // Tomcat worker thread that exited a prior request via an unusual
        // path should never leak the previous tenant into this one. Mirrors
        // DevTenantHeaderFilter's entry-point clear. The success path still
        // set()s the real tenant (line below) and the finally clears it.
        TenantContextHolder.clear();

        String header = req.getHeader(HEADER);
        if (header == null || header.length() <= PREFIX_LEN
                || !header.startsWith(KEY_PREFIX)) {
            unauthorized(res);
            return;
        }

        String prefix = header.substring(0, PREFIX_LEN);
        String secret = header.substring(PREFIX_LEN);

        Optional<ApiKeyLookupService.ApiKeyAuthRow> opt = lookup.findByPrefix(prefix);
        if (opt.isEmpty()) {
            // Run BCrypt anyway so the timing of unknown-prefix matches
            // the timing of known-prefix-wrong-secret.
            encoder.matches(secret, DUMMY_HASH);
            log.warn("api-key auth failed: reason=unknown-prefix prefix={}", prefix);
            cUnknownPrefix.increment();
            unauthorized(res);
            return;
        }
        ApiKeyLookupService.ApiKeyAuthRow row = opt.get();

        Instant now = clock.instant();
        if (!row.isActive(now)) {
            // Same timing equalization for revoked/expired keys.
            encoder.matches(secret, DUMMY_HASH);
            String reason = row.revokedAt() != null ? "revoked" : "expired";
            log.warn("api-key auth failed: reason={} prefix={}", reason, prefix);
            (row.revokedAt() != null ? cRevoked : cExpired).increment();
            unauthorized(res);
            return;
        }
        if (!encoder.matches(secret, row.keyHash())) {
            log.warn("api-key auth failed: reason=bad-secret prefix={}", prefix);
            cBadSecret.increment();
            eventPublisher.publishEvent(new SecurityAlertEvent(
                    SecurityAlertEvent.AlertType.API_KEY_BRUTE_FORCE,
                    SecurityAlertEvent.Severity.MEDIUM,
                    "api key auth failed (bad secret)",
                    Map.of("prefix", prefix)));
            unauthorized(res);
            return;
        }

        TenantContextHolder.set(row.tenantId());
        try {
            // P1-5: 경로가 scope 를 요구하면 키 보유 scope 와 대조. 키는 유효하나
            // 권한 부족이면 403(401 과 구분). 매핑 없는 경로는 scope 검사 생략.
            // 검사는 TenantContextHolder 가 설정된 상태에서 수행 — 앱 레벨 @Filter 조회가
            // 해당 키의 테넌트를 본다. getServletPath() 는 shouldNotFilter 의 인증
            // 게이트와 동일 기준이라 context-path 우회 위험이 없다.
            Optional<String> required = scopeResolver.requiredScope(req.getServletPath());
            if (required.isPresent()) {
                // scope 쿼리 예외는 fail-closed(전파→접근 거부)가 의도 —
                // touchLastUsed 와 달리 swallow 금지. 바깥 finally 가 context clear 보장.
                Set<String> held = scopeRepo.findScopeValuesByApiKeyId(row.id());
                if (!held.contains(required.get())) {
                    log.warn("api-key scope denied: prefix={} required={} held={}",
                            prefix, required.get(), held);
                    cInsufficientScope.increment();
                    forbidden(res);
                    return; // 바깥 finally 가 TenantContextHolder.clear() 보장
                }
            }

            MDC.put(MDC_API_KEY_PREFIX, prefix);
            MDC.put(MDC_TENANT_ID, row.tenantId().toString());
            try {
                // 조회는 native SQL 로 명시 tenant_id 로 격리하므로
                // 인증된 row.tenantId() 를 그대로 넘긴다.
                lookup.touchLastUsed(row.id(), row.tenantId(), now);
                if (log.isDebugEnabled()) {
                    log.debug("api-key auth ok: prefix={} tenantId={}", prefix, row.tenantId());
                }
                cSuccess.increment();
                chain.doFilter(req, res);
            } finally {
                MDC.remove(MDC_API_KEY_PREFIX);
                MDC.remove(MDC_TENANT_ID);
            }
        } finally {
            TenantContextHolder.clear();
        }
    }

    /** Single generic 401 — no detail leaks. */
    private void unauthorized(HttpServletResponse res) throws IOException {
        ProblemJson.write(res, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
    }

    /** 403 — 키는 유효하나 요청 경로 scope 미보유. */
    private void forbidden(HttpServletResponse res) throws IOException {
        ProblemJson.write(res, HttpServletResponse.SC_FORBIDDEN, "Forbidden", "insufficient_scope");
    }
}
