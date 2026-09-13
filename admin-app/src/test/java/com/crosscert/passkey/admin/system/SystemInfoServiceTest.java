package com.crosscert.passkey.admin.system;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.crosscert.passkey.core.config.DbSchemaProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.info.BuildProperties;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SystemInfoService 단위 테스트 — MockEnvironment + mock JdbcTemplate 로
 * DB·부트 컨텍스트 없이 뷰 조립 규칙만 검증한다.
 */
class SystemInfoServiceTest {

    private JdbcTemplate jdbc;
    private MockEnvironment env;
    private DbSchemaProperties dbSchema;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        env = new MockEnvironment();
        dbSchema = new DbSchemaProperties("PASSKEY");
        // mds_blob_cache.fetched_at 조회 — 기본은 "방금 동기화됨"으로 두어(note 단언 테스트가
        // "아직 동기화 전" 오버라이드에 걸리지 않게) status/행-없음 전용 테스트만 재스텁한다.
        when(jdbc.queryForObject(any(String.class), eq(Timestamp.class)))
                .thenReturn(Timestamp.from(Instant.now()));
    }

    private SystemInfoService newService(BuildProperties build) {
        return new SystemInfoService(build, jdbc, env, dbSchema);
    }

    private static BuildProperties buildProps(String version, Instant time) {
        Properties p = new Properties();
        p.setProperty("version", version);
        if (time != null) p.setProperty("time", String.valueOf(time.toEpochMilli()));
        return new BuildProperties(p);
    }

    // ── #68 environment = active profiles ────────────────────────────────────

    @Test
    void environmentIsDefaultWhenNoProfileIsActive() {
        SystemInfoView view = newService(null).get();

        assertThat(view.host().environment()).isEqualTo("default");
    }

    @Test
    void environmentJoinsMultipleActiveProfilesWithComma() {
        env.setActiveProfiles("prod", "oracle");

        SystemInfoView view = newService(null).get();

        assertThat(view.host().environment()).isEqualTo("prod,oracle");
    }

    // ── #73 deployedAt / version ─────────────────────────────────────────────

    @Test
    void deployedAtIsNullAndVersionIsDevWithoutBuildProperties() {
        SystemInfoView view = newService(null).get();

        assertThat(view.deployedAt()).isNull();
        assertThat(view.serverVersion()).isEqualTo("dev");
    }

    @Test
    void deployedAtIsRenderedInKstWhenBuildPropertiesArePresent() {
        // 2026-05-31T00:00:00Z == 2026-05-31T09:00+09:00 (KST)
        Instant built = Instant.parse("2026-05-31T00:00:00Z");
        BuildProperties build = buildProps("1.2.3", built);

        SystemInfoView view = newService(build).get();

        assertThat(view.serverVersion()).isEqualTo("1.2.3");
        assertThat(view.deployedAt())
                .isEqualTo(OffsetDateTime.ofInstant(built, ZoneOffset.ofHours(9)).toString())
                .startsWith("2026-05-31T09:00")
                .endsWith("+09:00");
        // UI 는 slice(0,10) 으로 날짜만 취한다 — KST 문자열에서도 앞 10자가 날짜다.
        assertThat(view.deployedAt().substring(0, 10)).isEqualTo("2026-05-31");
    }

    // ── #71/#72 DB 배너 + 상태 판정 ─────────────────────────────────────────────

    @Test
    void dbBannerFallsBackToUnknownAndIsRetriedOnEveryCallWhenQueryFails() {
        when(jdbc.queryForObject(any(String.class), eq(String.class)))
                .thenThrow(new DataAccessResourceFailureException("v$version denied"));
        SystemInfoService service = newService(null);

        assertThat(bannerOf(service.get())).isEqualTo("Oracle (unknown)");
        assertThat(bannerOf(service.get())).isEqualTo("Oracle (unknown)");

        // 실패는 캐시하지 않는다 — 매 요청 재시도.
        verify(jdbc, times(2)).queryForObject(any(String.class), eq(String.class));
    }

    @Test
    void dbBannerIsCachedAfterTheFirstSuccessfulQuery() {
        when(jdbc.queryForObject(any(String.class), eq(String.class)))
                .thenReturn("Oracle Database 19c Enterprise Edition");
        SystemInfoService service = newService(null);

        assertThat(bannerOf(service.get())).isEqualTo("Oracle Database 19c Enterprise Edition");
        assertThat(bannerOf(service.get())).isEqualTo("Oracle Database 19c Enterprise Edition");

        // 성공값은 캐시 — DB 는 한 번만 조회한다.
        verify(jdbc, times(1)).queryForObject(any(String.class), eq(String.class));
    }

    @Test
    void blankDbBannerIsNotCachedAndKeepsTheUnknownFallback() {
        when(jdbc.queryForObject(any(String.class), eq(String.class))).thenReturn("   ");
        SystemInfoService service = newService(null);

        assertThat(bannerOf(service.get())).isEqualTo("Oracle (unknown)");
        assertThat(bannerOf(service.get())).isEqualTo("Oracle (unknown)");

        verify(jdbc, times(2)).queryForObject(any(String.class), eq(String.class));
    }

    @Test
    void dbStatusIsOkWhenBannerQuerySucceeds() {
        when(jdbc.queryForObject(any(String.class), eq(String.class)))
                .thenReturn("Oracle Database 19c Enterprise Edition");

        SystemInfoView view = newService(null).get();

        assertThat(statusOf(view, "Oracle DB")).isEqualTo("OK");
    }

    @Test
    void dbStatusIsDownWhenConnectionCannotBeObtained() {
        when(jdbc.queryForObject(any(String.class), eq(String.class)))
                .thenThrow(new CannotGetJdbcConnectionException("connection refused"));

        SystemInfoView view = newService(null).get();

        assertThat(statusOf(view, "Oracle DB")).isEqualTo("DOWN");
        assertThat(bannerOf(view)).isEqualTo("Oracle (unknown)");
    }

    @Test
    void dbStatusIsDegradedWhenQueryFailsForOtherReasons() {
        when(jdbc.queryForObject(any(String.class), eq(String.class)))
                .thenThrow(new DataAccessResourceFailureException("v$version denied"));

        SystemInfoView view = newService(null).get();

        assertThat(statusOf(view, "Oracle DB")).isEqualTo("DEGRADED");
    }

    // ── B4-2 컴포넌트 목록 ──────────────────────────────────────────────────────

    @Test
    void componentsAreAdminAppOracleDbAndMdsSchedulerOnly() {
        SystemInfoView view = newService(null).get();

        assertThat(view.components().stream().map(SystemInfoView.Component::name))
                .containsExactly("admin-app", "Oracle DB", "MDS sync scheduler");
    }

    // ── #69 MDS 스케줄 note ──────────────────────────────────────────────────

    @Test
    void mdsComponentNoteReflectsTheConfiguredFixedDelay() {
        env.setProperty("passkey.mds.fixed-delay", "PT12H");

        SystemInfoView view = newService(null).get();

        assertThat(noteOf(view, "MDS sync scheduler")).isEqualTo("매 12시간");
    }

    @Test
    void mdsComponentNoteFallsBackToTheDeclaredDefaultDelay() {
        SystemInfoView view = newService(null).get();

        assertThat(noteOf(view, "MDS sync scheduler")).isEqualTo("매 6시간");
    }

    @Test
    void mdsComponentNoteHumanizesHoursAndMinutesTogether() {
        env.setProperty("passkey.mds.fixed-delay", "PT1H30M");

        SystemInfoView view = newService(null).get();

        assertThat(noteOf(view, "MDS sync scheduler")).isEqualTo("매 1시간 30분");
    }

    @Test
    void mdsComponentNoteNormalizesMinutesOnlyDurationToHoursAndMinutes() {
        // 브리프 원문 케이스: PT90M(분 단위 표기)도 PT1H30M 과 동일하게 정규화되어야 한다.
        env.setProperty("passkey.mds.fixed-delay", "PT90M");

        SystemInfoView view = newService(null).get();

        assertThat(noteOf(view, "MDS sync scheduler")).isEqualTo("매 1시간 30분");
    }

    @Test
    void mdsComponentNoteHumanizesWholeDays() {
        env.setProperty("passkey.mds.fixed-delay", "P1D");

        SystemInfoView view = newService(null).get();

        assertThat(noteOf(view, "MDS sync scheduler")).isEqualTo("매 1일");
    }

    @Test
    void mdsComponentNoteFallsBackToRawStringWhenUnparseable() {
        env.setProperty("passkey.mds.fixed-delay", "garbage");

        SystemInfoView view = newService(null).get();

        assertThat(noteOf(view, "MDS sync scheduler")).isEqualTo("매 garbage");
    }

    // ── B4-1 MDS 상태 판정 ──────────────────────────────────────────────────────

    @Test
    void mdsStatusIsDegradedWithNotSyncedYetNoteWhenNoRowExists() {
        // 실제 JdbcTemplate.queryForObject 는 행이 0건이면 null 이 아니라
        // EmptyResultDataAccessException 을 던진다 — mock 도 그 계약을 그대로 시뮬레이션한다.
        when(jdbc.queryForObject(any(String.class), eq(Timestamp.class)))
                .thenThrow(new EmptyResultDataAccessException(1));

        SystemInfoView view = newService(null).get();

        assertThat(statusOf(view, "MDS sync scheduler")).isEqualTo("DEGRADED");
        assertThat(noteOf(view, "MDS sync scheduler")).isEqualTo("아직 동기화 전");
    }

    @Test
    void mdsStatusIsDegradedWithFetchedAtNullColumnValueAlsoTreatedAsNotSyncedYet() {
        // 행은 존재하지만 fetched_at 컬럼 값 자체가 null 인 경우도 "행 없음"과 동일하게
        // "아직 동기화 전" 으로 취급한다(쿼리 자체는 성공, EmptyResultDataAccessException 아님).
        when(jdbc.queryForObject(any(String.class), eq(Timestamp.class))).thenReturn(null);

        SystemInfoView view = newService(null).get();

        assertThat(statusOf(view, "MDS sync scheduler")).isEqualTo("DEGRADED");
        assertThat(noteOf(view, "MDS sync scheduler")).isEqualTo("아직 동기화 전");
    }

    @Test
    void mdsStatusIsDegradedWithScheduleNoteWhenQueryFailsForOtherReasons() {
        // 쿼리 실패(DB 장애 등)는 "행 없음"(아직 동기화 전)과 달리 확정적 사실이 아니므로
        // note 는 평소 스케줄 표기를 유지한 채 status 만 DEGRADED 로 내린다.
        env.setProperty("passkey.mds.fixed-delay", "PT6H");
        when(jdbc.queryForObject(any(String.class), eq(Timestamp.class)))
                .thenThrow(new DataAccessResourceFailureException("mds_blob_cache unreachable"));

        SystemInfoView view = newService(null).get();

        assertThat(statusOf(view, "MDS sync scheduler")).isEqualTo("DEGRADED");
        assertThat(noteOf(view, "MDS sync scheduler")).isEqualTo("매 6시간");
    }

    @Test
    void mdsStatusIsOkWhenLastSyncIsWithinTwiceTheFixedDelay() {
        env.setProperty("passkey.mds.fixed-delay", "PT6H");
        Instant oneHourAgo = Instant.now().minusSeconds(3600);
        when(jdbc.queryForObject(any(String.class), eq(Timestamp.class)))
                .thenReturn(Timestamp.from(oneHourAgo));

        SystemInfoView view = newService(null).get();

        assertThat(statusOf(view, "MDS sync scheduler")).isEqualTo("OK");
        assertThat(noteOf(view, "MDS sync scheduler")).isEqualTo("매 6시간");
    }

    @Test
    void mdsStatusIsDegradedWhenLastSyncExceedsTwiceTheFixedDelay() {
        env.setProperty("passkey.mds.fixed-delay", "PT6H");
        Instant thirteenHoursAgo = Instant.now().minusSeconds(13 * 3600L);
        when(jdbc.queryForObject(any(String.class), eq(Timestamp.class)))
                .thenReturn(Timestamp.from(thirteenHoursAgo));

        SystemInfoView view = newService(null).get();

        assertThat(statusOf(view, "MDS sync scheduler")).isEqualTo("DEGRADED");
    }

    // ── B4-3 배너 실패 로그 1회화 ─────────────────────────────────────────────────

    @Test
    void bannerFailureLogsWarnOnceThenDebugOnRepeatedFailures() {
        Logger logger = (Logger) LoggerFactory.getLogger(SystemInfoService.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        // 2회차 DEBUG 로그는 루트 로거의 기본 레벨(INFO)에서는 필터링된다 —
        // 이 테스트에서만 명시적으로 DEBUG 를 켠다.
        logger.setLevel(Level.DEBUG);
        try {
            when(jdbc.queryForObject(any(String.class), eq(String.class)))
                    .thenThrow(new DataAccessResourceFailureException("v$version denied"));
            SystemInfoService service = newService(null);

            service.get();
            service.get();

            long warnCount = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .filter(e -> e.getFormattedMessage().contains("DB 배너 조회 실패"))
                    .count();
            long debugCount = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.DEBUG)
                    .filter(e -> e.getFormattedMessage().contains("DB 배너 조회 실패"))
                    .count();
            assertThat(warnCount).isEqualTo(1);
            assertThat(debugCount).isEqualTo(1);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
        }
    }

    @Test
    void bannerFailureLogsWarnAgainAfterASuccessInBetween() {
        Logger logger = (Logger) LoggerFactory.getLogger(SystemInfoService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            SystemInfoService service = newService(null);

            // 1) 첫 실패 — WARN.
            when(jdbc.queryForObject(any(String.class), eq(String.class)))
                    .thenThrow(new DataAccessResourceFailureException("v$version denied"));
            service.get();

            // 2) blank 배너는 예외 없이 반환되는 "성공" 이지만 캐시되지 않는다(기존
            //    blankDbBannerIsNotCachedAndKeepsTheUnknownFallback 과 동일 전제) — 따라서
            //    같은 인스턴스로 플래그만 리셋시키고 다음 조회에서 다시 실패를 유도할 수 있다.
            when(jdbc.queryForObject(any(String.class), eq(String.class))).thenReturn("   ");
            service.get();

            // 3) 리셋 후 재실패 — WARN 재발.
            when(jdbc.queryForObject(any(String.class), eq(String.class)))
                    .thenThrow(new DataAccessResourceFailureException("v$version denied again"));
            service.get();

            long warnCount = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .filter(e -> e.getFormattedMessage().contains("DB 배너 조회 실패"))
                    .count();
            // 1회차 실패(WARN) + 리셋 후 3회차 실패(WARN) = 2. 2회차는 성공(blank)이라 로그 없음.
            assertThat(warnCount).isEqualTo(2);
        } finally {
            logger.detachAppender(appender);
        }
    }

    private static String bannerOf(SystemInfoView view) {
        return view.components().stream()
                .filter(c -> "Oracle DB".equals(c.name()))
                .findFirst().orElseThrow().version();
    }

    private static String statusOf(SystemInfoView view, String name) {
        return view.components().stream()
                .filter(c -> name.equals(c.name()))
                .findFirst().orElseThrow().status();
    }

    private static String noteOf(SystemInfoView view, String name) {
        return view.components().stream()
                .filter(c -> name.equals(c.name()))
                .findFirst().orElseThrow().note();
    }
}
