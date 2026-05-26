package com.crosscert.passkey.admin.tenant;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.TenantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TenantAdminService {

    private final TenantRepository tenants;
    private final AuditLogService audit;
    private final ObjectMapper mapper;

    public TenantAdminService(TenantRepository tenants,
                              AuditLogService audit,
                              ObjectMapper mapper) {
        this.tenants = tenants;
        this.audit = audit;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<TenantAdminDto.TenantView> list() {
        return tenants.findAll().stream()
                .map(TenantAdminDto.TenantView::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public TenantAdminDto.TenantView get(String id) {
        Tenant t = tenants.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("tenant not found"));
        return TenantAdminDto.TenantView.from(t);
    }

    @Transactional
    public TenantAdminDto.TenantView create(TenantAdminDto.TenantCreateRequest req,
                                            long actorId, String actorEmail) {
        if (tenants.findById(req.id()).isPresent()) {
            throw new IllegalArgumentException("tenant id already exists");
        }
        validateJson(req.allowedOriginsJson(), "allowed_origins");
        validateJson(req.attestationPolicyJson(), "attestation_policy");

        Tenant t = new Tenant(req.id(), req.displayName(), req.rpId(), req.rpName(),
                              req.allowedOriginsJson(), req.attestationPolicyJson());
        tenants.save(t);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", req.id());
        payload.put("displayName", req.displayName());
        payload.put("rpId", req.rpId());
        audit.append(new AuditAppendRequest(
                actorId, actorEmail, "TENANT_CREATE",
                "TENANT", req.id(), payload));

        return TenantAdminDto.TenantView.from(t);
    }

    private void validateJson(String value, String fieldName) {
        try {
            mapper.readTree(value);
        } catch (Exception e) {
            throw new IllegalArgumentException(fieldName + " JSON invalid");
        }
    }
}
