package com.crosscert.passkey.admin.keymgmt;

import com.crosscert.passkey.admin.audit.AuditAppendRequest;
import com.crosscert.passkey.admin.audit.AuditLogService;
import com.crosscert.passkey.admin.scheduler.SchedulerLeaseService;
import com.crosscert.passkey.core.entity.SigningKey;
import com.crosscert.passkey.core.repository.SigningKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KeyExpirationJobTest {

    private SigningKeyRepository repo;
    private SchedulerLeaseService leases;
    private AuditLogService audit;
    private KeyExpirationJob job;
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        repo = mock(SigningKeyRepository.class);
        leases = mock(SchedulerLeaseService.class);
        audit = mock(AuditLogService.class);
        job = new KeyExpirationJob(repo, leases, audit, clock, Duration.ofMinutes(30));
    }

    @Test
    void skipsWhenLeaseUnavailable() {
        when(leases.tryAcquire(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);
        job.runOnce();
        verify(repo, never()).findAllByStatusAndRotatedAtBefore(anyString(), any());
    }

    @Test
    void transitionsExpiredRotatedToRevoked() throws Exception {
        when(leases.tryAcquire(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
        // rotated at clock - 31min — past the 30min grace
        SigningKey old = withId(rotatedKey("expired"), UUID.fromString("00000000-0000-0000-0000-000000000001"));
        setRotatedAt(old, clock.instant().minus(Duration.ofMinutes(31)));
        when(repo.findAllByStatusAndRotatedAtBefore(eq("ROTATED"), any()))
                .thenReturn(List.of(old));

        job.runOnce();

        assertThat(old.getStatus()).isEqualTo("REVOKED");
        assertThat(old.getRevokedAt()).isEqualTo(clock.instant());
        verify(repo).save(old);

        ArgumentCaptor<AuditAppendRequest> auditCap =
                ArgumentCaptor.forClass(AuditAppendRequest.class);
        verify(audit).append(auditCap.capture());
        assertThat(auditCap.getValue().action()).isEqualTo("SIGNING_KEY_REVOKE");
        assertThat(auditCap.getValue().payload().get("kid")).isEqualTo("expired");
    }

    @Test
    void leavesNonExpiredRotatedAlone() throws Exception {
        when(leases.tryAcquire(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
        when(repo.findAllByStatusAndRotatedAtBefore(eq("ROTATED"), any()))
                .thenReturn(List.of());
        job.runOnce();
        verify(repo, never()).save(any());
        verify(audit, never()).append(any());
    }

    private static SigningKey rotatedKey(String kid) {
        SigningKey k = new SigningKey(kid, "RS256", "{}", new byte[]{0});
        k.rotate(Instant.parse("2026-06-01T00:00:00Z"));
        return k;
    }

    private static SigningKey withId(SigningKey k, UUID id) throws Exception {
        Field f = SigningKey.class.getSuperclass().getDeclaredField("id");
        f.setAccessible(true);
        f.set(k, id);
        return k;
    }

    private static void setRotatedAt(SigningKey k, Instant t) throws Exception {
        Field f = SigningKey.class.getDeclaredField("rotatedAt");
        f.setAccessible(true);
        f.set(k, t);
    }
}
