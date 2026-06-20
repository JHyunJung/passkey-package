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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TenantAdminService {

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
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<TenantAdminDto.TenantView> list() {
        return tenantBoundary.currentTenantScope()
                .map(tid -> tenants.findById(tid)
                        .map(this::toView)
                        .map(java.util.List::of)
                        .orElseGet(java.util.List::of))
                .orElseGet(this::listAllWithBatchAggregates);
    }

    /**
     * PLATFORM_OPERATOR cross-tenant 목록. KPI 3종(credentials/apiKeys/lastEventAt)을
     * tenant 당 3쿼리(N+1) 대신 GROUP BY 배치 집계 3쿼리로 모은 뒤 toView 에서 lookup.
     * 반환 숫자/필드는 per-tenant 경로와 동일 — UI 표시값 불변.
     */
    private List<TenantAdminDto.TenantView> listAllWithBatchAggregates() {
        Map<UUID, Long> credByTenant =
                toCountMap(credentialRepository.countGroupedByTenantId());
        Map<UUID, Long> activeKeysByTenant =
                toCountMap(apiKeyRepository.countActiveGroupedByTenantId(clock.instant()));
        Map<UUID, Instant> lastEventByTenant =
                toInstantMap(auditLogRepository.findLatestCreatedAtGroupedByTenantId());

        return tenants.findAll().stream()
                .map(t -> toView(t,
                        credByTenant.getOrDefault(t.getId(), 0L),
                        activeKeysByTenant.getOrDefault(t.getId(), 0L),
                        lastEventByTenant.get(t.getId())))
                .toList();
    }

    private static Map<UUID, Long> toCountMap(List<Object[]> rows) {
        Map<UUID, Long> m = new HashMap<>();
        for (Object[] r : rows) {
            m.put((UUID) r[0], ((Number) r[1]).longValue());
        }
        return m;
    }

    private static Map<UUID, Instant> toInstantMap(List<Object[]> rows) {
        Map<UUID, Instant> m = new HashMap<>();
        for (Object[] r : rows) {
            m.put((UUID) r[0], (Instant) r[1]);
        }
        return m;
    }

    @Transactional(readOnly = true)
    public TenantAdminDto.TenantView get(String idOrSlug) {
        Tenant t = lookup(idOrSlug);
        tenantBoundary.assertCanAccessTenant(t.getId());
        return toView(t);
    }

    /**
     * 단건 경로(get/create/update)용 — per-tenant 쿼리 3개로 KPI 집계.
     * list() 는 listAllWithBatchAggregates() 가 배치 집계 후 아래 오버로드를 직접 호출.
     */
    private TenantAdminDto.TenantView toView(Tenant t) {
        long credentials = credentialRepository.countByTenantId(t.getId());
        long apiKeys = apiKeyRepository.countActiveByTenantId(t.getId(), clock.instant());
        Instant lastEventAt = auditLogRepository
                .findFirstByTenantIdOrderByCreatedAtDesc(t.getId())
                .map(AuditLog::getCreatedAt)
                .orElse(null);
        return toView(t, credentials, apiKeys, lastEventAt);
    }

    private TenantAdminDto.TenantView toView(Tenant t, long credentials, long apiKeys, Instant lastEventAt) {
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

        validateOriginFormats(req.allowedOrigins());
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

        log.info("tenant created: slug={} id={} rpId={}",
                req.slug(), t.getId(), req.rpId());

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
        validateOriginFormats(req.allowedOrigins());
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

    /**
     * 등록/수정 시 allowedOrigin 형식을 DB 도달 전에 검증 — 위반 시 400 fail-fast.
     * 웹 origin 또는 android:apk-key-hash origin 만 허용 (AllowedOriginFormat).
     */
    private static void validateOriginFormats(List<String> origins) {
        for (String origin : origins) {
            if (!AllowedOriginFormat.isValid(origin)) {
                throw new BusinessException(ErrorCode.INVALID_INPUT);
            }
        }
    }
}
