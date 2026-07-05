package com.crosscert.passkey.admin.funnel;

import com.crosscert.passkey.admin.auth.TenantBoundary;
import com.crosscert.passkey.core.ceremony.CeremonyAction;
import com.crosscert.passkey.core.config.KstTime;
import com.crosscert.passkey.core.repository.CeremonyEventRepository;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F06-funnelKST — the daily series day-bucket must reinterpret the
 * TRUNC(created_at) timestamp coming back from Oracle in the SAME timezone
 * the DB session used to compute TRUNC (KST — see AdminApplication's JVM
 * TZ=Asia/Seoul and hibernate.jdbc.time_zone=Asia/Seoul), not UTC.
 *
 * <p>Reproduction: today's KST midnight, expressed as an Instant, is
 * yesterday 15:00 UTC (KST = UTC+9, always — no DST). If buildSeries()
 * reinterprets that Instant in UTC, it buckets the row under "yesterday"
 * instead of "today" — every daily count is off by one day, and (per the
 * bug report) a query made between KST 00:00 and 09:00 additionally loses
 * today's freshest data off the rendering window entirely.
 *
 * <p>This reproduction is timezone-of-test-runner-independent: KST midnight
 * can never equal the same calendar date in UTC (the offset is a fixed +9h),
 * so the assertion below fails against the buggy UTC-reinterpretation code
 * regardless of what wall-clock time the test suite happens to run at.
 */
class FunnelServiceKstBucketTest {

    private final CeremonyEventRepository repo = mock(CeremonyEventRepository.class);
    private final TenantBoundary tenantBoundary = mock(TenantBoundary.class);
    private final FunnelService service = new FunnelService(repo, tenantBoundary);

    @Test
    void dailySeriesBucketsRowUnderKstDateNotUtcDate() {
        UUID tenant = UUID.randomUUID();

        // What Oracle's TRUNC(created_at) returns for "today" in a KST DB
        // session: today's KST midnight, as a Timestamp (Timestamp.toInstant()
        // yields the same underlying instant regardless of any zone).
        LocalDate todayKst = LocalDate.now(KstTime.ZONE);
        Timestamp dbTruncRow = Timestamp.from(
                todayKst.atStartOfDay(KstTime.ZONE).toInstant());

        List<Object[]> rows = new java.util.ArrayList<>();
        rows.add(new Object[]{dbTruncRow, CeremonyAction.REGISTRATION_BEGIN, 7L});
        when(repo.aggregateDailyByTenantAndActions(
                org.mockito.ArgumentMatchers.eq(tenant), any(), any()))
                .thenReturn(rows);
        when(repo.aggregateByTenantAndActionsGrouped(any(), any(), any())).thenReturn(List.of());
        when(repo.countByTenantIdAndActionAndCreatedAtAfter(any(), org.mockito.ArgumentMatchers.anyString(), any()))
                .thenReturn(0L);

        FunnelDto.View view = service.compute(tenant, 7);

        String todayLabel = todayKst.toString().substring(5);
        FunnelDto.DailyPoint todayPoint = view.series().stream()
                .filter(p -> p.day().equals(todayLabel))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "series window did not include today's KST label " + todayLabel
                                + " — series=" + view.series()));

        assertThat(todayPoint.attempts())
                .as("today's KST REGISTRATION_BEGIN count must be bucketed under today's KST date, "
                        + "not shifted to yesterday by a UTC reinterpretation of TRUNC(created_at)")
                .isEqualTo(7L);
    }
}
