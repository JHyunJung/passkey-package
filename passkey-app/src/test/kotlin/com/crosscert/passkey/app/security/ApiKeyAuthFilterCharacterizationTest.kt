package com.crosscert.passkey.app.security

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.crosscert.passkey.core.alert.SecurityAlertEvent
import com.crosscert.passkey.core.repository.ApiKeyScopeRepository
import com.crosscert.passkey.core.tenant.TenantContextHolder
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServletRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.context.ApplicationEventPublisher
import org.springframework.core.Ordered
import org.springframework.core.annotation.AnnotationUtils
import org.springframework.core.annotation.Order
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.test.util.ReflectionTestUtils
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

private const val DUMMY_HASH = "\$2a\$12\$KIXxA7B0NQI8YzCq0RZ.7eN5lUucl0wH0G3jWvSEqcD9QYxIqUyqK"
private const val UNAUTHORIZED_BODY = """{"type":"about:blank","status":401,"title":"Unauthorized"}"""
private const val FORBIDDEN_BODY =
    """{"type":"about:blank","status":403,"title":"Forbidden","error":"insufficient_scope"}"""

private val NOW = Instant.parse("2026-11-06T00:00:00Z")
private val TENANT = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
private val OTHER_TENANT = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
private val KEY_ID = UUID.fromString("00000000-0000-0000-0000-000000000042")
private const val PREFIX = "pk_abcd1234"
private const val SECRET = "TOPSECRETVALUE123"
private const val ROW_HASH = "ROW_HASH"
private const val REG_PATH = "/api/v1/rp/registration/start"

/**
 * Kotlin 전환 전 ApiKeyAuthFilter 의 외부 관찰 가능 동작(상태·바디 바이트·스킵 경로·
 * 타이밍 균등화용 더미 BCrypt 호출·메트릭·보안 이벤트·로그 문구·MDC·tenant context)을
 * 현재 Java 구현 기준으로 고정하는 특성 테스트. 기존 ApiKeyAuthFilterTest /
 * ApiKeyAuthFilterScopeTest 가 덮지 않는 분기만 다룬다.
 */
class ApiKeyAuthFilterCharacterizationTest {

    private val lookup: ApiKeyLookupService = mock()
    private val encoder: PasswordEncoder = mock()
    private val scopeRepo: ApiKeyScopeRepository = mock()
    private val events: ApplicationEventPublisher = mock()
    private val meters = SimpleMeterRegistry()
    private val filter = ApiKeyAuthFilter(
        lookup, encoder, scopeRepo, ApiKeyScopeResolver(), meters, events, Clock.fixed(NOW, ZoneOffset.UTC),
    )

    private val logger = LoggerFactory.getLogger(ApiKeyAuthFilter::class.java) as Logger
    private val originalLevel: Level? = logger.level
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun setUp() {
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(appender)
        logger.level = originalLevel
        TenantContextHolder.clear()
        MDC.clear()
    }

    private fun req(servletPath: String, apiKey: String?) = MockHttpServletRequest("POST", servletPath).apply {
        this.servletPath = servletPath
        apiKey?.let { addHeader("X-API-Key", it) }
    }

    private fun messages(): List<String> = appender.list.map { it.formattedMessage }

    private fun count(result: String): Double = meters.counter("passkey_apikey_auth_total", "result", result).count()

    private fun stubRow(expiresAt: Instant?, revokedAt: Instant?) {
        whenever(lookup.findByPrefix(PREFIX)).thenReturn(
            ApiKeyLookupService.ApiKeyAuthRow(KEY_ID, TENANT, ROW_HASH, expiresAt, revokedAt),
        )
    }

    // ── registration / order ────────────────────────────────────────────────

