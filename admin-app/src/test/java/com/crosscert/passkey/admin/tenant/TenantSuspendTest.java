package com.crosscert.passkey.admin.tenant;

import com.crosscert.passkey.core.entity.ApiKey;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.ApiKeyRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TenantSuspendTest {

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final ApiKeyRepository apiKeys = mock(ApiKeyRepository.class);
    private final com.crosscert.passkey.admin.audit.AuditLogService audit =
            mock(com.crosscert.passkey.admin.audit.AuditLogService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-29T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void suspend_setsStatusAndRevokesActiveKeys() {
        UUID tenantId = UUID.randomUUID();
        Tenant t = new Tenant("acme", "Acme", "acme.example.com", "Acme");
        when(tenants.findById(tenantId)).thenReturn(Optional.of(t));
        ApiKey k1 = mock(ApiKey.class);
        ApiKey k2 = mock(ApiKey.class);
        when(apiKeys.findActiveByTenantId(eq(tenantId), any())).thenReturn(List.of(k1, k2));

        TenantLifecycleService svc = new TenantLifecycleService(tenants, apiKeys, audit, clock);
        svc.suspend(tenantId, UUID.randomUUID(), "alice@crosscert.com");

        assertThat(t.getStatus()).isEqualTo("suspended");
        verify(k1).revoke(any());
        verify(k2).revoke(any());
        verify(tenants).save(t);
    }

    @Test
    void activate_setsStatusActive() {
        UUID tenantId = UUID.randomUUID();
        Tenant t = new Tenant("acme", "Acme", "acme.example.com", "Acme");
        t.suspend();
        when(tenants.findById(tenantId)).thenReturn(Optional.of(t));

        TenantLifecycleService svc = new TenantLifecycleService(tenants, apiKeys, audit, clock);
        svc.activate(tenantId, UUID.randomUUID(), "alice@crosscert.com");
        assertThat(t.getStatus()).isEqualTo("active");
    }
}
