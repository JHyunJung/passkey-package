package com.crosscert.passkey.admin.usage

import com.crosscert.passkey.core.config.KstTime
import com.crosscert.passkey.core.entity.AuditLog
import com.crosscert.passkey.core.entity.Tenant
import com.crosscert.passkey.core.entity.UsageMonthly
import com.crosscert.passkey.core.entity.UsagePeriodClose
import com.crosscert.passkey.core.repository.AuditLogRepository
import com.crosscert.passkey.core.repository.TenantRepository
import com.crosscert.passkey.core.repository.UsageDailyRepository
import com.crosscert.passkey.core.repository.UsageMonthlyRepository
import com.crosscert.passkey.core.repository.UsagePeriodCloseRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.util.Optional
import java.util.UUID

/** 사용량 정산 조회. 기준 시각은 2026-11-25 10:00 KST. */
class UsageSettlementServiceTest {

    private val tenantRepo: TenantRepository = mock()
    private val daily: UsageDailyRepository = mock()
    private val monthly: UsageMonthlyRepository = mock()
    private val closes: UsagePeriodCloseRepository = mock()
    private val auditRepo: AuditLogRepository = mock()
    private val rollup: UsageRollupService = mock()
    private val json = JsonMapper.builder().build()

    private val clock: Clock = Clock.fixed(OffsetDateTime.parse("2026-11-25T10:00:00+09:00").toInstant(), KstTime.ZONE)

    private val a = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val b = UUID.fromString("00000000-0000-0000-0000-00000000000b")
    private val x = UUID.fromString("00000000-0000-0000-0000-0000000000f0")

    private val oct = YearMonth.of(2026, 10)
    private val nov = YearMonth.of(2026, 11)
    private val closedAt = OffsetDateTime.parse("2026-11-01T02:00:00+09:00")

    private fun service() = UsageSettlementService(tenantRepo, daily, monthly, closes, auditRepo, rollup, json, clock)

    private fun tenant(id: UUID, slug: String, name: String): Tenant {
        val t: Tenant = mock()
        whenever(t.id).thenReturn(id)
        whenever(t.slug).thenReturn(slug)
        whenever(t.displayName).thenReturn(name)
        return t
    }

    private fun daily(t: UUID, reg: Long, auth: Long, regAtt: Long, authAtt: Long): Array<Any> = arrayOf(t, reg, auth, regAtt, authAtt)

    private fun close(period: String, status: String = UsagePeriodClose.STATUS_CLOSED, basis: String = "LEDGER", reg: Long, auth: Long, authAtt: Long = 0) =
        UsagePeriodClose(period, status, basis, reg, auth, 0, authAtt, 2, closedAt)

    private fun audit(action: String, payload: String): AuditLog {
        val l: AuditLog = mock()
        whenever(l.action).thenReturn(action)
        whenever(l.payload).thenReturn(payload)
        return l
    }

    private fun payload(period: String, reg: Long, auth: Long, vararg rows: Triple<UUID, Long, Long>): String {
        val ts = rows.joinToString(",") { """{"tenantId":"${it.first}","registrationSuccess":${it.second},"authenticationSuccess":${it.third}}""" }
        return """{"period":"$period","status":"CLOSED","basis":"LEDGER","registrationSuccess":$reg,"authenticationSuccess":$auth,"tenants":[$ts]}"""
    }

    @BeforeEach
    fun setUp() {
        val tenants = listOf(tenant(a, "acme", "Acme"), tenant(b, "beta", "Beta")) // 스터빙 밖에서 mock 을 만든다
        whenever(tenantRepo.findAll()).thenReturn(tenants)
        whenever(rollup.ledgerStart).thenReturn(LocalDate.of(2026, 10, 1))
        whenever(closes.findById(any())).thenReturn(Optional.empty())
        whenever(closes.findAllByOrderByPeriodDesc()).thenReturn(listOf())
        whenever(monthly.findByPeriod(any())).thenReturn(listOf())
        whenever(daily.sumByTenant(any(), any())).thenReturn(listOf())
        whenever(daily.findMinUsageDate()).thenReturn(Optional.of(LocalDate.of(2026, 9, 3)))
        whenever(daily.findMaxAggregatedAt(any(), any())).thenReturn(Optional.empty())
        whenever(auditRepo.findByActionIn(any())).thenReturn(listOf())
    }

