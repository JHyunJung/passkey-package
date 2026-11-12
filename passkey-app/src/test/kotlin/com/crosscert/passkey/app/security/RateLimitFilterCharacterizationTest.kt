package com.crosscert.passkey.app.security

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.github.bucket4j.BucketConfiguration
import io.github.bucket4j.distributed.BucketProxy
import io.github.bucket4j.distributed.proxy.ProxyManager
import io.github.bucket4j.distributed.proxy.RemoteBucketBuilder
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.AnnotationUtils
import org.springframework.core.annotation.Order
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.test.util.ReflectionTestUtils
import java.time.Duration
import java.util.function.Supplier

private const val TOO_MANY_BODY = """{"type":"about:blank","status":429,"title":"Too Many Requests"}"""

private fun bucket(allows: Boolean): BucketProxy = mock<BucketProxy>().also {
    whenever(it.tryConsume(1L)).thenReturn(allows)
}

private fun req(method: String, servletPath: String, apiKey: String?) =
    MockHttpServletRequest(method, servletPath).apply {
        this.servletPath = servletPath
        remoteAddr = "10.0.0.1"
        apiKey?.let { addHeader("X-API-Key", it) }
    }

private fun assertTokenBucket(c: BucketConfiguration, capacity: Long) {
    assertThat(c.bandwidths).hasSize(1)
    val b = c.bandwidths[0]
    assertThat(b.capacity).isEqualTo(capacity)
    assertThat(b.refillTokens).isEqualTo(capacity)
    assertThat(b.refillPeriodNanos).isEqualTo(Duration.ofMinutes(1).toNanos())
    assertThat(b.isRefillIntervally).`as`("greedy refill").isFalse()
    assertThat(b.initialTokens).isEqualTo(capacity)
}

/**
 * Kotlin 전환 전 RateLimitFilter 의 외부 관찰 가능 동작(티어 해석·버킷 키·버킷 설정값·
 * 429 바이트/헤더·Redis 장애 시 예외 전파·로그 문구·필터 순서)을 현재 Java 구현 기준으로
 * 고정하는 특성 테스트. 기존 RateLimitFilterTest 가 덮지 않는 분기만 다룬다.
 */
class RateLimitFilterCharacterizationTest {

    private val proxy: ProxyManager<String> = mock()
    private val builder: RemoteBucketBuilder<String> = mock()
    private val filter = RateLimitFilter(proxy)

    /** bucket key → stubbed bucket; unknown keys get an always-allow bucket. */
    private val bucketsByKey = linkedMapOf<String, BucketProxy>()

    /** every (key, config) the filter requested, in order. */
    private val builtKeys = mutableListOf<String>()
    private val configsByKey = linkedMapOf<String, BucketConfiguration>()

    private val logger = LoggerFactory.getLogger(RateLimitFilter::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun setUp() {
        whenever(proxy.builder()).thenReturn(builder)
        whenever(builder.build(any<String>(), any<Supplier<BucketConfiguration>>())).thenAnswer { inv ->
            val key = inv.getArgument(0, String::class.java)
            val s: Supplier<BucketConfiguration> = inv.getArgument(1)
            builtKeys += key
            configsByKey[key] = s.get()
            bucketsByKey.getOrPut(key) { bucket(true) }
        }
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(appender)
    }

    private fun messages(): List<String> = appender.list.map { it.formattedMessage }

    @Test
    fun order_is_highest_precedence_and_is_component() {
        val order = AnnotationUtils.findAnnotation(RateLimitFilter::class.java, Order::class.java)
        assertThat(order).isNotNull()
        assertThat(order?.value).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 1) // SecurityHeadersFilter(HIGHEST) 다음 — 429 에도 보안 헤더(2026-11-12)
        assertThat(AnnotationUtils.findAnnotation(RateLimitFilter::class.java, Component::class.java)).isNotNull()
    }

    // ── tier resolution, bucket keys, configuration values ──────────────────

    @Test
    fun ceremony_endpoints_keys_and_limits() {
        val expected = mapOf(
            "/api/v1/rp/registration/start" to 60L,
            "/api/v1/rp/registration/finish" to 60L,
            "/api/v1/rp/authentication/start" to 300L,
            "/api/v1/rp/authentication/finish" to 300L,
        )
        for ((path, capacity) in expected) {
            builtKeys.clear()
            configsByKey.clear()
            val chain: FilterChain = mock()
            // exact-match tiers ignore HTTP method.
            filter.doFilter(req("GET", path, "pk_abcd1234SECRET"), MockHttpServletResponse(), chain)

            val ipKey = "ratelimit:$path:ip:10.0.0.1"
            val keyKey = "ratelimit:$path:key:pk_abcd1234"
            assertThat(builtKeys).containsExactly(ipKey, keyKey)
            assertTokenBucket(configsByKey.getValue(ipKey), capacity)
            assertTokenBucket(configsByKey.getValue(keyKey), capacity)
            verify(chain).doFilter(anyOrNull(), anyOrNull())
        }
    }

