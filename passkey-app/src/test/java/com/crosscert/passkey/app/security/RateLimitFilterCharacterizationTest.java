package com.crosscert.passkey.app.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.distributed.proxy.RemoteBucketBuilder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

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
 * Kotlin 전환 전 RateLimitFilter 의 외부 관찰 가능 동작(티어 해석·버킷 키·버킷 설정값·
 * 429 바이트/헤더·Redis 장애 시 예외 전파·로그 문구·필터 순서)을 현재 Java 구현 기준으로
 * 고정하는 특성 테스트. 기존 RateLimitFilterTest 가 덮지 않는 분기만 다룬다.
 */
class RateLimitFilterCharacterizationTest {

    private static final String TOO_MANY_BODY =
            "{\"type\":\"about:blank\",\"status\":429,\"title\":\"Too Many Requests\"}";

    @SuppressWarnings("unchecked")
    private final ProxyManager<String> proxy = mock(ProxyManager.class);
    @SuppressWarnings("unchecked")
    private final RemoteBucketBuilder<String> builder = mock(RemoteBucketBuilder.class);
    private RateLimitFilter filter;

    /** bucket key → stubbed bucket; unknown keys get an always-allow bucket. */
    private final Map<String, BucketProxy> bucketsByKey = new LinkedHashMap<>();
    /** every (key, config) the filter requested, in order. */
    private final List<String> builtKeys = new ArrayList<>();
    private final Map<String, BucketConfiguration> configsByKey = new LinkedHashMap<>();

    private Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        filter = new RateLimitFilter(proxy);
        when(proxy.builder()).thenReturn(builder);
        when(builder.build(anyString(), any(Supplier.class))).thenAnswer(inv -> {
            String key = inv.getArgument(0, String.class);
            Supplier<BucketConfiguration> s = inv.getArgument(1);
            builtKeys.add(key);
            configsByKey.put(key, s.get());
            return bucketsByKey.computeIfAbsent(key, k -> bucket(true));
        });
        logger = (Logger) LoggerFactory.getLogger(RateLimitFilter.class);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    private static BucketProxy bucket(boolean allows) {
        BucketProxy b = mock(BucketProxy.class);
        when(b.tryConsume(1L)).thenReturn(allows);
        return b;
    }

