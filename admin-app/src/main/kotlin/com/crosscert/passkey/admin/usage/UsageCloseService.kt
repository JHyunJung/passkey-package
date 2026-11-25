package com.crosscert.passkey.admin.usage

import com.crosscert.passkey.admin.audit.AuditAppendRequest
import com.crosscert.passkey.admin.audit.AuditLogService
import com.crosscert.passkey.core.config.KstTime
import com.crosscert.passkey.core.entity.UsageDaily
import com.crosscert.passkey.core.entity.UsageMonthly
import com.crosscert.passkey.core.entity.UsagePeriodClose
import com.crosscert.passkey.core.repository.AuditLogRepository
import com.crosscert.passkey.core.repository.CeremonyEventRepository
import com.crosscert.passkey.core.repository.UsageDailyRepository
import com.crosscert.passkey.core.repository.UsageMonthlyRepository
import com.crosscert.passkey.core.repository.UsagePeriodCloseRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.util.UUID

private val log = LoggerFactory.getLogger(UsageCloseService::class.java)

private const val ACTION_MONTH_CLOSED = "USAGE_MONTH_CLOSED"
private const val ACTION_MONTH_PARTIAL = "USAGE_MONTH_PARTIAL"
private const val ACTION_BACKFILL_DONE = "USAGE_BACKFILL_DONE"
private const val ACTOR_SCHEDULER = "(scheduler)"
private const val TARGET_PERIOD = "usage_period"
private const val CLOSE_PK_NAME = "pk_usage_period_close"
private const val ORA_UNIQUE_VIOLATION = 1 // ORA-00001

/** sumByTenant 한 행. */
private data class TenantSum(
    val tenantId: UUID,
    val regSuccess: Long,
    val authSuccess: Long,
    val regAttempt: Long,
    val authAttempt: Long,
) {
    val hasAny: Boolean get() = regSuccess != 0L || authSuccess != 0L || regAttempt != 0L || authAttempt != 0L
}

/**
 * 월 마감(usage_monthly·usage_period_close 확정)과 최초 1회 백필. 마감된 달의 숫자는 청구 근거라 다시 바꾸지 않는다 —
 * 두 테이블에는 INSERT 만 하고, 같은 수치를 감사 로그(해시 체인)에도 남겨 사후 변경을 드러나게 한다.
 */
