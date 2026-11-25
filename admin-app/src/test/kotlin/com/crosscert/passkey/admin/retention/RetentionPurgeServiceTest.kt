package com.crosscert.passkey.admin.retention

import com.crosscert.passkey.admin.mds.MdsHistoryService
import com.crosscert.passkey.admin.usage.UsageCloseService
import com.crosscert.passkey.core.repository.AdminPasswordResetTokenRepository
import com.crosscert.passkey.core.repository.AdminSignupRequestRepository
import com.crosscert.passkey.core.repository.AdminUserRecoveryCodeRepository
import com.crosscert.passkey.core.repository.CeremonyEventRepository
import com.crosscert.passkey.core.repository.CredentialAuthEventRepository
import com.crosscert.passkey.core.repository.TenantWebauthnSnapshotRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import java.time.OffsetDateTime

@ExtendWith(MockitoExtension::class)
class RetentionPurgeServiceTest {

    @Mock
    lateinit var signupRequests: AdminSignupRequestRepository

    @Mock
    lateinit var resetTokens: AdminPasswordResetTokenRepository

    @Mock
    lateinit var recoveryCodes: AdminUserRecoveryCodeRepository

    @Mock
    lateinit var snapshots: TenantWebauthnSnapshotRepository

    @Mock
    lateinit var mdsHistory: MdsHistoryService

    @Mock
    lateinit var ceremonyEvents: CeremonyEventRepository

    @Mock
    lateinit var credentialAuthEvents: CredentialAuthEventRepository

    @Mock
    lateinit var usageClose: UsageCloseService

    lateinit var service: RetentionPurgeService

    @BeforeEach
    fun setUp() {
        service = RetentionPurgeService(
            signupRequests, resetTokens, recoveryCodes, snapshots, mdsHistory,
            ceremonyEvents, credentialAuthEvents, usageClose,
        )
    }

    @Test
    fun purgeSignupRequests_delegates_cutoff_and_returns_count() {
        val cutoff = OffsetDateTime.parse("2026-01-01T00:00:00Z")
        whenever(signupRequests.deleteRequestedBefore(eq(cutoff), anyInt())).thenReturn(3)
        assertThat(service.purgeSignupRequests(cutoff)).isEqualTo(3)
    }

    @Test
    fun purgeRecoveryCodes_delegates_to_used_before() {
        val cutoff = OffsetDateTime.parse("2026-01-01T00:00:00Z")
        whenever(recoveryCodes.deleteUsedBefore(eq(cutoff), anyInt())).thenReturn(7)
        assertThat(service.purgeRecoveryCodes(cutoff)).isEqualTo(7)
    }

    @Test
    fun purgeMdsHistory_delegates_to_history_service() {
        val cutoff = OffsetDateTime.parse("2026-01-01T00:00:00Z")
        whenever(mdsHistory.purgeStartedBefore(eq(cutoff), anyInt())).thenReturn(5)
        assertThat(service.purgeMdsHistory(cutoff)).isEqualTo(5)
    }

    @Test
    fun purgeCeremonyEvents_cutoffEarlierThanReleased_usesCutoff() {
        val cutoff = OffsetDateTime.parse("2026-08-27T10:00:00+09:00")
        whenever(usageClose.releasedBefore()).thenReturn(OffsetDateTime.parse("2026-10-01T00:00:00+09:00"))
        whenever(ceremonyEvents.deleteCreatedBefore(eq(cutoff), anyInt())).thenReturn(9)
        assertThat(service.purgeCeremonyEvents(cutoff)).isEqualTo(9)
        verify(ceremonyEvents).deleteCreatedBefore(cutoff, RetentionPurgeService.BATCH)
    }

    @Test
    fun purgeCeremonyEvents_unclosedMonthBlocksPurge() {
        val cutoff = OffsetDateTime.parse("2026-12-15T00:00:00+09:00")
        val released = OffsetDateTime.parse("2026-09-01T00:00:00+09:00")
        whenever(usageClose.releasedBefore()).thenReturn(released)
        whenever(ceremonyEvents.deleteCreatedBefore(eq(released), anyInt())).thenReturn(2)
        assertThat(service.purgeCeremonyEvents(cutoff)).isEqualTo(2)
        verify(ceremonyEvents).deleteCreatedBefore(released, RetentionPurgeService.BATCH)
    }

    @Test
    fun purgeCeremonyEvents_releasedBeforeThrows_propagatesAndDeletesNothing() {
        val cutoff = OffsetDateTime.parse("2026-12-15T00:00:00+09:00")
        whenever(usageClose.releasedBefore()).thenThrow(IllegalStateException("db down"))
        org.assertj.core.api.Assertions.assertThatThrownBy { service.purgeCeremonyEvents(cutoff) }
            .isInstanceOf(IllegalStateException::class.java)
        verifyNoInteractions(ceremonyEvents)
    }

    @Test
    fun purgeCredentialAuthEvents_unaffected_byUsageClose() {
        val cutoff = OffsetDateTime.parse("2026-12-15T00:00:00+09:00")
        whenever(credentialAuthEvents.deleteCreatedBefore(eq(cutoff), anyInt())).thenReturn(1)
        service.purgeCredentialAuthEvents(cutoff)
        verify(credentialAuthEvents).deleteCreatedBefore(cutoff, RetentionPurgeService.BATCH)
        verifyNoInteractions(usageClose)
    }

    @Test
    fun purgeCredentialAuthEvents_delegates_to_created_before() {
        val cutoff = OffsetDateTime.parse("2026-01-01T00:00:00Z")
        whenever(credentialAuthEvents.deleteCreatedBefore(eq(cutoff), anyInt())).thenReturn(4)
        assertThat(service.purgeCredentialAuthEvents(cutoff)).isEqualTo(4)
    }

    @Test
    fun purge_loops_until_batch_not_full_and_sums_totals() {
        val cutoff = OffsetDateTime.parse("2026-01-01T00:00:00Z")
        // 1회차 full batch(1000) → 반복, 2회차 250(<1000) → 종료. 총 1250.
        whenever(signupRequests.deleteRequestedBefore(eq(cutoff), anyInt()))
            .thenReturn(RetentionPurgeService.BATCH, 250)
        assertThat(service.purgeSignupRequests(cutoff))
            .isEqualTo(RetentionPurgeService.BATCH + 250)
    }
}
