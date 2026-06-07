package com.crosscert.passkey.admin.credential;

import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.admin.auth.TenantBoundary;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.PageView;
import com.crosscert.passkey.core.ceremony.CredentialAuthResult;
import com.crosscert.passkey.core.entity.Credential;
import com.crosscert.passkey.core.entity.CredentialAuthEvent;
import com.crosscert.passkey.core.mds.MdsAaguidCache;
import com.crosscert.passkey.core.repository.CredentialAuthEventRepository;
import com.crosscert.passkey.core.repository.CredentialRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CredentialAdminServiceAuthEventsTest {

    private final CredentialRepository creds = mock(CredentialRepository.class);
    private final MdsAaguidCache mds = mock(MdsAaguidCache.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final TenantBoundary tenantBoundary = mock(TenantBoundary.class);
    private final CredentialAuthEventRepository authEvents = mock(CredentialAuthEventRepository.class);

    // 실제 CredentialAdminService 생성자: (creds, mds, audit, tenantBoundary, authEvents)
    private final CredentialAdminService svc =
            new CredentialAdminService(creds, mds, audit, tenantBoundary, authEvents);

    @Test
    void listAuthEventsMapsToView() {
        UUID tenant = UUID.randomUUID();
        UUID credPk = UUID.randomUUID();
        String credB64 = "AAAA"; // base64url decodable

        Credential c = mock(Credential.class);
        when(c.getTenantId()).thenReturn(tenant);
        when(c.getId()).thenReturn(credPk);
        when(creds.findByCredentialId(any())).thenReturn(Optional.of(c));

        when(authEvents.findByCredentialIdOrderByCreatedAtDesc(eq(credPk), any()))
                .thenReturn(new PageImpl<>(List.of(
                        new CredentialAuthEvent(credPk, tenant, CredentialAuthResult.SUCCESS, null, 3))));

        PageView<CredentialAdminDto.AuthEventView> page = svc.listAuthEvents(tenant, credB64, 0, 50);

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).result()).isEqualTo(CredentialAuthResult.SUCCESS);
        assertThat(page.content().get(0).signCount()).isEqualTo(3);
        verify(tenantBoundary).assertCanAccessTenant(tenant);
    }

    @Test
    void listAuthEventsRejectsCrossTenant() {
        UUID tenant = UUID.randomUUID();
        UUID otherTenant = UUID.randomUUID();
        UUID credPk = UUID.randomUUID();

        Credential c = mock(Credential.class);
        when(c.getTenantId()).thenReturn(otherTenant);
        when(creds.findByCredentialId(any())).thenReturn(Optional.of(c));

        assertThatThrownBy(() -> svc.listAuthEvents(tenant, "AAAA", 0, 50))
                .isInstanceOf(BusinessException.class);
    }
}
