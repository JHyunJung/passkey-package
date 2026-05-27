package com.crosscert.passkey.admin.tenant;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.admin.auth.TenantBoundary;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.TenantRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class TenantAdminService {

    private static final Logger log = LoggerFactory.getLogger(TenantAdminService.class);

    private final TenantRepository tenants;
    private final AuditLogService audit;
    private final EntityManager em;
    private final TenantBoundary tenantBoundary;

    public TenantAdminService(TenantRepository tenants,
                              AuditLogService audit,
                              EntityManager em,
                              TenantBoundary tenantBoundary) {
        this.tenants = tenants;
        this.audit = audit;
        this.em = em;
        this.tenantBoundary = tenantBoundary;
    }

    @Transactional(readOnly = true)
    public List<TenantAdminDto.TenantView> list() {
        return tenantBoundary.currentTenantScope()
                .map(tid -> tenants.findById(tid)
                        .map(TenantAdminDto.TenantView::from)
                        .map(java.util.List::of)
                        .orElseGet(java.util.List::of))
                .orElseGet(() -> tenants.findAll().stream()
                        .map(TenantAdminDto.TenantView::from)
                        .toList());
    }

    @Transactional(readOnly = true)
    public TenantAdminDto.TenantView get(String idOrSlug) {
        Tenant t = lookup(idOrSlug);
        tenantBoundary.assertCanAccessTenant(t.getId());
        return TenantAdminDto.TenantView.from(t);
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
                "TENANT", req.slug(),
                t.getId(),
                payload));

        return TenantAdminDto.TenantView.from(t);
    }

    @Transactional
    public TenantAdminDto.TenantView update(String idOrSlug,
                                            TenantAdminDto.TenantUpdateRequest req,
                                            UUID actorId,
                                            String actorEmail) {
        Tenant t = lookup(idOrSlug);
        tenantBoundary.assertCanAccessTenant(t.getId());
        TenantSnapshot before = TenantSnapshot.of(t);

        // rpId / slug 는 silent ignore (별도 워크플로우 필요 — spec § 6.1)
        if (req.rpId() != null && !req.rpId().equals(t.getRpId())) {
            log.debug("rpId update ignored — not yet implemented (tenant={} from={} to={})",
                    t.getId(), t.getRpId(), req.rpId());
        }

        t.setDisplayName(req.displayName());
        t.setRpName(req.rpName());
        replaceAllowedOrigins(t, req.allowedOrigins());
        replaceAcceptedFormats(t, req.acceptedFormats());
        t.setRequireUserVerification(req.requireUserVerification());
        t.setMdsRequired(req.mdsRequired());

        tenants.saveAndFlush(t);

        TenantSnapshot after = TenantSnapshot.of(t);
        List<String> changed = before.diff(after);

        if (!changed.isEmpty()) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("before", before);
            payload.put("after", after);
            payload.put("changedFields", changed);
            audit.append(new AuditAppendRequest(
                    actorId, actorEmail, "TENANT_UPDATE",
                    "TENANT", t.getId().toString(),
                    t.getId(),
                    payload));
            log.info("tenant updated id={} slug={} changed={} actor={}",
                    t.getId(), t.getSlug(), changed, actorEmail);
        } else {
            log.debug("tenant update no-op id={} slug={} actor={}",
                    t.getId(), t.getSlug(), actorEmail);
        }

        return TenantAdminDto.TenantView.from(t);
    }

    private void replaceAllowedOrigins(Tenant t, List<String> origins) {
        t.clearAllowedOrigins();
        // Flush the orphan DELETEs before re-inserting — Oracle's unique constraint
        // on (tenant_id, origin, sort_order) would otherwise see both DELETE and INSERT
        // in the same batch with INSERTs ordered before DELETEs.
        em.flush();
        int order = 0;
        for (String origin : origins) {
            t.addAllowedOrigin(origin, order++);
        }
    }

    private void replaceAcceptedFormats(Tenant t, java.util.Set<String> formats) {
        t.clearAcceptedFormats();
        // Flush the orphan DELETEs before re-inserting — Oracle's unique constraint
        // UQ_TAF_TENANT_FORMAT(tenant_id, format) would otherwise be violated when
        // Hibernate batches INSERTs before DELETEs in the same flush cycle.
        em.flush();
        for (String format : formats) {
            t.addAcceptedFormat(format);
        }
    }

    /**
     * idOrSlug 의 UUID 파싱 시도 후 실패하면 slug 로 조회.
     * audit log payload 의 changedFields 키로 직렬화.
     */
    private Tenant lookup(String idOrSlug) {
        // Try slug first (operator-friendly URL pattern: /admin/api/tenants/acme)
        Optional<Tenant> bySlug = tenants.findBySlug(idOrSlug);
        if (bySlug.isPresent()) {
            return bySlug.get();
        }
        // UUID fallback (direct API call with UUID string)
        try {
            UUID id = UUID.fromString(idOrSlug);
            return tenants.findById(id)
                    .orElseThrow(() -> new BusinessException(ErrorCode.TENANT_NOT_FOUND));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.TENANT_NOT_FOUND);
        }
    }
}
