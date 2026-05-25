package com.crosscert.passkey.app.security;

import com.crosscert.passkey.core.vpd.TenantContextHolder;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiKeyAuthFilterTest {

    private ApiKeyLookupService lookup;
    private PasswordEncoder encoder;
    private ApiKeyAuthFilter filter;

    @BeforeEach
    void setup() {
        lookup = mock(ApiKeyLookupService.class);
        encoder = new BCryptPasswordEncoder(4); // low cost for test speed
        filter = new ApiKeyAuthFilter(lookup, encoder);
    }

    @AfterEach
    void cleanup() {
        TenantContextHolder.clear();
    }

    @Test
    void rejectsRequestWithoutHeader() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/rp/anything");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
        assertThat(TenantContextHolder.get()).isNull();
    }

    @Test
    void rejectsHeaderWithoutPkPrefix() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/rp/x");
        req.addHeader("X-API-Key", "AAAAAAAA_some_random_secret_value_here");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);
        assertThat(res.getStatus()).isEqualTo(401);
        // No DB call for malformed prefix.
        verify(lookup, never()).findByPrefix(any());
    }

    @Test
    void rejectsUnknownPrefix() throws Exception {
        when(lookup.findByPrefix("pk_unknwn")).thenReturn(Optional.empty());

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/rp/x");
        req.addHeader("X-API-Key", "pk_unknwnSECRETSECRETSECRET");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);
        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    void rejectsWrongSecret() throws Exception {
        String fullKey = "pk_abcd1234SECRETPART01234567890ABCDEF";
        String prefix = "pk_abcd1234";
        String hash = encoder.encode("DIFFERENT_SECRET");
        when(lookup.findByPrefix(prefix)).thenReturn(Optional.of(
                new ApiKeyLookupService.ApiKeyAuthRow(1L, "T_A", hash, null, null)));

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/rp/x");
        req.addHeader("X-API-Key", fullKey);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);
        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    void acceptsValidKeyAndSetsTenantContextDuringChain() throws Exception {
        String secret = "SECRET_PART_OK_01234567890ABCDEF";
        String fullKey = "pk_abcd1234" + secret;
        String prefix = "pk_abcd1234";
        String hash = encoder.encode(secret);
        when(lookup.findByPrefix(prefix)).thenReturn(Optional.of(
                new ApiKeyLookupService.ApiKeyAuthRow(7L, "T_A", hash, null, null)));

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/rp/x");
        req.addHeader("X-API-Key", fullKey);
        MockHttpServletResponse res = new MockHttpServletResponse();
        String[] tenantSeenInChain = new String[1];
        FilterChain chain = (r, s) -> tenantSeenInChain[0] = TenantContextHolder.get();

        filter.doFilter(req, res, chain);

        assertThat(tenantSeenInChain[0]).isEqualTo("T_A");
        assertThat(TenantContextHolder.get()).isNull();
        verify(lookup).touchLastUsed(eq(7L), any(Instant.class));
    }

    @Test
    void rejectsRevokedKey() throws Exception {
        String secret = "SECRET";
        String prefix = "pk_revoked0";
        when(lookup.findByPrefix(prefix)).thenReturn(Optional.of(
                new ApiKeyLookupService.ApiKeyAuthRow(
                        99L, "T_A", encoder.encode(secret),
                        /* expiresAt */ null,
                        /* revokedAt */ Instant.now().minusSeconds(60))));

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/rp/x");
        req.addHeader("X-API-Key", prefix + secret);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);
        assertThat(res.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void rejectsExpiredKey() throws Exception {
        String secret = "SECRET";
        String prefix = "pk_expired0";
        when(lookup.findByPrefix(prefix)).thenReturn(Optional.of(
                new ApiKeyLookupService.ApiKeyAuthRow(
                        100L, "T_A", encoder.encode(secret),
                        /* expiresAt */ Instant.now().minusSeconds(60),
                        /* revokedAt */ null)));

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/rp/x");
        req.addHeader("X-API-Key", prefix + secret);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);
        assertThat(res.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }
}
