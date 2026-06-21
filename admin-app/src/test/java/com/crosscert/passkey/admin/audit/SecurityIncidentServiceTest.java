package com.crosscert.passkey.admin.audit;

import com.crosscert.passkey.core.alert.SecurityAlertEvent;
import com.crosscert.passkey.core.entity.SecurityIncident;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.SecurityIncidentRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SecurityIncidentServiceTest {

    SecurityIncidentRepository repo;
    AuditChainVerifier verifier;
    AuditLogService audit;
    ApplicationEventPublisher events;
    TenantRepository tenants;
    Clock clock;
    com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    SecurityIncidentService svc;

    final UUID TENANT = UUID.randomUUID();
    final UUID ENTRY = UUID.randomUUID();
    final UUID ACTOR = UUID.randomUUID();

    @BeforeEach
    void setup() {
        repo = mock(SecurityIncidentRepository.class);
        verifier = mock(AuditChainVerifier.class);
        audit = mock(AuditLogService.class);
        events = mock(ApplicationEventPublisher.class);
        tenants = mock(TenantRepository.class);
        clock = Clock.fixed(Instant.parse("2026-06-21T00:00:00Z"), ZoneOffset.ofHours(9));
        objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        svc = new SecurityIncidentService(repo, verifier, audit, events, tenants, clock, objectMapper);

        Tenant t = mock(Tenant.class);
        when(t.getDisplayName()).thenReturn("Acme Corp");
        when(tenants.findById(TENANT)).thenReturn(Optional.of(t));
        when(repo.saveAndFlush(any(SecurityIncident.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void stubTampered(boolean tampered) {
        // verifyTenant returns a TenantResult record; ok()==true means chain intact.
        AuditChainVerifier.TenantResult r = tampered
                ? AuditChainVerifier.TenantResult.broken(TENANT, ENTRY)
                : AuditChainVerifier.TenantResult.valid(TENANT);
        when(verifier.verifyTenant(TENANT)).thenReturn(r);
    }

    @Test
    void create_whenTampered_savesAuditsAndPublishesAlert() {
        stubTampered(true);
        when(repo.existsByTenantIdAndStatus(TENANT, "OPEN")).thenReturn(false);

        SecurityIncident i = svc.create(TENANT, ENTRY, ACTOR, "alice@crosscert.com");

        assertThat(i.getStatus()).isEqualTo("OPEN");
        assertThat(i.getTenantId()).isEqualTo(TENANT);
        verify(repo).saveAndFlush(any(SecurityIncident.class));
        verify(audit).append(any());
        // 단위 테스트는 트랜잭션 동기화 비활성 → afterCommit 우회 즉시 발행(else 분기).
        verify(events).publishEvent(any(SecurityAlertEvent.class));
    }

    @Test
    void create_whenAlreadyOpen_throwsConflict() {
        stubTampered(true);
        when(repo.existsByTenantIdAndStatus(TENANT, "OPEN")).thenReturn(true);

        assertThatThrownBy(() -> svc.create(TENANT, ENTRY, ACTOR, "alice@crosscert.com"))
                .isInstanceOf(IncidentConflictException.class);
        verify(repo, never()).saveAndFlush(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void create_whenNotActuallyTampered_throwsUnprocessable() {
        stubTampered(false);

        assertThatThrownBy(() -> svc.create(TENANT, ENTRY, ACTOR, "alice@crosscert.com"))
                .isInstanceOf(IncidentNotTamperedException.class);
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    void resolve_whenOpen_setsResolvedFields() {
        SecurityIncident open = SecurityIncident.open(TENANT, ENTRY, "{}", ACTOR,
                OffsetDateTime.now(clock));
        // Atomic conditional UPDATE succeeds (1 row), then we re-read the RESOLVED row.
        when(repo.resolveIfOpen(eq(open.getId()), eq(ACTOR), eq("DBA 복구 완료"), any(OffsetDateTime.class)))
                .thenReturn(1);
        open.resolve(ACTOR, "DBA 복구 완료", OffsetDateTime.now(clock));
        when(repo.findById(open.getId())).thenReturn(Optional.of(open));

        SecurityIncident resolved = svc.resolve(open.getId(), "DBA 복구 완료", ACTOR, "alice@crosscert.com");

        assertThat(resolved.getStatus()).isEqualTo("RESOLVED");
        assertThat(resolved.getResolutionNote()).isEqualTo("DBA 복구 완료");
        assertThat(resolved.getResolvedAt()).isNotNull();
        verify(audit).append(any());
    }

    @Test
    void resolve_whenNotOpen_throwsConflict() {
        UUID id = UUID.randomUUID();
        // Atomic UPDATE affects 0 rows → already RESOLVED or missing (race loser lands here too).
        when(repo.resolveIfOpen(eq(id), eq(ACTOR), eq("note"), any(OffsetDateTime.class)))
                .thenReturn(0);

        assertThatThrownBy(() -> svc.resolve(id, "note", ACTOR, "alice@crosscert.com"))
                .isInstanceOf(IncidentConflictException.class);
        verify(audit, never()).append(any());
    }

    @Test
    void resolve_whenNoteBlank_throwsValidation() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> svc.resolve(id, "  ", id, "alice@crosscert.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> svc.resolve(id, null, id, "alice@crosscert.com"))
                .isInstanceOf(IllegalArgumentException.class);

        verify(repo, never()).resolveIfOpen(any(), any(), any(), any());
        verify(audit, never()).append(any());
    }

    @Test
    void create_whenUniqueIndexViolated_translatesToConflictWithoutSideEffects() {
        // 선체크는 통과(false)했지만 동시 생성 race 에서 DB 부분 유니크 인덱스가 막는 경우:
        // saveAndFlush 가 즉시 위반을 던지고 409 로 변환되며 audit/alert side-effect 가 없어야 한다.
        stubTampered(true);
        when(repo.existsByTenantIdAndStatus(TENANT, "OPEN")).thenReturn(false);
        when(repo.saveAndFlush(any(SecurityIncident.class)))
                .thenThrow(new DataIntegrityViolationException("ux_incident_open_per_tenant"));

        assertThatThrownBy(() -> svc.create(TENANT, ENTRY, ACTOR, "alice@crosscert.com"))
                .isInstanceOf(IncidentConflictException.class);

        verify(audit, never()).append(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void create_whenActorIdNull_throws() {
        // actorId 가드(Objects.requireNonNull → NPE)가 영속화 전에 차단해야 한다.
        assertThatThrownBy(() -> svc.create(TENANT, ENTRY, null, "alice@crosscert.com"))
                .isInstanceOf(NullPointerException.class);
        verify(repo, never()).saveAndFlush(any());
        verify(audit, never()).append(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void resolve_whenActorIdNull_throws() {
        // actorId 가드(Objects.requireNonNull → NPE)가 UPDATE 전에 차단해야 한다.
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> svc.resolve(id, "note", null, "alice@crosscert.com"))
                .isInstanceOf(NullPointerException.class);
        verify(repo, never()).resolveIfOpen(any(), any(), any(), any());
        verify(audit, never()).append(any());
    }
}