    private fun closedOctober(auditPayload: String?, monthlyAuth: Long = 7) {
        whenever(closes.findById("2026-10")).thenReturn(Optional.of(close("2026-10", reg = 8, auth = 12, authAtt = 20)))
        whenever(monthly.findByPeriod("2026-10")).thenReturn(
            listOf(UsageMonthly(a, "2026-10", 5, 5, 6, 8), UsageMonthly(b, "2026-10", 3, monthlyAuth, 4, 12)),
        )
        val entries = if (auditPayload == null) listOf() else listOf(audit("USAGE_MONTH_CLOSED", auditPayload))
        whenever(auditRepo.findByActionIn(any())).thenReturn(entries)
    }

    @Test
    fun closedMonth_readsMonthlyRows_andVerifiesAgainstAudit_match() {
        closedOctober(payload("2026-10", 8, 12, Triple(a, 5, 5), Triple(b, 3, 7)))

        val v = service().view(oct)

        assertThat(v.status).isEqualTo(UsageDto.Status.CLOSED)
        assertThat(v.basis).isEqualTo("LEDGER")
        assertThat(v.asOf).isEqualTo(closedAt)
        assertThat(v.verification).isEqualTo(UsageDto.Verification.MATCH)
        assertThat(v.totalReg).isEqualTo(8)
        assertThat(v.totalAuth).isEqualTo(12)
        assertThat(v.tenants.map { it.name }).containsExactly("Acme", "Beta") // 합계가 10 으로 같아 이름순
    }

    @Test
    fun closedMonth_auditPayloadDiffers_mismatch() {
        // usage_monthly 의 authSuccess 만 1 다르게(8) — 감사 payload 는 7
        closedOctober(payload("2026-10", 8, 12, Triple(a, 5, 5), Triple(b, 3, 7)), monthlyAuth = 8)

        assertThat(service().view(oct).verification).isEqualTo(UsageDto.Verification.MISMATCH)
    }

    @Test
    fun closedMonth_noAuditRow_mismatch() {
        closedOctober(null)

        assertThat(service().view(oct).verification).isEqualTo(UsageDto.Verification.MISMATCH)
    }

    @Test
    fun closedMonth_auditTotalsDiffer_mismatch() {
        // 테넌트 행은 같지만 payload 의 전체 합계가 마감 행과 다르다
        closedOctober(payload("2026-10", 8, 13, Triple(a, 5, 5), Triple(b, 3, 7)))

        assertThat(service().view(oct).verification).isEqualTo(UsageDto.Verification.MISMATCH)
    }

    @Test
    fun closedMonth_twoAuditRowsForPeriod_mismatch() {
        val p = payload("2026-10", 8, 12, Triple(a, 5, 5), Triple(b, 3, 7))
        closedOctober(p)
        val two = listOf(audit("USAGE_MONTH_CLOSED", p), audit("USAGE_MONTH_PARTIAL", p))
        whenever(auditRepo.findByActionIn(any())).thenReturn(two)

        assertThat(service().view(oct).verification).isEqualTo(UsageDto.Verification.MISMATCH)
    }

    @Test
    fun closedMonth_auditOfOtherPeriodIsIgnored() {
        closedOctober(payload("2026-10", 8, 12, Triple(a, 5, 5), Triple(b, 3, 7)))
        val other = audit("USAGE_MONTH_CLOSED", payload("2026-09", 1, 1, Triple(a, 1, 1)))
        val mine = audit("USAGE_MONTH_CLOSED", payload("2026-10", 8, 12, Triple(a, 5, 5), Triple(b, 3, 7)))
        whenever(auditRepo.findByActionIn(any())).thenReturn(listOf(other, mine))

        assertThat(service().view(oct).verification).isEqualTo(UsageDto.Verification.MATCH)
    }

