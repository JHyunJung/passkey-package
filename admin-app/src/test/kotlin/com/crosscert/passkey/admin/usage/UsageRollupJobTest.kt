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
    lateinit var leases: SchedulerLeaseService

    lateinit var job: UsageRollupJob

    @BeforeEach
    fun setUp() {
        job = UsageRollupJob(rollup, leases)
    }

    @Test
    fun leaseNotAcquired_doesNothing() {
        whenever(leases.tryAcquire(anyString(), anyString(), any<Duration>())).thenReturn(false)
        job.runOnce()
        verifyNoInteractions(rollup)
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
