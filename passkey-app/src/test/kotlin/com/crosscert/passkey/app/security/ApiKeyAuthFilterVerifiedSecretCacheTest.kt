package com.crosscert.passkey.app.security

import com.crosscert.passkey.core.repository.ApiKeyScopeRepository
import com.crosscert.passkey.core.tenant.TenantContextHolder
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.ApplicationEventPublisher
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * P1: 같은 키의 반복 요청에서 BCrypt(키 해시 대상)를 생략하는지, 그리고 캐시가
 * TTL·keyHash·폐기·만료·secret 불일치에서 정확히 BCrypt 로 되돌아가는지 검증한다.
 * 수락·거부 판정 자체는 ApiKeyAuthFilterRepeatedRequestTest 가 고정한다.
 */
class ApiKeyAuthFilterVerifiedSecretCacheTest {

    private lateinit var lookup: ApiKeyLookupService
    private lateinit var encoder: BCryptPasswordEncoder
    private lateinit var clock: MutableClock
    private lateinit var filter: ApiKeyAuthFilter
    private lateinit var hash: String

    @BeforeEach
    fun setUp() {
        lookup = mock()
        val scopeRepo: ApiKeyScopeRepository = mock()
        whenever(scopeRepo.findScopeValuesByApiKeyId(KEY_ID)).thenReturn(setOf("registration"))
        encoder = spy(BCryptPasswordEncoder(4))
        hash = encoder.encode(SECRET)!!
        clock = MutableClock(Instant.parse("2026-10-13T00:00:00Z"))
        returnRow(hash, null, null)
        filter = ApiKeyAuthFilter(
            lookup, encoder, scopeRepo, ApiKeyScopeResolver(),
            SimpleMeterRegistry(), mock<ApplicationEventPublisher>(), clock,
        )
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

    private fun send(secret: String): Int {
        val req = MockHttpServletRequest("POST", PATH)
        req.servletPath = PATH
        req.requestURI = PATH
        req.addHeader("X-API-Key", PREFIX + secret)
        val res = MockHttpServletResponse()
        val chain: FilterChain = mock()
        filter.doFilter(req, res, chain)
        return res.status
    }

    @Test
    fun second_valid_request_within_ttl_skips_bcrypt_but_still_looks_up_db() {
        assertThat(send(SECRET)).isEqualTo(200)
        assertThat(send(SECRET)).isEqualTo(200)

        verify(encoder, times(1)).matches(anyOrNull(), eq(hash))
        verify(lookup, times(2)).findByPrefix(PREFIX)
    }

    @Test
    fun bcrypt_runs_again_once_ttl_elapses() {
        assertThat(send(SECRET)).isEqualTo(200)
        clock.advance(Duration.ofSeconds(29))
        assertThat(send(SECRET)).isEqualTo(200)
        verify(encoder, times(1)).matches(anyOrNull(), eq(hash))

        clock.advance(Duration.ofSeconds(1)) // verifiedAt + 30s → 만료
        assertThat(send(SECRET)).isEqualTo(200)
        verify(encoder, times(2)).matches(anyOrNull(), eq(hash))
    }

    @Test
    fun wrong_secret_after_success_always_runs_bcrypt() {
        assertThat(send(SECRET)).isEqualTo(200)
        assertThat(send("WRONG_SECRET_01234567890ABCDEFGH")).isEqualTo(401)

        verify(encoder, times(2)).matches(anyOrNull(), eq(hash))
    }

    @Test
    fun key_hash_change_misses_cache_even_for_same_secret() {
        assertThat(send(SECRET)).isEqualTo(200)

        val rehashed = encoder.encode(SECRET)!! // 같은 secret, 다른 salt → 다른 keyHash
        returnRow(rehashed, null, null)
        assertThat(send(SECRET)).isEqualTo(200)

        verify(encoder, times(1)).matches(anyOrNull(), eq(rehashed))
    }

    @Test
    fun revocation_evicts_cached_verification() {
        assertThat(send(SECRET)).isEqualTo(200)

        returnRow(hash, null, clock.instant())
        assertThat(send(SECRET)).isEqualTo(401)

        returnRow(hash, null, null)
        assertThat(send(SECRET)).isEqualTo(200)
        verify(encoder, times(2)).matches(anyOrNull(), eq(hash))
    }

    @Test
    fun expiry_evicts_cached_verification() {
        returnRow(hash, clock.instant().plusSeconds(5), null)
        assertThat(send(SECRET)).isEqualTo(200)

        clock.advance(Duration.ofSeconds(5))
        assertThat(send(SECRET)).isEqualTo(401)

        returnRow(hash, clock.instant().plusSeconds(3600), null)
        assertThat(send(SECRET)).isEqualTo(200)
        verify(encoder, times(2)).matches(anyOrNull(), eq(hash))
    }

    private companion object {
        val TENANT: UUID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")
        val KEY_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000043")
        const val PREFIX = "pk_cache002"
        const val SECRET = "SECRET_PART_OK_01234567890ABCDEF"
        const val PATH = "/api/v1/rp/registration/start"
    }
}