    @Test
    fun currentMonth_readsDaily_statusInProgress_verificationNotApplicable() {
        val asOf = OffsetDateTime.parse("2026-11-25T09:00:00+09:00")
        whenever(daily.sumByTenant(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 1)))
            .thenReturn(listOf(daily(a, 2, 4, 3, 5)))
        whenever(daily.findMaxAggregatedAt(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 1))).thenReturn(Optional.of(asOf))

        val v = service().view(nov)

        assertThat(v.status).isEqualTo(UsageDto.Status.IN_PROGRESS)
        assertThat(v.verification).isEqualTo(UsageDto.Verification.NOT_APPLICABLE)
        assertThat(v.basis).isEqualTo("LEDGER") // 11-01 >= ledgerStart(10-01)
        assertThat(v.asOf).isEqualTo(asOf)
        assertThat(v.totalReg).isEqualTo(2)
        assertThat(v.totalAuth).isEqualTo(4)
    }

    @Test
    fun inProgressMonthBeforeLedgerStart_basisLegacy() {
        whenever(rollup.ledgerStart).thenReturn(LocalDate.of(2026, 12, 1))

        assertThat(service().view(nov).basis).isEqualTo("LEGACY")
    }

    @Test
    fun partialMonth_statusPartial() {
        whenever(closes.findById("2026-10")).thenReturn(Optional.of(close("2026-10", UsagePeriodClose.STATUS_PARTIAL, "LEGACY", reg = 1, auth = 2)))
        whenever(monthly.findByPeriod("2026-10")).thenReturn(listOf(UsageMonthly(a, "2026-10", 1, 2, 0, 0)))
        val entry = audit("USAGE_MONTH_PARTIAL", payload("2026-10", 1, 2, Triple(a, 1, 2)))
        whenever(auditRepo.findByActionIn(any())).thenReturn(listOf(entry))

        val v = service().view(oct)

        assertThat(v.status).isEqualTo(UsageDto.Status.PARTIAL)
        assertThat(v.basis).isEqualTo("LEGACY")
        assertThat(v.verification).isEqualTo(UsageDto.Verification.MATCH)
    }

    @Test
    fun tenantWithoutUsage_isListedWithZero() {
        whenever(daily.sumByTenant(any(), any())).thenReturn(listOf(daily(a, 2, 4, 0, 0)))

        val rows = service().view(nov).tenants

        assertThat(rows.map { it.name }).containsExactly("Acme", "Beta")
        val beta = rows.single { it.tenantId == b }
        assertThat(beta.slug).isEqualTo("beta")
        assertThat(beta.regSuccess).isZero()
        assertThat(beta.authSuccess).isZero()
    }

    @Test
    fun usageForUnknownTenant_isListedById_andIncludedInTotal() {
        whenever(daily.sumByTenant(any(), any())).thenReturn(listOf(daily(a, 1, 1, 0, 0), daily(x, 3, 4, 0, 0)))

        val v = service().view(nov)

        val unknown = v.tenants.single { it.tenantId == x }
        assertThat(unknown.name).isEqualTo(x.toString())
        assertThat(unknown.slug).isNull()
        assertThat(v.totalReg).isEqualTo(4)
        assertThat(v.totalAuth).isEqualTo(5)
    }

    @Test
    fun tenants_sortedByTotalDescending_thenName() {
        whenever(daily.sumByTenant(any(), any())).thenReturn(listOf(daily(a, 1, 1, 0, 0), daily(b, 5, 5, 0, 0)))

        assertThat(service().view(nov).tenants.map { it.name }).containsExactly("Beta", "Acme")
    }

    @Test
    fun trend_has12Months_newestFirst_missingMonthsAreZero() {
        whenever(closes.findById("2026-10")).thenReturn(Optional.of(close("2026-10", reg = 8, auth = 12, authAtt = 20)))
        whenever(monthly.findByPeriod("2026-10")).thenReturn(
            listOf(UsageMonthly(a, "2026-10", 5, 5, 6, 8), UsageMonthly(b, "2026-10", 3, 7, 4, 12)),
        )
        whenever(daily.sumByTenant(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 1))).thenReturn(listOf(daily(a, 2, 4, 3, 5)))

        val trend = service().view(nov).trend

        assertThat(trend).hasSize(12)
        assertThat(trend.map { it.period }.first()).isEqualTo("2026-11")
        assertThat(trend.map { it.period }.last()).isEqualTo("2025-12")
        assertThat(trend[0].status).isEqualTo(UsageDto.Status.IN_PROGRESS)
        assertThat(trend[0].regSuccess).isEqualTo(2)
        assertThat(trend[0].activeTenants).isEqualTo(1)
        assertThat(trend[1].period).isEqualTo("2026-10")
        assertThat(trend[1].status).isEqualTo(UsageDto.Status.CLOSED)
        assertThat(trend[1].activeTenants).isEqualTo(2)
        val empty = trend[5] // 2026-06
        assertThat(empty.regSuccess).isZero()
        assertThat(empty.authSuccess).isZero()
        assertThat(empty.activeTenants).isZero()
        assertThat(empty.authAttempt).isZero()
    }

    @Test
    fun trend_authRate_usesAttemptColumn() {
        whenever(closes.findById("2026-10")).thenReturn(Optional.of(close("2026-10", reg = 0, auth = 90, authAtt = 100)))
        whenever(monthly.findByPeriod("2026-10")).thenReturn(listOf(UsageMonthly(a, "2026-10", 0, 90, 0, 100)))

        val m = service().view(nov).trend.single { it.period == "2026-10" }

        assertThat(m.authSuccess).isEqualTo(90)
        assertThat(m.authAttempt).isEqualTo(100)
    }

    @Test
    fun periods_fromFirstDailyMonthToCurrent_newestFirst() {
        assertThat(service().view(nov).periods).containsExactly("2026-11", "2026-10", "2026-09")
    }

    @Test
    fun periods_noData_onlyCurrentMonth() {
        whenever(daily.findMinUsageDate()).thenReturn(Optional.empty())

        assertThat(service().view(nov).periods).containsExactly("2026-11")
    }

    @Test
    fun oldMonthWithoutData_showsZeros() {
        val v = service().view(YearMonth.of(2020, 1))

        assertThat(v.period).isEqualTo("2020-01")
        assertThat(v.totalReg).isZero()
        assertThat(v.totalAuth).isZero()
        assertThat(v.tenants).hasSize(2)
    }

    /** 행이 없는 마감 월(close 합계 0)에 감사 payload 만 바꿔 끼운다. */
    private fun emptyClosedOctober(auditPayload: String) {
        whenever(closes.findById("2026-10")).thenReturn(Optional.of(close("2026-10", reg = 0, auth = 0)))
        whenever(monthly.findByPeriod("2026-10")).thenReturn(listOf())
        val entry = audit("USAGE_MONTH_CLOSED", auditPayload)
        whenever(auditRepo.findByActionIn(any())).thenReturn(listOf(entry))
    }

    @Test
    fun emptyClosedMonth_withEmptyTenantsArray_match() {
        emptyClosedOctober("""{"period":"2026-10","registrationSuccess":0,"authenticationSuccess":0,"tenants":[]}""")

        assertThat(service().view(oct).verification).isEqualTo(UsageDto.Verification.MATCH)
    }

    @Test
    fun auditPayload_missingTenants_mismatch() {
        emptyClosedOctober("""{"period":"2026-10","registrationSuccess":0,"authenticationSuccess":0}""")

        assertThat(service().view(oct).verification).isEqualTo(UsageDto.Verification.MISMATCH)
    }

    @Test
    fun auditPayload_tenantsNotArray_mismatch() {
        emptyClosedOctober("""{"period":"2026-10","registrationSuccess":0,"authenticationSuccess":0,"tenants":{}}""")

        assertThat(service().view(oct).verification).isEqualTo(UsageDto.Verification.MISMATCH)
    }

    @Test
    fun auditPayload_missingTotals_mismatch() {
        emptyClosedOctober("""{"period":"2026-10","tenants":[]}""")

        assertThat(service().view(oct).verification).isEqualTo(UsageDto.Verification.MISMATCH)
    }

    @Test
    fun auditPayload_nonNumericTotal_mismatch() {
        emptyClosedOctober("""{"period":"2026-10","registrationSuccess":"0","authenticationSuccess":0,"tenants":[]}""")

        assertThat(service().view(oct).verification).isEqualTo(UsageDto.Verification.MISMATCH)
    }

    @Test
    fun auditPayload_tenantElementMalformed_mismatch() {
        // 마감 행·monthly 는 정상이고 payload 의 한 원소만 tenantId 가 깨졌다 — 건너뛰고 일치로 보면 안 된다
        closedOctober(
            """{"period":"2026-10","registrationSuccess":8,"authenticationSuccess":12,"tenants":""" +
                """[{"tenantId":"$a","registrationSuccess":5,"authenticationSuccess":5},""" +
                """{"tenantId":"not-a-uuid","registrationSuccess":3,"authenticationSuccess":7}]}""",
        )

        assertThat(service().view(oct).verification).isEqualTo(UsageDto.Verification.MISMATCH)
    }

    @Test
    fun auditPayload_tenantElementMissingNumber_mismatch() {
        closedOctober(
            """{"period":"2026-10","registrationSuccess":8,"authenticationSuccess":12,"tenants":""" +
                """[{"tenantId":"$a","registrationSuccess":5,"authenticationSuccess":5},{"tenantId":"$b","registrationSuccess":3}]}""",
        )

        assertThat(service().view(oct).verification).isEqualTo(UsageDto.Verification.MISMATCH)
    }
}
