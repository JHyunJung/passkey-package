package com.crosscert.passkey.app.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.crosscert.passkey.core.alert.SecurityAlertEvent;
import com.crosscert.passkey.core.repository.ApiKeyScopeRepository;
import com.crosscert.passkey.core.tenant.TenantContextHolder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Kotlin 전환 전 ApiKeyAuthFilter 의 외부 관찰 가능 동작(상태·바디 바이트·스킵 경로·
 * 타이밍 균등화용 더미 BCrypt 호출·메트릭·보안 이벤트·로그 문구·MDC·tenant context)을
 * 현재 Java 구현 기준으로 고정하는 특성 테스트. 기존 ApiKeyAuthFilterTest /
 * ApiKeyAuthFilterScopeTest 가 덮지 않는 분기만 다룬다.
 */
class ApiKeyAuthFilterCharacterizationTest {

    private static final String DUMMY_HASH =
            "$2a$12$KIXxA7B0NQI8YzCq0RZ.7eN5lUucl0wH0G3jWvSEqcD9QYxIqUyqK";
    private static final String UNAUTHORIZED_BODY =
            "{\"type\":\"about:blank\",\"status\":401,\"title\":\"Unauthorized\"}";
    private static final String FORBIDDEN_BODY =
            "{\"type\":\"about:blank\",\"status\":403,\"title\":\"Forbidden\",\"error\":\"insufficient_scope\"}";

    private static final Instant NOW = Instant.parse("2026-11-06T00:00:00Z");
    private static final UUID TENANT = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OTHER_TENANT = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID KEY_ID = UUID.fromString("00000000-0000-0000-0000-000000000042");
    private static final String PREFIX = "pk_abcd1234";
    private static final String SECRET = "TOPSECRETVALUE123";
    private static final String ROW_HASH = "ROW_HASH";
    private static final String REG_PATH = "/api/v1/rp/registration/start";

    private final ApiKeyLookupService lookup = mock(ApiKeyLookupService.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final ApiKeyScopeRepository scopeRepo = mock(ApiKeyScopeRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private ApiKeyAuthFilter filter;

    private Logger logger;
    private Level originalLevel;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void setUp() {
        filter = new ApiKeyAuthFilter(lookup, encoder, scopeRepo, new ApiKeyScopeResolver(),
                meters, events, Clock.fixed(NOW, ZoneOffset.UTC));
        logger = (Logger) LoggerFactory.getLogger(ApiKeyAuthFilter.class);
        originalLevel = logger.getLevel();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        logger.setLevel(originalLevel);
        TenantContextHolder.clear();
        MDC.clear();
    }

    private static MockHttpServletRequest req(String servletPath, String apiKey) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", servletPath);
        r.setServletPath(servletPath);
        if (apiKey != null) {
            r.addHeader("X-API-Key", apiKey);
        }
        return r;
    }

    private List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private double count(String result) {
        return meters.counter("passkey_apikey_auth_total", "result", result).count();
    }

    private void stubRow(Instant expiresAt, Instant revokedAt) {
        when(lookup.findByPrefix(PREFIX)).thenReturn(Optional.of(
                new ApiKeyLookupService.ApiKeyAuthRow(KEY_ID, TENANT, ROW_HASH, expiresAt, revokedAt)));
    }

    // ── registration / order ────────────────────────────────────────────────

    @Test
    void order_is_highest_precedence_plus_5_and_is_component() {
        Order order = AnnotationUtils.findAnnotation(ApiKeyAuthFilter.class, Order.class);
        assertThat(order).isNotNull();
        assertThat(order.value()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 5);
        assertThat(AnnotationUtils.findAnnotation(ApiKeyAuthFilter.class, Component.class)).isNotNull();
    }

    @Test
    void all_result_counters_are_registered_at_construction_with_zero() {
        for (String r : List.of("success", "unknown_prefix", "revoked", "expired",
                "bad_secret", "insufficient_scope")) {
            assertThat(meters.find("passkey_apikey_auth_total").tag("result", r).counter())
                    .as(r).isNotNull();
            assertThat(count(r)).as(r).isZero();
        }
        assertThat(meters.find("passkey_apikey_auth_total").counters()).hasSize(6);
    }

    // ── shouldNotFilter ─────────────────────────────────────────────────────

    @Test
    void non_rp_path_is_skipped_without_any_auth_work() throws Exception {
        MockHttpServletRequest r = req("/actuator/health", null);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(r, res, chain);

        verify(chain).doFilter(r, res);
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getContentAsString()).isEmpty();
        verifyNoInteractions(lookup, encoder, scopeRepo, events);
    }

