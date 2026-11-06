package com.crosscert.passkey.app.security

import com.crosscert.passkey.core.repository.ApiKeyScopeRepository
import com.crosscert.passkey.core.tenant.TenantContextHolder
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.time.Clock
import java.time.Instant
import java.util.UUID

private val TENANT_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
private val KEY_ID_7 = UUID.fromString("00000000-0000-0000-0000-000000000007")
private val KEY_ID_1 = UUID.fromString("00000000-0000-0000-0000-000000000001")
private val KEY_ID_99 = UUID.fromString("00000000-0000-0000-0000-000000000099")
private val KEY_ID_100 = UUID.fromString("00000000-0000-0000-0000-000000000100")

class ApiKeyAuthFilterTest {

    private val lookup: ApiKeyLookupService = mock()
    private val encoder = BCryptPasswordEncoder(4) // low cost for test speed
    private val scopeRepo: ApiKeyScopeRepository = mock()
    private val filter = ApiKeyAuthFilter(
        lookup, encoder, scopeRepo, ApiKeyScopeResolver(),
        SimpleMeterRegistry(), mock(), Clock.systemUTC(),
    )

    @AfterEach
    fun cleanup() {
        TenantContextHolder.clear()
    }

    private fun request(method: String, path: String, apiKey: String? = null) =
        MockHttpServletRequest(method, path).apply {
            servletPath = path
            apiKey?.let { addHeader("X-API-Key", it) }
        }

    @Test
    fun rejectsRequestWithoutHeader() {
        val req = request("GET", "/api/v1/rp/anything")
        val res = MockHttpServletResponse()
        val chain: FilterChain = mock()

        filter.doFilter(req, res, chain)

        assertThat(res.status).isEqualTo(401)
        verify(chain, never()).doFilter(anyOrNull(), anyOrNull())
        assertThat(TenantContextHolder.get()).isNull()
    }

    @Test
    fun rejectsHeaderWithoutPkPrefix() {
        val req = request("GET", "/api/v1/rp/x", "AAAAAAAA_some_random_secret_value_here")
        val res = MockHttpServletResponse()
        val chain: FilterChain = mock()

        filter.doFilter(req, res, chain)
        assertThat(res.status).isEqualTo(401)
        // No DB call for malformed prefix.
        verify(lookup, never()).findByPrefix(any())
    }

    @Test
    fun rejectsUnknownPrefix() {
        whenever(lookup.findByPrefix("pk_unknwn")).thenReturn(null)

        val req = request("GET", "/api/v1/rp/x", "pk_unknwnSECRETSECRETSECRET")
        val res = MockHttpServletResponse()
        val chain: FilterChain = mock()

        filter.doFilter(req, res, chain)
        assertThat(res.status).isEqualTo(401)
    }

    @Test
    fun rejectsWrongSecret() {
        val fullKey = "pk_abcd1234SECRETPART01234567890ABCDEF"
        val prefix = "pk_abcd1234"
        val hash = encoder.encode("DIFFERENT_SECRET")
        whenever(lookup.findByPrefix(prefix)).thenReturn(
            ApiKeyLookupService.ApiKeyAuthRow(KEY_ID_1, TENANT_A, hash, null, null),
        )

        val req = request("GET", "/api/v1/rp/x", fullKey)
        val res = MockHttpServletResponse()
        val chain: FilterChain = mock()

        filter.doFilter(req, res, chain)
        assertThat(res.status).isEqualTo(401)
    }

    @Test
    fun acceptsValidKeyAndSetsTenantContextDuringChain() {
        val secret = "SECRET_PART_OK_01234567890ABCDEF"
        val fullKey = "pk_abcd1234$secret"
        val prefix = "pk_abcd1234"
        val hash = encoder.encode(secret)
        whenever(lookup.findByPrefix(prefix)).thenReturn(
            ApiKeyLookupService.ApiKeyAuthRow(KEY_ID_7, TENANT_A, hash, null, null),
        )
        // mapped scope 경로 + 키가 해당 scope 보유 → fail-closed 통과(이 테스트는 인증/테넌트 컨텍스트 검증 목적).
        whenever(scopeRepo.findScopeValuesByApiKeyId(KEY_ID_7)).thenReturn(setOf("registration"))

        val req = request("POST", "/api/v1/rp/registration/start", fullKey)
        val res = MockHttpServletResponse()
        var tenantSeenInChain: UUID? = null
        val chain = FilterChain { _, _ -> tenantSeenInChain = TenantContextHolder.get() }

        filter.doFilter(req, res, chain)

        assertThat(tenantSeenInChain).isEqualTo(TENANT_A)
        assertThat(TenantContextHolder.get()).isNull()
        verify(lookup).touchLastUsed(eq(KEY_ID_7), eq(TENANT_A), any<Instant>())
    }

    @Test
    fun rejectsRevokedKey() {
        val secret = "SECRET"
        val prefix = "pk_revoked0"
        whenever(lookup.findByPrefix(prefix)).thenReturn(
            ApiKeyLookupService.ApiKeyAuthRow(
                KEY_ID_99, TENANT_A, encoder.encode(secret),
                expiresAt = null,
                revokedAt = Instant.now().minusSeconds(60),
            ),
        )

        val req = request("GET", "/api/v1/rp/x", prefix + secret)
        val res = MockHttpServletResponse()
        val chain: FilterChain = mock()

        filter.doFilter(req, res, chain)
        assertThat(res.status).isEqualTo(401)
        verify(chain, never()).doFilter(anyOrNull(), anyOrNull())
    }

    @Test
    fun rejectsExpiredKey() {
        val secret = "SECRET"
        val prefix = "pk_expired0"
        whenever(lookup.findByPrefix(prefix)).thenReturn(
            ApiKeyLookupService.ApiKeyAuthRow(
                KEY_ID_100, TENANT_A, encoder.encode(secret),
                expiresAt = Instant.now().minusSeconds(60),
                revokedAt = null,
            ),
        )

        val req = request("GET", "/api/v1/rp/x", prefix + secret)
        val res = MockHttpServletResponse()
        val chain: FilterChain = mock()

        filter.doFilter(req, res, chain)
        assertThat(res.status).isEqualTo(401)
        verify(chain, never()).doFilter(anyOrNull(), anyOrNull())
    }
}
