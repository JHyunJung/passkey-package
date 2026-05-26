package com.crosscert.passkey.admin.scheduler;

import com.crosscert.passkey.core.entity.SchedulerLease;
import com.crosscert.passkey.core.repository.SchedulerLeaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SchedulerLeaseServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private SchedulerLeaseRepository repo;
    private SchedulerLeaseService svc;

    @BeforeEach
    void setUp() {
        repo = mock(SchedulerLeaseRepository.class);
        svc = new SchedulerLeaseService(repo, FIXED_CLOCK);
    }

    // ----------------------------------------------------------------
    // tryAcquire — absent lease
    // ----------------------------------------------------------------

    @Test
    void tryAcquireInsertsLeaseWhenAbsent() {
        when(repo.findByNameForUpdate("mds-sync")).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        boolean got = svc.tryAcquire("mds-sync", "host-1", Duration.ofMinutes(5));

        assertThat(got).isTrue();
        verify(repo).save(any(SchedulerLease.class));
    }

    @Test
    void tryAcquireReturnsFalseOnDuplicateKeyRace() {
        // Two nodes both see empty; the second one hits the unique constraint.
        when(repo.findByNameForUpdate("mds-sync")).thenReturn(Optional.empty());
        when(repo.save(any())).thenThrow(new DataIntegrityViolationException("unique"));

        boolean got = svc.tryAcquire("mds-sync", "host-1", Duration.ofMinutes(5));

        assertThat(got).isFalse();
    }

    // ----------------------------------------------------------------
    // tryAcquire — existing lease
    // ----------------------------------------------------------------

    @Test
    void tryAcquireTakesOverExpiredLease() {
        Instant expired = NOW.minusSeconds(60);
        SchedulerLease lease = new SchedulerLease(
                UUID.randomUUID(), "mds-sync", "other-host", expired);
        when(repo.findByNameForUpdate("mds-sync")).thenReturn(Optional.of(lease));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        boolean got = svc.tryAcquire("mds-sync", "host-1", Duration.ofMinutes(5));

        assertThat(got).isTrue();
        assertThat(lease.getHolder()).isEqualTo("host-1");
        verify(repo).save(lease);
    }

    @Test
    void tryAcquireRenewsOwnLease() {
        Instant future = NOW.plusSeconds(60);
        SchedulerLease lease = new SchedulerLease(
                UUID.randomUUID(), "mds-sync", "host-1", future);
        when(repo.findByNameForUpdate("mds-sync")).thenReturn(Optional.of(lease));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        boolean got = svc.tryAcquire("mds-sync", "host-1", Duration.ofMinutes(5));

        assertThat(got).isTrue();
        verify(repo).save(lease);
    }

    @Test
    void tryAcquireReturnsFalseWhenAnotherHolderActive() {
        Instant future = NOW.plusSeconds(60);
        SchedulerLease lease = new SchedulerLease(
                UUID.randomUUID(), "mds-sync", "somebody-else", future);
        when(repo.findByNameForUpdate("mds-sync")).thenReturn(Optional.of(lease));

        boolean got = svc.tryAcquire("mds-sync", "host-1", Duration.ofMinutes(5));

        assertThat(got).isFalse();
        verify(repo, never()).save(any());
    }

    // ----------------------------------------------------------------
    // release
    // ----------------------------------------------------------------

    @Test
    void releaseIssuesAtomicPredicateDelete() {
        when(repo.deleteByNameAndHolder("mds-sync", "host-1")).thenReturn(1);

        svc.release("mds-sync", "host-1");

        verify(repo).deleteByNameAndHolder("mds-sync", "host-1");
    }

    @Test
    void releaseNoOpWhenHolderMismatch() {
        when(repo.deleteByNameAndHolder("mds-sync", "host-1")).thenReturn(0);

        svc.release("mds-sync", "host-1");

        verify(repo).deleteByNameAndHolder("mds-sync", "host-1");
        // No entity-level delete invoked — atomicity via SQL predicate.
        verify(repo, never()).delete(any());
    }
}
