package com.crosscert.passkey.admin.apikey;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.core.entity.ApiKey;
import com.crosscert.passkey.core.repository.ApiKeyRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiKeyAdminServiceTest {

    private ApiKeyRepository repo;
    private AuditLogService audit;
    private PasswordEncoder encoder;
    private ApiKeyAdminService service;
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        repo = mock(ApiKeyRepository.class);
        audit = mock(AuditLogService.class);
        encoder = new BCryptPasswordEncoder(4); // fast for tests
        service = new ApiKeyAdminService(repo, audit, encoder, new SecureRandom(), clock);
    }

    @Test
    void issueProducesPlainTextAndPersistsHash() {
        when(repo.findByKeyPrefix(anyString())).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ApiKeyAdminDto.ApiKeyCreateRequest req = new ApiKeyAdminDto.ApiKeyCreateRequest(
                "T_A", "primary", "[]", null);
        ApiKeyAdminDto.ApiKeyCreateResponse resp =
                service.issue(req, 7L, "alice@example.com");

        assertThat(resp.plainText()).startsWith("pk_");
        assertThat(resp.plainText()).hasSizeGreaterThan(20);
        assertThat(resp.prefix()).hasSize(11); // pk_ + 8 chars

        ArgumentCaptor<ApiKey> keyCaptor = ArgumentCaptor.forClass(ApiKey.class);
        verify(repo).save(keyCaptor.capture());
        ApiKey saved = keyCaptor.getValue();
        // Verify the persisted hash actually matches the plaintext secret
        // (full plainText = prefix + secret; we know prefix == resp.prefix).
        String secret = resp.plainText().substring(resp.prefix().length());
        assertThat(encoder.matches(secret, saved.getKeyHash())).isTrue();
    }

    @Test
    void issueAppendsAuditWithoutSecret() {
        when(repo.findByKeyPrefix(anyString())).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.issue(new ApiKeyAdminDto.ApiKeyCreateRequest("T_A", "primary", "[]", null),
                      7L, "alice@example.com");

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
                .thenReturn(Optional.of(new ApiKey("T_A","pk_xxxxxxxx","h","n","[]")))
                .thenReturn(Optional.of(new ApiKey("T_A","pk_yyyyyyyy","h","n","[]")))
                .thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ApiKeyAdminDto.ApiKeyCreateResponse resp = service.issue(
                new ApiKeyAdminDto.ApiKeyCreateRequest("T_A", "primary", "[]", null),
                7L, "alice@example.com");
        assertThat(resp.prefix()).startsWith("pk_");
    }

    @Test
    void revokeThrowsBusinessExceptionWhenIdNotFound() {
        when(repo.findById(404L)).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.revoke(404L, 7L, "alice@example.com"))
                .isInstanceOf(com.crosscert.passkey.core.api.BusinessException.class)
                .extracting(e -> ((com.crosscert.passkey.core.api.BusinessException) e).getErrorCode())
                .isEqualTo(com.crosscert.passkey.core.api.ErrorCode.API_KEY_NOT_FOUND);

        org.mockito.Mockito.verify(audit, org.mockito.Mockito.never()).append(any());
    }

    @Test
    void revokeSetsRevokedAtAndAuditsPrefixOnly() {
        ApiKey existing = new ApiKey("T_A", "pk_abcd1234", "$2a$04$x", "primary", "[]");
        try {
            var f = ApiKey.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(existing, 99L);
        } catch (Exception e) { throw new RuntimeException(e); }
        when(repo.findById(99L)).thenReturn(Optional.of(existing));

        service.revoke(99L, 7L, "alice@example.com");

        assertThat(existing.isActive(clock.instant())).isFalse();
        ArgumentCaptor<AuditAppendRequest> auditCaptor =
                ArgumentCaptor.forClass(AuditAppendRequest.class);
        verify(audit).append(auditCaptor.capture());
        AuditAppendRequest aud = auditCaptor.getValue();
        assertThat(aud.action()).isEqualTo("API_KEY_REVOKE");
        assertThat(aud.payload().get("prefix")).isEqualTo("pk_abcd1234");
    }
}
