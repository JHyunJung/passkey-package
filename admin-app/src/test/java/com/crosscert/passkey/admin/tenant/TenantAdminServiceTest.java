package com.crosscert.passkey.admin.tenant;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.admin.auth.TenantBoundary;
import com.crosscert.passkey.core.repository.TenantAaguidPolicyRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import com.crosscert.passkey.core.repository.TenantWebauthnSnapshotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantAdminServiceTest {

    private TenantRepository repo;
    private AuditLogService audit;
    private EntityManager em;
    private TenantBoundary boundary;
    private TenantAaguidPolicyRepository aaguidPolicyRepo;
    private TenantWebauthnSnapshotRepository snapshotRepo;
    private com.crosscert.passkey.core.repository.CredentialRepository credentialRepository;
    private com.crosscert.passkey.core.repository.ApiKeyRepository apiKeyRepository;
    private com.crosscert.passkey.core.repository.AuditLogRepository auditLogRepository;
    private TenantAdminService service;

    @BeforeEach
    void setUp() {
        repo = mock(TenantRepository.class);
        audit = mock(AuditLogService.class);
        em = mock(EntityManager.class);
        boundary = mock(TenantBoundary.class);
        aaguidPolicyRepo = mock(TenantAaguidPolicyRepository.class);
        snapshotRepo = mock(TenantWebauthnSnapshotRepository.class);
        credentialRepository = mock(com.crosscert.passkey.core.repository.CredentialRepository.class);
        apiKeyRepository = mock(com.crosscert.passkey.core.repository.ApiKeyRepository.class);
        auditLogRepository = mock(com.crosscert.passkey.core.repository.AuditLogRepository.class);
        service = new TenantAdminService(repo, audit, em, boundary,
                aaguidPolicyRepo, snapshotRepo,
                credentialRepository, apiKeyRepository, auditLogRepository,
                new ObjectMapper(), Clock.systemUTC());
    }

    @Test
    void createPersistsTenantAndAppendsAudit() {
        when(repo.existsBySlug("T_A")).thenReturn(false);
        when(repo.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        TenantAdminDto.TenantCreateRequest req = new TenantAdminDto.TenantCreateRequest(
                "T_A", "Tenant A", "localhost", "Tenant A",
                List.of("http://localhost"),
                Set.of("none", "packed"),
                true, false,
                "NONE", 60000);

        TenantAdminDto.TenantView view = service.create(req, UUID.randomUUID(), "alice@example.com");

        assertThat(view.slug()).isEqualTo("T_A");
        ArgumentCaptor<Tenant> tenantCaptor = ArgumentCaptor.forClass(Tenant.class);
        verify(repo).saveAndFlush(tenantCaptor.capture());
        assertThat(tenantCaptor.getValue().getRpId()).isEqualTo("localhost");
        assertThat(tenantCaptor.getValue().isRequireUserVerification()).isTrue();
        assertThat(tenantCaptor.getValue().isMdsRequired()).isFalse();
        assertThat(tenantCaptor.getValue().getAllowedOriginValues())
                .containsExactly("http://localhost");
        assertThat(tenantCaptor.getValue().getAcceptedFormatValues())
                .containsExactlyInAnyOrder("none", "packed");

        ArgumentCaptor<AuditAppendRequest> auditCaptor = ArgumentCaptor.forClass(AuditAppendRequest.class);
        verify(audit).append(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo("TENANT_CREATE");
        assertThat(auditCaptor.getValue().targetId()).isEqualTo("T_A");
    }

    @Test
    void listUsesBatchAggregatesNotPerTenantQueries() {
        // two ACTIVE tenants. BaseEntity.id is assigned by @UuidGenerator at persist
        // time, so a freshly-constructed entity has a null id in a pure unit test —
        // assign deterministic ids reflectively (same helper pattern as
        // ApiKeyRotateServiceTest#setId) so the aggregate Map lookups have stable keys.
        Tenant a = new Tenant("T_A", "Tenant A", "a.example", "Tenant A");
        Tenant b = new Tenant("T_B", "Tenant B", "b.example", "Tenant B");
        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();
        setId(a, idA);
        setId(b, idB);
        // audit_log.created_at is OffsetDateTime (KST, +09:00) after the timezone migration;
        // the batch aggregate row carries an OffsetDateTime that the view exposes as-is.
        OffsetDateTime eventA = OffsetDateTime.parse("2026-05-01T09:00:00+09:00");

        when(boundary.currentTenantScope()).thenReturn(java.util.Optional.empty());
        when(repo.findAll()).thenReturn(List.of(a, b));

        // batch aggregate stubs — Object[]{tenantId(UUID), count/maxCreatedAt}
        when(credentialRepository.countGroupedByTenantId())
                .thenReturn(List.<Object[]>of(new Object[]{idA, 5L}, new Object[]{idB, 0L}));
        when(apiKeyRepository.countActiveGroupedByTenantId(any()))
                .thenReturn(List.<Object[]>of(new Object[]{idA, 2L})); // idB absent => 0
        when(auditLogRepository.findLatestCreatedAtGroupedByTenantId())
                .thenReturn(List.<Object[]>of(new Object[]{idA, eventA})); // idB absent => null

        List<TenantAdminDto.TenantView> views = service.list();

        assertThat(views).hasSize(2);
        TenantAdminDto.TenantView va = views.stream().filter(v -> v.id().equals(idA)).findFirst().orElseThrow();
        TenantAdminDto.TenantView vb = views.stream().filter(v -> v.id().equals(idB)).findFirst().orElseThrow();
        assertThat(va.credentials()).isEqualTo(5L);
        assertThat(va.apiKeys()).isEqualTo(2L);
        assertThat(va.lastEventAt()).isEqualTo(eventA);
        assertThat(vb.credentials()).isEqualTo(0L);
        assertThat(vb.apiKeys()).isEqualTo(0L); // tenant absent from active-key aggregate => 0
        assertThat(vb.lastEventAt()).isNull();   // tenant absent from event aggregate => null

        // invariant: list() must NOT use the per-tenant N+1 methods anymore
        verify(credentialRepository, never()).countByTenantId(any());
        verify(apiKeyRepository, never()).countActiveByTenantId(any(), any());
        verify(auditLogRepository, never()).findFirstByTenantIdOrderByCreatedAtDesc(any());
    }

    /** Assigns BaseEntity.id on an un-persisted entity (mirrors ApiKeyRotateServiceTest#setId). */
    private static void setId(Tenant t, UUID id) {
        try {
            var f = Tenant.class.getSuperclass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(t, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void createRejectsMalformedOrigin() {
        when(repo.existsBySlug("T_A")).thenReturn(false);
        TenantAdminDto.TenantCreateRequest req = new TenantAdminDto.TenantCreateRequest(
                "T_A", "Tenant A", "localhost", "Tenant A",
                List.of("ftp://bad.example.com"), // 형식 위반
                Set.of("none"),
                true, false,
                "NONE", 60000);
        assertThatThrownBy(() -> service.create(req, UUID.randomUUID(), "alice@example.com"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @Test
    void updateRejectsMalformedOrigin() {
        // lookup(idOrSlug) → findBySlug 가 기존 tenant 반환 (realistic existing tenant)
        Tenant existing = new Tenant("T_A", "Tenant A", "localhost", "Tenant A");
        existing.addAllowedOrigin("http://localhost", 0);
        existing.addAcceptedFormat("none");
        setId(existing, UUID.randomUUID());
        when(repo.findBySlug("T_A")).thenReturn(java.util.Optional.of(existing));
        // tenantBoundary.assertCanAccessTenant 는 void mock → 통과

        TenantAdminDto.TenantUpdateRequest req = new TenantAdminDto.TenantUpdateRequest(
                "Tenant A", "localhost", "Tenant A",
                List.of("ftp://bad.example.com"), // 형식 위반
                Set.of("none"),
                true, false,
                "NONE", 60000);

        assertThatThrownBy(() -> service.update("T_A", req, UUID.randomUUID(), "alice@example.com"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_INPUT));

        // validate-before-mutate: 형식 위반이면 saveAndFlush 까지 도달하지 않음
        verify(repo, never()).saveAndFlush(any());
        // validate-before-snapshot (hoist lock-in): access check 직후 검증 → snapshot insert 시도 안 함
        verify(snapshotRepo, never()).save(any());
    }

    @Test
    void createRejectsDuplicateSlug() {
        when(repo.existsBySlug("T_A")).thenReturn(true);
        TenantAdminDto.TenantCreateRequest req = new TenantAdminDto.TenantCreateRequest(
                "T_A", "Tenant A", "localhost", "Tenant A",
                List.of("http://localhost"),
                Set.of("none"),
                true, false,
                "NONE", 60000);
        assertThatThrownBy(() -> service.create(req, UUID.randomUUID(), "alice@example.com"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.TENANT_DUPLICATE));
    }
}
