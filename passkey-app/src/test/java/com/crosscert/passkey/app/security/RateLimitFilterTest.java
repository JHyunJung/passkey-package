package com.crosscert.passkey.app.security;

import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.distributed.proxy.RemoteBucketBuilder;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RateLimitFilterTest {

    @SuppressWarnings("unchecked")
    private final ProxyManager<String> proxy = mock(ProxyManager.class);
    @SuppressWarnings("unchecked")
    private final RemoteBucketBuilder<String> builder = mock(RemoteBucketBuilder.class);
    private RateLimitFilter filter;

    /** Map of bucket key → returned BucketProxy mock; populated per test. */
    private final Map<String, BucketProxy> bucketsByKey = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter(proxy);
        when(proxy.builder()).thenReturn(builder);
        when(builder.build(anyString(), any(Supplier.class)))
                .thenAnswer(inv -> bucketsByKey.get(inv.getArgument(0, String.class)));
    }

    private BucketProxy stubBucket(String key, boolean allows) {
        BucketProxy bucket = mock(BucketProxy.class);
        when(bucket.tryConsume(1L)).thenReturn(allows);
        bucketsByKey.put(key, bucket);
        return bucket;
    }

    @Test
    void allowsWhenBothIpAndKeyBucketsHaveTokens() throws Exception {
        // IP bucket key + Key bucket key (both must allow).
        String ipKey = "ratelimit:/api/v1/rp/registration/start:ip:127.0.0.1";
        String keyKey = "ratelimit:/api/v1/rp/registration/start:key:pk_abcd1234";
        stubBucket(ipKey, true);
        stubBucket(keyKey, true);

        MockHttpServletRequest req = new MockHttpServletRequest("POST",
                "/api/v1/rp/registration/start");
        req.setServletPath("/api/v1/rp/registration/start");
        req.setRemoteAddr("127.0.0.1");
        req.addHeader("X-API-Key", "pk_abcd1234SECRETSECRETSECRET");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsWith429WhenIpBucketEmpty() throws Exception {
        // IP bucket exhausted — even a valid key cannot pass. This is
        // the codex P1 fix: prefix-spray attacks are dropped here
        // before they can create per-prefix buckets.
        String ipKey = "ratelimit:/api/v1/rp/registration/start:ip:9.9.9.9";
        stubBucket(ipKey, false);

        MockHttpServletRequest req = new MockHttpServletRequest("POST",
                "/api/v1/rp/registration/start");
        req.setServletPath("/api/v1/rp/registration/start");
        req.setRemoteAddr("9.9.9.9");
        req.addHeader("X-API-Key", "pk_abcd1234SECRETSECRETSECRET");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(429);
        assertThat(res.getHeader("Retry-After")).isEqualTo("60");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void rejectsWith429WhenKeyBucketEmpty() throws Exception {
        String ipKey = "ratelimit:/api/v1/rp/registration/start:ip:127.0.0.1";
        String keyKey = "ratelimit:/api/v1/rp/registration/start:key:pk_abcd1234";
        stubBucket(ipKey, true);
        stubBucket(keyKey, false);

        MockHttpServletRequest req = new MockHttpServletRequest("POST",
                "/api/v1/rp/registration/start");
        req.setServletPath("/api/v1/rp/registration/start");
        req.setRemoteAddr("127.0.0.1");
        req.addHeader("X-API-Key", "pk_abcd1234SECRETSECRETSECRET");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(429);
        assertThat(res.getContentType()).isEqualTo("application/problem+json");
        assertThat(res.getContentAsString()).contains("\"status\":429");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void malformedHeaderRoutesToAnonBucketNotPerPrefix() throws Exception {
        // Header doesn't start with pk_ — should use the anon-IP key
        // bucket, not get a fresh per-header bucket (codex P1 hardening).
        String ipKey = "ratelimit:/api/v1/rp/registration/start:ip:127.0.0.1";
        String anonKey = "ratelimit:/api/v1/rp/registration/start:key:anon-127.0.0.1";
        stubBucket(ipKey, true);
        stubBucket(anonKey, true);

        MockHttpServletRequest req = new MockHttpServletRequest("POST",
                "/api/v1/rp/registration/start");
        req.setServletPath("/api/v1/rp/registration/start");
        req.setRemoteAddr("127.0.0.1");
        // Long enough to bypass the length check but not the pk_ prefix check.
        req.addHeader("X-API-Key", "garbage_payload_here_not_pk_prefix");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
        // Crucially: NO bucket built for "ratelimit:...:key:garbage_pay..."
        // The only key buckets consumed are the well-known anon-IP one
        // and the IP bucket.
    }

    @Test
    void doesNotFilterUnknownPath() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET",
                "/.well-known/jwks.json");
        req.setServletPath("/.well-known/jwks.json");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
        verify(proxy, never()).builder();
    }
}
