package com.crosscert.passkey.app.security;

import com.crosscert.passkey.core.repository.ApiKeyScopeRepository;
import com.crosscert.passkey.core.vpd.TenantContextHolder;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1-5: 경로 요구 scope 미보유 시 403 (vs 인증 실패 401) 단위 검증.
 * 실제 {@link ApiKeyScopeResolver} + mock lookup/encoder/scopeRepo 로
 * 필터의 scope enforcement 분기만 직접 doFilter 로 검증한다.
 */
class ApiKeyAuthFilterScopeTest {

    private final ApiKeyLookupService lookup = mock(ApiKeyLookupService.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final ApiKeyScopeRepository scopeRepo = mock(ApiKeyScopeRepository.class);
    private final ApiKeyScopeResolver resolver = new ApiKeyScopeResolver();
    private ApiKeyAuthFilter filter;

    private final UUID keyId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();

    // PREFIX_LEN = 11 ("pk_" + 8 chars). secret is the remainder of the header.
    private final String prefix = "pk_abcd1234";
    private final String secret = "SECRETSECRETSECRET";
    private static final String HASH = "HASH";

    @BeforeEach
    void setUp() {
        filter = new ApiKeyAuthFilter(lookup, encoder, scopeRepo, resolver);
        when(lookup.findByPrefix(prefix)).thenReturn(Optional.of(
                new ApiKeyLookupService.ApiKeyAuthRow(keyId, tenantId, HASH, null, null)));
        when(encoder.matches(secret, HASH)).thenReturn(true);
    }

    @AfterEach
    void cleanup() {
        TenantContextHolder.clear();
    }

    private MockHttpServletRequest req(String uri) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", uri);
        r.setServletPath(uri);
        r.setRequestURI(uri);
        r.addHeader("X-API-Key", prefix + secret);
        return r;
    }

    @Test
    void allows_when_key_has_required_scope() throws Exception {
        when(scopeRepo.findScopeValuesByApiKeyId(keyId)).thenReturn(Set.of("registration"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req("/api/v1/rp/registration/start"), res, chain);

        verify(chain).doFilter(any(), any());
        assertThat(TenantContextHolder.get()).isNull();
    }

    @Test
    void forbids_when_key_lacks_required_scope() throws Exception {
        when(scopeRepo.findScopeValuesByApiKeyId(keyId)).thenReturn(Set.of("authentication"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req("/api/v1/rp/registration/start"), res, chain);

        assertThat(res.getStatus()).isEqualTo(403);
        verify(chain, never()).doFilter(any(), any());
        assertThat(TenantContextHolder.get()).isNull();
    }

    @Test
    void forbids_when_key_has_no_scopes() throws Exception {
        when(scopeRepo.findScopeValuesByApiKeyId(keyId)).thenReturn(Set.of());
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req("/api/v1/rp/registration/start"), res, chain);

        assertThat(res.getStatus()).isEqualTo(403);
        verify(chain, never()).doFilter(any(), any());
        assertThat(TenantContextHolder.get()).isNull();
    }

    @Test
    void allows_unmapped_path_without_scope_lookup() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req("/api/v1/rp/other"), res, chain);

        verify(chain).doFilter(any(), any());
        verify(scopeRepo, never()).findScopeValuesByApiKeyId(any());
    }
}
