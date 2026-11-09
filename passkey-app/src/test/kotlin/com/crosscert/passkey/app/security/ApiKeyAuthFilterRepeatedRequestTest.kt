package com.crosscert.passkey.app.security

import com.crosscert.passkey.core.alert.SecurityAlertEvent
import com.crosscert.passkey.core.repository.ApiKeyScopeRepository
import com.crosscert.passkey.core.tenant.TenantContextHolder
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.ApplicationEventPublisher
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** 테스트가 시간을 임의로 전진시킬 수 있는 Clock (JwksCacheTest 와 같은 형태). */
internal class MutableClock(start: Instant) : Clock() {
    @Volatile
    private var now: Instant = start

    fun advance(d: Duration) {
        now = now.plus(d)
    }

    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
}

/**
 * P1 특성화: 같은 API 키로 요청이 반복될 때의 수락·거부 판정과 부수효과(DB 조회,
 * last_used 갱신, 메트릭, 보안 경보, 테넌트 컨텍스트)를 고정한다. 검증 캐시 도입
 * 전후 모두 통과해야 한다 — 캐시는 판정을 바꾸지 않는다.
 */
class ApiKeyAuthFilterRepeatedRequestTest {

    private lateinit var lookup: ApiKeyLookupService
    private lateinit var scopeRepo: ApiKeyScopeRepository
    private lateinit var events: ApplicationEventPublisher
    private lateinit var meterRegistry: SimpleMeterRegistry
    private lateinit var clock: MutableClock
    private lateinit var encoder: PasswordEncoder
    private lateinit var filter: ApiKeyAuthFilter
    private lateinit var hash: String
    private val tenantsSeenInChain: MutableList<UUID?> = Collections.synchronizedList(ArrayList())

    @BeforeEach
    fun setUp() {
        lookup = mock()
        scopeRepo = mock()
        events = mock()
        meterRegistry = SimpleMeterRegistry()
        clock = MutableClock(Instant.parse("2026-10-13T00:00:00Z"))
        encoder = BCryptPasswordEncoder(4) // low cost for test speed
        hash = encoder.encode(SECRET)!!
        whenever(scopeRepo.findScopeValuesByApiKeyId(KEY_ID)).thenReturn(setOf("registration"))
        returnRow(hash, null, null)
        filter = ApiKeyAuthFilter(lookup, encoder, scopeRepo, ApiKeyScopeResolver(), meterRegistry, events, clock)
    }

    @AfterEach
    fun cleanup() {
        TenantContextHolder.clear()
    }

    private fun returnRow(keyHash: String, expiresAt: Instant?, revokedAt: Instant?) {
        whenever(lookup.findByPrefix(PREFIX)).thenReturn(
            ApiKeyLookupService.ApiKeyAuthRow(KEY_ID, TENANT, keyHash, expiresAt, revokedAt),
        )
    }

    private fun send(secret: String): MockHttpServletResponse {
        val req = MockHttpServletRequest("POST", PATH)
        req.servletPath = PATH
        req.requestURI = PATH
        req.addHeader("X-API-Key", PREFIX + secret)
        val res = MockHttpServletResponse()
        val chain = FilterChain { _, _ -> tenantsSeenInChain.add(TenantContextHolder.get()) }
        filter.doFilter(req, res, chain)
        return res
    }

    private fun count(result: String): Double =
        meterRegistry.counter(COUNTER, "result", result).count()

    @Test
    fun repeated_valid_requests_each_hit_db_touch_metrics_and_tenant_context() {
        repeat(3) {
            assertThat(send(SECRET).status).isEqualTo(200)
            assertThat(TenantContextHolder.get()).isNull()
        }

        assertThat(tenantsSeenInChain).containsExactly(TENANT, TENANT, TENANT)
        verify(lookup, times(3)).findByPrefix(PREFIX)
        verify(lookup, times(3)).touchLastUsed(eq(KEY_ID), eq(TENANT), any<Instant>())
        verify(scopeRepo, times(3)).findScopeValuesByApiKeyId(KEY_ID)
        assertThat(count("success")).isEqualTo(3.0)
    }

    @Test
    fun wrong_secret_after_success_is_rejected_with_alert_and_metric() {
        assertThat(send(SECRET).status).isEqualTo(200)

        val res = send("WRONG_SECRET_01234567890ABCDEFGH")

        assertThat(res.status).isEqualTo(401)
        assertThat(tenantsSeenInChain).hasSize(1)
        assertThat(count("bad_secret")).isEqualTo(1.0)
        verify(events, times(1)).publishEvent(any<SecurityAlertEvent>())
        verify(lookup, times(1)).touchLastUsed(eq(KEY_ID), eq(TENANT), any<Instant>())
    }

