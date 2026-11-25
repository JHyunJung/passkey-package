package com.crosscert.passkey.admin.usage

import com.crosscert.passkey.admin.scheduler.SchedulerLeaseService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.lang.management.ManagementFactory
import java.time.Duration

private val log = LoggerFactory.getLogger(UsageRollupJob::class.java)

private const val LEASE_NAME = "usage-rollup"

/**
 * 사용량 일별 집계·월 마감 배치. 기본 1시간마다 (최초 1회 백필) → 최근 3일 재집계 → 마감할 달 확정 순으로 돈다.
 * SchedulerLease 로 다중 인스턴스 중 하나만 실행한다. 백필이 실패하면 이번 실행은 거기서 멈춘다
 * (백필 전에 마감하면 부분 월이어야 할 달이 CLOSED 로 확정될 수 있다).
 */
@Component
class UsageRollupJob(
    private val rollup: UsageRollupService,
    private val close: UsageCloseService,
    private val leases: SchedulerLeaseService,
) {

    @Scheduled(
        fixedDelayString = "\${passkey.usage.rollup.fixed-delay:PT1H}",
        initialDelayString = "\${passkey.usage.rollup.initial-delay:PT2M}",
    )
    fun runOnce() {
        val holder = ManagementFactory.getRuntimeMXBean().name
        if (!leases.tryAcquire(LEASE_NAME, holder, Duration.ofMinutes(30))) {
            log.debug("UsageRollupJob skipped — another instance holds the lease")
            return
        }
        // 정상·예외 어느 경로든 lease 를 즉시 반환한다(TTL 은 크래시 시 회수용 안전망).
        try {
            close.backfillIfNeeded()
            val days = rollup.rollupRecent()
            val closed = close.closeDueMonths()
            log.info("usage rollup done: days={} closed={}", days, closed)
        } finally {
            leases.release(LEASE_NAME, holder)
        }
    }
}
