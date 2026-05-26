package com.crosscert.passkey.admin.tenant;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class TenantAdminService {

    private final TenantRepository tenants;
    private final AuditLogService audit;

    public TenantAdminService(TenantRepository tenants,
                              AuditLogService audit) {
        this.tenants = tenants;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<TenantAdminDto.TenantView> list() {
        return tenants.findAll().stream()
                .map(TenantAdminDto.TenantView::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public TenantAdminDto.TenantView get(String idOrSlug) {
        // Try slug first (operator-friendly URL pattern: /admin/api/tenants/acme)
        Optional<Tenant> bySlug = tenants.findBySlug(idOrSlug);
        if (bySlug.isPresent()) {
            return TenantAdminDto.TenantView.from(bySlug.get());
        }
        // UUID fallback (direct API call with UUID string)
        try {
            UUID asUuid = UUID.fromString(idOrSlug);
            return tenants.findById(asUuid)
                    .map(TenantAdminDto.TenantView::from)
                    .orElseThrow(() -> new BusinessException(ErrorCode.TENANT_NOT_FOUND));
        } catch (IllegalArgumentException invalidUuid) {
            throw new BusinessException(ErrorCode.TENANT_NOT_FOUND);
        }
    }

    @Transactional
    public TenantAdminDto.TenantView create(TenantAdminDto.TenantCreateRequest req,
                                            UUID actorId, String actorEmail) {
        if (tenants.existsBySlug(req.slug())) {
            throw new BusinessException(ErrorCode.TENANT_DUPLICATE);
        }

        Tenant t = new Tenant(req.slug(), req.displayName(), req.rpId(), req.rpName());
        t.setRequireUserVerification(req.requireUserVerification());
        t.setMdsRequired(req.mdsRequired());

        int order = 0;
        for (String origin : req.allowedOrigins()) {
            t.addAllowedOrigin(origin, order++);
        }
        for (String format : req.acceptedFormats()) {
            t.addAcceptedFormat(format);
        }

        tenants.saveAndFlush(t);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("slug", req.slug());
        payload.put("displayName", req.displayName());
        payload.put("rpId", req.rpId());
        audit.append(new AuditAppendRequest(
                actorId, actorEmail, "TENANT_CREATE",
                "TENANT", req.slug(), payload));

        return TenantAdminDto.TenantView.from(t);
    }
}
