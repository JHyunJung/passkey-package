package com.crosscert.passkey.admin.usage

import com.crosscert.passkey.core.entity.UsageDaily
import com.crosscert.passkey.core.entity.UsagePeriodClose
import com.crosscert.passkey.core.repository.AuditLogRepository
import com.crosscert.passkey.core.repository.TenantRepository
import com.crosscert.passkey.core.repository.UsageDailyRepository
import com.crosscert.passkey.core.repository.UsageMonthlyRepository
import com.crosscert.passkey.core.repository.UsagePeriodCloseRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.OffsetDateTime
import java.time.YearMonth
import java.util.UUID

private val log = LoggerFactory.getLogger(UsageSettlementService::class.java)

private const val ACTION_MONTH_CLOSED = "USAGE_MONTH_CLOSED"
private const val ACTION_MONTH_PARTIAL = "USAGE_MONTH_PARTIAL"
private const val TREND_MONTHS = 12L

/** 한 달의 테넌트별 성공 건수(성공 건수가 0 이어도 시도가 있었으면 행이 있다). */
private data class TenantCount(val tenantId: UUID, val regSuccess: Long, val authSuccess: Long)

/** 한 달치 원자료. 마감된 달은 usage_monthly·usage_period_close, 아니면 usage_daily 에서 읽는다. */
private class MonthData(
    val status: UsageDto.Status,
    val basis: String,
    val asOf: OffsetDateTime?,
    val counts: List<TenantCount>,
    val regTotal: Long,
    val authTotal: Long,
    val authAttempt: Long,
    val close: UsagePeriodClose?,
)

/**
 * 사용량 정산 조회(읽기 전용). 청구 근거는 마감된 달의 usage_monthly·usage_period_close 이고, 마감 전 달은 usage_daily 합계다.
 * 마감된 달은 마감 때 감사 로그에 남긴 같은 숫자와 대조해 결과를 함께 돌려준다.
 */