    private static MockHttpServletRequest req(String method, String servletPath, String apiKey) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, servletPath);
        r.setServletPath(servletPath);
        r.setRemoteAddr("10.0.0.1");
        if (apiKey != null) {
            r.addHeader("X-API-Key", apiKey);
        }
        return r;
    }

    private static void assertTokenBucket(BucketConfiguration c, long capacity) {
        assertThat(c.getBandwidths()).hasSize(1);
        Bandwidth b = c.getBandwidths()[0];
        assertThat(b.getCapacity()).isEqualTo(capacity);
        assertThat(b.getRefillTokens()).isEqualTo(capacity);
        assertThat(b.getRefillPeriodNanos()).isEqualTo(Duration.ofMinutes(1).toNanos());
        assertThat(b.isRefillIntervally()).as("greedy refill").isFalse();
        assertThat(b.getInitialTokens()).isEqualTo(capacity);
    }

    private List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void order_is_highest_precedence_and_is_component() {
        Order order = AnnotationUtils.findAnnotation(RateLimitFilter.class, Order.class);
        assertThat(order).isNotNull();
        assertThat(order.value()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        assertThat(AnnotationUtils.findAnnotation(RateLimitFilter.class, Component.class)).isNotNull();
    }

    // ── tier resolution, bucket keys, configuration values ──────────────────

    @Test
    void ceremony_endpoints_keys_and_limits() throws Exception {
        Map<String, Long> expected = Map.of(
                "/api/v1/rp/registration/start", 60L,
                "/api/v1/rp/registration/finish", 60L,
                "/api/v1/rp/authentication/start", 300L,
                "/api/v1/rp/authentication/finish", 300L);
        for (Map.Entry<String, Long> e : expected.entrySet()) {
            builtKeys.clear();
            configsByKey.clear();
            FilterChain chain = mock(FilterChain.class);
            // exact-match tiers ignore HTTP method.
            filter.doFilter(req("GET", e.getKey(), "pk_abcd1234SECRET"),
                    new MockHttpServletResponse(), chain);

            String ipKey = "ratelimit:" + e.getKey() + ":ip:10.0.0.1";
            String keyKey = "ratelimit:" + e.getKey() + ":key:pk_abcd1234";
            assertThat(builtKeys).containsExactly(ipKey, keyKey);
            assertTokenBucket(configsByKey.get(ipKey), e.getValue());
            assertTokenBucket(configsByKey.get(keyKey), e.getValue());
            verify(chain).doFilter(any(), any());
        }
    }

    @Test
    void credentials_delete_tier_is_case_insensitive_and_capped_at_20() throws Exception {
        filter.doFilter(req("delete", "/api/v1/rp/credentials/AAEC", "pk_abcd1234SECRET"),
                new MockHttpServletResponse(), mock(FilterChain.class));

        assertThat(builtKeys).containsExactly(
                "ratelimit:/api/v1/rp/credentials:DELETE:ip:10.0.0.1",
                "ratelimit:/api/v1/rp/credentials:DELETE:key:pk_abcd1234");
        assertTokenBucket(configsByKey.get(builtKeys.get(0)), 20);
    }

    @Test
    void credentials_non_delete_under_prefix_uses_default_tier_60() throws Exception {
        filter.doFilter(req("POST", "/api/v1/rp/credentials/AAEC/label", "pk_abcd1234SECRET"),
                new MockHttpServletResponse(), mock(FilterChain.class));

        assertThat(builtKeys).containsExactly(
                "ratelimit:/api/v1/rp/credentials:ip:10.0.0.1",
                "ratelimit:/api/v1/rp/credentials:key:pk_abcd1234");
        assertTokenBucket(configsByKey.get(builtKeys.get(0)), 60);
    }

    @Test
    void unlimited_paths_are_skipped_without_touching_redis() throws Exception {
        for (String path : List.of("/api/v1/rp/credentialsX", "/api/v1/rp/x",
                "/api/v1/rp/registration", "/api/v1/rp/registration/start/extra", "")) {
            FilterChain chain = mock(FilterChain.class);
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req("POST", path, "pk_abcd1234SECRET"), res, chain);
            verify(chain).doFilter(any(), any());
            assertThat(res.getHeader("Retry-After")).as(path).isNull();
        }
        verify(proxy, never()).builder();
    }

    @Test
    void null_servlet_path_is_skipped() throws ServletException {
        HttpServletRequest r = mock(HttpServletRequest.class);
        when(r.getServletPath()).thenReturn(null);
        when(r.getMethod()).thenReturn("POST");
        assertThat(filter.shouldNotFilter(r)).isTrue();
    }

    @Test
    void tier_uses_servlet_path_not_request_uri() throws Exception {
        MockHttpServletRequest r = req("POST", "/api/v1/rp/registration/start", null);
        r.setRequestURI("/passkey/api/v1/rp/registration/start");
        filter.doFilter(r, new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(builtKeys.get(0)).isEqualTo("ratelimit:/api/v1/rp/registration/start:ip:10.0.0.1");
    }

    // ── key-bucket selection by header shape ────────────────────────────────

    @Test
    void header_of_exactly_prefix_length_gets_own_key_bucket() throws Exception {
        filter.doFilter(req("POST", "/api/v1/rp/registration/start", "pk_abcd1234"),
                new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(builtKeys.get(1)).isEqualTo("ratelimit:/api/v1/rp/registration/start:key:pk_abcd1234");
    }

    @Test
    void short_or_missing_header_uses_anon_ip_bucket() throws Exception {
        filter.doFilter(req("POST", "/api/v1/rp/registration/start", "pk_abcd123"),
                new MockHttpServletResponse(), mock(FilterChain.class));
        filter.doFilter(req("POST", "/api/v1/rp/registration/start", null),
                new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(builtKeys).containsExactly(
                "ratelimit:/api/v1/rp/registration/start:ip:10.0.0.1",
                "ratelimit:/api/v1/rp/registration/start:key:anon-10.0.0.1",
                "ratelimit:/api/v1/rp/registration/start:ip:10.0.0.1",
                "ratelimit:/api/v1/rp/registration/start:key:anon-10.0.0.1");
    }

    // ── 429 responses and logs ──────────────────────────────────────────────

    @Test
    void ip_rejection_writes_exact_429_never_builds_key_bucket_and_logs() throws Exception {
        bucketsByKey.put("ratelimit:/api/v1/rp/registration/start:ip:10.0.0.1", bucket(false));
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req("POST", "/api/v1/rp/registration/start", "pk_abcd1234SECRET"), res, chain);

        assertThat(res.getStatus()).isEqualTo(429);
        assertThat(res.getHeader("Retry-After")).isEqualTo("60");
        assertThat(res.getHeaderNames()).containsExactlyInAnyOrder("Retry-After", "Content-Type");
        assertThat(res.getContentType()).isEqualTo("application/problem+json");
        assertThat(res.getContentAsString()).isEqualTo(TOO_MANY_BODY);
        assertThat(builtKeys).containsExactly("ratelimit:/api/v1/rp/registration/start:ip:10.0.0.1");
        verifyNoInteractions(chain);
        assertThat(messages()).hasSize(1);
        assertThat(messages().get(0)).matches(
                "rate limit exceeded: scope=ip path=/api/v1/rp/registration/start remoteAddr=10\\.0\\.0\\.1 durMs=\\d+");
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
    }

    @Test
    void key_rejection_writes_exact_429_and_logs_prefix_without_secret() throws Exception {
        bucketsByKey.put("ratelimit:/api/v1/rp/credentials:DELETE:key:pk_abcd1234", bucket(false));
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req("DELETE", "/api/v1/rp/credentials/AAEC", "pk_abcd1234SECRET"), res,
                mock(FilterChain.class));

        assertThat(res.getStatus()).isEqualTo(429);
        assertThat(res.getHeader("Retry-After")).isEqualTo("60");
        assertThat(res.getContentAsString()).isEqualTo(TOO_MANY_BODY);
        assertThat(messages()).hasSize(1);
        assertThat(messages().get(0)).matches(
                "rate limit exceeded: scope=key path=/api/v1/rp/credentials:DELETE prefix=pk_abcd1234 durMs=\\d+");
        assertThat(messages().get(0)).doesNotContain("SECRET");
    }

    @Test
    void anon_key_rejection_logs_anon_key_part() throws Exception {
        bucketsByKey.put("ratelimit:/api/v1/rp/authentication/start:key:anon-10.0.0.1", bucket(false));
        filter.doFilter(req("POST", "/api/v1/rp/authentication/start", null),
                new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(messages().get(0)).matches(
                "rate limit exceeded: scope=key path=/api/v1/rp/authentication/start prefix=anon-10\\.0\\.0\\.1 durMs=\\d+");
    }

    @Test
    void success_emits_no_log_and_no_headers() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req("POST", "/api/v1/rp/registration/start", "pk_abcd1234SECRET"), res,
                mock(FilterChain.class));
        assertThat(messages()).isEmpty();
        assertThat(res.getHeaderNames()).isEmpty();
    }

    // ── Redis failure: exception propagates (no fail-open) ──────────────────

    @Test
    void redis_failure_on_consume_propagates_and_blocks_chain() {
        BucketProxy broken = mock(BucketProxy.class);
        RuntimeException redisDown = new IllegalStateException("redis down");
        when(broken.tryConsume(1L)).thenThrow(redisDown);
        bucketsByKey.put("ratelimit:/api/v1/rp/registration/start:ip:10.0.0.1", broken);
        FilterChain chain = mock(FilterChain.class);

        assertThatThrownBy(() -> filter.doFilter(
                req("POST", "/api/v1/rp/registration/start", "pk_abcd1234SECRET"),
                new MockHttpServletResponse(), chain))
                .isSameAs(redisDown);
        verifyNoInteractions(chain);
    }

    @Test
    @SuppressWarnings("unchecked")
    void redis_failure_on_build_propagates_and_blocks_chain() {
        RuntimeException redisDown = new IllegalStateException("redis down");
        when(builder.build(anyString(), any(Supplier.class))).thenThrow(redisDown);
        FilterChain chain = mock(FilterChain.class);

        assertThatThrownBy(() -> filter.doFilter(
                req("POST", "/api/v1/rp/registration/start", "pk_abcd1234SECRET"),
                new MockHttpServletResponse(), chain))
                .isSameAs(redisDown);
        verifyNoInteractions(chain);
    }

    @Test
    void missing_tier_in_do_filter_internal_throws_npe_like_original() {
        // shouldNotFilter 가 막는 경로라 운영상 도달 불가 — 원 Java 의 NPE 예외 흐름을 고정한다.
        MockHttpServletRequest r = req("POST", "/not/limited", null);
        assertThatThrownBy(() -> filter.doFilterInternal(r, new MockHttpServletResponse(),
                mock(FilterChain.class)))
                .isExactlyInstanceOf(NullPointerException.class);
        verify(proxy, never()).builder();
    }
}
