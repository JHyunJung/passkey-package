package com.crosscert.passkey.admin.tenant;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.admin.auth.TenantBoundary;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import com.crosscert.passkey.core.entity.AuditLog;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.entity.TenantAaguidPolicy;
import com.crosscert.passkey.core.entity.TenantWebauthnSnapshot;
import com.crosscert.passkey.core.repository.ApiKeyRepository;
import com.crosscert.passkey.core.repository.AuditLogRepository;
import com.crosscert.passkey.core.repository.CredentialRepository;
import com.crosscert.passkey.core.repository.TenantAaguidPolicyRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import com.crosscert.passkey.core.repository.TenantWebauthnSnapshotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
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
    private final TenantAaguidPolicyRepository aaguidPolicyRepo;
    private final TenantWebauthnSnapshotRepository snapshotRepo;
    private final CredentialRepository credentialRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public TenantAdminService(TenantRepository tenants,
                              AuditLogService audit,
                              EntityManager em,
                              TenantBoundary tenantBoundary,
                              TenantAaguidPolicyRepository aaguidPolicyRepo,
                              TenantWebauthnSnapshotRepository snapshotRepo,
                              CredentialRepository credentialRepository,
                              ApiKeyRepository apiKeyRepository,
                              AuditLogRepository auditLogRepository,
                              ObjectMapper objectMapper) {
        this.tenants = tenants;
        this.audit = audit;
        this.em = em;
        this.tenantBoundary = tenantBoundary;
        this.aaguidPolicyRepo = aaguidPolicyRepo;
        this.snapshotRepo = snapshotRepo;
        this.credentialRepository = credentialRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<TenantAdminDto.TenantView> list() {
        return tenantBoundary.currentTenantScope()
                .map(tid -> tenants.findById(tid)
                        .map(this::toView)
                        .map(java.util.List::of)
                        .orElseGet(java.util.List::of))
                .orElseGet(() -> tenants.findAll().stream()
                        .map(this::toView)
                        .toList());
    }

    @Transactional(readOnly = true)
    public TenantAdminDto.TenantView get(String idOrSlug) {
        Tenant t = lookup(idOrSlug);
        tenantBoundary.assertCanAccessTenant(t.getId());
        return toView(t);
    }

    /**
     * Phase F2 — TenantView 변환을 한 곳에서 처리. KPI 3종 (credentials/apiKeys/
     * lastEventAt) 을 per-tenant 로 집계한다.
     *
     * <p>NOTE: list() 호출 시 N+1 — tenant 수만큼 3개의 추가 쿼리가 나간다. 현재
     * 시드된 tenant 수는 ≤4 (demo-rp + IT seeds) 이므로 허용 가능. 향후 cross-tenant
     * 환경에서 tenant 수가 늘면 단일 GROUP BY 쿼리로 묶거나 별도 dashboard 캐싱이
     * 필요하다 — 후속 Task 에서 처리.
     */
    private TenantAdminDto.TenantView toView(Tenant t) {
        long credentials = credentialRepository.countByTenantId(t.getId());
        long apiKeys = apiKeyRepository.countActiveByTenantId(t.getId(), Instant.now());
        Instant lastEventAt = auditLogRepository
                .findFirstByTenantIdOrderByCreatedAtDesc(t.getId())
                .map(AuditLog::getCreatedAt)
                .orElse(null);
        return TenantAdminDto.TenantView.from(t, credentials, apiKeys, lastEventAt);
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
        t.setAttestationConveyance(req.attestationConveyance());
        t.setWebauthnTimeoutMs(req.webauthnTimeoutMs());

        int order = 0;
        for (String origin : req.allowedOrigins()) {
            t.addAllowedOrigin(origin, order++);
        }
        for (String format : req.acceptedFormats()) {
            t.addAcceptedFormat(format);
        }

        tenants.saveAndFlush(t);

        // AAGUID Policy 기본값 (ANY, mdsStrict=false) 자동 INSERT
        aaguidPolicyRepo.save(new TenantAaguidPolicy(
                t.getId(),
                TenantAaguidPolicy.Mode.ANY,
                false,
                "system:create"
        ));

        // 초기 WebAuthn snapshot
        try {
            String originsJson = objectMapper.writeValueAsString(t.getAllowedOriginValues());
            String formatsJson = objectMapper.writeValueAsString(new ArrayList<>(t.getAcceptedFormatValues()));
            snapshotRepo.save(new TenantWebauthnSnapshot(
                    t.getId(),
                    t.getRpId(), t.getRpName(),
                    originsJson, formatsJson,
                    t.isRequireUserVerification(), t.isMdsRequired(),
                    "system:create"
            ));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to snapshot on tenant create", e);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("slug", req.slug());
        payload.put("displayName", req.displayName());
        payload.put("rpId", req.rpId());
        audit.append(new AuditAppendRequest(
                actorId, actorEmail, "TENANT_CREATE",
                "TENANT", req.slug(),
                t.getId(),
                payload));

        return toView(t);
    }

    @Transactional
    public TenantAdminDto.TenantView update(String idOrSlug,
                                            TenantAdminDto.TenantUpdateRequest req,
                                            UUID actorId,
                                            String actorEmail) {
        Tenant t = lookup(idOrSlug);
        tenantBoundary.assertCanAccessTenant(t.getId());
        TenantSnapshot before = TenantSnapshot.of(t);

        // 변경 직전 값을 snapshot 으로 보존
        try {
            snapshotRepo.save(new TenantWebauthnSnapshot(
                    t.getId(),
                    t.getRpId(), t.getRpName(),
                    objectMapper.writeValueAsString(t.getAllowedOriginValues()),
                    objectMapper.writeValueAsString(new ArrayList<>(t.getAcceptedFormatValues())),
                    t.isRequireUserVerification(), t.isMdsRequired(),
                    actorEmail
            ));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to snapshot before update", e);
        }

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
        t.setAttestationConveyance(req.attestationConveyance());
        t.setWebauthnTimeoutMs(req.webauthnTimeoutMs());

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

        return toView(t);
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
