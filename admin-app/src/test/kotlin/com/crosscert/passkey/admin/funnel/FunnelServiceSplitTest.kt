package com.crosscert.passkey.admin.funnel

import com.crosscert.passkey.admin.auth.TenantBoundary
import com.crosscert.passkey.core.ceremony.CeremonyAction
import com.crosscert.passkey.core.ceremony.CredentialAuthResult
import com.crosscert.passkey.core.config.KstTime
import com.crosscert.passkey.core.repository.CeremonyEventRepository
import com.crosscert.passkey.core.repository.CredentialAuthEventRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.sql.Timestamp
import java.time.LocalDate
import java.util.UUID

/** 인증 현황 탭용: 일별 값을 등록·인증으로 나누고(기존 series 는 합계 그대로), 인증 실패를 원인별로 센다. */
class FunnelServiceSplitTest {

    private val repo: CeremonyEventRepository = mock()
    private val authEvents: CredentialAuthEventRepository = mock()
    private val service = FunnelService(repo, authEvents, mock<TenantBoundary>())

    @Test
    fun splitsDailySeriesAndCountsFailures() {
        val tenant = UUID.randomUUID()
        val today = LocalDate.now(KstTime.ZONE)
        val day = Timestamp.from(today.atStartOfDay(KstTime.ZONE).toInstant())
        val rows: MutableList<Array<Any?>> = ArrayList()
        rows.add(arrayOf(day, CeremonyAction.REGISTRATION_BEGIN, 4L))
        rows.add(arrayOf(day, CeremonyAction.REGISTRATION_SUCCESS, 3L))
        rows.add(arrayOf(day, CeremonyAction.AUTHENTICATION_BEGIN, 10L))
        rows.add(arrayOf(day, CeremonyAction.AUTHENTICATION_SUCCESS, 9L))
        whenever(repo.aggregateDailyByTenantAndActions(eq(tenant), anyOrNull(), anyOrNull())).thenReturn(rows)
        whenever(repo.aggregateByTenantAndActionsGrouped(anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(listOf())
        whenever(repo.countByTenantIdAndActionAndCreatedAtAfter(anyOrNull(), anyString(), anyOrNull())).thenReturn(0L)
        val fails: MutableList<Array<Any?>> = ArrayList()
        fails.add(arrayOf("SIGNATURE_INVALID", 5L))
        fails.add(arrayOf(null, 1L))
        whenever(authEvents.countByReason(eq(tenant), eq(CredentialAuthResult.FAILED), anyOrNull())).thenReturn(fails)

        val v = service.compute(tenant, 7)

        val label = today.toString().substring(5)
        assertThat(v.registrationSeries).hasSize(7)
        assertThat(v.authenticationSeries).hasSize(7)
        assertThat(checkNotNull(v.registrationSeries)[6]).isEqualTo(FunnelDto.DailyPoint(label, 4, 3))
        assertThat(checkNotNull(v.authenticationSeries)[6]).isEqualTo(FunnelDto.DailyPoint(label, 10, 9))
        // 기존 JSON 의 series 는 합계 그대로
        assertThat(checkNotNull(v.series)[6]).isEqualTo(FunnelDto.DailyPoint(label, 14, 12))
        assertThat(checkNotNull(v.registrationSeries)[0].attempts).isZero()
        assertThat(v.authFailures).containsExactly(FunnelDto.FailureCount("SIGNATURE_INVALID", 5), FunnelDto.FailureCount(null, 1))
    }
}
