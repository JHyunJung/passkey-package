package com.crosscert.passkey.admin.usage

import com.crosscert.passkey.admin.scheduler.SchedulerLeaseService
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.assertj.core.api.Assertions.assertThat
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.time.Duration

@ExtendWith(MockitoExtension::class)
class UsageRollupJobTest {

    @Mock
    lateinit var rollup: UsageRollupService

    @Mock
    lateinit var close: UsageCloseService

    @Mock
    lateinit var leases: SchedulerLeaseService

    lateinit var job: UsageRollupJob

    @BeforeEach
    fun setUp() {
        job = UsageRollupJob(rollup, close, leases)
    }

    @Test
    fun leaseNotAcquired_doesNothing() {
        whenever(leases.tryAcquire(anyString(), anyString(), any<Duration>())).thenReturn(false)
        job.runOnce()
        verifyNoInteractions(rollup, close)
        verify(leases, never()).release(anyString(), anyString())
    }

    @Test
    fun leaseAcquired_runsRollup_andReleases() {
        whenever(leases.tryAcquire(anyString(), anyString(), any<Duration>())).thenReturn(true)
        job.runOnce()
        verify(rollup).rollupRecent()
        assertLeaseAcquiredAndReleasedWithSameHolder()
    }

    @Test
    fun leaseAcquired_runsBackfillThenRollupThenClose() {
        whenever(leases.tryAcquire(anyString(), anyString(), any<Duration>())).thenReturn(true)
        job.runOnce()
        val order = inOrder(close, rollup, leases)
        order.verify(close).backfillIfNeeded()
        order.verify(rollup).rollupRecent()
        order.verify(close).closeDueMonths()
        order.verify(leases).release(eq("usage-rollup"), anyString())
    }

    @Test
    fun backfillThrows_skipsRollupAndClose_stillReleasesLease() {
        // 백필이 끝나지 않은 채 마감하면 부분 월이 될 달이 CLOSED 로 확정될 수 있다 — 이번 실행은 여기서 멈춘다.
        whenever(leases.tryAcquire(anyString(), anyString(), any<Duration>())).thenReturn(true)
        whenever(close.backfillIfNeeded()).thenThrow(IllegalStateException("audit down"))
        assertThatThrownBy { job.runOnce() }.isInstanceOf(IllegalStateException::class.java)
        verify(rollup, never()).rollupRecent()
        verify(close, never()).closeDueMonths()
        assertLeaseAcquiredAndReleasedWithSameHolder()
    }

    @Test
    fun rollupThrows_stillReleasesLease() {
        whenever(leases.tryAcquire(anyString(), anyString(), any<Duration>())).thenReturn(true)
        whenever(rollup.rollupRecent()).thenThrow(RuntimeException("db error"))
        assertThatThrownBy { job.runOnce() }.isInstanceOf(RuntimeException::class.java)
        assertLeaseAcquiredAndReleasedWithSameHolder()
    }

    private fun assertLeaseAcquiredAndReleasedWithSameHolder() {
        val name = argumentCaptor<String>()
        val holder = argumentCaptor<String>()
        val ttl = argumentCaptor<Duration>()
        verify(leases).tryAcquire(name.capture(), holder.capture(), ttl.capture())
        assertThat(name.lastValue).isEqualTo("usage-rollup")
        assertThat(ttl.lastValue).isEqualTo(Duration.ofMinutes(30))
        verify(leases).release(eq("usage-rollup"), eq(holder.lastValue))
    }
}