    @Test
    void rp_base_without_trailing_slash_is_skipped() throws Exception {
        MockHttpServletRequest r = req("/api/v1/rp", null);
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(r, new MockHttpServletResponse(), chain);
        verify(chain).doFilter(any(), any());
    }

    @Test
    void skip_decision_uses_servlet_path_not_request_uri() throws Exception {
        // context-path 가 붙은 requestURI 라도 servletPath 가 /api/v1/rp/ 이면 인증 대상.
        MockHttpServletRequest gated = new MockHttpServletRequest("GET", "/passkey/api/v1/rp/x");
        gated.setRequestURI("/passkey/api/v1/rp/x");
        gated.setServletPath("/api/v1/rp/x");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(gated, res, chain);
        assertThat(res.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());

        // 반대로 requestURI 가 /api/v1/rp/ 여도 servletPath 가 아니면 스킵.
        MockHttpServletRequest skipped = new MockHttpServletRequest("GET", "/api/v1/rp/x");
        skipped.setRequestURI("/api/v1/rp/x");
        skipped.setServletPath("/other");
        FilterChain chain2 = mock(FilterChain.class);
        filter.doFilter(skipped, new MockHttpServletResponse(), chain2);
        verify(chain2).doFilter(any(), any());
    }

    @Test
    void null_servlet_path_is_skipped() throws ServletException {
        HttpServletRequest r = mock(HttpServletRequest.class);
        when(r.getServletPath()).thenReturn(null);
        assertThat(filter.shouldNotFilter(r)).isTrue();
    }

    // ── header shape ────────────────────────────────────────────────────────