    @Test
    fun credentials_delete_tier_is_case_insensitive_and_capped_at_20() {
        filter.doFilter(
            req("delete", "/api/v1/rp/credentials/AAEC", "pk_abcd1234SECRET"),
            MockHttpServletResponse(), mock(),
        )

        assertThat(builtKeys).containsExactly(
            "ratelimit:/api/v1/rp/credentials:DELETE:ip:10.0.0.1",
            "ratelimit:/api/v1/rp/credentials:DELETE:key:pk_abcd1234",
        )
        assertTokenBucket(configsByKey.getValue(builtKeys[0]), 20)
    }

    @Test
    fun credentials_non_delete_under_prefix_uses_default_tier_60() {
        filter.doFilter(
            req("POST", "/api/v1/rp/credentials/AAEC/label", "pk_abcd1234SECRET"),
            MockHttpServletResponse(), mock(),
        )

        assertThat(builtKeys).containsExactly(
            "ratelimit:/api/v1/rp/credentials:ip:10.0.0.1",
            "ratelimit:/api/v1/rp/credentials:key:pk_abcd1234",
        )
        assertTokenBucket(configsByKey.getValue(builtKeys[0]), 60)
    }

    @Test
    fun unlimited_paths_are_skipped_without_touching_redis() {
        for (path in listOf(
            "/api/v1/rp/credentialsX", "/api/v1/rp/x",
            "/api/v1/rp/registration", "/api/v1/rp/registration/start/extra", "",
        )) {
            val chain: FilterChain = mock()
            val res = MockHttpServletResponse()
            filter.doFilter(req("POST", path, "pk_abcd1234SECRET"), res, chain)
            verify(chain).doFilter(anyOrNull(), anyOrNull())
            assertThat(res.getHeader("Retry-After")).`as`(path).isNull()
        }
        verify(proxy, never()).builder()
    }

    @Test
    fun null_servlet_path_is_skipped() {
        val r: HttpServletRequest = mock()
        whenever(r.servletPath).thenReturn(null)
        whenever(r.method).thenReturn("POST")
        // shouldNotFilter 는 protected — Java 테스트의 같은 패키지 접근을 ReflectionTestUtils 로 대체(같은 메서드 직접 호출).
        assertThat(ReflectionTestUtils.invokeMethod<Boolean>(filter, "shouldNotFilter", r)).isTrue()
    }

    @Test
    fun tier_uses_servlet_path_not_request_uri() {
        val r = req("POST", "/api/v1/rp/registration/start", null).apply {
            requestURI = "/passkey/api/v1/rp/registration/start"
        }
        filter.doFilter(r, MockHttpServletResponse(), mock())
        assertThat(builtKeys[0]).isEqualTo("ratelimit:/api/v1/rp/registration/start:ip:10.0.0.1")
    }

    // ── key-bucket selection by header shape ────────────────────────────────

    @Test
    fun header_of_exactly_prefix_length_gets_own_key_bucket() {
        filter.doFilter(
            req("POST", "/api/v1/rp/registration/start", "pk_abcd1234"),
            MockHttpServletResponse(), mock(),
        )
        assertThat(builtKeys[1]).isEqualTo("ratelimit:/api/v1/rp/registration/start:key:pk_abcd1234")
    }

    @Test
    fun short_or_missing_header_uses_anon_ip_bucket() {
        filter.doFilter(
            req("POST", "/api/v1/rp/registration/start", "pk_abcd123"),
            MockHttpServletResponse(), mock(),
        )
        filter.doFilter(
            req("POST", "/api/v1/rp/registration/start", null),
            MockHttpServletResponse(), mock(),
        )
        assertThat(builtKeys).containsExactly(
            "ratelimit:/api/v1/rp/registration/start:ip:10.0.0.1",
            "ratelimit:/api/v1/rp/registration/start:key:anon-10.0.0.1",
            "ratelimit:/api/v1/rp/registration/start:ip:10.0.0.1",
            "ratelimit:/api/v1/rp/registration/start:key:anon-10.0.0.1",
        )
    }

    // ── 429 responses and logs ──────────────────────────────────────────────

