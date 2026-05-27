package com.crosscert.passkey.admin.tenant;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.admin.auth.TenantBoundary;
import com.crosscert.passkey.core.repository.TenantRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantAdminServiceTest {

    private TenantRepository repo;
    private AuditLogService audit;
    private EntityManager em;
    private TenantBoundary boundary;
    private TenantAdminService service;

    @BeforeEach
    void setUp() {
        repo = mock(TenantRepository.class);
        audit = mock(AuditLogService.class);
        em = mock(EntityManager.class);
        boundary = mock(TenantBoundary.class);
        service = new TenantAdminService(repo, audit, em, boundary);
    }

    @Test
    void createPersistsTenantAndAppendsAudit() {
        when(repo.existsBySlug("T_A")).thenReturn(false);
        when(repo.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        TenantAdminDto.TenantCreateRequest req = new TenantAdminDto.TenantCreateRequest(
                "T_A", "Tenant A", "localhost", "Tenant A",
                List.of("http://localhost"),
                Set.of("none", "packed"),
                true, false);

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
    void createRejectsDuplicateSlug() {
        when(repo.existsBySlug("T_A")).thenReturn(true);
        TenantAdminDto.TenantCreateRequest req = new TenantAdminDto.TenantCreateRequest(
                "T_A", "Tenant A", "localhost", "Tenant A",
                List.of("http://localhost"),
                Set.of("none"),
                true, false);
        assertThatThrownBy(() -> service.create(req, UUID.randomUUID(), "alice@example.com"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.TENANT_DUPLICATE));
    }
}
