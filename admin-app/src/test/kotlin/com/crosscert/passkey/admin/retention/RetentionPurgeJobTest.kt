package com.crosscert.passkey.admin.retention

import com.crosscert.passkey.admin.audit.AuditAppendRequest
import com.crosscert.passkey.admin.audit.AuditLogService
import com.crosscert.passkey.admin.scheduler.SchedulerLeaseService
import com.crosscert.passkey.core.config.KstTime
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Duration
import java.time.Instant

@ExtendWith(MockitoExtension::class)
class RetentionPurgeJobTest {

    @Mock
    lateinit var service: RetentionPurgeService

    @Mock
    lateinit var leases: SchedulerLeaseService

    @Mock
    lateinit var audit: AuditLogService

    var clock: Clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), KstTime.ZONE)
    lateinit var job: RetentionPurgeJob

    @BeforeEach
    fun setUp() {
        job = RetentionPurgeJob(
            service, leases, audit, clock,
            Duration.ofDays(90), Duration.ofDays(30), Duration.ofDays(180),
            Duration.ofDays(365), Duration.ofDays(90), Duration.ofDays(90),
            Duration.ofDays(90),
        )
    }

    @Test
    fun skips_when_lease_not_acquired() {
        whenever(leases.tryAcquire(anyString(), anyString(), any<Duration>())).thenReturn(false)
        job.runOnce()
        verifyNoInteractions(service)
        verifyNoInteractions(audit)
    }

    @Test
    fun one_table_failure_does_not_block_others_and_audits() {
        whenever(leases.tryAcquire(anyString(), anyString(), any<Duration>())).thenReturn(true)
        whenever(service.purgeSignupRequests(anyOrNull())).thenReturn(2)
        whenever(service.purgeResetTokens(anyOrNull())).thenThrow(RuntimeException("db error"))
        whenever(service.purgeRecoveryCodes(anyOrNull())).thenReturn(1)
        whenever(service.purgeSnapshots(anyOrNull())).thenReturn(0)
        whenever(service.purgeMdsHistory(anyOrNull())).thenReturn(4)
        whenever(service.purgeCeremonyEvents(anyOrNull())).thenReturn(6)
        whenever(service.purgeCredentialAuthEvents(anyOrNull())).thenReturn(3)

        job.runOnce()

        verify(service).purgeSignupRequests(anyOrNull())
        verify(service).purgeResetTokens(anyOrNull())
        verify(service).purgeRecoveryCodes(anyOrNull())
        verify(service).purgeSnapshots(anyOrNull())
        verify(service).purgeMdsHistory(anyOrNull())
        verify(service).purgeCeremonyEvents(anyOrNull())
        verify(service).purgeCredentialAuthEvents(anyOrNull())
        val cap = argumentCaptor<AuditAppendRequest>()
        verify(audit).append(cap.capture())
        assertThat(cap.lastValue.action).isEqualTo("RETENTION_PURGE")
        assertThat(checkNotNull(cap.lastValue.payload)["signupRequestsPurged"]).isEqualTo(2)
        // sentinel 제거: 실패한 테이블은 count 키 자체가 없다(합산 오염 방지).
        assertThat(cap.lastValue.payload).doesNotContainKey("resetTokensPurged")
        assertThat(checkNotNull(checkNotNull(cap.lastValue.payload)["failed"]).toString()).contains("resetTokens")
        // lease 는 정상·예외 어느 경로든 즉시 반환.
        verify(leases).release(anyString(), anyString())
    }

    @Test
    fun all_success_audits_every_count_and_empty_failed() {
        whenever(leases.tryAcquire(anyString(), anyString(), any<Duration>())).thenReturn(true)
        whenever(service.purgeSignupRequests(anyOrNull())).thenReturn(5)
        whenever(service.purgeResetTokens(anyOrNull())).thenReturn(3)
        whenever(service.purgeRecoveryCodes(anyOrNull())).thenReturn(0)
        whenever(service.purgeSnapshots(anyOrNull())).thenReturn(7)
        whenever(service.purgeMdsHistory(anyOrNull())).thenReturn(2)
        whenever(service.purgeCeremonyEvents(anyOrNull())).thenReturn(8)
        whenever(service.purgeCredentialAuthEvents(anyOrNull())).thenReturn(11)

        job.runOnce()

        val cap = argumentCaptor<AuditAppendRequest>()
        verify(audit).append(cap.capture())
        val payload = checkNotNull(cap.lastValue.payload)
        assertThat(payload["signupRequestsPurged"]).isEqualTo(5)
        assertThat(payload["resetTokensPurged"]).isEqualTo(3)
        assertThat(payload["recoveryCodesPurged"]).isEqualTo(0)
        assertThat(payload["snapshotsPurged"]).isEqualTo(7)
        assertThat(payload["mdsHistoryPurged"]).isEqualTo(2)
        assertThat(payload["ceremonyEventsPurged"]).isEqualTo(8)
        assertThat(payload["credentialAuthEventsPurged"]).isEqualTo(11)
        assertThat(payload["failed"] as List<*>?).isEmpty()
        verify(leases).release(anyString(), anyString())
    }

    @Test
    fun all_zero_still_audits_with_zero_counts_and_empty_failed() {
        whenever(leases.tryAcquire(anyString(), anyString(), any<Duration>())).thenReturn(true)
        whenever(service.purgeSignupRequests(anyOrNull())).thenReturn(0)
        whenever(service.purgeResetTokens(anyOrNull())).thenReturn(0)
        whenever(service.purgeRecoveryCodes(anyOrNull())).thenReturn(0)
        whenever(service.purgeSnapshots(anyOrNull())).thenReturn(0)
        whenever(service.purgeMdsHistory(anyOrNull())).thenReturn(0)
        whenever(service.purgeCeremonyEvents(anyOrNull())).thenReturn(0)
        whenever(service.purgeCredentialAuthEvents(anyOrNull())).thenReturn(0)

        job.runOnce()

        val cap = argumentCaptor<AuditAppendRequest>()
        verify(audit).append(cap.capture())
        val payload = checkNotNull(cap.lastValue.payload)
        assertThat(payload["signupRequestsPurged"]).isEqualTo(0)
        assertThat(payload["resetTokensPurged"]).isEqualTo(0)
        assertThat(payload["recoveryCodesPurged"]).isEqualTo(0)
        assertThat(payload["snapshotsPurged"]).isEqualTo(0)
        assertThat(payload["mdsHistoryPurged"]).isEqualTo(0)
        assertThat(payload["ceremonyEventsPurged"]).isEqualTo(0)
        assertThat(payload["credentialAuthEventsPurged"]).isEqualTo(0)
        assertThat(payload["failed"] as List<*>?).isEmpty()
        verify(leases).release(anyString(), anyString())
    }
}
