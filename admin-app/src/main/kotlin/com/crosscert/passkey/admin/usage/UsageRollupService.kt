package com.crosscert.passkey.admin.usage

import com.crosscert.passkey.core.config.KstTime
import com.crosscert.passkey.core.repository.CeremonyEventRepository
import com.crosscert.passkey.core.repository.UsageDailyRepository
import com.crosscert.passkey.core.repository.UsagePeriodCloseRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** 정기 실행이 되짚어 다시 세는 최근 일수(오늘 포함). 늦게 도착한 기록·재시도 보정용. */
private const val RECENT_DAYS = 3L

/**
 * 장부(ceremony_event)를 일별 요약(usage_daily)으로 집계한다. 병합은 멱등이라 같은 구간을 다시 돌려도 안전하다.
 * 닫힌 달은 [rollupDays]/[rollupRecent] 가 건너뛴다 — 마감된 숫자가 다시 바뀌지 않게 하는 방어선.
 * 이 확인과 MERGE 는 한 트랜잭션이고, 그 트랜잭션은 먼저 usage_period_close 에 SHARE 락을 건다
 * ([UsagePeriodCloseRepository.lockForRollup]). 마감([UsageCloseService])은 EXCLUSIVE 락을 쥐므로, 진행 중인 마감이 커밋된 뒤에
 * 확인해 그달을 건너뛰고, 진행 중인 집계가 있으면 마감이 그 커밋을 기다린다.
 */
@Service
class UsageRollupService(
    private val daily: UsageDailyRepository,
    private val closes: UsagePeriodCloseRepository,
    private val ceremonyEvents: CeremonyEventRepository,
    private val clock: Clock,
    @Value("\${passkey.usage.ledger-start-date:}") ledgerStartRaw: String,
) {

    /** 이 날짜 이상은 "장부" 기준, 이전은 "이전 방식". 설정이 없거나 틀리면 [UsagePeriods.NO_LEDGER]. */
    val ledgerStart: LocalDate = UsagePeriods.parseLedgerStart(ledgerStartRaw)

    /** 닫힌 달에 속한 날은 건너뛴다. 반환값은 실제로 집계한 날 수. */
    @Transactional
    fun rollupDays(fromDay: LocalDate, toDayInclusive: LocalDate): Int {
        if (fromDay > toDayInclusive) return 0
        // 첫 문장 — 마감 확인보다 먼저. 진행 중인 마감이 있으면 그 커밋까지 기다린다.
        closes.lockForRollup()
        var aggregated = 0
        // 열린 날들의 연속 구간. 닫힌 달을 만나면 거기서 끊어 병합 한 번으로 처리한다.
        var runStart: LocalDate? = null
        var runEnd: LocalDate? = null

        fun flush() {
            val s = runStart ?: return
            val e = checkNotNull(runEnd)
            merge(s, e.plusDays(1))
            aggregated += ChronoUnit.DAYS.between(s, e).toInt() + 1
            runStart = null
            runEnd = null
        }

        var month = YearMonth.from(fromDay)
        val lastMonth = YearMonth.from(toDayInclusive)
        while (month <= lastMonth) {
            val segStart = maxOf(fromDay, month.atDay(1))
            val segEnd = minOf(toDayInclusive, month.atEndOfMonth())
            if (closes.existsById(UsagePeriods.periodOf(month))) {
                flush()
            } else {
                if (runStart == null) runStart = segStart
                runEnd = segEnd
            }
            month = month.plusMonths(1)
        }
        flush()
        return aggregated
    }

    /**
     * 정기 실행분: 기본은 오늘 포함 최근 3일. usage_daily 의 마지막 날이 그보다 앞서면 그날부터.
     * usage_daily 가 비어 있으면(기준점 없음 — 빈 설치에서 백필이 끝난 뒤 admin-app 이 꺼져 있던 동안 기록이 쌓인 경우 등)
     * 장부의 가장 오래된 행이 속한 날(KST)부터 센다. 장부도 비어 있으면 최근 3일.
     * 범위를 정하는 두 조회는 락 전이어도 된다 — 닫힌 달 확인과 MERGE 는 [rollupDays] 안, 락 뒤에서 한다.
     */
    @Transactional // 내부의 rollupDays 호출은 같은 객체 안이라 프록시를 거치지 않는다 — 여기서 트랜잭션을 연다.
    fun rollupRecent(): Int {
        val today = LocalDate.now(clock)
        val default = today.minusDays(RECENT_DAYS - 1)
        val from = daily.findMaxUsageDate().map { minOf(default, it) }.orElseGet {
            ceremonyEvents.findMinCreatedAt()
                .map { minOf(default, it.atZoneSameInstant(KstTime.ZONE).toLocalDate()) }
                .orElse(default)
        }
        return rollupDays(from, today)
    }

    /** 닫힌 달 검사 없이 한 달 전체를 다시 센다. 마감 직전, 마감 트랜잭션(EXCLUSIVE 락 보유) 안에서만 쓴다. */
    fun rollupMonthForClose(month: YearMonth) {
        merge(month.atDay(1), month.plusMonths(1).atDay(1))
    }

    private fun merge(fromDay: LocalDate, toDayExclusive: LocalDate) {
        daily.mergeFromLedger(
            UsagePeriods.dayStart(fromDay),
            UsagePeriods.dayStart(toDayExclusive),
            ledgerStart,
            OffsetDateTime.now(clock),
        )
    }
}