    @Test
    fun order_is_highest_precedence_plus_5_and_is_component() {
        val order = AnnotationUtils.findAnnotation(ApiKeyAuthFilter::class.java, Order::class.java)
        assertThat(order).isNotNull()
        assertThat(order?.value).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 5)
        assertThat(AnnotationUtils.findAnnotation(ApiKeyAuthFilter::class.java, Component::class.java)).isNotNull()
    }

    @Test
    fun all_result_counters_are_registered_at_construction_with_zero() {
        for (r in listOf("success", "unknown_prefix", "revoked", "expired", "bad_secret", "insufficient_scope")) {
            assertThat(meters.find("passkey_apikey_auth_total").tag("result", r).counter()).`as`(r).isNotNull()
            assertThat(count(r)).`as`(r).isZero()
        }
        assertThat(meters.find("passkey_apikey_auth_total").counters()).hasSize(6)
    }

    // ── shouldNotFilter ─────────────────────────────────────────────────────

    @Test
    fun non_rp_path_is_skipped_without_any_auth_work() {
        val r = req("/actuator/health", null)
        val res = MockHttpServletResponse()
        val chain: FilterChain = mock()

        filter.doFilter(r, res, chain)

        verify(chain).doFilter(r, res)
        assertThat(res.status).isEqualTo(200)
        assertThat(res.contentAsString).isEmpty()
        verifyNoInteractions(lookup, encoder, scopeRepo, events)
    }

    @Test
    fun rp_base_without_trailing_slash_is_skipped() {
        val r = req("/api/v1/rp", null)
        val chain: FilterChain = mock()
        filter.doFilter(r, MockHttpServletResponse(), chain)
        verify(chain).doFilter(anyOrNull(), anyOrNull())
    }

    @Test
    fun skip_decision_uses_servlet_path_not_request_uri() {
        // context-path 가 붙은 requestURI 라도 servletPath 가 /api/v1/rp/ 이면 인증 대상.
        val gated = MockHttpServletRequest("GET", "/passkey/api/v1/rp/x").apply {
            requestURI = "/passkey/api/v1/rp/x"
            servletPath = "/api/v1/rp/x"
        }
        val res = MockHttpServletResponse()
        val chain: FilterChain = mock()
        filter.doFilter(gated, res, chain)
        assertThat(res.status).isEqualTo(401)
        verify(chain, never()).doFilter(anyOrNull(), anyOrNull())

        // 반대로 requestURI 가 /api/v1/rp/ 여도 servletPath 가 아니면 스킵.
        val skipped = MockHttpServletRequest("GET", "/api/v1/rp/x").apply {
            requestURI = "/api/v1/rp/x"
            servletPath = "/other"
        }
        val chain2: FilterChain = mock()
        filter.doFilter(skipped, MockHttpServletResponse(), chain2)
        verify(chain2).doFilter(anyOrNull(), anyOrNull())
    }

    @Test
    fun null_servlet_path_is_skipped() {
        val r: HttpServletRequest = mock()
        whenever(r.servletPath).thenReturn(null)
        // shouldNotFilter 는 protected — Java 테스트의 같은 패키지 접근을 ReflectionTestUtils 로 대체(같은 메서드 직접 호출).
        assertThat(ReflectionTestUtils.invokeMethod<Boolean>(filter, "shouldNotFilter", r)).isTrue()
    }

    // ── header shape ────────────────────────────────────────────────────────

    @Test
    fun missing_header_writes_exact_401_problem_json() {
        val res = MockHttpServletResponse()
        filter.doFilter(req(REG_PATH, null), res, mock())

        assertThat(res.status).isEqualTo(401)
        assertThat(res.contentType).isEqualTo("application/problem+json")
        assertThat(res.contentAsString).isEqualTo(UNAUTHORIZED_BODY)
        assertThat(res.getHeader("WWW-Authenticate")).isNull()
        verifyNoInteractions(lookup, encoder)
        assertThat(messages()).isEmpty()
    }

    @Test
    fun header_of_exactly_prefix_length_is_rejected_before_lookup() {
        val res = MockHttpServletResponse()
        filter.doFilter(req(REG_PATH, "pk_abcd1234"), res, mock())

        assertThat(res.status).isEqualTo(401)
        assertThat(res.contentAsString).isEqualTo(UNAUTHORIZED_BODY)
        verifyNoInteractions(lookup, encoder)
    }

    @Test
    fun header_one_char_past_prefix_splits_prefix_and_secret() {
        whenever(lookup.findByPrefix(any<String>())).thenReturn(null)
        filter.doFilter(req(REG_PATH, "pk_abcd1234X"), MockHttpServletResponse(), mock())

        verify(lookup).findByPrefix("pk_abcd1234")
        verify(encoder).matches("X", DUMMY_HASH)
    }

    @Test
    fun stale_tenant_context_is_cleared_on_entry_even_on_401() {
        TenantContextHolder.set(OTHER_TENANT)
        filter.doFilter(req(REG_PATH, null), MockHttpServletResponse(), mock())
        assertThat(TenantContextHolder.get()).isNull()
    }

    // ── unknown prefix ──────────────────────────────────────────────────────

    @Test
    fun unknown_prefix_runs_dummy_bcrypt_counts_and_logs_without_secret() {
        whenever(lookup.findByPrefix(PREFIX)).thenReturn(null)
        val res = MockHttpServletResponse()
        val chain: FilterChain = mock()

        filter.doFilter(req(REG_PATH, PREFIX + SECRET), res, chain)

        verify(encoder).matches(SECRET, DUMMY_HASH)
        assertThat(res.status).isEqualTo(401)
        assertThat(res.contentAsString).isEqualTo(UNAUTHORIZED_BODY)
        assertThat(count("unknown_prefix")).isEqualTo(1.0)
        assertThat(messages()).containsExactly("api-key auth failed: reason=unknown-prefix prefix=pk_abcd1234")
        assertThat(appender.list[0].level).isEqualTo(Level.WARN)
        assertThat(messages()).noneMatch { it.contains(SECRET) }
        verifyNoInteractions(events, scopeRepo)
        verify(chain, never()).doFilter(anyOrNull(), anyOrNull())
    }

    // ── revoked / expired ───────────────────────────────────────────────────

    @Test
    fun revoked_runs_dummy_bcrypt_not_row_hash() {
        stubRow(null, NOW.minusSeconds(1))
        val res = MockHttpServletResponse()

        filter.doFilter(req(REG_PATH, PREFIX + SECRET), res, mock())

        verify(encoder).matches(SECRET, DUMMY_HASH)
        verify(encoder, never()).matches(SECRET, ROW_HASH)
        assertThat(res.contentAsString).isEqualTo(UNAUTHORIZED_BODY)
        assertThat(count("revoked")).isEqualTo(1.0)
        assertThat(count("expired")).isZero()
        assertThat(messages()).containsExactly("api-key auth failed: reason=revoked prefix=pk_abcd1234")
        verifyNoInteractions(events, scopeRepo)
        verify(lookup, never()).touchLastUsed(any(), any(), any())
    }

    @Test
    fun revoked_and_expired_reports_revoked() {
        stubRow(NOW.minusSeconds(10), NOW.minusSeconds(1))
        filter.doFilter(req(REG_PATH, PREFIX + SECRET), MockHttpServletResponse(), mock())
        assertThat(count("revoked")).isEqualTo(1.0)
        assertThat(count("expired")).isZero()
        assertThat(messages()).containsExactly("api-key auth failed: reason=revoked prefix=pk_abcd1234")
    }

    @Test
    fun expires_exactly_now_is_expired() {
        stubRow(NOW, null)
        val res = MockHttpServletResponse()

        filter.doFilter(req(REG_PATH, PREFIX + SECRET), res, mock())

        verify(encoder).matches(SECRET, DUMMY_HASH)
        assertThat(res.status).isEqualTo(401)
        assertThat(count("expired")).isEqualTo(1.0)
        assertThat(messages()).containsExactly("api-key auth failed: reason=expired prefix=pk_abcd1234")
    }

    @Test
    fun expires_in_future_is_active() {
        stubRow(NOW.plusMillis(1), null)
        whenever(encoder.matches(SECRET, ROW_HASH)).thenReturn(true)
        whenever(scopeRepo.findScopeValuesByApiKeyId(KEY_ID)).thenReturn(setOf("registration"))
        val chain: FilterChain = mock()

        filter.doFilter(req(REG_PATH, PREFIX + SECRET), MockHttpServletResponse(), chain)

        verify(chain).doFilter(anyOrNull(), anyOrNull())
        assertThat(count("success")).isEqualTo(1.0)
    }

    @Test
    fun row_is_active_boundaries() {
        assertThat(ApiKeyLookupService.ApiKeyAuthRow(KEY_ID, TENANT, ROW_HASH, null, null).isActive(NOW)).isTrue()
        assertThat(ApiKeyLookupService.ApiKeyAuthRow(KEY_ID, TENANT, ROW_HASH, NOW, null).isActive(NOW)).isFalse()
        assertThat(ApiKeyLookupService.ApiKeyAuthRow(KEY_ID, TENANT, ROW_HASH, NOW.plusNanos(1), null).isActive(NOW))
            .isTrue()
        assertThat(ApiKeyLookupService.ApiKeyAuthRow(KEY_ID, TENANT, ROW_HASH, null, NOW.plusSeconds(60)).isActive(NOW))
            .`as`("any non-null revokedAt (even future) is inactive").isFalse()
    }

    // ── bad secret ──────────────────────────────────────────────────────────

    @Test
    fun bad_secret_publishes_brute_force_alert_with_prefix_only() {
        stubRow(null, null)
        whenever(encoder.matches(SECRET, ROW_HASH)).thenReturn(false)
        val res = MockHttpServletResponse()

        filter.doFilter(req(REG_PATH, PREFIX + SECRET), res, mock())

        verify(encoder).matches(SECRET, ROW_HASH)
        verify(encoder, never()).matches(SECRET, DUMMY_HASH)
        assertThat(res.status).isEqualTo(401)
        assertThat(res.contentAsString).isEqualTo(UNAUTHORIZED_BODY)
        assertThat(count("bad_secret")).isEqualTo(1.0)
        assertThat(messages()).containsExactly("api-key auth failed: reason=bad-secret prefix=pk_abcd1234")

        val captor = argumentCaptor<Any>()
        verify(events).publishEvent(captor.capture())
        assertThat(captor.lastValue).isEqualTo(
            SecurityAlertEvent(
                SecurityAlertEvent.AlertType.API_KEY_BRUTE_FORCE,
                SecurityAlertEvent.Severity.MEDIUM,
                "api key auth failed (bad secret)",
                mapOf("prefix" to PREFIX),
            ),
        )
        verifyNoInteractions(scopeRepo)
        verify(lookup, never()).touchLastUsed(any(), any(), any())
        assertThat(TenantContextHolder.get()).isNull()
    }

    // ── scope denied ────────────────────────────────────────────────────────

    @Test
    fun scope_denied_writes_exact_403_logs_held_and_skips_touch() {
        stubRow(null, null)
        whenever(encoder.matches(SECRET, ROW_HASH)).thenReturn(true)
        whenever(scopeRepo.findScopeValuesByApiKeyId(KEY_ID)).thenReturn(setOf("authentication"))
        val res = MockHttpServletResponse()

        filter.doFilter(req(REG_PATH, PREFIX + SECRET), res, mock())

        assertThat(res.status).isEqualTo(403)
        assertThat(res.contentType).isEqualTo("application/problem+json")
        assertThat(res.contentAsString).isEqualTo(FORBIDDEN_BODY)
        assertThat(messages()).containsExactly(
            "api-key scope denied: prefix=pk_abcd1234 required=registration held=[authentication]",
        )
        verify(lookup, never()).touchLastUsed(any(), any(), any())
        assertThat(count("success")).isZero()
        assertThat(MDC.get("apiKeyPrefix")).isNull()
        assertThat(TenantContextHolder.get()).isNull()
    }

    @Test
    fun scope_query_sees_authenticated_tenant_context() {
        stubRow(null, null)
        whenever(encoder.matches(SECRET, ROW_HASH)).thenReturn(true)
        var seen: UUID? = null
        whenever(scopeRepo.findScopeValuesByApiKeyId(KEY_ID)).thenAnswer {
            seen = TenantContextHolder.get()
            setOf("registration")
        }

        filter.doFilter(req(REG_PATH, PREFIX + SECRET), MockHttpServletResponse(), mock())

        assertThat(seen).isEqualTo(TENANT)
    }

    // ── success ─────────────────────────────────────────────────────────────

    @Test
    fun success_sets_mdc_during_chain_touches_with_clock_now_and_cleans_up() {
        logger.level = Level.DEBUG
        stubRow(null, null)
        whenever(encoder.matches(SECRET, ROW_HASH)).thenReturn(true)
        whenever(scopeRepo.findScopeValuesByApiKeyId(KEY_ID)).thenReturn(setOf("registration"))
        val res = MockHttpServletResponse()
        val mdcSeen = arrayOfNulls<String>(2)
        var tenantSeen: UUID? = null
        val chain = FilterChain { _, _ ->
            mdcSeen[0] = MDC.get("apiKeyPrefix")
            mdcSeen[1] = MDC.get("tenantId")
            tenantSeen = TenantContextHolder.get()
        }

        filter.doFilter(req(REG_PATH, PREFIX + SECRET), res, chain)

        assertThat(mdcSeen).containsExactly(PREFIX, TENANT.toString())
        assertThat(tenantSeen).isEqualTo(TENANT)
        verify(lookup).touchLastUsed(KEY_ID, TENANT, NOW)
        assertThat(count("success")).isEqualTo(1.0)
        assertThat(res.status).isEqualTo(200)
        assertThat(res.contentAsString).isEmpty()
        assertThat(messages()).containsExactly(
            "api-key auth ok: prefix=pk_abcd1234 tenantId=aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
        )
        assertThat(appender.list[0].level).isEqualTo(Level.DEBUG)
        assertThat(MDC.get("apiKeyPrefix")).isNull()
        assertThat(MDC.get("tenantId")).isNull()
        assertThat(TenantContextHolder.get()).isNull()
        verifyNoInteractions(events)
    }

    @Test
    fun success_emits_no_log_when_debug_disabled() {
        logger.level = Level.INFO
        stubRow(null, null)
        whenever(encoder.matches(SECRET, ROW_HASH)).thenReturn(true)
        whenever(scopeRepo.findScopeValuesByApiKeyId(KEY_ID)).thenReturn(setOf("registration"))

        filter.doFilter(req(REG_PATH, PREFIX + SECRET), MockHttpServletResponse(), mock())

        assertThat(messages()).isEmpty()
    }

    @Test
    fun chain_exception_propagates_and_mdc_and_tenant_are_cleared() {
        stubRow(null, null)
        whenever(encoder.matches(SECRET, ROW_HASH)).thenReturn(true)
        whenever(scopeRepo.findScopeValuesByApiKeyId(KEY_ID)).thenReturn(setOf("registration"))
        val chain = FilterChain { _, _ -> throw ServletException("boom") }

        assertThatThrownBy { filter.doFilter(req(REG_PATH, PREFIX + SECRET), MockHttpServletResponse(), chain) }
            .isInstanceOf(ServletException::class.java).hasMessage("boom")

        assertThat(MDC.get("apiKeyPrefix")).isNull()
        assertThat(MDC.get("tenantId")).isNull()
        assertThat(TenantContextHolder.get()).isNull()
        assertThat(count("success")).isEqualTo(1.0)
    }

    // ── lookup failure (DB down) ────────────────────────────────────────────

    @Test
    fun lookup_exception_propagates_fail_closed_without_chain() {
        val db: RuntimeException = IllegalStateException("db down")
        whenever(lookup.findByPrefix(PREFIX)).thenThrow(db)
        val chain: FilterChain = mock()

        assertThatThrownBy { filter.doFilter(req(REG_PATH, PREFIX + SECRET), MockHttpServletResponse(), chain) }
            .isSameAs(db)

        verifyNoInteractions(chain, encoder)
        assertThat(TenantContextHolder.get()).isNull()
    }

    @Test
    fun null_key_hash_follows_bad_secret_path_with_real_bcrypt() {
        // 원 record 는 null keyHash 를 허용했고 BCryptPasswordEncoder.matches(raw, null) 은 false →
        // 401 + bad_secret + brute-force 알림. 500 이 되면 안 된다.
        val real = ApiKeyAuthFilter(
            lookup, BCryptPasswordEncoder(4), scopeRepo, ApiKeyScopeResolver(), meters, events,
            Clock.fixed(NOW, ZoneOffset.UTC),
        )
        whenever(lookup.findByPrefix(PREFIX)).thenReturn(
            ApiKeyLookupService.ApiKeyAuthRow(KEY_ID, TENANT, null, null, null),
        )
        val res = MockHttpServletResponse()
        val chain: FilterChain = mock()

        real.doFilter(req(REG_PATH, PREFIX + SECRET), res, chain)

        assertThat(res.status).isEqualTo(401)
        assertThat(res.contentAsString).isEqualTo(UNAUTHORIZED_BODY)
        assertThat(count("bad_secret")).isEqualTo(1.0)
        verify(events).publishEvent(any<SecurityAlertEvent>())
        verifyNoInteractions(chain, scopeRepo)
        assertThat(TenantContextHolder.get()).isNull()
    }
}