@Service
@Transactional(readOnly = true)
class UsageSettlementService(
    private val tenantRepo: TenantRepository,
    private val daily: UsageDailyRepository,
    private val monthly: UsageMonthlyRepository,
    private val closes: UsagePeriodCloseRepository,
    private val auditRepo: AuditLogRepository,
    private val rollup: UsageRollupService,
    private val json: ObjectMapper,
    private val clock: Clock,
) {

    fun view(period: YearMonth): UsageDto.View {
        val current = YearMonth.now(clock)
        val data = load(period)
        val periodText = UsagePeriods.periodOf(period)

        val tenants = tenantRepo.findAll().associateBy { it.id }
        val counts = data.counts.associateBy { it.tenantId }
        val rows = tenants.map { (id, t) ->
            val c = counts[id]
            UsageDto.TenantRow(id, t.slug, t.displayName, c?.regSuccess ?: 0, c?.authSuccess ?: 0)
        } + data.counts.filter { it.tenantId !in tenants }.map {
            // 건수는 있는데 테넌트가 없는 id(삭제된 테넌트 등) — 합계에서 빠지지 않게 id 로 덧붙인다.
            UsageDto.TenantRow(it.tenantId, null, it.tenantId.toString(), it.regSuccess, it.authSuccess)
        }
        val sorted = rows.sortedWith(compareByDescending<UsageDto.TenantRow> { it.regSuccess + it.authSuccess }.thenBy { it.name })

        return UsageDto.View(
            period = periodText,
            status = data.status,
            basis = data.basis,
            asOf = data.asOf,
            verification = verify(periodText, data),
            tenants = sorted,
            totalReg = sorted.sumOf { it.regSuccess },
            totalAuth = sorted.sumOf { it.authSuccess },
            trend = (0 until TREND_MONTHS).map { i ->
                val m = current.minusMonths(i)
                val d = if (m == period) data else load(m)
                UsageDto.MonthRow(
                    UsagePeriods.periodOf(m), d.status, d.regTotal, d.authTotal,
                    d.counts.count { it.regSuccess + it.authSuccess > 0 }, d.authAttempt,
                )
            },
            periods = periods(current),
        )
    }

    /** usage_daily 의 첫 날이 속한 달부터 이번 달까지(최신 먼저). 데이터가 없으면 이번 달 하나. */
    private fun periods(current: YearMonth): List<String> {
        val first = daily.findMinUsageDate().map { YearMonth.from(it) }.orElse(current)
        val result = mutableListOf<String>()
        var m = current
        while (m >= first) {
            result += UsagePeriods.periodOf(m)
            m = m.minusMonths(1)
        }
        return result.ifEmpty { listOf(UsagePeriods.periodOf(current)) }
    }

    private fun load(month: YearMonth): MonthData {
        val period = UsagePeriods.periodOf(month)
        val close = closes.findById(period).orElse(null)
        if (close != null) {
            val counts = monthly.findByPeriod(period).map { TenantCount(it.tenantId, it.regSuccess, it.authSuccess) }
            val status = if (close.status == UsagePeriodClose.STATUS_PARTIAL) UsageDto.Status.PARTIAL else UsageDto.Status.CLOSED
            return MonthData(status, close.basis, close.closedAt, counts, close.regSuccess, close.authSuccess, close.authAttempt, close)
        }
        val from = month.atDay(1)
        val to = month.plusMonths(1).atDay(1)
        val sums = daily.sumByTenant(from, to)
        val counts = sums.map { TenantCount(it[0] as UUID, (it[1] as Number).toLong(), (it[2] as Number).toLong()) }
        // 마감과 같은 규칙: 그달 1일이 장부 시작일 이상이면 장부, 아니면 이전 방식.
        val basis = if (from >= rollup.ledgerStart) UsageDaily.BASIS_LEDGER else UsageDaily.BASIS_LEGACY
        return MonthData(
            UsageDto.Status.IN_PROGRESS, basis, daily.findMaxAggregatedAt(from, to).orElse(null), counts,
            counts.sumOf { it.regSuccess }, counts.sumOf { it.authSuccess }, sums.sumOf { (it[4] as Number).toLong() }, null,
        )
    }

    /**
     * 마감된 달: 마감 때 남긴 감사 기록(그 기간 것이 정확히 한 건)이 usage_monthly 의 테넌트별 두 수치와
     * 마감 행의 전체 합계에 모두 같아야 MATCH. 없거나 둘 이상이거나 하나라도 다르면 MISMATCH.
     */
    private fun verify(period: String, data: MonthData): UsageDto.Verification {
        val close = data.close ?: return UsageDto.Verification.NOT_APPLICABLE
        val entries = auditRepo.findByActionIn(listOf(ACTION_MONTH_CLOSED, ACTION_MONTH_PARTIAL))
            .mapNotNull { parse(it.payload) }
            .filter { it.path("period").asString("") == period }
        if (entries.size != 1) return UsageDto.Verification.MISMATCH
        val p = entries.single()
        val audited = p.path("tenants").mapNotNull { t ->
            val id = runCatching { UUID.fromString(t.path("tenantId").asString("")) }.getOrNull() ?: return@mapNotNull null
            TenantCount(id, t.path("registrationSuccess").asLong(-1), t.path("authenticationSuccess").asLong(-1))
        }
        val sameRows = audited.size == p.path("tenants").size() && audited.toSet() == data.counts.toSet() && audited.size == data.counts.size
        val sameTotals = p.path("registrationSuccess").asLong(-1) == close.regSuccess &&
            p.path("authenticationSuccess").asLong(-1) == close.authSuccess
        return if (sameRows && sameTotals) UsageDto.Verification.MATCH else UsageDto.Verification.MISMATCH
    }

    private fun parse(payload: String?): JsonNode? =
        try {
            payload?.let { json.readTree(it) }
        } catch (e: Exception) {
            log.warn("[usage] 감사 payload 해석 실패 — 대조에서 제외: {}", e.javaClass.simpleName)
            null
        }
}