    @Test
    fun secret_differing_only_in_last_char_or_length_after_success_is_rejected() {
        assertThat(send(SECRET).status).isEqualTo(200)

        val lastCharChanged = SECRET.substring(0, SECRET.length - 1) + "X"
        assertThat(send(lastCharChanged).status).isEqualTo(401)
        assertThat(send(SECRET.substring(0, SECRET.length - 1)).status).isEqualTo(401)
        assertThat(send(SECRET + "X").status).isEqualTo(401)

        assertThat(tenantsSeenInChain).hasSize(1)
        assertThat(count("bad_secret")).isEqualTo(3.0)
    }

    @Test
    fun revoked_after_success_is_rejected() {
        assertThat(send(SECRET).status).isEqualTo(200)

        returnRow(hash, null, clock.instant())
        val res = send(SECRET)

        assertThat(res.status).isEqualTo(401)
        assertThat(tenantsSeenInChain).hasSize(1)
        assertThat(count("revoked")).isEqualTo(1.0)
    }

    @Test
    fun expired_by_clock_after_success_is_rejected() {
        returnRow(hash, clock.instant().plusSeconds(10), null)
        assertThat(send(SECRET).status).isEqualTo(200)

        clock.advance(Duration.ofSeconds(10)) // expiresAt == now → inactive
        val res = send(SECRET)

        assertThat(res.status).isEqualTo(401)
        assertThat(tenantsSeenInChain).hasSize(1)
        assertThat(count("expired")).isEqualTo(1.0)
    }

    @Test
    fun key_hash_change_after_success_rejects_old_secret_and_accepts_new() {
        assertThat(send(SECRET).status).isEqualTo(200)

        val rotated = "ROTATED_SECRET_0123456789ABCDEFG"
        returnRow(encoder.encode(rotated)!!, null, null)

        assertThat(send(SECRET).status).isEqualTo(401)
        assertThat(send(rotated).status).isEqualTo(200)
        assertThat(count("bad_secret")).isEqualTo(1.0)
        assertThat(count("success")).isEqualTo(2.0)
    }

    @Test
    fun scope_is_rechecked_on_every_request_after_success() {
        assertThat(send(SECRET).status).isEqualTo(200)

        whenever(scopeRepo.findScopeValuesByApiKeyId(KEY_ID)).thenReturn(setOf("authentication"))
        val res = send(SECRET)

        assertThat(res.status).isEqualTo(403)
        assertThat(tenantsSeenInChain).hasSize(1)
        assertThat(count("insufficient_scope")).isEqualTo(1.0)
        assertThat(TenantContextHolder.get()).isNull()
        verify(lookup, times(1)).touchLastUsed(eq(KEY_ID), eq(TENANT), any<Instant>())
    }

    @Test
    fun unknown_prefix_after_success_is_rejected() {
        assertThat(send(SECRET).status).isEqualTo(200)

        whenever(lookup.findByPrefix(PREFIX)).thenReturn(null)
        val res = send(SECRET)

        assertThat(res.status).isEqualTo(401)
        assertThat(tenantsSeenInChain).hasSize(1)
        assertThat(count("unknown_prefix")).isEqualTo(1.0)
        verify(lookup, times(1)).touchLastUsed(eq(KEY_ID), eq(TENANT), any<Instant>())
    }

    @Test
    fun concurrent_valid_and_wrong_secrets_keep_each_decision() {
        val pool = Executors.newFixedThreadPool(8)
        val ok = ArrayList<Future<Int>>()
        val bad = ArrayList<Future<Int>>()
        try {
            repeat(40) {
                ok.add(pool.submit(Callable { send(SECRET).status }))
                bad.add(pool.submit(Callable { send("WRONG_SECRET_01234567890ABCDEFGH").status }))
            }
            for (f in ok) assertThat(f.get(30, TimeUnit.SECONDS)).isEqualTo(200)
            for (f in bad) assertThat(f.get(30, TimeUnit.SECONDS)).isEqualTo(401)
        } finally {
            pool.shutdownNow()
        }
        assertThat(count("success")).isEqualTo(40.0)
        assertThat(count("bad_secret")).isEqualTo(40.0)
        assertThat(tenantsSeenInChain).hasSize(40).containsOnly(TENANT)
    }

    private companion object {
        val TENANT: UUID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        val KEY_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000042")

        // PREFIX_LEN = 11 ("pk_" + 8 chars)
        const val PREFIX = "pk_cache001"
        const val SECRET = "SECRET_PART_OK_01234567890ABCDEF"
        const val PATH = "/api/v1/rp/registration/start"
        const val COUNTER = "passkey_apikey_auth_total"
    }
}
