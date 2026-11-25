package com.crosscert.passkey.admin.usage

import com.crosscert.passkey.core.config.KstTime
import com.crosscert.passkey.core.entity.UsagePeriodClose
import com.crosscert.passkey.core.repository.CeremonyEventRepository
import com.crosscert.passkey.core.repository.UsageDailyRepository
import com.crosscert.passkey.core.repository.UsagePeriodCloseRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.util.Optional

class UsageRollupServiceTest {

    private val daily: UsageDailyRepository = mock()
    private val closes: UsagePeriodCloseRepository = mock()
    private val ceremonyEvents: CeremonyEventRepository = mock()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-11-25T01:00:00Z"), KstTime.ZONE) // 10:00 KST
    private val ledgerStart = LocalDate.of(2026, 11, 20)

    private fun service(raw: String = "2026-11-20") = UsageRollupService(daily, closes, ceremonyEvents, clock, raw)

    private fun kst(s: String): OffsetDateTime = OffsetDateTime.parse(s)

    @BeforeEach
    fun setUp() {
        whenever(daily.findMaxUsageDate()).thenReturn(Optional.empty())
        whenever(closes.existsById(any())).thenReturn(false)
        whenever(ceremonyEvents.findMinCreatedAt()).thenReturn(Optional.empty())
    }

    @Test
    fun rollupRecent_defaultsToLast3Days() {
        whenever(daily.findMaxUsageDate()).thenReturn(Optional.of(LocalDate.of(2026, 11, 25)))
        val days = service().rollupRecent()
        verify(daily).mergeFromLedger(
            eq(kst("2026-11-23T00:00+09:00")), eq(kst("2026-11-26T00:00+09:00")), eq(ledgerStart), any(),
        )
        assertThat(days).isEqualTo(3)
    }

    @Test
    fun rollupRecent_catchesUpAfterOutage() {
        whenever(daily.findMaxUsageDate()).thenReturn(Optional.of(LocalDate.of(2026, 11, 10)))
        service().rollupRecent()
        verify(daily).mergeFromLedger(
            eq(kst("2026-11-10T00:00+09:00")), eq(kst("2026-11-26T00:00+09:00")), eq(ledgerStart), any(),
        )
    }

    @Test
    fun rollupRecent_emptyDaily_usesLast3Days() {
        service().rollupRecent()
        verify(daily).mergeFromLedger(
            eq(kst("2026-11-23T00:00+09:00")), eq(kst("2026-11-26T00:00+09:00")), eq(ledgerStart), any(),
        )
    }

    @Test
    fun rollupRecent_emptyDaily_startsFromOldestLedgerRow() {
        // 빈 설치에서 백필이 끝난 뒤(요약 0행) 10월에 기록이 쌓이고 admin-app 이 11월 말까지 꺼져 있었다.
        // 요약에 기준점이 없으니 최근 3일이 아니라 장부의 가장 오래된 행이 속한 날(KST)부터 다시 센다 — 10월이 빠지면 마감도 안 된다.
        whenever(ceremonyEvents.findMinCreatedAt()).thenReturn(Optional.of(OffsetDateTime.parse("2026-10-09T16:30:00Z"))) // 10-10 01:30 KST
        val days = service().rollupRecent()
        verify(daily).mergeFromLedger(
            eq(kst("2026-10-10T00:00+09:00")), eq(kst("2026-11-26T00:00+09:00")), eq(ledgerStart), any(),
        )
        assertThat(days).isEqualTo(47)
    }

    @Test
    fun rollupRecent_emptyDaily_recentOldestRow_stillLast3Days() {
        whenever(ceremonyEvents.findMinCreatedAt()).thenReturn(Optional.of(kst("2026-11-25T09:00+09:00")))
        service().rollupRecent()
        verify(daily).mergeFromLedger(
            eq(kst("2026-11-23T00:00+09:00")), eq(kst("2026-11-26T00:00+09:00")), eq(ledgerStart), any(),
        )
    }

