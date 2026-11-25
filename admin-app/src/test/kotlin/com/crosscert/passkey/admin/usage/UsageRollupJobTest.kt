package com.crosscert.passkey.admin.usage

import com.crosscert.passkey.admin.scheduler.SchedulerLeaseService
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
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
        verify(leases).release(anyString(), anyString())
    }

    @Test
    fun rollupThrows_stillReleasesLease() {
        whenever(leases.tryAcquire(anyString(), anyString(), any<Duration>())).thenReturn(true)
        whenever(rollup.rollupRecent()).thenThrow(RuntimeException("db error"))
        assertThatThrownBy { job.runOnce() }.isInstanceOf(RuntimeException::class.java)
        verify(leases).release(anyString(), anyString())
    }
}