    @Test
    fun ip_rejection_writes_exact_429_never_builds_key_bucket_and_logs() {
        bucketsByKey["ratelimit:/api/v1/rp/registration/start:ip:10.0.0.1"] = bucket(false)
        val res = MockHttpServletResponse()
        val chain: FilterChain = mock()

        filter.doFilter(req("POST", "/api/v1/rp/registration/start", "pk_abcd1234SECRET"), res, chain)

        assertThat(res.status).isEqualTo(429)
        assertThat(res.getHeader("Retry-After")).isEqualTo("60")
        assertThat(res.headerNames).containsExactlyInAnyOrder("Retry-After", "Content-Type")
        assertThat(res.contentType).isEqualTo("application/problem+json")
        assertThat(res.contentAsString).isEqualTo(TOO_MANY_BODY)
        assertThat(builtKeys).containsExactly("ratelimit:/api/v1/rp/registration/start:ip:10.0.0.1")
        verifyNoInteractions(chain)
        assertThat(messages()).hasSize(1)
        assertThat(messages()[0]).matches(
            "rate limit exceeded: scope=ip path=/api/v1/rp/registration/start remoteAddr=10\\.0\\.0\\.1 durMs=\\d+",
        )
        assertThat(appender.list[0].level).isEqualTo(Level.WARN)
    }

    @Test
    fun key_rejection_writes_exact_429_and_logs_prefix_without_secret() {
        bucketsByKey["ratelimit:/api/v1/rp/credentials:DELETE:key:pk_abcd1234"] = bucket(false)
        val res = MockHttpServletResponse()

        filter.doFilter(req("DELETE", "/api/v1/rp/credentials/AAEC", "pk_abcd1234SECRET"), res, mock())

        assertThat(res.status).isEqualTo(429)
        assertThat(res.getHeader("Retry-After")).isEqualTo("60")
        assertThat(res.contentAsString).isEqualTo(TOO_MANY_BODY)
        assertThat(messages()).hasSize(1)
        assertThat(messages()[0]).matches(
            "rate limit exceeded: scope=key path=/api/v1/rp/credentials:DELETE prefix=pk_abcd1234 durMs=\\d+",
        )
        assertThat(messages()[0]).doesNotContain("SECRET")
    }

    @Test
    fun anon_key_rejection_logs_anon_key_part() {
        bucketsByKey["ratelimit:/api/v1/rp/authentication/start:key:anon-10.0.0.1"] = bucket(false)
        filter.doFilter(req("POST", "/api/v1/rp/authentication/start", null), MockHttpServletResponse(), mock())
        assertThat(messages()[0]).matches(
            "rate limit exceeded: scope=key path=/api/v1/rp/authentication/start prefix=anon-10\\.0\\.0\\.1 durMs=\\d+",
        )
    }

    @Test
    fun success_emits_no_log_and_no_headers() {
        val res = MockHttpServletResponse()
        filter.doFilter(req("POST", "/api/v1/rp/registration/start", "pk_abcd1234SECRET"), res, mock())
        assertThat(messages()).isEmpty()
        assertThat(res.headerNames).isEmpty()
    }

    // ── Redis failure: exception propagates (no fail-open) ──────────────────

    @Test
    fun redis_failure_on_consume_propagates_and_blocks_chain() {
        val broken: BucketProxy = mock()
        val redisDown: RuntimeException = IllegalStateException("redis down")
        whenever(broken.tryConsume(1L)).thenThrow(redisDown)
        bucketsByKey["ratelimit:/api/v1/rp/registration/start:ip:10.0.0.1"] = broken
        val chain: FilterChain = mock()

        assertThatThrownBy {
            filter.doFilter(
                req("POST", "/api/v1/rp/registration/start", "pk_abcd1234SECRET"),
                MockHttpServletResponse(), chain,
            )
        }.isSameAs(redisDown)
        verifyNoInteractions(chain)
    }

    @Test
    fun redis_failure_on_build_propagates_and_blocks_chain() {
        val redisDown: RuntimeException = IllegalStateException("redis down")
        whenever(builder.build(any<String>(), any<Supplier<BucketConfiguration>>())).thenThrow(redisDown)
        val chain: FilterChain = mock()

        assertThatThrownBy {
            filter.doFilter(
                req("POST", "/api/v1/rp/registration/start", "pk_abcd1234SECRET"),
                MockHttpServletResponse(), chain,
            )
        }.isSameAs(redisDown)
        verifyNoInteractions(chain)
    }

    @Test
    fun missing_tier_in_do_filter_internal_throws_npe_like_original() {
        // shouldNotFilter 가 막는 경로라 운영상 도달 불가 — 원 Java 의 NPE 예외 흐름을 고정한다.
        // doFilterInternal 은 protected — ReflectionTestUtils 로 같은 메서드를 직접 호출(대상 예외는 언랩돼 전파).
        val r = req("POST", "/not/limited", null)
        assertThatThrownBy {
            ReflectionTestUtils.invokeMethod<Unit>(filter, "doFilterInternal", r, MockHttpServletResponse(), mock<FilterChain>())
        }.isExactlyInstanceOf(NullPointerException::class.java)
        verify(proxy, never()).builder()
    }
}
