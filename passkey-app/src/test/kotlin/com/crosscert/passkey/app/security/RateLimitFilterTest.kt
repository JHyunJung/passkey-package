package com.crosscert.passkey.app.security

import io.github.bucket4j.BucketConfiguration
import io.github.bucket4j.distributed.BucketProxy
import io.github.bucket4j.distributed.proxy.ProxyManager
import io.github.bucket4j.distributed.proxy.RemoteBucketBuilder
import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.function.Supplier

class RateLimitFilterTest {

    private val proxy: ProxyManager<String> = mock()
    private val builder: RemoteBucketBuilder<String> = mock()
    private val filter = RateLimitFilter(proxy)

    /** Map of bucket key → returned BucketProxy mock; populated per test. */
    private val bucketsByKey = linkedMapOf<String, BucketProxy>()

    @BeforeEach
    fun setUp() {
        whenever(proxy.builder()).thenReturn(builder)
        whenever(builder.build(any<String>(), any<Supplier<BucketConfiguration>>()))
            .thenAnswer { bucketsByKey[it.getArgument(0, String::class.java)] }
    }

    private fun stubBucket(key: String, allows: Boolean): BucketProxy {
        val bucket: BucketProxy = mock()
        whenever(bucket.tryConsume(1L)).thenReturn(allows)
        bucketsByKey[key] = bucket
        return bucket
    }

    private fun request(method: String, path: String, apiKey: String, remoteAddr: String = "127.0.0.1") =
        MockHttpServletRequest(method, path).apply {
            servletPath = path
            this.remoteAddr = remoteAddr
            addHeader("X-API-Key", apiKey)
        }

    @Test
    fun allowsWhenBothIpAndKeyBucketsHaveTokens() {
        // IP bucket key + Key bucket key (both must allow).
        val ipKey = "ratelimit:/api/v1/rp/registration/start:ip:127.0.0.1"
        val keyKey = "ratelimit:/api/v1/rp/registration/start:key:pk_abcd1234"
        stubBucket(ipKey, true)
        stubBucket(keyKey, true)

        val req = request("POST", "/api/v1/rp/registration/start", "pk_abcd1234SECRETSECRETSECRET")
        val res = MockHttpServletResponse()
        val chain: FilterChain = mock()

        filter.doFilter(req, res, chain)

        verify(chain).doFilter(req, res)
        assertThat(res.status).isEqualTo(200)
    }

    @Test
    fun rejectsWith429WhenIpBucketEmpty() {
        // IP bucket exhausted — even a valid key cannot pass. This is
        // the codex P1 fix: prefix-spray attacks are dropped here
        // before they can create per-prefix buckets.
        val ipKey = "ratelimit:/api/v1/rp/registration/start:ip:9.9.9.9"
        stubBucket(ipKey, false)

        val req = request("POST", "/api/v1/rp/registration/start", "pk_abcd1234SECRETSECRETSECRET", "9.9.9.9")
        val res = MockHttpServletResponse()
        val chain: FilterChain = mock()

        filter.doFilter(req, res, chain)

        assertThat(res.status).isEqualTo(429)
        assertThat(res.getHeader("Retry-After")).isEqualTo("60")
        verify(chain, never()).doFilter(anyOrNull(), anyOrNull())
    }

    @Test
    fun rejectsWith429WhenKeyBucketEmpty() {
        val ipKey = "ratelimit:/api/v1/rp/registration/start:ip:127.0.0.1"
        val keyKey = "ratelimit:/api/v1/rp/registration/start:key:pk_abcd1234"
        stubBucket(ipKey, true)
        stubBucket(keyKey, false)

        val req = request("POST", "/api/v1/rp/registration/start", "pk_abcd1234SECRETSECRETSECRET")
        val res = MockHttpServletResponse()
        val chain: FilterChain = mock()

        filter.doFilter(req, res, chain)

        assertThat(res.status).isEqualTo(429)
        assertThat(res.contentType).isEqualTo("application/problem+json")
        assertThat(res.contentAsString).contains("\"status\":429")
        verify(chain, never()).doFilter(anyOrNull(), anyOrNull())
    }

    @Test
    fun malformedHeaderRoutesToAnonBucketNotPerPrefix() {
        // Header doesn't start with pk_ — should use the anon-IP key
        // bucket, not get a fresh per-header bucket (codex P1 hardening).
        val ipKey = "ratelimit:/api/v1/rp/registration/start:ip:127.0.0.1"
        val anonKey = "ratelimit:/api/v1/rp/registration/start:key:anon-127.0.0.1"
        stubBucket(ipKey, true)
        stubBucket(anonKey, true)

        // Long enough to bypass the length check but not the pk_ prefix check.
        val req = request("POST", "/api/v1/rp/registration/start", "garbage_payload_here_not_pk_prefix")
        val res = MockHttpServletResponse()
        val chain: FilterChain = mock()

        filter.doFilter(req, res, chain)

        verify(chain).doFilter(req, res)
        // Crucially: NO bucket built for "ratelimit:...:key:garbage_pay..."
        // The only key buckets consumed are the well-known anon-IP one
        // and the IP bucket.
    }

    @Test
    fun credentialsDeleteUsesLowerTierBucketAndRejectsWith429() {
        // P0-4: DELETE /credentials/{id} is rate-limited via the prefix rule,
        // keyed on the ":DELETE" scope (not the raw path-variable URI). When
        // its key bucket is exhausted the request is rejected with 429.
        val ipKey = "ratelimit:/api/v1/rp/credentials:DELETE:ip:127.0.0.1"
        val keyKey = "ratelimit:/api/v1/rp/credentials:DELETE:key:pk_abcd1234"
        stubBucket(ipKey, true)
        stubBucket(keyKey, false)

        val req = request("DELETE", "/api/v1/rp/credentials/AAEC", "pk_abcd1234SECRETSECRETSECRET")
        val res = MockHttpServletResponse()
        val chain: FilterChain = mock()

        filter.doFilter(req, res, chain)

        assertThat(res.status).isEqualTo(429)
        verify(chain, never()).doFilter(anyOrNull(), anyOrNull())
    }

    @Test
    fun credentialsListIsRateLimitedAndPassesWhenBucketsAllow() {
        // P0-4: GET /credentials (list) is matched by the prefix rule on the
        // default ":/api/v1/rp/credentials" scope (not the ":DELETE" tier) and
        // passes when both buckets have tokens.
        val ipKey = "ratelimit:/api/v1/rp/credentials:ip:127.0.0.1"
        val keyKey = "ratelimit:/api/v1/rp/credentials:key:pk_abcd1234"
        stubBucket(ipKey, true)
        stubBucket(keyKey, true)

        val req = request("GET", "/api/v1/rp/credentials", "pk_abcd1234SECRETSECRETSECRET")
        val res = MockHttpServletResponse()
        val chain: FilterChain = mock()

        filter.doFilter(req, res, chain)

        verify(chain).doFilter(req, res)
        assertThat(res.status).isEqualTo(200)
    }

    @Test
    fun doesNotFilterUnknownPath() {
        val req = MockHttpServletRequest("GET", "/.well-known/jwks.json").apply {
            servletPath = "/.well-known/jwks.json"
        }
        val res = MockHttpServletResponse()
        val chain: FilterChain = mock()

        filter.doFilter(req, res, chain)

        verify(chain).doFilter(req, res)
        verify(proxy, never()).builder()
    }
}
