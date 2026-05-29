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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
        when(credentials.findCredentialIdsByUserHandle(any())).thenReturn(List.of(existing));

        RegistrationStartService svc = new RegistrationStartService(
                tenants, credentials, challenges, store, mapper, clock);
        RegistrationStartRequest req = new RegistrationStartRequest(
                Base64.getUrlEncoder().withoutPadding().encodeToString(userHandle),
                "Disp", "alice");

        RegistrationStartResponse resp = svc.start(req);

        JsonNode exclude = resp.publicKeyCredentialCreationOptions().get("excludeCredentials");
        assertThat(exclude).isNotNull();
        assertThat(exclude.isArray()).isTrue();
        assertThat(exclude).hasSize(1);
        assertThat(exclude.get(0).get("type").asText()).isEqualTo("public-key");
        String expectedId = Base64.getUrlEncoder().withoutPadding().encodeToString(existing);
        assertThat(exclude.get(0).get("id").asText()).isEqualTo(expectedId);
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
        when(credentials.findCredentialIdsByUserHandle(any())).thenReturn(List.of());

        RegistrationStartService svc = new RegistrationStartService(
                tenants, credentials, challenges, store, mapper, clock);
        RegistrationStartRequest req = new RegistrationStartRequest(
                Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[]{9, 9}),
                "Disp", "alice");

        RegistrationStartResponse resp = svc.start(req);
        JsonNode exclude = resp.publicKeyCredentialCreationOptions().get("excludeCredentials");
        assertThat(exclude).isNotNull();
        assertThat(exclude.isArray()).isTrue();
        assertThat(exclude).isEmpty();
    }
}
