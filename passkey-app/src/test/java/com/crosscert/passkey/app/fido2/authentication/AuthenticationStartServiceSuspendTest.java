package com.crosscert.passkey.app.fido2.authentication;

import com.crosscert.passkey.app.api.v1.rp.dto.AuthenticationStartRequest;
import com.crosscert.passkey.app.fido2.challenge.ChallengeIssuer;
import com.crosscert.passkey.app.fido2.challenge.ChallengeStore;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.CredentialRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import com.crosscert.passkey.core.vpd.TenantContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthenticationStartServiceSuspendTest {

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final CredentialRepository credentials = mock(CredentialRepository.class);
    private final ChallengeIssuer challenges = mock(ChallengeIssuer.class);
    private final ChallengeStore store = mock(ChallengeStore.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-29T00:00:00Z"), ZoneOffset.UTC);

    @AfterEach
    void clear() { TenantContextHolder.clear(); }

    @Test
    void start_rejectsSuspendedTenant_withTenantSuspendedError() {
        UUID tenantId = UUID.randomUUID();
        TenantContextHolder.set(tenantId);
        Tenant t = mock(Tenant.class);
        when(t.isSuspended()).thenReturn(true);
        when(tenants.findById(tenantId)).thenReturn(Optional.of(t));

        AuthenticationStartService svc = new AuthenticationStartService(
                tenants, credentials, challenges, store, mapper, clock);
        AuthenticationStartRequest req = new AuthenticationStartRequest(null);

        assertThatThrownBy(() -> svc.start(req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.TENANT_SUSPENDED);
    }
}
