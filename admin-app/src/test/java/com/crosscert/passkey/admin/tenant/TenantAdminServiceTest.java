package com.crosscert.passkey.admin.tenant;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.TenantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantAdminServiceTest {

    private TenantRepository repo;
    private AuditLogService audit;
    private TenantAdminService service;

    @BeforeEach
    void setUp() {
        repo = mock(TenantRepository.class);
        audit = mock(AuditLogService.class);
        service = new TenantAdminService(repo, audit, new ObjectMapper());
    }

    @Test
    void createPersistsTenantAndAppendsAudit() {
        when(repo.findById("T_A")).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        TenantAdminDto.TenantCreateRequest req = new TenantAdminDto.TenantCreateRequest(
                "T_A", "Tenant A", "localhost", "Tenant A",
                "[\"http://localhost\"]",
                "{\"acceptedFormats\":[\"none\"],\"requireUserVerification\":true,\"mdsRequired\":false}");

        TenantAdminDto.TenantView view = service.create(req, 7L, "alice@example.com");

        assertThat(view.id()).isEqualTo("T_A");
        ArgumentCaptor<Tenant> tenantCaptor = ArgumentCaptor.forClass(Tenant.class);
        verify(repo).save(tenantCaptor.capture());
        assertThat(tenantCaptor.getValue().getRpId()).isEqualTo("localhost");

        ArgumentCaptor<AuditAppendRequest> auditCaptor = ArgumentCaptor.forClass(AuditAppendRequest.class);
        verify(audit).append(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo("TENANT_CREATE");
        assertThat(auditCaptor.getValue().targetId()).isEqualTo("T_A");
    }

    @Test
    void createRejectsDuplicateId() {
        when(repo.findById("T_A")).thenReturn(Optional.of(new Tenant("T_A", "X")));
        TenantAdminDto.TenantCreateRequest req = new TenantAdminDto.TenantCreateRequest(
                "T_A", "Tenant A", "localhost", "Tenant A",
                "[\"http://localhost\"]",
                "{\"acceptedFormats\":[\"none\"],\"requireUserVerification\":true,\"mdsRequired\":false}");
        assertThatThrownBy(() -> service.create(req, 7L, "alice@example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenant id already exists");
    }

    @Test
    void createRejectsMalformedOriginsJson() {
        when(repo.findById("T_A")).thenReturn(Optional.empty());
        TenantAdminDto.TenantCreateRequest req = new TenantAdminDto.TenantCreateRequest(
                "T_A", "Tenant A", "localhost", "Tenant A",
                "not json",
                "{\"acceptedFormats\":[\"none\"],\"requireUserVerification\":true,\"mdsRequired\":false}");
        assertThatThrownBy(() -> service.create(req, 7L, "alice@example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowed_origins JSON invalid");
    }

    @Test
    void createRejectsMalformedPolicyJson() {
        when(repo.findById("T_A")).thenReturn(Optional.empty());
        TenantAdminDto.TenantCreateRequest req = new TenantAdminDto.TenantCreateRequest(
                "T_A", "Tenant A", "localhost", "Tenant A",
                "[\"http://localhost\"]",
                "not json");
        assertThatThrownBy(() -> service.create(req, 7L, "alice@example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attestation_policy JSON invalid");
    }
}
