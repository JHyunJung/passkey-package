package com.crosscert.passkey.admin.policy;

import com.crosscert.passkey.core.entity.SecurityPolicy;
import com.crosscert.passkey.core.repository.SecurityPolicyRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class SecurityPolicyService {

    private static final Long SINGLETON_ID = 1L;

    private final SecurityPolicyRepository repo;
    private final ObjectMapper objectMapper;

    public SecurityPolicyService(SecurityPolicyRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public SecurityPolicyDto.View get() {
        SecurityPolicy p = repo.findById(SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("security_policy singleton row missing — V31 not applied?"));
        return toView(p);
    }

    @Transactional
    public SecurityPolicyDto.View update(SecurityPolicyDto.UpdateRequest req, String updatedBy) {
        SecurityPolicy p = repo.findById(SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("security_policy singleton row missing — V31 not applied?"));
        p.setSessionIdleTimeoutMinutes(req.sessionIdleTimeoutMinutes());
        p.setPasswordMinLength(req.passwordMinLength());
        p.setMfaRequired(Boolean.TRUE.equals(req.mfaRequired()));
        p.setCorsAllowlistJson(serialize(req.corsAllowlist()));
        p.setUpdatedAt(Instant.now());
        p.setUpdatedBy(updatedBy);
        repo.save(p);
        return toView(p);
    }

    private SecurityPolicyDto.View toView(SecurityPolicy p) {
        return new SecurityPolicyDto.View(
                p.getSessionIdleTimeoutMinutes(),
                p.getPasswordMinLength(),
                p.isMfaRequired(),
                deserialize(p.getCorsAllowlistJson()),
                p.getUpdatedAt(),
                p.getUpdatedBy()
        );
    }

    private String serialize(List<String> list) {
        try {
            return objectMapper.writeValueAsString(list == null ? List.of() : list);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("serialize cors_allowlist failed", e);
        }
    }

    private List<String> deserialize(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("deserialize cors_allowlist failed (corrupt JSON: " + json + ")", e);
        }
    }
}