    @Test
    void missing_header_writes_exact_401_problem_json() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req(REG_PATH, null), res, mock(FilterChain.class));

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentType()).isEqualTo("application/problem+json");
        assertThat(res.getContentAsString()).isEqualTo(UNAUTHORIZED_BODY);
        assertThat(res.getHeader("WWW-Authenticate")).isNull();
        verifyNoInteractions(lookup, encoder);
        assertThat(messages()).isEmpty();
    }

    @Test
    void header_of_exactly_prefix_length_is_rejected_before_lookup() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req(REG_PATH, "pk_abcd1234"), res, mock(FilterChain.class));

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).isEqualTo(UNAUTHORIZED_BODY);
        verifyNoInteractions(lookup, encoder);
    }

    @Test
    void header_one_char_past_prefix_splits_prefix_and_secret() throws Exception {
        when(lookup.findByPrefix(anyString())).thenReturn(Optional.empty());
        filter.doFilter(req(REG_PATH, "pk_abcd1234X"), new MockHttpServletResponse(),
                mock(FilterChain.class));

        verify(lookup).findByPrefix("pk_abcd1234");
        verify(encoder).matches("X", DUMMY_HASH);
    }

    @Test
    void stale_tenant_context_is_cleared_on_entry_even_on_401() throws Exception {
        TenantContextHolder.set(OTHER_TENANT);
        filter.doFilter(req(REG_PATH, null), new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(TenantContextHolder.get()).isNull();
    }

    // ── unknown prefix ──────────────────────────────────────────────────────

    @Test
    void unknown_prefix_runs_dummy_bcrypt_counts_and_logs_without_secret() throws Exception {
        when(lookup.findByPrefix(PREFIX)).thenReturn(Optional.empty());
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req(REG_PATH, PREFIX + SECRET), res, chain);

        verify(encoder).matches(SECRET, DUMMY_HASH);
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).isEqualTo(UNAUTHORIZED_BODY);
        assertThat(count("unknown_prefix")).isEqualTo(1.0);
        assertThat(messages()).containsExactly(
                "api-key auth failed: reason=unknown-prefix prefix=pk_abcd1234");
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(messages()).noneMatch(m -> m.contains(SECRET));
        verifyNoInteractions(events, scopeRepo);
        verify(chain, never()).doFilter(any(), any());
    }

    // ── revoked / expired ───────────────────────────────────────────────────

    @Test
    void revoked_runs_dummy_bcrypt_not_row_hash() throws Exception {
        stubRow(null, NOW.minusSeconds(1));
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req(REG_PATH, PREFIX + SECRET), res, mock(FilterChain.class));

        verify(encoder).matches(SECRET, DUMMY_HASH);
        verify(encoder, never()).matches(SECRET, ROW_HASH);
        assertThat(res.getContentAsString()).isEqualTo(UNAUTHORIZED_BODY);
        assertThat(count("revoked")).isEqualTo(1.0);
        assertThat(count("expired")).isZero();
        assertThat(messages()).containsExactly("api-key auth failed: reason=revoked prefix=pk_abcd1234");
        verifyNoInteractions(events, scopeRepo);
        verify(lookup, never()).touchLastUsed(any(), any(), any());
    }

    @Test
    void revoked_and_expired_reports_revoked() throws Exception {
        stubRow(NOW.minusSeconds(10), NOW.minusSeconds(1));
        filter.doFilter(req(REG_PATH, PREFIX + SECRET), new MockHttpServletResponse(),
                mock(FilterChain.class));
        assertThat(count("revoked")).isEqualTo(1.0);
        assertThat(count("expired")).isZero();
        assertThat(messages()).containsExactly("api-key auth failed: reason=revoked prefix=pk_abcd1234");
    }

    @Test
    void expires_exactly_now_is_expired() throws Exception {
        stubRow(NOW, null);
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req(REG_PATH, PREFIX + SECRET), res, mock(FilterChain.class));

        verify(encoder).matches(SECRET, DUMMY_HASH);
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(count("expired")).isEqualTo(1.0);
        assertThat(messages()).containsExactly("api-key auth failed: reason=expired prefix=pk_abcd1234");
    }

    @Test
    void expires_in_future_is_active() throws Exception {
        stubRow(NOW.plusMillis(1), null);
        when(encoder.matches(SECRET, ROW_HASH)).thenReturn(true);
        when(scopeRepo.findScopeValuesByApiKeyId(KEY_ID)).thenReturn(Set.of("registration"));
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req(REG_PATH, PREFIX + SECRET), new MockHttpServletResponse(), chain);

        verify(chain).doFilter(any(), any());
        assertThat(count("success")).isEqualTo(1.0);
    }

    @Test
    void row_is_active_boundaries() {
        assertThat(new ApiKeyLookupService.ApiKeyAuthRow(KEY_ID, TENANT, ROW_HASH, null, null)
                .isActive(NOW)).isTrue();
        assertThat(new ApiKeyLookupService.ApiKeyAuthRow(KEY_ID, TENANT, ROW_HASH, NOW, null)
                .isActive(NOW)).isFalse();
        assertThat(new ApiKeyLookupService.ApiKeyAuthRow(KEY_ID, TENANT, ROW_HASH, NOW.plusNanos(1), null)
                .isActive(NOW)).isTrue();
        assertThat(new ApiKeyLookupService.ApiKeyAuthRow(KEY_ID, TENANT, ROW_HASH, null, NOW.plusSeconds(60))
                .isActive(NOW)).as("any non-null revokedAt (even future) is inactive").isFalse();
    }

    // ── bad secret ──────────────────────────────────────────────────────────

    @Test
    void bad_secret_publishes_brute_force_alert_with_prefix_only() throws Exception {
        stubRow(null, null);
        when(encoder.matches(SECRET, ROW_HASH)).thenReturn(false);
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req(REG_PATH, PREFIX + SECRET), res, mock(FilterChain.class));

        verify(encoder).matches(SECRET, ROW_HASH);
        verify(encoder, never()).matches(SECRET, DUMMY_HASH);
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).isEqualTo(UNAUTHORIZED_BODY);
        assertThat(count("bad_secret")).isEqualTo(1.0);
        assertThat(messages()).containsExactly("api-key auth failed: reason=bad-secret prefix=pk_abcd1234");

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new SecurityAlertEvent(
                SecurityAlertEvent.AlertType.API_KEY_BRUTE_FORCE,
                SecurityAlertEvent.Severity.MEDIUM,
                "api key auth failed (bad secret)",
                Map.of("prefix", PREFIX)));
        verifyNoInteractions(scopeRepo);
        verify(lookup, never()).touchLastUsed(any(), any(), any());
        assertThat(TenantContextHolder.get()).isNull();
    }

    // ── scope denied ────────────────────────────────────────────────────────

    @Test
    void scope_denied_writes_exact_403_logs_held_and_skips_touch() throws Exception {
        stubRow(null, null);
        when(encoder.matches(SECRET, ROW_HASH)).thenReturn(true);
        when(scopeRepo.findScopeValuesByApiKeyId(KEY_ID)).thenReturn(Set.of("authentication"));
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req(REG_PATH, PREFIX + SECRET), res, mock(FilterChain.class));

        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentType()).isEqualTo("application/problem+json");
        assertThat(res.getContentAsString()).isEqualTo(FORBIDDEN_BODY);
        assertThat(messages()).containsExactly(
                "api-key scope denied: prefix=pk_abcd1234 required=registration held=[authentication]");
        verify(lookup, never()).touchLastUsed(any(), any(), any());
        assertThat(count("success")).isZero();
        assertThat(MDC.get("apiKeyPrefix")).isNull();
        assertThat(TenantContextHolder.get()).isNull();
    }

    @Test
    void scope_query_sees_authenticated_tenant_context() throws Exception {
        stubRow(null, null);
        when(encoder.matches(SECRET, ROW_HASH)).thenReturn(true);
        UUID[] seen = new UUID[1];
        when(scopeRepo.findScopeValuesByApiKeyId(KEY_ID)).thenAnswer(inv -> {
            seen[0] = TenantContextHolder.get();
            return Set.of("registration");
        });

        filter.doFilter(req(REG_PATH, PREFIX + SECRET), new MockHttpServletResponse(),
                mock(FilterChain.class));

        assertThat(seen[0]).isEqualTo(TENANT);
    }

    // ── success ─────────────────────────────────────────────────────────────

    @Test
    void success_sets_mdc_during_chain_touches_with_clock_now_and_cleans_up() throws Exception {
        logger.setLevel(Level.DEBUG);
        stubRow(null, null);
        when(encoder.matches(SECRET, ROW_HASH)).thenReturn(true);
        when(scopeRepo.findScopeValuesByApiKeyId(KEY_ID)).thenReturn(Set.of("registration"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        String[] mdcSeen = new String[2];
        UUID[] tenantSeen = new UUID[1];
        FilterChain chain = (rq, rs) -> {
            mdcSeen[0] = MDC.get("apiKeyPrefix");
            mdcSeen[1] = MDC.get("tenantId");
            tenantSeen[0] = TenantContextHolder.get();
        };

        filter.doFilter(req(REG_PATH, PREFIX + SECRET), res, chain);

        assertThat(mdcSeen).containsExactly(PREFIX, TENANT.toString());
        assertThat(tenantSeen[0]).isEqualTo(TENANT);
        verify(lookup).touchLastUsed(KEY_ID, TENANT, NOW);
        assertThat(count("success")).isEqualTo(1.0);
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getContentAsString()).isEmpty();
        assertThat(messages()).containsExactly(
                "api-key auth ok: prefix=pk_abcd1234 tenantId=aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.DEBUG);
        assertThat(MDC.get("apiKeyPrefix")).isNull();
        assertThat(MDC.get("tenantId")).isNull();
        assertThat(TenantContextHolder.get()).isNull();
        verifyNoInteractions(events);
    }

    @Test
    void success_emits_no_log_when_debug_disabled() throws Exception {
        logger.setLevel(Level.INFO);
        stubRow(null, null);
        when(encoder.matches(SECRET, ROW_HASH)).thenReturn(true);
        when(scopeRepo.findScopeValuesByApiKeyId(KEY_ID)).thenReturn(Set.of("registration"));

        filter.doFilter(req(REG_PATH, PREFIX + SECRET), new MockHttpServletResponse(),
                mock(FilterChain.class));

        assertThat(messages()).isEmpty();
    }

    @Test
    void chain_exception_propagates_and_mdc_and_tenant_are_cleared() {
        stubRow(null, null);
        when(encoder.matches(SECRET, ROW_HASH)).thenReturn(true);
        when(scopeRepo.findScopeValuesByApiKeyId(KEY_ID)).thenReturn(Set.of("registration"));
        FilterChain chain = (rq, rs) -> { throw new ServletException("boom"); };

        assertThatThrownBy(() -> filter.doFilter(req(REG_PATH, PREFIX + SECRET),
                new MockHttpServletResponse(), chain))
                .isInstanceOf(ServletException.class).hasMessage("boom");

        assertThat(MDC.get("apiKeyPrefix")).isNull();
        assertThat(MDC.get("tenantId")).isNull();
        assertThat(TenantContextHolder.get()).isNull();
        assertThat(count("success")).isEqualTo(1.0);
    }

    // ── lookup failure (DB down) ────────────────────────────────────────────

    @Test
    void lookup_exception_propagates_fail_closed_without_chain() {
        RuntimeException db = new IllegalStateException("db down");
        when(lookup.findByPrefix(PREFIX)).thenThrow(db);
        FilterChain chain = mock(FilterChain.class);

        assertThatThrownBy(() -> filter.doFilter(req(REG_PATH, PREFIX + SECRET),
                new MockHttpServletResponse(), chain))
                .isSameAs(db);

        verifyNoInteractions(chain, encoder);
        assertThat(TenantContextHolder.get()).isNull();
    }
}
