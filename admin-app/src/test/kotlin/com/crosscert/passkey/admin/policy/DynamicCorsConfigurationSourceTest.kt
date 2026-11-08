package com.crosscert.passkey.admin.policy

import com.crosscert.passkey.core.entity.SecurityPolicy
import com.crosscert.passkey.core.repository.SecurityPolicyRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.mock.web.MockHttpServletRequest
import tools.jackson.databind.ObjectMapper
import java.util.Optional

class DynamicCorsConfigurationSourceTest {

    private val repo: SecurityPolicyRepository = mock()
    private val mapper = ObjectMapper()
    private val source = DynamicCorsConfigurationSource(repo, mapper)

    private fun policyWithCors(json: String): SecurityPolicy {
        val p = mock<SecurityPolicy>()
        whenever(p.corsAllowlistJson).thenReturn(json)
        return p
    }

    @Test
    fun absentPolicy_returnsNull() {
        whenever(repo.findById(1L)).thenReturn(Optional.empty())
        assertThat(source.getCorsConfiguration(MockHttpServletRequest())).isNull()
    }

    @Test
    fun emptyAllowlist_returnsNull() {
        val p = policyWithCors("[]")
        whenever(repo.findById(1L)).thenReturn(Optional.of(p))
        assertThat(source.getCorsConfiguration(MockHttpServletRequest())).isNull()
    }

    @Test
    fun blankAllowlist_returnsNull() {
        val p = policyWithCors("")
        whenever(repo.findById(1L)).thenReturn(Optional.of(p))
        assertThat(source.getCorsConfiguration(MockHttpServletRequest())).isNull()
    }

    @Test
    fun populatedAllowlist_returnsConfigWithOriginAndCredentials() {
        val p = policyWithCors("[\"https://app.example.com\"]")
        whenever(repo.findById(1L)).thenReturn(Optional.of(p))

        val cfg = source.getCorsConfiguration(MockHttpServletRequest())

        assertThat(cfg).isNotNull()
        assertThat(checkNotNull(cfg).allowedOrigins).contains("https://app.example.com")
        assertThat(cfg.allowCredentials).isTrue()
    }

    @Test
    fun malformedJson_returnsNull_noCrash() {
        val p = policyWithCors("not-json")
        whenever(repo.findById(1L)).thenReturn(Optional.of(p))
        assertThat(source.getCorsConfiguration(MockHttpServletRequest())).isNull()
    }

    @Test
    fun repeatedCallsWithinTtl_hitCache_singleDbRead() {
        val p = policyWithCors("[\"https://app.example.com\"]")
        whenever(repo.findById(1L)).thenReturn(Optional.of(p))

        // Many calls (CorsFilter fires on every request) inside the 10s TTL window
        // must collapse to a single DB read.
        for (i in 0 until 50) {
            val cfg = source.getCorsConfiguration(MockHttpServletRequest())
            assertThat(cfg).isNotNull()
            assertThat(checkNotNull(cfg).allowedOrigins).contains("https://app.example.com")
        }
        verify(repo, times(1)).findById(1L)
    }
}
