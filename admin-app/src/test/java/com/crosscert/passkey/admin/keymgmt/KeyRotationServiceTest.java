package com.crosscert.passkey.admin.keymgmt;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.admin.scheduler.SchedulerLeaseService;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import com.crosscert.passkey.core.entity.SigningKey;
import com.crosscert.passkey.core.jwt.KeyEnvelope;
import com.crosscert.passkey.core.jwt.SigningKeyProvider;
import com.crosscert.passkey.core.repository.SigningKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KeyRotationServiceTest {

    private SigningKeyRepository repo;
    private SigningKeyProvider provider;
    private SchedulerLeaseService leases;
    private AuditLogService audit;
    private KeyEnvelope envelope;
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
    private KeyRotationService svc;

    @BeforeEach
    void setUp() {
        repo = mock(SigningKeyRepository.class);
        provider = mock(SigningKeyProvider.class);
        leases = mock(SchedulerLeaseService.class);
        audit = mock(AuditLogService.class);
        envelope = new KeyEnvelope(
                Base64.getEncoder().encodeToString(new byte[32]),
                new SecureRandom());
        svc = new KeyRotationService(repo, provider, leases, audit, envelope, clock);
    }

    @Test
    void rotateTransitionsActiveAndInsertsNew() throws Exception {
        SigningKey current = freshActiveKey("old-kid");
        when(leases.tryAcquire(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
        when(repo.findFirstByStatusOrderByCreatedAtDesc("ACTIVE"))
                .thenReturn(Optional.of(current));
        when(repo.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        KeyRotationService.RotateResult result = svc.rotate(UUID.randomUUID(), "alice@example.com");

        assertThat(current.getStatus()).isEqualTo("ROTATED");
        assertThat(current.getRotatedAt()).isEqualTo(clock.instant());

        // Codex T17 P1-1: UPDATE-old (saveAndFlush) must happen BEFORE
        // INSERT-new (save) so Hibernate emits SQL in that order and
        // the function-based unique index sees one ACTIVE at any moment.
        InOrder ordered = Mockito.inOrder(repo);
        ordered.verify(repo).saveAndFlush(current);
        ordered.verify(repo).save(any(SigningKey.class));

        ArgumentCaptor<SigningKey> savedCaptor = ArgumentCaptor.forClass(SigningKey.class);
        verify(repo).save(savedCaptor.capture());
        SigningKey newRow = savedCaptor.getValue();
        assertThat(newRow.getStatus()).isEqualTo("ACTIVE");
        assertThat(newRow.getKid()).isNotEqualTo("old-kid");
        assertThat(newRow.getAlg()).isEqualTo("RS256");

        // No active TX in this unit test → provider.reload() runs inline
        // (the production path schedules it via TransactionSynchronization).
        verify(provider).reload();
        ArgumentCaptor<AuditAppendRequest> auditCap =
                ArgumentCaptor.forClass(AuditAppendRequest.class);
        verify(audit).append(auditCap.capture());
        assertThat(auditCap.getValue().action()).isEqualTo("SIGNING_KEY_ROTATE");
        assertThat(auditCap.getValue().payload()).containsKey("oldKid");
        assertThat(auditCap.getValue().payload()).containsKey("newKid");

        assertThat(result.oldKid()).isEqualTo("old-kid");
        assertThat(result.newKid()).isEqualTo(newRow.getKid());
    }

    @Test
    void rotateThrowsBusinessExceptionWhenLeaseUnavailable() {
        when(leases.tryAcquire(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);
        assertThatThrownBy(() -> svc.rotate(UUID.randomUUID(), "alice@example.com"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.KEY_ROTATION_CONFLICT);
    }

    @Test
    void rotateThrowsWhenNoActiveKeyExists() {
        when(leases.tryAcquire(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
        when(repo.findFirstByStatusOrderByCreatedAtDesc("ACTIVE"))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> svc.rotate(UUID.randomUUID(), "alice@example.com"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.KEY_NO_ACTIVE);
    }

    private SigningKey freshActiveKey(String kid) throws Exception {
        var gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        var pair = gen.generateKeyPair();
        com.nimbusds.jose.jwk.RSAKey rsa =
                new com.nimbusds.jose.jwk.RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey((RSAPrivateKey) pair.getPrivate())
                .keyID(kid)
                .keyUse(com.nimbusds.jose.jwk.KeyUse.SIGNATURE)
                .algorithm(com.nimbusds.jose.JWSAlgorithm.RS256)
                .build();
        return new SigningKey(kid, "RS256", rsa.toPublicJWK().toJSONString(),
                envelope.seal(pair.getPrivate().getEncoded()));
    }
}
