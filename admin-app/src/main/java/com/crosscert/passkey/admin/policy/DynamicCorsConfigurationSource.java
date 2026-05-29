package com.crosscert.passkey.admin.policy;

import com.crosscert.passkey.core.entity.SecurityPolicy;
import com.crosscert.passkey.core.repository.SecurityPolicyRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.List;

/** P0-6: security_policy.cors_allowlist 를 실제 CORS 정책으로 적용. */
@Component
public class DynamicCorsConfigurationSource implements CorsConfigurationSource {

    private static final Long SINGLETON_ID = 1L;
    private final SecurityPolicyRepository repo;
    private final ObjectMapper mapper;

    public DynamicCorsConfigurationSource(SecurityPolicyRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    @Override
    @Transactional(readOnly = true)
    public CorsConfiguration getCorsConfiguration(HttpServletRequest request) {
        List<String> allow = repo.findById(SINGLETON_ID)
                .map(SecurityPolicy::getCorsAllowlistJson)
                .map(this::parse)
                .orElse(List.of());
        if (allow.isEmpty()) {
            return null; // CORS 비활성 (same-origin 만 허용)
        }
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(allow);
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setAllowCredentials(true);
        return cfg;
    }

    private List<String> parse(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return mapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
