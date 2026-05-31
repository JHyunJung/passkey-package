package com.crosscert.passkey.app.fido2.registration;

import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationStartRequest;
import com.crosscert.passkey.app.api.v1.rp.dto.RegistrationStartResponse;
import com.crosscert.passkey.app.fido2.challenge.ChallengeIssuer;
import com.crosscert.passkey.app.fido2.challenge.ChallengeStore;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.CredentialRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import com.crosscert.passkey.core.vpd.TenantContextHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegistrationStartServiceExcludeTest {

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final CredentialRepository credentials = mock(CredentialRepository.class);
    private final ChallengeIssuer challenges = mock(ChallengeIssuer.class);
    private final ChallengeStore store = mock(ChallengeStore.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-29T00:00:00Z"), ZoneOffset.UTC);

    @AfterEach
    void clear() { TenantContextHolder.clear(); }

    @Test
    void start_includesExcludeCredentials_whenUserHasExistingCredential() {
        UUID tenantId = UUID.randomUUID();
        TenantContextHolder.set(tenantId);
        Tenant t = mock(Tenant.class);
        when(t.getRpId()).thenReturn("example.com");
        when(t.getRpName()).thenReturn("Example");
        when(tenants.findById(tenantId)).thenReturn(Optional.of(t));
        when(challenges.newChallengeBytes()).thenReturn(new byte[32]);
        when(challenges.newToken()).thenReturn("tok_test_token_value");

        byte[] existing = new byte[]{1, 2, 3, 4};
        byte[] userHandle = new byte[]{9, 9};
        // eq(userHandle): start() must hand the repo the *decoded* userHandle bytes,
        // not the base64url string — eq() catches a regression that passes the wrong bytes.
        when(credentials.findCredentialIdsByUserHandle(eq(userHandle))).thenReturn(List.of(existing));

        RegistrationStartService svc = new RegistrationStartService(
                tenants, credentials, challenges, store, mapper, clock,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        RegistrationStartRequest req = new RegistrationStartRequest(
                Base64.getUrlEncoder().withoutPadding().encodeToString(userHandle),
                "Disp", "alice");

        RegistrationStartResponse resp = svc.start(req);

        verify(credentials).findCredentialIdsByUserHandle(eq(userHandle));
        JsonNode exclude = resp.publicKeyCredentialCreationOptions().get("excludeCredentials");
        assertThat(exclude).isNotNull();
        assertThat(exclude.isArray()).isTrue();
        assertThat(exclude).hasSize(1);
        assertThat(exclude.get(0).get("type").asText()).isEqualTo("public-key");
        String expectedId = Base64.getUrlEncoder().withoutPadding().encodeToString(existing);
        assertThat(exclude.get(0).get("id").asText()).isEqualTo(expectedId);
    }

    @Test
    void start_includesAllExcludeCredentials_whenUserHasMultipleCredentials() {
        UUID tenantId = UUID.randomUUID();
        TenantContextHolder.set(tenantId);
        Tenant t = mock(Tenant.class);
        when(t.getRpId()).thenReturn("example.com");
        when(t.getRpName()).thenReturn("Example");
        when(tenants.findById(tenantId)).thenReturn(Optional.of(t));
        when(challenges.newChallengeBytes()).thenReturn(new byte[32]);
        when(challenges.newToken()).thenReturn("tok_test_token_value");

        byte[] first = new byte[]{1, 2, 3, 4};
        byte[] second = new byte[]{5, 6, 7, 8, 9};
        byte[] userHandle = new byte[]{9, 9};
        when(credentials.findCredentialIdsByUserHandle(eq(userHandle)))
                .thenReturn(List.of(first, second));

        RegistrationStartService svc = new RegistrationStartService(
                tenants, credentials, challenges, store, mapper, clock,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        RegistrationStartRequest req = new RegistrationStartRequest(
                Base64.getUrlEncoder().withoutPadding().encodeToString(userHandle),
                "Disp", "alice");

        RegistrationStartResponse resp = svc.start(req);

        verify(credentials).findCredentialIdsByUserHandle(eq(userHandle));
        JsonNode exclude = resp.publicKeyCredentialCreationOptions().get("excludeCredentials");
        assertThat(exclude).isNotNull();
        assertThat(exclude.isArray()).isTrue();
        assertThat(exclude).hasSize(2);
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        assertThat(exclude.get(0).get("type").asText()).isEqualTo("public-key");
        assertThat(exclude.get(0).get("id").asText()).isEqualTo(enc.encodeToString(first));
        assertThat(exclude.get(1).get("type").asText()).isEqualTo("public-key");
        assertThat(exclude.get(1).get("id").asText()).isEqualTo(enc.encodeToString(second));
    }

    @Test
    void start_emptyExcludeCredentials_whenNoExisting() {
        UUID tenantId = UUID.randomUUID();
        TenantContextHolder.set(tenantId);
        Tenant t = mock(Tenant.class);
        when(t.getRpId()).thenReturn("example.com");
        when(t.getRpName()).thenReturn("Example");
        when(tenants.findById(tenantId)).thenReturn(Optional.of(t));
        when(challenges.newChallengeBytes()).thenReturn(new byte[32]);
        when(challenges.newToken()).thenReturn("tok_test_token_value");
        byte[] userHandle = new byte[]{9, 9};
        when(credentials.findCredentialIdsByUserHandle(eq(userHandle))).thenReturn(List.of());

        RegistrationStartService svc = new RegistrationStartService(
                tenants, credentials, challenges, store, mapper, clock,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        RegistrationStartRequest req = new RegistrationStartRequest(
                Base64.getUrlEncoder().withoutPadding().encodeToString(userHandle),
                "Disp", "alice");

        RegistrationStartResponse resp = svc.start(req);
        verify(credentials).findCredentialIdsByUserHandle(eq(userHandle));
        JsonNode exclude = resp.publicKeyCredentialCreationOptions().get("excludeCredentials");
        assertThat(exclude).isNotNull();
        assertThat(exclude.isArray()).isTrue();
        assertThat(exclude).isEmpty();
    }

    @Test
    void start_rejectsSuspendedTenant_withTenantSuspendedError() {
        UUID tenantId = UUID.randomUUID();
        TenantContextHolder.set(tenantId);
        Tenant t = mock(Tenant.class);
        when(t.isSuspended()).thenReturn(true);
        when(tenants.findById(tenantId)).thenReturn(Optional.of(t));

        RegistrationStartService svc = new RegistrationStartService(
                tenants, credentials, challenges, store, mapper, clock,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        RegistrationStartRequest req = new RegistrationStartRequest(
                Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[]{9, 9}),
                "Disp", "alice");

        assertThatThrownBy(() -> svc.start(req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.TENANT_SUSPENDED);
    }
}