@Service
class UsageCloseService(
    private val rollup: UsageRollupService,
    private val daily: UsageDailyRepository,
    private val monthly: UsageMonthlyRepository,
    private val closes: UsagePeriodCloseRepository,
    private val ceremonyEvents: CeremonyEventRepository,
    private val auditRepo: AuditLogRepository,
    private val audit: AuditLogService,
    private val tx: TransactionTemplate,
    private val clock: Clock,
    @Value("\${passkey.retention.ceremony-event:P90D}") private val retention: Duration,
    @Value("\${passkey.usage.close-after:PT2H}") private val closeAfter: Duration,
) {

    /**
     * 한 번만 실행된다. 실행했으면 true.
     * 완료 표식은 감사 로그 USAGE_BACKFILL_DONE 한 건이고 맨 마지막에 쓴다. 중간에 실패하면 예외를 그대로 던지고
     * 다음 실행이 처음부터 다시 한다(일별 집계는 멱등, 이미 확정된 부분 월은 건너뛴다).
     */
    fun backfillIfNeeded(): Boolean {
        if (auditRepo.existsByAction(ACTION_BACKFILL_DONE)) return false
        val now = OffsetDateTime.now(clock)
        val oldest = ceremonyEvents.findMinCreatedAt().orElse(null)
        val partialPeriods = mutableListOf<String>()
        if (oldest != null) {
            val fromDay = oldest.atZoneSameInstant(KstTime.ZONE).toLocalDate()
            val today = LocalDate.now(clock)
            rollup.rollupDays(fromDay, today)

            // 부분 월: 그달 1일 00:00 KST 가 (백필 시각 − 원본 보존 기간) 보다 앞서는 지난 달 — 원본 일부가 이미 지워졌을 수 있다.
            val cutoff = now.minus(retention)
            val closed = closedPeriods()
            var m = YearMonth.from(fromDay)
            val lastMonth = YearMonth.now(clock).minusMonths(1)
            while (m <= lastMonth && UsagePeriods.monthStart(m).isBefore(cutoff)) {
                val period = UsagePeriods.periodOf(m)
                if (period !in closed) closeMonth(m, UsagePeriodClose.STATUS_PARTIAL)
                partialPeriods += period
                m = m.plusMonths(1)
            }
        }
        val payload = linkedMapOf<String, Any?>(
            "backfilledAt" to now.toString(),
            "oldestLedgerAt" to oldest?.toString(),
            "partialPeriods" to partialPeriods,
        )
        checkNotNull(audit.append(AuditAppendRequest(null, ACTOR_SCHEDULER, ACTION_BACKFILL_DONE, null, null, null, payload))) {
            "USAGE_BACKFILL_DONE 감사 기록 실패"
        }
        log.info("usage backfill done: oldestLedgerAt={} partialPeriods={}", oldest, partialPeriods)
        return true
    }

    /**
     * 마감한 기간 목록(오래된 달부터).
     * 대상은 usage_daily 의 첫 날이 속한 달부터 지난 달까지 중 아직 마감되지 않은 달이고, 다음 달 1일 00:00 KST +
     * close-after 가 지나야 마감한다(자정을 넘겨 커밋된 기록을 기다리는 여유). 한 달이 실패하면 순서를 지키기 위해 그 뒤 달은
     * 이번 실행에서 시도하지 않는다 — 오류 로그를 남기고 그때까지 마감한 목록을 돌려준다(다음 실행이 다시 시도).
     */
    fun closeDueMonths(): List<String> {
        val first = daily.findMinUsageDate().orElse(null) ?: return emptyList()
        val now = OffsetDateTime.now(clock)
        val closed = closedPeriods()
        val result = mutableListOf<String>()
        var m = YearMonth.from(first)
        val currentMonth = YearMonth.now(clock)
        while (m < currentMonth) {
            if (now.isBefore(UsagePeriods.monthStart(m.plusMonths(1)).plus(closeAfter))) break
            val period = UsagePeriods.periodOf(m)
            if (period !in closed) {
                try {
                    if (closeMonth(m, UsagePeriodClose.STATUS_CLOSED)) result += period
                } catch (e: Exception) {
                    log.error("usage month close failed: period={} — 이후 달은 다음 실행에서 다시 시도한다", period, e)
                    break
                }
            }
            m = m.plusMonths(1)
        }
        return result
    }

    /**
     * 이 시각 이전의 ceremony_event 만 삭제해도 된다.
     * 원본의 첫 달부터 차례로 보아 처음 만나는 "마감 행 없는 달" 의 시작. 원본이 없으면(또는 지난 달까지 전부 마감이면) 이번 달 시작.
     */
    fun releasedBefore(): OffsetDateTime {
        val currentMonth = YearMonth.now(clock)
        val oldest = ceremonyEvents.findMinCreatedAt().orElse(null)
            ?: return UsagePeriods.monthStart(currentMonth)
        val closed = closedPeriods()
        var m = YearMonth.from(oldest.atZoneSameInstant(KstTime.ZONE))
        while (m < currentMonth && UsagePeriods.periodOf(m) in closed) m = m.plusMonths(1)
        return UsagePeriods.monthStart(minOf(m, currentMonth))
    }

    /**
     * 한 달을 한 트랜잭션으로 확정한다. 확정했으면 true, 이미 마감된 달이면 false(트랜잭션은 롤백 — 재집계도 되돌린다).
     * 순서: ⓪ usage_period_close EXCLUSIVE 락(일반 집계·다른 마감과 직렬화) ① 마감 여부 확인 ② 그달 재집계 ③ 합산
     * ④ usage_period_close INSERT(flush — PK 충돌을 여기서 드러낸다) ⑤ usage_monthly INSERT ⑥ 맨 마지막에 감사 기록.
     * 감사 기록이 실패하면 예외가 트랜잭션 밖으로 나가 전체가 롤백된다.
     *
     * 락 순서: 마감은 테이블 락 → usage_daily 행 → 감사 체인 락, 일반 집계는 테이블 락 → usage_daily 행. 둘 다 테이블 락을
     * 첫 문장으로 잡고 그 모드가 서로 배타라, usage_daily 행 락을 쥔 채 상대를 기다리는 순환(교착)이 생기지 않는다.
     *
     * internal: 같은 모듈 테스트가 직접 호출한다.
     */
    internal fun closeMonth(month: YearMonth, status: String): Boolean =
        tx.execute { txStatus -> closeInTx(month, status, txStatus) } == true

    private fun closeInTx(month: YearMonth, status: String, txStatus: TransactionStatus): Boolean {
        val period = UsagePeriods.periodOf(month)
        val from = month.atDay(1)
        closes.lockForClose()
        // 락을 쥔 뒤라 이 확인 이후 다른 마감이 끼어들 수 없다. 아래 PK 는 그래도 남겨 둔 마지막 방어선이다
        // (UsagePeriodClose 는 Persistable 이라 saveAndFlush 가 항상 persist(INSERT)다).
        if (closes.existsById(period)) {
            log.info("usage month already closed — skip: period={}", period)
            txStatus.setRollbackOnly()
            return false
        }
        rollup.rollupMonthForClose(month)
        val tenants = daily.sumByTenant(from, month.plusMonths(1).atDay(1))
            .map { r ->
                TenantSum(
                    r[0] as UUID,
                    (r[1] as Number).toLong(), (r[2] as Number).toLong(),
                    (r[3] as Number).toLong(), (r[4] as Number).toLong(),
                )
            }
            .filter { it.hasAny }
            .sortedBy { it.tenantId.toString() }
        val basis = if (from >= rollup.ledgerStart) UsageDaily.BASIS_LEDGER else UsageDaily.BASIS_LEGACY
        val regSuccess = tenants.sumOf { it.regSuccess }
        val authSuccess = tenants.sumOf { it.authSuccess }

        try {
            closes.saveAndFlush(
                UsagePeriodClose(
                    period, status, basis,
                    regSuccess, authSuccess, tenants.sumOf { it.regAttempt }, tenants.sumOf { it.authAttempt },
                    tenants.size, OffsetDateTime.now(clock),
                ),
            )
        } catch (e: DataIntegrityViolationException) {
            // 같은 기간의 PK 충돌(ORA-00001 on pk_usage_period_close)만 "이미 마감됨" 이다. CHECK 위반·NUMBER 자릿수 초과 같은
            // 다른 무결성 위반은 진짜 실패라 그대로 던진다(이후 달 마감·백필 표식을 막는다).
            // try 는 마감 행 INSERT 하나만 감싼다 — 앞의 재집계(MERGE)는 즉시 실행되고 flush 할 다른 변경이 없다.
            if (!isClosePkCollision(e)) throw e
            log.info("usage month closed by another instance — skip: period={}", period)
            txStatus.setRollbackOnly()
            return false
        }
        monthly.saveAllAndFlush(
            tenants.map { UsageMonthly(it.tenantId, period, it.regSuccess, it.authSuccess, it.regAttempt, it.authAttempt) },
        )

        val payload = linkedMapOf<String, Any?>(
            "period" to period,
            "status" to status,
            "basis" to basis,
            "registrationSuccess" to regSuccess,
            "authenticationSuccess" to authSuccess,
            "tenants" to tenants.map {
                linkedMapOf(
                    "tenantId" to it.tenantId.toString(),
                    "registrationSuccess" to it.regSuccess,
                    "authenticationSuccess" to it.authSuccess,
                )
            },
        )
        val action = if (status == UsagePeriodClose.STATUS_PARTIAL) ACTION_MONTH_PARTIAL else ACTION_MONTH_CLOSED
        // append 는 같은 트랜잭션에 참여한다(REQUIRED). 전역 체인 락을 커밋까지 쥐므로 반드시 마지막 문장이어야 한다.
        // 정상 경로에서 null 은 나오지 않지만, 나온다면 기록이 없는 것이므로 실패로 보고 롤백한다.
        checkNotNull(audit.append(AuditAppendRequest(null, ACTOR_SCHEDULER, action, TARGET_PERIOD, period, null, payload))) {
            "$action 감사 기록 실패: period=$period"
        }
        log.info("usage month {}: period={} basis={} tenants={} reg={} auth={}", status, period, basis, tenants.size, regSuccess, authSuccess)
        return true
    }

    private fun closedPeriods(): Set<String> = closes.findAll().mapTo(HashSet()) { it.period }

    /** 원인 사슬에 pk_usage_period_close 를 지목한 ORA-00001(unique constraint) 이 있는가. 제약 이름은 대소문자 무시. */
    private fun isClosePkCollision(e: Throwable): Boolean =
        generateSequence(e) { c -> c.cause?.takeIf { it !== c } }.any { c ->
            c is SQLException && c.errorCode == ORA_UNIQUE_VIOLATION &&
                c.message?.contains(CLOSE_PK_NAME, ignoreCase = true) == true
        }
}
