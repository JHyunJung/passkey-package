package com.crosscert.passkey.admin.audit;

import com.crosscert.passkey.core.alert.SecurityAlertEvent;
import com.crosscert.passkey.core.entity.SecurityIncident;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.SecurityIncidentRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

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
        svc = new SecurityIncidentService(repo, verifier, audit, events, tenants, clock);

        Tenant t = mock(Tenant.class);
        when(t.getDisplayName()).thenReturn("Acme Corp");
        when(tenants.findById(TENANT)).thenReturn(Optional.of(t));
        when(repo.save(any(SecurityIncident.class))).thenAnswer(inv -> inv.getArgument(0));
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
        verify(repo).save(any(SecurityIncident.class));
        verify(audit).append(any());
        verify(events).publishEvent(any(SecurityAlertEvent.class));
    }

    @Test
    void create_whenAlreadyOpen_throwsConflict() {
        stubTampered(true);
        when(repo.existsByTenantIdAndStatus(TENANT, "OPEN")).thenReturn(true);

        assertThatThrownBy(() -> svc.create(TENANT, ENTRY, ACTOR, "alice@crosscert.com"))
                .isInstanceOf(IncidentConflictException.class);
        verify(repo, never()).save(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void create_whenNotActuallyTampered_throwsUnprocessable() {
        stubTampered(false);

        assertThatThrownBy(() -> svc.create(TENANT, ENTRY, ACTOR, "alice@crosscert.com"))
                .isInstanceOf(IncidentNotTamperedException.class);
        verify(repo, never()).save(any());
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
}
