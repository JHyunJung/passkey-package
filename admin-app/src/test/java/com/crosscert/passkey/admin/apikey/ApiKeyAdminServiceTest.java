package com.crosscert.passkey.admin.apikey;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.admin.auth.TenantBoundary;
import com.crosscert.passkey.core.entity.ApiKey;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.ApiKeyRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiKeyAdminServiceTest {

    private static final UUID TENANT_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR_UUID  = UUID.fromString("00000000-0000-0000-0000-000000000010");

    private ApiKeyRepository repo;
    private AuditLogService audit;
    private PasswordEncoder encoder;
    private TenantBoundary boundary;
    private TenantRepository tenants;
    private ApiKeyAdminService service;
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        repo = mock(ApiKeyRepository.class);
        audit = mock(AuditLogService.class);
        encoder = new BCryptPasswordEncoder(4); // fast for tests
        boundary = mock(TenantBoundary.class);
        tenants = mock(TenantRepository.class);
        // Default: target tenant is active so existing issue() tests pass the
        // P0-2 suspended-tenant guard. suspended cases override this stub.
        Tenant active = mock(Tenant.class);
        when(active.isSuspended()).thenReturn(false);
        when(tenants.findById(any())).thenReturn(Optional.of(active));
        service = new ApiKeyAdminService(repo, audit, encoder, new SecureRandom(), clock, boundary, tenants);
        // @UuidGenerator sets id during JPA persist. In unit tests we must
        // set it reflectively so getId() is non-null after repo.save() mock.
        // The service uses saveAndFlush for issue() and save() for revoke().
        // Phase 8 T4: id is now on BaseEntity superclass — use getSuperclass().
        org.mockito.stubbing.Answer<ApiKey> assignId = inv -> {
            ApiKey k = inv.getArgument(0);
            try {
                var f = ApiKey.class.getSuperclass().getDeclaredField("id");
                f.setAccessible(true);
                if (f.get(k) == null) f.set(k, UUID.randomUUID());
            } catch (Exception e) { throw new RuntimeException(e); }
            return k;
        };
        when(repo.save(any())).thenAnswer(assignId);
        when(repo.saveAndFlush(any())).thenAnswer(assignId);
    }

    @Test
    void issueProducesPlainTextAndPersistsHash() {
        when(repo.findByKeyPrefix(anyString())).thenReturn(Optional.empty());

        ApiKeyAdminDto.ApiKeyCreateRequest req = new ApiKeyAdminDto.ApiKeyCreateRequest(
                TENANT_UUID, "primary", java.util.Set.of("registration", "authentication"));
        ApiKeyAdminDto.ApiKeyCreateResponse resp =
                service.issue(req, ACTOR_UUID, "alice@example.com");

        assertThat(resp.plainText()).startsWith("pk_");
        assertThat(resp.plainText()).hasSizeGreaterThan(20);
        assertThat(resp.prefix()).hasSize(11); // pk_ + 8 chars

        ArgumentCaptor<ApiKey> keyCaptor = ArgumentCaptor.forClass(ApiKey.class);
        verify(repo).saveAndFlush(keyCaptor.capture());
        ApiKey saved = keyCaptor.getValue();
        // Verify the persisted hash actually matches the plaintext secret
        // (full plainText = prefix + secret; we know prefix == resp.prefix).
        String secret = resp.plainText().substring(resp.prefix().length());
        assertThat(encoder.matches(secret, saved.getKeyHash())).isTrue();
    }

    @Test
    void issueAppendsAuditWithoutSecret() {
        when(repo.findByKeyPrefix(anyString())).thenReturn(Optional.empty());

        service.issue(new ApiKeyAdminDto.ApiKeyCreateRequest(
                        TENANT_UUID, "primary", java.util.Set.of("registration")),
                      ACTOR_UUID, "alice@example.com");

        ArgumentCaptor<AuditAppendRequest> auditCaptor =
                ArgumentCaptor.forClass(AuditAppendRequest.class);
        verify(audit).append(auditCaptor.capture());
        AuditAppendRequest aud = auditCaptor.getValue();
        assertThat(aud.action()).isEqualTo("API_KEY_ISSUE");
        // payload must contain prefix + tenantId + name but NOT plaintext.
        assertThat(aud.payload()).containsKey("prefix");
        assertThat(aud.payload()).doesNotContainKey("plainText");
        assertThat(aud.payload()).doesNotContainKey("secret");
    }

    @Test
    void issueRetriesOnPrefixCollision() {
        // First two random prefixes already exist; third is fresh.
        when(repo.findByKeyPrefix(anyString()))
                .thenReturn(Optional.of(new ApiKey(TENANT_UUID,"pk_xxxxxxxx","h","n")))
                .thenReturn(Optional.of(new ApiKey(TENANT_UUID,"pk_yyyyyyyy","h","n")))
                .thenReturn(Optional.empty());

        ApiKeyAdminDto.ApiKeyCreateResponse resp = service.issue(
                new ApiKeyAdminDto.ApiKeyCreateRequest(
                        TENANT_UUID, "primary", java.util.Set.of("registration")),
                ACTOR_UUID, "alice@example.com");
        assertThat(resp.prefix()).startsWith("pk_");
    }

    @Test
    void issueRejectsSuspendedTenant_withTenantSuspendedError() {
        Tenant suspended = mock(Tenant.class);
        when(suspended.isSuspended()).thenReturn(true);
        when(tenants.findById(TENANT_UUID)).thenReturn(Optional.of(suspended));

        ApiKeyAdminDto.ApiKeyCreateRequest req = new ApiKeyAdminDto.ApiKeyCreateRequest(
                TENANT_UUID, "primary", java.util.Set.of("registration"));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.issue(req, ACTOR_UUID, "alice@example.com"))
                .isInstanceOf(com.crosscert.passkey.core.api.BusinessException.class)
                .extracting(e -> ((com.crosscert.passkey.core.api.BusinessException) e).getErrorCode())
                .isEqualTo(com.crosscert.passkey.core.api.ErrorCode.TENANT_SUSPENDED);

        // No key persisted, no audit appended on the rejection path.
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.never()).saveAndFlush(any());
        org.mockito.Mockito.verify(audit, org.mockito.Mockito.never()).append(any());
    }

    @Test
    void revokeThrowsBusinessExceptionWhenIdNotFound() {
        UUID missingId = UUID.randomUUID();
        when(repo.findById(missingId)).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.revoke(missingId, ACTOR_UUID, "alice@example.com"))
                .isInstanceOf(com.crosscert.passkey.core.api.BusinessException.class)
                .extracting(e -> ((com.crosscert.passkey.core.api.BusinessException) e).getErrorCode())
                .isEqualTo(com.crosscert.passkey.core.api.ErrorCode.API_KEY_NOT_FOUND);

        org.mockito.Mockito.verify(audit, org.mockito.Mockito.never()).append(any());
    }

    @Test
    void revokeSetsRevokedAtAndAuditsPrefixOnly() {
        UUID existingId = UUID.randomUUID();
        ApiKey existing = new ApiKey(TENANT_UUID, "pk_abcd1234", "$2a$04$x", "primary");
        try {
            // Phase 8 T4: id is now on BaseEntity superclass — use getSuperclass().
            var f = ApiKey.class.getSuperclass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(existing, existingId);
        } catch (Exception e) { throw new RuntimeException(e); }
        when(repo.findById(existingId)).thenReturn(Optional.of(existing));

        service.revoke(existingId, ACTOR_UUID, "alice@example.com");

        assertThat(existing.isActive(clock.instant())).isFalse();
        ArgumentCaptor<AuditAppendRequest> auditCaptor =
                ArgumentCaptor.forClass(AuditAppendRequest.class);
        verify(audit).append(auditCaptor.capture());
        AuditAppendRequest aud = auditCaptor.getValue();
        assertThat(aud.action()).isEqualTo("API_KEY_REVOKE");
        assertThat(aud.payload().get("prefix")).isEqualTo("pk_abcd1234");
    }
}
