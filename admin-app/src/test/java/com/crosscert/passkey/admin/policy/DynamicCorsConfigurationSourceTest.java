package com.crosscert.passkey.admin.policy;

import com.crosscert.passkey.core.entity.SecurityPolicy;
import com.crosscert.passkey.core.repository.SecurityPolicyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DynamicCorsConfigurationSourceTest {

    private final SecurityPolicyRepository repo = mock(SecurityPolicyRepository.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final DynamicCorsConfigurationSource source =
            new DynamicCorsConfigurationSource(repo, mapper);

    private SecurityPolicy policyWithCors(String json) {
        SecurityPolicy p = mock(SecurityPolicy.class);
        when(p.getCorsAllowlistJson()).thenReturn(json);
        return p;
    }

    @Test
    void absentPolicy_returnsNull() {
        when(repo.findById(1L)).thenReturn(Optional.empty());
        assertThat(source.getCorsConfiguration(null)).isNull();
    }

    @Test
    void emptyAllowlist_returnsNull() {
        SecurityPolicy p = policyWithCors("[]");
        when(repo.findById(1L)).thenReturn(Optional.of(p));
        assertThat(source.getCorsConfiguration(null)).isNull();
    }

    @Test
    void blankAllowlist_returnsNull() {
        SecurityPolicy p = policyWithCors("");
        when(repo.findById(1L)).thenReturn(Optional.of(p));
        assertThat(source.getCorsConfiguration(null)).isNull();
    }

    @Test
    void populatedAllowlist_returnsConfigWithOriginAndCredentials() {
        SecurityPolicy p = policyWithCors("[\"https://app.example.com\"]");
        when(repo.findById(1L)).thenReturn(Optional.of(p));

        CorsConfiguration cfg = source.getCorsConfiguration(null);

        assertThat(cfg).isNotNull();
        assertThat(cfg.getAllowedOrigins()).contains("https://app.example.com");
        assertThat(cfg.getAllowCredentials()).isTrue();
    }

    @Test
    void malformedJson_returnsNull_noCrash() {
        SecurityPolicy p = policyWithCors("not-json");
        when(repo.findById(1L)).thenReturn(Optional.of(p));
        assertThat(source.getCorsConfiguration(null)).isNull();
    }
}
