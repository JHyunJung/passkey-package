package com.crosscert.passkey.app.fido2.authentication;

import com.crosscert.passkey.app.api.v1.rp.dto.AuthenticationStartRequest;
import com.crosscert.passkey.app.fido2.challenge.ChallengeIssuer;
import com.crosscert.passkey.app.fido2.challenge.ChallengeStore;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import com.crosscert.passkey.core.entity.Credential;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
                tenants, credentials, challenges, store, mapper, clock,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        AuthenticationStartRequest req = new AuthenticationStartRequest(null);

        assertThatThrownBy(() -> svc.start(req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.TENANT_SUSPENDED);
    }

    @Test
    void start_emits_tenant_timeout_and_preferred_uv_when_uv_not_required() {
        UUID tenantId = UUID.randomUUID();
        TenantContextHolder.set(tenantId);
        Tenant t = mock(Tenant.class);
        when(t.isSuspended()).thenReturn(false);
        when(t.getRpId()).thenReturn("example.com");
        when(t.getWebauthnTimeoutMs()).thenReturn(90000);
        when(t.isRequireUserVerification()).thenReturn(false);
        when(tenants.findById(tenantId)).thenReturn(Optional.of(t));
        when(challenges.newChallengeBytes()).thenReturn(new byte[32]);
        when(challenges.newToken()).thenReturn("tok_test_token_value");

        byte[] userHandle = new byte[]{9, 9};
        byte[] credId = new byte[]{1, 2, 3, 4};
        Credential cred = mock(Credential.class);
        when(cred.getCredentialId()).thenReturn(credId);
        // eq(userHandle): start() must hand the repo the *decoded* userHandle bytes.
        when(credentials.findByUserHandle(eq(userHandle))).thenReturn(List.of(cred));

        AuthenticationStartService svc = new AuthenticationStartService(
                tenants, credentials, challenges, store, mapper, clock,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        AuthenticationStartRequest req = new AuthenticationStartRequest(
                Base64.getUrlEncoder().withoutPadding().encodeToString(userHandle));

        var resp = svc.start(req);

        verify(credentials).findByUserHandle(eq(userHandle));
        JsonNode options = resp.publicKeyCredentialRequestOptions();
        assertThat(options.get("timeout").asInt()).isEqualTo(90000);
        assertThat(options.get("userVerification").asText()).isEqualTo("preferred");
        JsonNode allow = options.get("allowCredentials");
        assertThat(allow).isNotNull();
        assertThat(allow.isArray()).isTrue();
        assertThat(allow).hasSize(1);
        assertThat(allow.get(0).get("type").asText()).isEqualTo("public-key");
        String expectedId = Base64.getUrlEncoder().withoutPadding().encodeToString(credId);
        assertThat(allow.get(0).get("id").asText()).isEqualTo(expectedId);
    }

    @Test
    void start_emits_required_uv_when_tenant_requires_it() {
        UUID tenantId = UUID.randomUUID();
        TenantContextHolder.set(tenantId);
        Tenant t = mock(Tenant.class);
        when(t.isSuspended()).thenReturn(false);
        when(t.getRpId()).thenReturn("example.com");
        when(t.getWebauthnTimeoutMs()).thenReturn(90000);
        when(t.isRequireUserVerification()).thenReturn(true);
        when(tenants.findById(tenantId)).thenReturn(Optional.of(t));
        when(challenges.newChallengeBytes()).thenReturn(new byte[32]);
        when(challenges.newToken()).thenReturn("tok_test_token_value");

        byte[] userHandle = new byte[]{9, 9};
        byte[] credId = new byte[]{1, 2, 3, 4};
        Credential cred = mock(Credential.class);
        when(cred.getCredentialId()).thenReturn(credId);
        when(credentials.findByUserHandle(eq(userHandle))).thenReturn(List.of(cred));

        AuthenticationStartService svc = new AuthenticationStartService(
                tenants, credentials, challenges, store, mapper, clock,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        AuthenticationStartRequest req = new AuthenticationStartRequest(
                Base64.getUrlEncoder().withoutPadding().encodeToString(userHandle));

        var resp = svc.start(req);

        JsonNode options = resp.publicKeyCredentialRequestOptions();
        assertThat(options.get("timeout").asInt()).isEqualTo(90000);
        assertThat(options.get("userVerification").asText()).isEqualTo("required");
    }
}
