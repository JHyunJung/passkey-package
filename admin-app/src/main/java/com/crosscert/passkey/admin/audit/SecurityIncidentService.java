package com.crosscert.passkey.admin.audit;

import com.crosscert.passkey.core.alert.SecurityAlertEvent;
import com.crosscert.passkey.core.entity.SecurityIncident;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.SecurityIncidentRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * audit chain 위변조 incident 의 생성/해결/목록 관리.
 *
 * <p>생성 시 {@link AuditChainVerifier} 로 위변조를 재검증(위조 요청으로 가짜 incident
 * 를 만드는 것을 차단)하고, 테넌트당 OPEN 1건 제약은 선체크 + V50 부분 유니크 인덱스로
 * 이중 방어한다. 생성/해결 모두 audit 로그를 남기며, 생성 시 CRITICAL
 * {@link SecurityAlertEvent} 를 발행한다.
 *
 * <p>전 테넌트 incident 를 단일 목록으로 조회한다(전역 조회). incident 는 PLATFORM_OPERATOR
 * 전용 플랫폼 테이블이며, 컨트롤러가 {@code @PreAuthorize("hasRole('PLATFORM_OPERATOR')")}
 * 로 RP_ADMIN 노출 경로를 차단한다.
 */
@Service
public class SecurityIncidentService {

    private final SecurityIncidentRepository repo;
    private final AuditChainVerifier verifier;
    private final AuditLogService audit;
    private final ApplicationEventPublisher events;
    private final TenantRepository tenants;
    private final Clock clock;

    public SecurityIncidentService(SecurityIncidentRepository repo,
                                   AuditChainVerifier verifier,
                                   AuditLogService audit,
                                   ApplicationEventPublisher events,
                                   TenantRepository tenants,
                                   Clock clock) {
        this.repo = repo;
        this.verifier = verifier;
        this.audit = audit;
        this.events = events;
        this.tenants = tenants;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<SecurityIncident> list() {
        return repo.findAllByOrderByCreatedAtDesc();
    }

    @Transactional
    public SecurityIncident create(UUID tenantId, UUID tamperedEntryId,
                                   UUID actorId, String actorEmail) {
        // 1. 위변조 실재 재검증 — 위조 요청으로 가짜 incident 생성 방지.
        AuditChainVerifier.TenantResult r = verifier.verifyTenant(tenantId);
        if (r.ok()) {
            throw new IncidentNotTamperedException(
                    "tenant chain is intact; cannot create incident: " + tenantId);
        }
        // 2. 테넌트당 OPEN 1건 선체크(친절한 409). DB 유니크 인덱스가 최종 방어.
        if (repo.existsByTenantIdAndStatus(tenantId, SecurityIncident.STATUS_OPEN)) {
            throw new IncidentConflictException("open incident already exists for tenant: " + tenantId);
        }
        // 3. 스냅샷 detail + 저장
        String tenantName = tenants.findById(tenantId).map(Tenant::getDisplayName).orElse(tenantId.toString());
        String detail = "{\"tenantName\":\"" + tenantName.replace("\"", "\\\"")
                + "\",\"tamperedEntryId\":\"" + (tamperedEntryId == null ? "" : tamperedEntryId) + "\"}";
        OffsetDateTime now = OffsetDateTime.now(clock);
        SecurityIncident incident = SecurityIncident.open(tenantId, tamperedEntryId, detail, actorId, now);
        try {
            repo.save(incident);
        } catch (DataIntegrityViolationException e) {
            // 부분 유니크 인덱스 위반(동시 생성 경쟁) → 409 로 변환.
            throw new IncidentConflictException("open incident already exists for tenant: " + tenantId);
        }
        // 4. audit append
        audit.append(new AuditAppendRequest(
                actorId, actorEmail, "AUDIT_CHAIN_INCIDENT_CREATED",
                "SECURITY_INCIDENT", incident.getId().toString(), tenantId,
                Map.of("tamperedEntryId", tamperedEntryId == null ? "" : tamperedEntryId.toString())));
        // 5. CRITICAL 알림
        events.publishEvent(new SecurityAlertEvent(
                SecurityAlertEvent.AlertType.AUDIT_CHAIN_TAMPERING,
                SecurityAlertEvent.Severity.CRITICAL,
                "audit chain incident created",
                Map.of("tenantId", tenantId.toString(),
                       "incidentId", incident.getId().toString())));
        return incident;
    }

    @Transactional
    public SecurityIncident resolve(UUID incidentId, String note, UUID actorId, String actorEmail) {
        // 0. note 검증(서비스 레이어 가드) — blank 면 ck_security_incident_resolution(RESOLVED 시
        //    resolution_note NOT NULL)이 flush 에서 터지므로 선제 차단. 컨트롤러 @NotBlank 와 이중 방어.
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("resolution note must not be blank");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        // 1. 조건부 원자 UPDATE — OPEN 일 때만 전이. 동시 resolve race 를 DB 레벨에서 직렬화.
        int updated = repo.resolveIfOpen(incidentId, actorId, note, now);
        if (updated == 0) {
            // 이미 RESOLVED 됐거나 없음 → race 패자도 안전하게 여기로.
            throw new IncidentConflictException("no open incident with id: " + incidentId);
        }
        // 2. 전이된 행을 다시 읽어 audit append + 반환에 쓴다.
        SecurityIncident incident = repo.findById(incidentId)
                .orElseThrow(() -> new IncidentConflictException("no open incident with id: " + incidentId));
        audit.append(new AuditAppendRequest(
                actorId, actorEmail, "AUDIT_CHAIN_INCIDENT_RESOLVED",
                "SECURITY_INCIDENT", incident.getId().toString(), incident.getTenantId(),
                Map.of("note", note)));
        return incident;
    }
}
