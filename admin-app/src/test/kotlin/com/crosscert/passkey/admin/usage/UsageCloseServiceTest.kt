package com.crosscert.passkey.admin.usage

import com.crosscert.passkey.admin.audit.AuditAppendRequest
import com.crosscert.passkey.admin.audit.AuditLogService
import com.crosscert.passkey.core.config.KstTime
import com.crosscert.passkey.core.entity.AuditLog
import com.crosscert.passkey.core.entity.UsageMonthly
import com.crosscert.passkey.core.entity.UsagePeriodClose
import com.crosscert.passkey.core.repository.AuditLogRepository
import com.crosscert.passkey.core.repository.CeremonyEventRepository
import com.crosscert.passkey.core.repository.UsageDailyRepository
import com.crosscert.passkey.core.repository.UsageMonthlyRepository
import com.crosscert.passkey.core.repository.UsagePeriodCloseRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.sql.SQLIntegrityConstraintViolationException
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.util.Optional
import java.util.UUID

class UsageCloseServiceTest {

    private val rollup: UsageRollupService = mock()
    private val daily: UsageDailyRepository = mock()
    private val monthly: UsageMonthlyRepository = mock()
    private val closes: UsagePeriodCloseRepository = mock()
    private val ceremonyEvents: CeremonyEventRepository = mock()
    private val auditRepo: AuditLogRepository = mock()
    private val audit: AuditLogService = mock()
    private val txManager: PlatformTransactionManager = mock()

    /** 트랜잭션마다 새 상태를 돌려준다 — 커밋·롤백 판정을 마지막 상태로 확인한다. */
    private val txStatuses = mutableListOf<SimpleTransactionStatus>()

    private val tenantA = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val tenantB = UUID.fromString("00000000-0000-0000-0000-00000000000b")
    private val tenantC = UUID.fromString("00000000-0000-0000-0000-00000000000c")

    private fun clockAt(kst: String): Clock = Clock.fixed(OffsetDateTime.parse(kst).toInstant(), KstTime.ZONE)

    private fun service(clock: Clock = clockAt("2026-12-01T03:00+09:00"), rollup: UsageRollupService = this.rollup) = UsageCloseService(
        rollup, daily, monthly, closes, ceremonyEvents, auditRepo, audit, JsonMapper.builder().build(),
        TransactionTemplate(txManager), clock, Duration.ofDays(90), Duration.ofHours(2),
    )

    /** 앞선 시도가 남긴 USAGE_BACKFILL_STARTED 감사 기록. */
    private fun startedEntry(backfillAt: String, createdAt: String = backfillAt): AuditLog {
        val l: AuditLog = mock()
        whenever(l.action).thenReturn("USAGE_BACKFILL_STARTED")
        whenever(l.payload).thenReturn("""{"backfillAt":"$backfillAt"}""")
        whenever(l.createdAt).thenReturn(OffsetDateTime.parse(createdAt))
        return l
    }

    private fun stubStarted(vararg entries: AuditLog) {
        whenever(auditRepo.findByTargetTypeAndTargetIdAndActionIn("usage_period", "backfill", listOf("USAGE_BACKFILL_STARTED")))
            .thenReturn(entries.toList())
    }

    private fun closeRow(period: String) =
        UsagePeriodClose(period, UsagePeriodClose.STATUS_CLOSED, "LEDGER", 0, 0, 0, 0, 0, OffsetDateTime.now())

    private fun row(t: UUID, reg: Long, auth: Long, regAtt: Long, authAtt: Long): Array<Any> =
        arrayOf(t, reg, auth, regAtt, authAtt)

    /** Oracle 드라이버가 마감 행 PK 충돌에서 내는 모양(원인에 ORA-00001 + 제약 이름). */
    private fun pkCollision() = DataIntegrityViolationException(
        "could not execute statement",
        SQLIntegrityConstraintViolationException(
            "ORA-00001: unique constraint (PSK_APP_OWNER.PK_USAGE_PERIOD_CLOSE) violated", "23000", 1,
        ),
    )

    /** 마감 행 INSERT 의 다른 무결성 위반(CHECK) — "이미 마감됨" 이 아니다. */
    private fun checkViolation() = DataIntegrityViolationException(
        "could not execute statement",
        SQLIntegrityConstraintViolationException(
            "ORA-02290: check constraint (PSK_APP_OWNER.CK_USAGE_CLOSE_STATUS) violated", "23000", 2290,
        ),
    )

