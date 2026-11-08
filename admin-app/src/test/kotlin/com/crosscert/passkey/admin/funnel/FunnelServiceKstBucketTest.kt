package com.crosscert.passkey.admin.funnel

import com.crosscert.passkey.admin.auth.TenantBoundary
import com.crosscert.passkey.core.ceremony.CeremonyAction
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

/**
 * F06-funnelKST — the daily series day-bucket must reinterpret the
 * TRUNC(created_at) timestamp coming back from Oracle in the SAME timezone
 * the DB session used to compute TRUNC (KST — see AdminApplication's JVM
 * TZ=Asia/Seoul and hibernate.jdbc.time_zone=Asia/Seoul), not UTC.
 *
 * Reproduction: today's KST midnight, expressed as an Instant, is
 * yesterday 15:00 UTC (KST = UTC+9, always — no DST). If buildSeries()
 * reinterprets that Instant in UTC, it buckets the row under "yesterday"
 * instead of "today" — every daily count is off by one day, and (per the
 * bug report) a query made between KST 00:00 and 09:00 additionally loses
 * today's freshest data off the rendering window entirely.
 *
 * This reproduction is timezone-of-test-runner-independent: KST midnight
 * can never equal the same calendar date in UTC (the offset is a fixed +9h),
 * so the assertion below fails against the buggy UTC-reinterpretation code
 * regardless of what wall-clock time the test suite happens to run at.
 */
class FunnelServiceKstBucketTest {

    private val repo: CeremonyEventRepository = mock()
    private val tenantBoundary: TenantBoundary = mock()
    private val service = FunnelService(repo, mock<CredentialAuthEventRepository>(), tenantBoundary)

    @Test
    fun dailySeriesBucketsRowUnderKstDateNotUtcDate() {
        // What Oracle's TRUNC(created_at) returns for "today" in a KST DB
        // session under Hibernate 6: today's KST midnight, as a Timestamp
        // (Timestamp.toInstant() yields the same underlying instant regardless of any zone).
        val todayKst = LocalDate.now(KstTime.ZONE)
        assertTodayBucket(todayKst, Timestamp.from(todayKst.atStartOfDay(KstTime.ZONE).toInstant()))
    }

    @Test
    fun dailySeriesBucketsHibernate7LocalDateTimeRowUnderSameKstDate() {
        // Hibernate 7 returns the native-query DATE column as LocalDateTime — the DB
        // wall clock (KST midnight) itself. It must land in the same bucket as above.
        val todayKst = LocalDate.now(KstTime.ZONE)
        assertTodayBucket(todayKst, todayKst.atStartOfDay())
    }

    private fun assertTodayBucket(todayKst: LocalDate, dbTruncRow: Any) {
        val tenant = UUID.randomUUID()

        val rows: MutableList<Array<Any?>> = ArrayList()
        rows.add(arrayOf(dbTruncRow, CeremonyAction.REGISTRATION_BEGIN, 7L))
        whenever(repo.aggregateDailyByTenantAndActions(eq(tenant), anyOrNull(), anyOrNull()))
            .thenReturn(rows)
        whenever(repo.aggregateByTenantAndActionsGrouped(anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(listOf())
        whenever(repo.countByTenantIdAndActionAndCreatedAtAfter(anyOrNull(), anyString(), anyOrNull()))
            .thenReturn(0L)

        val view = service.compute(tenant, 7)

        val todayLabel = todayKst.toString().substring(5)
        val todayPoint = checkNotNull(view.series).stream()
            .filter { p -> p.day.equals(todayLabel) }
            .findFirst()
            .orElseThrow {
                AssertionError(
                    "series window did not include today's KST label " + todayLabel +
                        " — series=" + view.series,
                )
            }

        assertThat(todayPoint.attempts)
            .`as`(
                "today's KST REGISTRATION_BEGIN count must be bucketed under today's KST date, " +
                    "not shifted to yesterday by a UTC reinterpretation of TRUNC(created_at)",
            )
            .isEqualTo(7L)
    }
}
