package com.crosscert.passkey.admin.scheduler

import com.crosscert.passkey.core.config.KstTime
import com.crosscert.passkey.core.entity.SchedulerLease
import com.crosscert.passkey.core.repository.SchedulerLeaseRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.dao.DataIntegrityViolationException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Optional
import java.util.UUID

class SchedulerLeaseServiceTest {

    companion object {
        private val NOW: Instant = Instant.parse("2026-06-01T00:00:00Z")
        private val FIXED_CLOCK: Clock = Clock.fixed(NOW, KstTime.ZONE)
    }

    private lateinit var repo: SchedulerLeaseRepository
    private lateinit var svc: SchedulerLeaseService

    @BeforeEach
    fun setUp() {
        repo = mock()
        svc = SchedulerLeaseService(repo, FIXED_CLOCK)
    }

    // ----------------------------------------------------------------
    // tryAcquire — absent lease
    // ----------------------------------------------------------------

    @Test
    fun tryAcquireInsertsLeaseWhenAbsent() {
        whenever(repo.findByNameForUpdate("mds-sync")).thenReturn(Optional.empty())
        whenever(repo.save(anyOrNull<SchedulerLease>())).thenAnswer { inv -> inv.getArgument<SchedulerLease>(0) }

        val got = svc.tryAcquire("mds-sync", "host-1", Duration.ofMinutes(5))

        assertThat(got).isTrue()
        verify(repo).save(any<SchedulerLease>())
    }

    @Test
    fun tryAcquireReturnsFalseOnDuplicateKeyRace() {
        // Two nodes both see empty; the second one hits the unique constraint.
        whenever(repo.findByNameForUpdate("mds-sync")).thenReturn(Optional.empty())
        whenever(repo.save(anyOrNull<SchedulerLease>())).thenThrow(DataIntegrityViolationException("unique"))

        val got = svc.tryAcquire("mds-sync", "host-1", Duration.ofMinutes(5))

        assertThat(got).isFalse()
    }

    // ----------------------------------------------------------------
    // tryAcquire — existing lease
    // ----------------------------------------------------------------

    @Test
    fun tryAcquireTakesOverExpiredLease() {
        val expired = OffsetDateTime.now(FIXED_CLOCK).minusSeconds(60)
        val lease = SchedulerLease(
            UUID.randomUUID(), "mds-sync", "other-host", expired,
        )
        whenever(repo.findByNameForUpdate("mds-sync")).thenReturn(Optional.of(lease))
        whenever(repo.save(anyOrNull<SchedulerLease>())).thenAnswer { inv -> inv.getArgument<SchedulerLease>(0) }

        val got = svc.tryAcquire("mds-sync", "host-1", Duration.ofMinutes(5))

        assertThat(got).isTrue()
        assertThat(lease.holder).isEqualTo("host-1")
        verify(repo).save(lease)
    }

    @Test
    fun tryAcquireRenewsOwnLease() {
        val future = OffsetDateTime.now(FIXED_CLOCK).plusSeconds(60)
        val lease = SchedulerLease(
            UUID.randomUUID(), "mds-sync", "host-1", future,
        )
        whenever(repo.findByNameForUpdate("mds-sync")).thenReturn(Optional.of(lease))
        whenever(repo.save(anyOrNull<SchedulerLease>())).thenAnswer { inv -> inv.getArgument<SchedulerLease>(0) }

        val got = svc.tryAcquire("mds-sync", "host-1", Duration.ofMinutes(5))

        assertThat(got).isTrue()
        verify(repo).save(lease)
    }

    @Test
    fun tryAcquireReturnsFalseWhenAnotherHolderActive() {
        val future = OffsetDateTime.now(FIXED_CLOCK).plusSeconds(60)
        val lease = SchedulerLease(
            UUID.randomUUID(), "mds-sync", "somebody-else", future,
        )
        whenever(repo.findByNameForUpdate("mds-sync")).thenReturn(Optional.of(lease))

        val got = svc.tryAcquire("mds-sync", "host-1", Duration.ofMinutes(5))

        assertThat(got).isFalse()
        verify(repo, never()).save(anyOrNull<SchedulerLease>())
    }

    // ----------------------------------------------------------------
    // release
    // ----------------------------------------------------------------

    @Test
    fun releaseIssuesAtomicPredicateDelete() {
        whenever(repo.deleteByNameAndHolder("mds-sync", "host-1")).thenReturn(1)

        svc.release("mds-sync", "host-1")

        verify(repo).deleteByNameAndHolder("mds-sync", "host-1")
    }

    @Test
    fun releaseNoOpWhenHolderMismatch() {
        whenever(repo.deleteByNameAndHolder("mds-sync", "host-1")).thenReturn(0)

        svc.release("mds-sync", "host-1")

        verify(repo).deleteByNameAndHolder("mds-sync", "host-1")
        // No entity-level delete invoked — atomicity via SQL predicate.
        verify(repo, never()).delete(anyOrNull())
    }
}