    @Test
    fun rollupRecent_withDailyRows_doesNotReadLedgerMin() {
        whenever(daily.findMaxUsageDate()).thenReturn(Optional.of(LocalDate.of(2026, 11, 24)))
        service().rollupRecent()
        verify(ceremonyEvents, never()).findMinCreatedAt()
    }

    @Test
    fun rollupDays_skipsClosedMonth() {
        whenever(closes.existsById("2026-10")).thenReturn(true)
        val days = service().rollupDays(LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 2))
        verify(daily).mergeFromLedger(
            eq(kst("2026-11-01T00:00+09:00")), eq(kst("2026-11-03T00:00+09:00")), eq(ledgerStart), any(),
        )
        verify(daily, never()).mergeFromLedger(eq(kst("2026-10-30T00:00+09:00")), any(), any(), any())
        assertThat(days).isEqualTo(2)
    }

    @Test
    fun rollupDays_allClosed_doesNothing() {
        whenever(closes.existsById("2026-10")).thenReturn(true)
        val days = service().rollupDays(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 7))
        verify(daily, never()).mergeFromLedger(any(), any(), any(), any())
        assertThat(days).isEqualTo(0)
    }

    @Test
    fun rollupDays_closedMiddleMonth_splitsIntoTwoMerges() {
        whenever(closes.existsById("2026-10")).thenReturn(true)
        val days = service().rollupDays(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 11, 1))
        verify(daily).mergeFromLedger(
            eq(kst("2026-09-30T00:00+09:00")), eq(kst("2026-10-01T00:00+09:00")), eq(ledgerStart), any(),
        )
        verify(daily).mergeFromLedger(
            eq(kst("2026-11-01T00:00+09:00")), eq(kst("2026-11-02T00:00+09:00")), eq(ledgerStart), any(),
        )
        assertThat(days).isEqualTo(2)
    }

    @Test
    fun ledgerStart_unsetOrMalformed_passesSentinel() {
        for (raw in listOf("", "x")) {
            val svc = service(raw)
            assertThat(svc.ledgerStart).isEqualTo(UsagePeriods.NO_LEDGER)
            svc.rollupDays(LocalDate.of(2026, 11, 25), LocalDate.of(2026, 11, 25))
        }
        verify(daily, org.mockito.kotlin.times(2)).mergeFromLedger(
            any(), any(), eq(UsagePeriods.NO_LEDGER), any(),
        )
    }

    @Test
    fun rollupMonthForClose_coversWholeMonth_ignoringClosedCheck() {
        whenever(closes.existsById("2026-10")).thenReturn(true)
        service().rollupMonthForClose(YearMonth.of(2026, 10))
        verify(daily).mergeFromLedger(
            eq(kst("2026-10-01T00:00+09:00")), eq(kst("2026-11-01T00:00+09:00")), eq(ledgerStart), any(),
        )
    }

    @Test
    fun rollupDays_reversedRange_returnsZeroWithoutTouchingRepositories() {
        assertThat(service().rollupDays(LocalDate.of(2026, 11, 25), LocalDate.of(2026, 11, 23))).isEqualTo(0)
        assertThat(service().rollupDays(LocalDate.of(2026, 12, 2), LocalDate.of(2026, 11, 23))).isEqualTo(0)
        verify(daily, never()).mergeFromLedger(any(), any(), any(), any())
        verify(closes, never()).existsById(any())
    }

    @Test
    fun rollupDays_takesShareLockBeforeClosedCheck() {
        // 마감 확인과 MERGE 가 진행 중인 마감과 엇갈리지 않도록, 확인보다 먼저 usage_period_close SHARE 락을 건다.
        service().rollupDays(LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 2))
        val order = org.mockito.kotlin.inOrder(closes, daily)
        order.verify(closes).lockForRollup()
        order.verify(closes).existsById("2026-10")
        order.verify(daily).mergeFromLedger(any(), any(), any(), any())
    }

    @Test
    fun rollupDays_reversedRange_takesNoLock() {
        service().rollupDays(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 1))
        verify(closes, never()).lockForRollup()
    }
}
