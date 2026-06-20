package com.crosscert.passkey.admin.apikey;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.admin.auth.TenantBoundary;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
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
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import com.crosscert.passkey.core.config.KstTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiKeyRotateServiceTest {

    private static final UUID TENANT_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR_UUID  = UUID.fromString("00000000-0000-0000-0000-000000000010");

    private ApiKeyRepository repo;
    private AuditLogService audit;
    private PasswordEncoder encoder;
    private TenantBoundary boundary;
    private TenantRepository tenants;
    private ApiKeyAdminService service;
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), KstTime.ZONE);
    private final Duration grace = Duration.ofHours(24);

    @BeforeEach
    void setUp() {
        repo = mock(ApiKeyRepository.class);
        audit = mock(AuditLogService.class);
        encoder = new BCryptPasswordEncoder(4); // fast for tests
        boundary = mock(TenantBoundary.class);
        tenants = mock(TenantRepository.class);
        Tenant active = mock(Tenant.class);
        when(active.isSuspended()).thenReturn(false);
        when(tenants.findById(any())).thenReturn(Optional.of(active));
        when(repo.findByKeyPrefix(anyString())).thenReturn(Optional.empty());
        service = new ApiKeyAdminService(repo, audit, encoder, new SecureRandom(), clock, boundary, tenants);
        setGrace(service, grace);

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

    private static void setGrace(ApiKeyAdminService svc, Duration g) {
        try {
            var f = ApiKeyAdminService.class.getDeclaredField("rotationGrace");
            f.setAccessible(true);
            f.set(svc, g);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private ApiKey activeKey() {
        ApiKey old = new ApiKey(TENANT_UUID, "pk_old12345", "$2a$04$x", "primary");
        old.addScope("registration");
        old.addScope("authentication");
        setId(old, UUID.randomUUID());
        return old;
    }

    private static void setId(ApiKey k, UUID id) {
        try {
            var f = ApiKey.class.getSuperclass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(k, id);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    void rotateIssuesNewKeyCopiesScopesAndGraceExpiresOldKey() {
        ApiKey old = activeKey();
        when(repo.findById(old.getId())).thenReturn(Optional.of(old));

        ApiKeyAdminDto.ApiKeyRotateResponse resp =
                service.rotate(old.getId(), ACTOR_UUID, "alice@example.com");

        // (a) new key plaintext returned once with copied scopes
        assertThat(resp.plaintextKey()).startsWith("pk_");
        assertThat(resp.prefix()).hasSize(11);
        assertThat(resp.scopes()).containsExactlyInAnyOrder("registration", "authentication");

        // old key grace-expired at now + grace
        assertThat(old.getExpiresAt()).isEqualTo(OffsetDateTime.now(clock).plus(grace));
        assertThat(resp.oldKeyExpiresAt()).isEqualTo(OffsetDateTime.now(clock).plus(grace));

        // the new key persisted via saveAndFlush carries the copied scopes + a hash matching the secret
        ArgumentCaptor<ApiKey> savedCaptor = ArgumentCaptor.forClass(ApiKey.class);
        verify(repo).saveAndFlush(savedCaptor.capture());
        ApiKey fresh = savedCaptor.getValue();
        assertThat(fresh.getScopeValues()).containsExactlyInAnyOrder("registration", "authentication");
        String secret = resp.plaintextKey().substring(resp.prefix().length());
        assertThat(encoder.matches(secret, fresh.getKeyHash())).isTrue();
        assertThat(fresh.getName()).isEqualTo("primary");
    }

    @Test
    void rotateAuditsPrefixesNotSecret() {
        ApiKey old = activeKey();
        when(repo.findById(old.getId())).thenReturn(Optional.of(old));

        ApiKeyAdminDto.ApiKeyRotateResponse resp =
                service.rotate(old.getId(), ACTOR_UUID, "alice@example.com");

        ArgumentCaptor<AuditAppendRequest> auditCaptor = ArgumentCaptor.forClass(AuditAppendRequest.class);
        verify(audit).append(auditCaptor.capture());
        AuditAppendRequest aud = auditCaptor.getValue();
        assertThat(aud.action()).isEqualTo("API_KEY_ROTATED");
        assertThat(aud.payload()).containsKeys("oldPrefix", "newPrefix", "tenantId", "oldKeyExpiresAt");
        assertThat(aud.payload().get("oldPrefix")).isEqualTo("pk_old12345");
        assertThat(aud.payload().values()).doesNotContain(resp.plaintextKey());
    }

    @Test
    void rotateRejectsRevokedKey() {
        ApiKey old = activeKey();
        old.revoke(OffsetDateTime.now(clock).minusSeconds(60));
        when(repo.findById(old.getId())).thenReturn(Optional.of(old));

        assertThatThrownBy(() -> service.rotate(old.getId(), ACTOR_UUID, "alice@example.com"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.API_KEY_NOT_FOUND);

        verify(repo, never()).saveAndFlush(any());
        verify(audit, never()).append(any());
    }

    @Test
    void rotateRejectsExpiredKey() {
        ApiKey old = activeKey();
        old.expireAt(OffsetDateTime.now(clock).minusSeconds(60));
        when(repo.findById(old.getId())).thenReturn(Optional.of(old));

        assertThatThrownBy(() -> service.rotate(old.getId(), ACTOR_UUID, "alice@example.com"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.API_KEY_NOT_FOUND);
    }

    @Test
    void rotateRejectsMissingKey() {
        UUID missing = UUID.randomUUID();
        when(repo.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rotate(missing, ACTOR_UUID, "alice@example.com"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.API_KEY_NOT_FOUND);
    }

    @Test
    void rotateRejectsSuspendedTenant() {
        ApiKey old = activeKey();
        when(repo.findById(old.getId())).thenReturn(Optional.of(old));
        Tenant suspended = mock(Tenant.class);
        when(suspended.isSuspended()).thenReturn(true);
        when(tenants.findById(TENANT_UUID)).thenReturn(Optional.of(suspended));

        assertThatThrownBy(() -> service.rotate(old.getId(), ACTOR_UUID, "alice@example.com"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.TENANT_SUSPENDED);

        verify(repo, never()).saveAndFlush(any());
        verify(audit, never()).append(any());
    }
}
