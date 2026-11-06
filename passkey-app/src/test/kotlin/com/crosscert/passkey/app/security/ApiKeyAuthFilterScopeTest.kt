package com.crosscert.passkey.app.security

import com.crosscert.passkey.core.repository.ApiKeyScopeRepository
import com.crosscert.passkey.core.tenant.TenantContextHolder
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Clock
import java.util.UUID

private const val HASH = "HASH"

/**
 * P1-5: 경로 요구 scope 미보유 시 403 (vs 인증 실패 401) 단위 검증.
 * 실제 [ApiKeyScopeResolver] + mock lookup/encoder/scopeRepo 로
 * 필터의 scope enforcement 분기만 직접 doFilter 로 검증한다.
 */
class ApiKeyAuthFilterScopeTest {

    private val lookup: ApiKeyLookupService = mock()
    private val encoder: PasswordEncoder = mock()
    private val scopeRepo: ApiKeyScopeRepository = mock()
    private val resolver = ApiKeyScopeResolver()
    private val meterRegistry = SimpleMeterRegistry()
    private val filter = ApiKeyAuthFilter(lookup, encoder, scopeRepo, resolver, meterRegistry, mock(), Clock.systemUTC())

    private val keyId = UUID.randomUUID()
    private val tenantId = UUID.randomUUID()

    // PREFIX_LEN = 11 ("pk_" + 8 chars). secret is the remainder of the header.
    private val prefix = "pk_abcd1234"
    private val secret = "SECRETSECRETSECRET"

    @BeforeEach
    fun setUp() {
        whenever(lookup.findByPrefix(prefix)).thenReturn(
            ApiKeyLookupService.ApiKeyAuthRow(keyId, tenantId, HASH, null, null),
        )
        whenever(encoder.matches(secret, HASH)).thenReturn(true)
    }

    @AfterEach
    fun cleanup() {
        TenantContextHolder.clear()
    }

    private fun req(uri: String) = MockHttpServletRequest("POST", uri).apply {
        servletPath = uri
        requestURI = uri
        addHeader("X-API-Key", prefix + secret)
    }

    @Test
    fun allows_when_key_has_required_scope() {
        whenever(scopeRepo.findScopeValuesByApiKeyId(keyId)).thenReturn(setOf("registration"))
        val res = MockHttpServletResponse()
        val chain: FilterChain = mock()

        filter.doFilter(req("/api/v1/rp/registration/start"), res, chain)

        verify(chain).doFilter(anyOrNull(), anyOrNull())
        assertThat(TenantContextHolder.get()).isNull()
    }

    @Test
    fun forbids_when_key_lacks_required_scope() {
        whenever(scopeRepo.findScopeValuesByApiKeyId(keyId)).thenReturn(setOf("authentication"))
        val res = MockHttpServletResponse()
        val chain: FilterChain = mock()

        filter.doFilter(req("/api/v1/rp/registration/start"), res, chain)

        assertThat(res.status).isEqualTo(403)
        verify(chain, never()).doFilter(anyOrNull(), anyOrNull())
        assertThat(TenantContextHolder.get()).isNull()
        // P1-2: insufficient_scope 결과 메트릭이 1회 기록됐는지 대표 검증.
        assertThat(meterRegistry.counter("passkey_apikey_auth_total", "result", "insufficient_scope").count())
            .isEqualTo(1.0)
    }

    @Test
    fun forbids_when_key_has_no_scopes() {
        whenever(scopeRepo.findScopeValuesByApiKeyId(keyId)).thenReturn(emptySet())
        val res = MockHttpServletResponse()
        val chain: FilterChain = mock()

        filter.doFilter(req("/api/v1/rp/registration/start"), res, chain)

        assertThat(res.status).isEqualTo(403)
        verify(chain, never()).doFilter(anyOrNull(), anyOrNull())
        assertThat(TenantContextHolder.get()).isNull()
    }

    @Test
    fun forbids_unmapped_rp_path_fail_closed() {
        // fail-closed: /api/v1/rp 하위인데 scope 매핑 없는 경로는 sentinel scope 요구 →
        // 어떤 키도 보유 불가 → 403. (이전 fail-open: 매핑 없으면 통과시켰음)
        whenever(scopeRepo.findScopeValuesByApiKeyId(keyId)).thenReturn(setOf("registration"))
        val res = MockHttpServletResponse()
        val chain: FilterChain = mock()

        filter.doFilter(req("/api/v1/rp/other"), res, chain)

        assertThat(res.status).isEqualTo(403)
        verify(chain, never()).doFilter(anyOrNull(), anyOrNull())
        assertThat(TenantContextHolder.get()).isNull()
    }

    @Test
    fun clears_tenant_context_even_when_scope_query_throws() {
        whenever(scopeRepo.findScopeValuesByApiKeyId(keyId)).thenThrow(RuntimeException("db"))
        val res = MockHttpServletResponse()
        val chain: FilterChain = mock()

        assertThatThrownBy { filter.doFilter(req("/api/v1/rp/registration/start"), res, chain) }
            .isInstanceOf(Exception::class.java)
        // scope 쿼리 예외가 전파돼도 바깥 finally 가 context 를 비워 cross-request 오염 방지.
        assertThat(TenantContextHolder.get()).isNull()
    }

    @Test
    fun uses_servlet_path_not_request_uri_for_scope() {
        whenever(scopeRepo.findScopeValuesByApiKeyId(keyId)).thenReturn(setOf("registration"))
        val r = MockHttpServletRequest("POST", "/passkey/api/v1/rp/registration/start").apply {
            requestURI = "/passkey/api/v1/rp/registration/start" // context-path 포함
            servletPath = "/api/v1/rp/registration/start" // context-path 제거됨
            addHeader("X-API-Key", prefix + secret)
        }
        val res = MockHttpServletResponse()
        val chain: FilterChain = mock()

        filter.doFilter(r, res, chain)

        verify(chain).doFilter(anyOrNull(), anyOrNull()) // servletPath 로 scope 매칭돼 통과
    }
}