    @BeforeEach
    fun setUp() {
        whenever(txManager.getTransaction(any())).thenAnswer { SimpleTransactionStatus().also { txStatuses += it } }
        whenever(rollup.ledgerStart).thenReturn(LocalDate.of(2026, 1, 1))
        whenever(audit.append(any())).thenReturn(mock<AuditLog>())
        whenever(daily.findMinUsageDate()).thenReturn(Optional.empty())
        whenever(ceremonyEvents.findMinCreatedAt()).thenReturn(Optional.empty())
    }

    private fun auditRequests(n: Int = 1): List<AuditAppendRequest> {
        val captor = argumentCaptor<AuditAppendRequest>()
        verify(audit, times(n)).append(captor.capture())
        return captor.allValues
    }

    private fun savedClose(): UsagePeriodClose {
        val captor = argumentCaptor<UsagePeriodClose>()
        verify(closes).saveAndFlush(captor.capture())
        return captor.lastValue
    }

    private fun savedMonthly(): List<UsageMonthly> {
        val captor = argumentCaptor<Iterable<UsageMonthly>>()
        verify(monthly).saveAllAndFlush(captor.capture())
        return captor.lastValue.toList()
    }

    // ---------------------------------------------------------------- closeDueMonths

    @Test
    fun closeDueMonths_closesLastMonthAfterGrace() {
        whenever(daily.findMinUsageDate()).thenReturn(Optional.of(LocalDate.of(2026, 11, 3)))

        val closed = service().closeDueMonths()

        assertThat(closed).containsExactly("2026-11")
        verify(rollup).rollupMonthForClose(YearMonth.of(2026, 11))
        verify(daily).sumByTenant(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 1))
        val close = savedClose()
        assertThat(close.period).isEqualTo("2026-11")
        assertThat(close.status).isEqualTo(UsagePeriodClose.STATUS_CLOSED)
        assertThat(close.isNew).`as`("새 인스턴스라 save 가 persist(INSERT)가 된다").isTrue()
        val req = auditRequests().single()
        assertThat(req.action).isEqualTo("USAGE_MONTH_CLOSED")
        assertThat(req.actorId).isNull()
        assertThat(req.actorEmail).isEqualTo("(scheduler)")
        assertThat(req.targetType).isEqualTo("usage_period")
        assertThat(req.targetId).isEqualTo("2026-11")
        assertThat(req.tenantId).isNull()
        assertThat(txStatuses.single().isRollbackOnly).isFalse()
    }

    @Test
    fun closeDueMonths_beforeGrace_doesNothing() {
        whenever(daily.findMinUsageDate()).thenReturn(Optional.of(LocalDate.of(2026, 11, 3)))

        val closed = service(clockAt("2026-12-01T01:59+09:00")).closeDueMonths()

        assertThat(closed).isEmpty()
        verifyNoInteractions(rollup, monthly, audit)
        verify(closes, never()).saveAndFlush(any())
    }

    @Test
    fun closeDueMonths_noUsageRows_doesNothing() {
        assertThat(service().closeDueMonths()).isEmpty()
        verifyNoInteractions(rollup, audit)
    }

    @Test
    fun closeDueMonths_oldestFirst_andStopsOnFailure() {
        whenever(daily.findMinUsageDate()).thenReturn(Optional.of(LocalDate.of(2026, 9, 10)))
        whenever(daily.sumByTenant(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1)))
            .thenReturn(listOf(row(tenantA, 1, 1, 1, 1)))
        whenever(monthly.saveAllAndFlush(any<Iterable<UsageMonthly>>())).thenAnswer { inv ->
            val rows = inv.getArgument<Iterable<UsageMonthly>>(0).toList()
            if (rows.any { it.period == "2026-10" }) throw IllegalStateException("db down")
            rows
        }

        val closed = service().closeDueMonths()

        assertThat(closed).containsExactly("2026-09")
        verify(rollup).rollupMonthForClose(YearMonth.of(2026, 9))
        verify(rollup).rollupMonthForClose(YearMonth.of(2026, 10))
        verify(rollup, never()).rollupMonthForClose(YearMonth.of(2026, 11))
        // 실패한 2026-10 의 트랜잭션은 롤백 경로를 탄다(마지막 상태가 커밋되지 않음 — 템플릿이 rollback 호출).
        verify(txManager).rollback(txStatuses[1])
        assertThat(auditRequests().map { it.targetId }).containsExactly("2026-09")
    }

    @Test
    fun closeDueMonths_skipsAlreadyClosedMonths_andContinues() {
        whenever(daily.findMinUsageDate()).thenReturn(Optional.of(LocalDate.of(2026, 9, 10)))
        whenever(closes.findAll()).thenReturn(listOf(closeRow("2026-09")))

        val closed = service().closeDueMonths()

        assertThat(closed).containsExactly("2026-10", "2026-11")
        verify(rollup, never()).rollupMonthForClose(YearMonth.of(2026, 9))
    }

    @Test
    fun closeDueMonths_yearBoundary() {
        whenever(daily.findMinUsageDate()).thenReturn(Optional.of(LocalDate.of(2026, 11, 30)))

        val closed = service(clockAt("2027-01-01T02:00+09:00")).closeDueMonths()

        assertThat(closed).containsExactly("2026-11", "2026-12")
        verify(daily).sumByTenant(LocalDate.of(2026, 12, 1), LocalDate.of(2027, 1, 1))
    }

    // ---------------------------------------------------------------- closeMonth (한 달 확정)

    @Test
    fun close_auditIsLastCall() {
        whenever(daily.sumByTenant(any(), any())).thenReturn(listOf(row(tenantA, 1, 2, 3, 4)))

        assertThat(service().closeMonth(YearMonth.of(2026, 11), UsagePeriodClose.STATUS_CLOSED)).isTrue()

        val order = inOrder(rollup, daily, closes, monthly, audit)
        order.verify(closes).lockForClose()
        order.verify(closes).existsById("2026-11")
        order.verify(rollup).rollupMonthForClose(YearMonth.of(2026, 11))
        order.verify(daily).sumByTenant(any(), any())
        order.verify(closes).saveAndFlush(any())
        order.verify(monthly).saveAllAndFlush(any<Iterable<UsageMonthly>>())
        order.verify(audit).append(any())
    }

    @Test
    fun close_auditFailure_propagates() {
        whenever(audit.append(any())).thenThrow(IllegalStateException("chain lock timeout"))

        assertThatThrownBy { service().closeMonth(YearMonth.of(2026, 11), UsagePeriodClose.STATUS_CLOSED) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("chain lock timeout")
        verify(txManager).rollback(txStatuses.single())
        verify(txManager, never()).commit(any())
    }

    @Test
    fun close_auditReturnsNull_isTreatedAsFailure() {
        whenever(audit.append(any())).thenReturn(null)

        assertThatThrownBy { service().closeMonth(YearMonth.of(2026, 11), UsagePeriodClose.STATUS_CLOSED) }
            .isInstanceOf(IllegalStateException::class.java)
        verify(txManager).rollback(txStatuses.single())
        verify(txManager, never()).commit(any())
    }

    @Test
    fun close_skipsZeroTenants_butCountsTenantCount() {
        whenever(daily.sumByTenant(any(), any())).thenReturn(
            listOf(row(tenantB, 0, 0, 0, 5), row(tenantC, 0, 0, 0, 0), row(tenantA, 1, 2, 3, 4)),
        )

        service().closeMonth(YearMonth.of(2026, 11), UsagePeriodClose.STATUS_CLOSED)

        val rows = savedMonthly()
        assertThat(rows.map { it.tenantId }).containsExactlyInAnyOrder(tenantA, tenantB)
        val a = rows.single { it.tenantId == tenantA }
        assertThat(listOf(a.period, a.regSuccess, a.authSuccess, a.regAttempt, a.authAttempt))
            .containsExactly("2026-11", 1L, 2L, 3L, 4L)
        val close = savedClose()
        assertThat(close.tenantCount).isEqualTo(2)
        assertThat(listOf(close.regSuccess, close.authSuccess, close.regAttempt, close.authAttempt))
            .containsExactly(1L, 2L, 3L, 9L)

        val payload = auditRequests().single().payload!!
        assertThat(payload["period"]).isEqualTo("2026-11")
        assertThat(payload["status"]).isEqualTo("CLOSED")
        assertThat(payload["basis"]).isEqualTo("LEDGER")
        assertThat(payload["registrationSuccess"]).isEqualTo(1L)
        assertThat(payload["authenticationSuccess"]).isEqualTo(2L)
        // tenantId 문자열 오름차순, 각 원소는 세 키만.
        assertThat(payload["tenants"]).isEqualTo(
            listOf(
                mapOf("tenantId" to tenantA.toString(), "registrationSuccess" to 1L, "authenticationSuccess" to 2L),
                mapOf("tenantId" to tenantB.toString(), "registrationSuccess" to 0L, "authenticationSuccess" to 0L),
            ),
        )
    }

    @Test
    fun close_monthWithNoUsage_closesWithZeros() {
        service().closeMonth(YearMonth.of(2026, 11), UsagePeriodClose.STATUS_CLOSED)

        assertThat(savedMonthly()).isEmpty()
        val close = savedClose()
        assertThat(close.tenantCount).isZero()
        assertThat(close.regSuccess + close.authSuccess).isZero()
        assertThat(auditRequests().single().payload!!["tenants"]).isEqualTo(emptyList<Any>())
    }

    @Test
    fun close_basis() {
        fun basisWith(ledgerStart: LocalDate): String {
            org.mockito.Mockito.clearInvocations(closes)
            whenever(rollup.ledgerStart).thenReturn(ledgerStart)
            service().closeMonth(YearMonth.of(2026, 11), UsagePeriodClose.STATUS_CLOSED)
            return savedClose().basis
        }
        assertThat(basisWith(LocalDate.of(2026, 11, 1))).isEqualTo("LEDGER")
        assertThat(basisWith(LocalDate.of(2026, 11, 2))).isEqualTo("LEGACY")
        assertThat(basisWith(UsagePeriods.NO_LEDGER)).isEqualTo("LEGACY")
    }

    @Test
    fun close_duplicatePeriod_isSkippedSilently() {
        whenever(daily.findMinUsageDate()).thenReturn(Optional.of(LocalDate.of(2026, 11, 3)))
        whenever(closes.saveAndFlush(any<UsagePeriodClose>())).thenThrow(pkCollision())

        val closed = service().closeDueMonths()

        assertThat(closed).isEmpty()
        verify(monthly, never()).saveAllAndFlush(any<Iterable<UsageMonthly>>())
        verifyNoInteractions(audit)
        // 재집계까지 되돌리도록 트랜잭션은 롤백 전용으로 끝난다.
        assertThat(txStatuses.single().isRollbackOnly).isTrue()
    }

    @Test
    fun closeDueMonths_pkCollision_skipsThatMonth_andContinues() {
        whenever(daily.findMinUsageDate()).thenReturn(Optional.of(LocalDate.of(2026, 9, 10)))
        whenever(closes.saveAndFlush(any<UsagePeriodClose>())).thenAnswer { inv ->
            val row = inv.getArgument<UsagePeriodClose>(0)
            if (row.period == "2026-09") throw pkCollision()
            row
        }

        assertThat(service().closeDueMonths()).containsExactly("2026-10", "2026-11")
        assertThat(auditRequests(2).map { it.targetId }).containsExactly("2026-10", "2026-11")
    }

    @Test
    fun closeDueMonths_nonPkIntegrityViolation_isFailure_andStopsLoop() {
        whenever(daily.findMinUsageDate()).thenReturn(Optional.of(LocalDate.of(2026, 9, 10)))
        whenever(closes.saveAndFlush(any<UsagePeriodClose>())).thenThrow(checkViolation())

        assertThat(service().closeDueMonths()).isEmpty()
        verify(rollup, never()).rollupMonthForClose(YearMonth.of(2026, 10))
        verify(txManager).rollback(txStatuses.single())
        verifyNoInteractions(audit)
    }

    @Test
    fun close_nonPkIntegrityViolation_propagates() {
        whenever(closes.saveAndFlush(any<UsagePeriodClose>())).thenThrow(checkViolation())

        assertThatThrownBy { service().closeMonth(YearMonth.of(2026, 11), UsagePeriodClose.STATUS_CLOSED) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun close_pkViolationOfAnotherConstraint_isNotTreatedAsDuplicate() {
        // ORA-00001 이어도 다른 제약이면 중복 마감이 아니다.
        whenever(closes.saveAndFlush(any<UsagePeriodClose>())).thenThrow(
            DataIntegrityViolationException(
                "x", SQLIntegrityConstraintViolationException("ORA-00001: unique constraint (PSK_APP_OWNER.SOMETHING_ELSE) violated", "23000", 1),
            ),
        )
        assertThatThrownBy { service().closeMonth(YearMonth.of(2026, 11), UsagePeriodClose.STATUS_CLOSED) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun backfill_partialCloseNonPkViolation_propagates_noMarker() {
        whenever(ceremonyEvents.findMinCreatedAt())
            .thenReturn(Optional.of(OffsetDateTime.parse("2026-07-27T10:00+09:00")))
        whenever(closes.saveAndFlush(any<UsagePeriodClose>())).thenThrow(checkViolation())

        assertThatThrownBy { service(clockAt("2026-11-25T10:00+09:00")).backfillIfNeeded() }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        verify(rollup, never()).rollupMonthForClose(YearMonth.of(2026, 8))
        // 시작 표식만 남고 완료 표식은 없다.
        assertThat(auditRequests().map { it.action }).containsExactly("USAGE_BACKFILL_STARTED")
    }

    @Test
    fun close_monthlyConstraintViolation_isFailureNotSkip() {
        // "이미 마감됨" 으로 보는 무결성 위반은 마감 행 INSERT 의 것뿐이다. usage_monthly 쪽 위반은 실패로 전파된다.
        whenever(daily.sumByTenant(any(), any())).thenReturn(listOf(row(tenantA, 1, 2, 3, 4)))
        whenever(monthly.saveAllAndFlush(any<Iterable<UsageMonthly>>()))
            .thenThrow(DataIntegrityViolationException("ORA-00001 on USAGE_MONTHLY"))

        assertThatThrownBy { service().closeMonth(YearMonth.of(2026, 11), UsagePeriodClose.STATUS_CLOSED) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        verify(txManager).rollback(txStatuses.single())
        verifyNoInteractions(audit)
    }

    @Test
    fun close_alreadyClosedInsideTx_isSkippedAndRolledBack() {
        whenever(closes.existsById("2026-11")).thenReturn(true)

        val closedNow = service().closeMonth(YearMonth.of(2026, 11), UsagePeriodClose.STATUS_CLOSED)

        assertThat(closedNow).isFalse()
        // 락을 쥔 뒤 확인하므로 재집계까지 가지 않는다.
        verify(closes).lockForClose()
        verify(rollup, never()).rollupMonthForClose(any())
        verify(closes, never()).saveAndFlush(any())
        verifyNoInteractions(audit)
        assertThat(txStatuses.single().isRollbackOnly).isTrue()
    }

    // ---------------------------------------------------------------- backfill

    @Test
    fun backfill_runsOnce() {
        whenever(auditRepo.existsByAction("USAGE_BACKFILL_DONE")).thenReturn(true)

        assertThat(service().backfillIfNeeded()).isFalse()

        verifyNoInteractions(rollup, daily, monthly, closes, ceremonyEvents, audit)
    }

    @Test
    fun backfill_marksOldMonthsPartial() {
        whenever(ceremonyEvents.findMinCreatedAt())
            .thenReturn(Optional.of(OffsetDateTime.parse("2026-08-27T10:00+09:00")))

        assertThat(service(clockAt("2026-11-25T10:00+09:00")).backfillIfNeeded()).isTrue()

        verify(rollup).rollupDays(LocalDate.of(2026, 8, 27), LocalDate.of(2026, 11, 25))
        verify(rollup).rollupMonthForClose(YearMonth.of(2026, 8))
        verify(rollup, never()).rollupMonthForClose(YearMonth.of(2026, 9))
        verify(rollup, never()).rollupMonthForClose(YearMonth.of(2026, 10))
        val close = savedClose()
        assertThat(close.period).isEqualTo("2026-08")
        assertThat(close.status).isEqualTo(UsagePeriodClose.STATUS_PARTIAL)

        val reqs = auditRequests(3)
        assertThat(reqs.map { it.action }).containsExactly("USAGE_BACKFILL_STARTED", "USAGE_MONTH_PARTIAL", "USAGE_BACKFILL_DONE")
        assertThat(reqs[1].payload!!["status"]).isEqualTo("PARTIAL")
        val marker = reqs[2].payload!!
        assertThat(marker["partialPeriods"]).isEqualTo(listOf("2026-08"))
        assertThat(marker["oldestLedgerAt"]).isEqualTo("2026-08-27T10:00+09:00")
        assertThat(marker["backfilledAt"]).isEqualTo("2026-11-25T10:00+09:00")
    }

    @Test
    fun backfill_partialCloseFails_noMarker_andPropagates() {
        whenever(ceremonyEvents.findMinCreatedAt())
            .thenReturn(Optional.of(OffsetDateTime.parse("2026-07-27T10:00+09:00")))
        whenever(audit.append(argThat { action == "USAGE_MONTH_PARTIAL" })).thenThrow(IllegalStateException("audit down"))

        assertThatThrownBy { service(clockAt("2026-11-25T10:00+09:00")).backfillIfNeeded() }
            .isInstanceOf(IllegalStateException::class.java)

        verify(rollup, never()).rollupMonthForClose(YearMonth.of(2026, 8))
        assertThat(auditRequests(2).map { it.action }).containsExactly("USAGE_BACKFILL_STARTED", "USAGE_MONTH_PARTIAL")
    }

    @Test
    fun backfill_retry_skipsAlreadyClosedPartialMonth() {
        whenever(ceremonyEvents.findMinCreatedAt())
            .thenReturn(Optional.of(OffsetDateTime.parse("2026-07-27T10:00+09:00")))
        whenever(closes.findAll()).thenReturn(listOf(closeRow("2026-07")))
        stubStarted(startedEntry("2026-11-25T10:00+09:00")) // 앞선 시도가 같은 시각에 시작했다

        service(clockAt("2026-11-25T10:00+09:00")).backfillIfNeeded()

        verify(rollup, never()).rollupMonthForClose(YearMonth.of(2026, 7))
        verify(rollup).rollupMonthForClose(YearMonth.of(2026, 8))
        val reqs = auditRequests(2)
        assertThat(reqs.map { it.action }).containsExactly("USAGE_MONTH_PARTIAL", "USAGE_BACKFILL_DONE")
        assertThat(reqs[1].payload!!["partialPeriods"]).isEqualTo(listOf("2026-07", "2026-08"))
    }

    @Test
    fun backfill_oldestRowInCurrentMonth_noPartial() {
        whenever(ceremonyEvents.findMinCreatedAt())
            .thenReturn(Optional.of(OffsetDateTime.parse("2026-11-02T10:00+09:00")))

        service(clockAt("2026-11-25T10:00+09:00")).backfillIfNeeded()

        verify(rollup).rollupDays(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 25))
        verify(rollup, never()).rollupMonthForClose(any())
        val marker = auditRequests(2).last()
        assertThat(marker.action).isEqualTo("USAGE_BACKFILL_DONE")
        assertThat(marker.payload!!["partialPeriods"]).isEqualTo(emptyList<String>())
    }

    @Test
    fun backfill_noLedgerRows_onlyWritesMarker() {
        assertThat(service().backfillIfNeeded()).isTrue()

        verifyNoInteractions(rollup, monthly)
        verify(closes, never()).saveAndFlush(any())
        val reqs = auditRequests(2)
        assertThat(reqs.map { it.action }).containsExactly("USAGE_BACKFILL_STARTED", "USAGE_BACKFILL_DONE")
        val marker = reqs[1]
        assertThat(marker.actorEmail).isEqualTo("(scheduler)")
        assertThat(marker.tenantId).isNull()
        assertThat(marker.payload!!["oldestLedgerAt"]).isNull()
        assertThat(marker.payload["partialPeriods"]).isEqualTo(emptyList<String>())
    }

    @Test
    fun backfill_firstAttempt_writesStartedMarkerInItsOwnCommittedTx_beforeAnyWork() {
        whenever(ceremonyEvents.findMinCreatedAt())
            .thenReturn(Optional.of(OffsetDateTime.parse("2026-08-27T10:00+09:00")))

        service(clockAt("2026-11-25T10:00+09:00")).backfillIfNeeded()

        val started = auditRequests(3).first()
        assertThat(started.action).isEqualTo("USAGE_BACKFILL_STARTED")
        assertThat(started.actorId).isNull()
        assertThat(started.actorEmail).isEqualTo("(scheduler)")
        assertThat(started.targetType).isEqualTo("usage_period")
        assertThat(started.targetId).isEqualTo("backfill")
        assertThat(started.tenantId).isNull()
        assertThat(started.payload).isEqualTo(mapOf("backfillAt" to "2026-11-25T10:00+09:00"))
        // 표식 트랜잭션이 먼저 커밋되고, 그 뒤에 집계·부분 월 마감이 시작된다.
        val order = inOrder(audit, txManager, rollup)
        order.verify(audit).append(argThat { action == "USAGE_BACKFILL_STARTED" })
        order.verify(txManager).commit(txStatuses[0])
        order.verify(rollup).rollupDays(any(), any())
    }

    @Test
    fun backfill_startedMarkerFailure_stopsBeforeAnyWork() {
        whenever(ceremonyEvents.findMinCreatedAt())
            .thenReturn(Optional.of(OffsetDateTime.parse("2026-08-27T10:00+09:00")))
        whenever(audit.append(any())).thenReturn(null)

        assertThatThrownBy { service(clockAt("2026-11-25T10:00+09:00")).backfillIfNeeded() }
            .isInstanceOf(IllegalStateException::class.java)
        verifyNoInteractions(rollup)
        verify(closes, never()).saveAndFlush(any())
    }

    @Test
    fun backfill_retryAtLaterClock_classifiesWithOriginalStartTime() {
        // 1차 시도(11-25)는 시작 표식을 남긴 뒤 집계에서 실패했다. 2차 시도는 12-20 — 지금 시각으로 다시 계산하면
        // (12-20 − 90일 = 09-21) 9월까지 부분 월이 되지만, 원본 보존 덕에 9월 원본은 온전하다. 1차 시각 기준이면 8월만 부분이다.
        whenever(ceremonyEvents.findMinCreatedAt())
            .thenReturn(Optional.of(OffsetDateTime.parse("2026-08-27T10:00+09:00")))
        whenever(rollup.rollupDays(any(), any())).thenThrow(IllegalStateException("db down"))

        assertThatThrownBy { service(clockAt("2026-11-25T10:00+09:00")).backfillIfNeeded() }
            .isInstanceOf(IllegalStateException::class.java)
        val first = auditRequests().single()
        assertThat(first.action).isEqualTo("USAGE_BACKFILL_STARTED")
        val startedAt = first.payload!!["backfillAt"] as String

        // 2차 시도: 1차의 표식이 감사 로그에 있다.
        org.mockito.Mockito.reset(rollup, audit)
        whenever(rollup.ledgerStart).thenReturn(LocalDate.of(2026, 1, 1))
        whenever(audit.append(any())).thenReturn(mock<AuditLog>())
        stubStarted(startedEntry(startedAt))

        assertThat(service(clockAt("2026-12-20T10:00+09:00")).backfillIfNeeded()).isTrue()

        verify(rollup).rollupDays(LocalDate.of(2026, 8, 27), LocalDate.of(2026, 12, 20)) // 집계는 오늘까지
        verify(rollup).rollupMonthForClose(YearMonth.of(2026, 8))
        verify(rollup, never()).rollupMonthForClose(YearMonth.of(2026, 9))
        val reqs = auditRequests(2)
        assertThat(reqs.map { it.action }).`as`("시작 표식은 다시 쓰지 않는다").containsExactly("USAGE_MONTH_PARTIAL", "USAGE_BACKFILL_DONE")
        assertThat(reqs[1].payload!!["partialPeriods"]).isEqualTo(listOf("2026-08"))
        assertThat(reqs[1].payload!!["backfilledAt"]).isEqualTo("2026-11-25T10:00+09:00")
    }

    @Test
    fun backfill_severalStartedMarkers_usesEarliest() {
        whenever(ceremonyEvents.findMinCreatedAt())
            .thenReturn(Optional.of(OffsetDateTime.parse("2026-08-27T10:00+09:00")))
        stubStarted(
            startedEntry("2026-12-20T10:00+09:00"),
            startedEntry("2026-11-25T10:00+09:00"),
        )

        service(clockAt("2026-12-21T10:00+09:00")).backfillIfNeeded()

        verify(rollup, never()).rollupMonthForClose(YearMonth.of(2026, 9))
        assertThat(auditRequests(2).last().payload!!["backfilledAt"]).isEqualTo("2026-11-25T10:00+09:00")
    }

    @Test
    fun backfill_startedMarkerUnreadable_failsWithoutClassifying() {
        whenever(ceremonyEvents.findMinCreatedAt())
            .thenReturn(Optional.of(OffsetDateTime.parse("2026-08-27T10:00+09:00")))
        val broken: AuditLog = mock()
        whenever(broken.payload).thenReturn("""{"backfillAt":"not-a-time"}""")
        whenever(broken.createdAt).thenReturn(OffsetDateTime.parse("2026-11-25T10:00+09:00"))
        stubStarted(broken)

        assertThatThrownBy { service(clockAt("2026-12-20T10:00+09:00")).backfillIfNeeded() }
            .isInstanceOf(IllegalStateException::class.java)
        verifyNoInteractions(rollup, audit)
    }

    // ---------------------------------------------------------------- 요약이 비어 있을 때의 복구 (F1)

    @Test
    fun closeDueMonths_startsFromOldestLedgerMonth_whenEarlierThanDaily() {
        // 원본은 10월부터 있는데 요약은 11월부터 — 10월도 마감 대상이다(0 건이면 0 으로 마감).
        whenever(daily.findMinUsageDate()).thenReturn(Optional.of(LocalDate.of(2026, 11, 3)))
        whenever(ceremonyEvents.findMinCreatedAt()).thenReturn(Optional.of(OffsetDateTime.parse("2026-10-20T10:00+09:00")))

        assertThat(service().closeDueMonths()).containsExactly("2026-10", "2026-11")
    }

    @Test
    fun closeDueMonths_onlyLedgerRows_noDaily_stillCloses() {
        whenever(ceremonyEvents.findMinCreatedAt()).thenReturn(Optional.of(OffsetDateTime.parse("2026-10-31T23:59+09:00")))

        assertThat(service().closeDueMonths()).containsExactly("2026-10", "2026-11")
        verify(rollup).rollupMonthForClose(YearMonth.of(2026, 10))
    }

    @Test
    fun scenario_emptyBackfill_thenTraffic_thenOutageAcrossMonthBoundary_octoberIsAggregatedAndClosed() {
        // 실제 집계 서비스를 쓴다(저장소는 mock). 요약 표에는 아무 것도 쌓이지 않는다고 본다 — 기준점이 생기지 않는 최악의 경우.
        val realRollup = UsageRollupService(daily, closes, ceremonyEvents, clockAt("2026-11-28T10:00+09:00"), "2026-10-01")

        // ① 10-05: 빈 설치에서 백필 — 장부가 비어 있어 표식만 남는다.
        assertThat(service(clockAt("2026-10-05T10:00+09:00"), realRollup).backfillIfNeeded()).isTrue()
        verify(daily, never()).mergeFromLedger(any(), any(), any(), any())

        // ② 그 뒤 10월에 기록이 쌓이는 동안 admin-app 은 꺼져 있었다. ③ 11-28 에 돌아와 정기 실행.
        whenever(auditRepo.existsByAction("USAGE_BACKFILL_DONE")).thenReturn(true)
        whenever(ceremonyEvents.findMinCreatedAt()).thenReturn(Optional.of(OffsetDateTime.parse("2026-10-12T09:00+09:00")))
        realRollup.rollupRecent()
        verify(daily).mergeFromLedger(
            eq(OffsetDateTime.parse("2026-10-12T00:00+09:00")), eq(OffsetDateTime.parse("2026-11-29T00:00+09:00")), any(), any(),
        )
        val closed = service(clockAt("2026-11-28T10:00+09:00"), realRollup).closeDueMonths()

        assertThat(closed).containsExactly("2026-10")
        verify(daily).mergeFromLedger(
            eq(OffsetDateTime.parse("2026-10-01T00:00+09:00")), eq(OffsetDateTime.parse("2026-11-01T00:00+09:00")), any(), any(),
        )
        assertThat(savedClose().period).isEqualTo("2026-10")
    }

    // ---------------------------------------------------------------- releasedBefore

    @Test
    fun releasedBefore_isStartOfOldestUnclosedMonth() {
        whenever(ceremonyEvents.findMinCreatedAt())
            .thenReturn(Optional.of(OffsetDateTime.parse("2026-08-27T10:00+09:00")))
        whenever(closes.findAll()).thenReturn(listOf(closeRow("2026-08"), closeRow("2026-09")))

        assertThat(service().releasedBefore()).isEqualTo(OffsetDateTime.parse("2026-10-01T00:00+09:00"))
    }

    @Test
    fun releasedBefore_noLedgerRows_isStartOfCurrentMonth() {
        assertThat(service().releasedBefore()).isEqualTo(OffsetDateTime.parse("2026-12-01T00:00+09:00"))
    }

    @Test
    fun releasedBefore_allPastMonthsClosed_isStartOfCurrentMonth() {
        whenever(ceremonyEvents.findMinCreatedAt())
            .thenReturn(Optional.of(OffsetDateTime.parse("2026-10-27T10:00+09:00")))
        whenever(closes.findAll()).thenReturn(listOf(closeRow("2026-10"), closeRow("2026-11")))

        assertThat(service().releasedBefore()).isEqualTo(OffsetDateTime.parse("2026-12-01T00:00+09:00"))
    }
}
