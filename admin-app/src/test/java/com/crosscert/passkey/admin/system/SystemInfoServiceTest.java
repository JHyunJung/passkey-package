package com.crosscert.passkey.admin.system;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

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

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        env = new MockEnvironment();
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
        SystemInfoView view = new SystemInfoService(null, jdbc, env).get();

        assertThat(view.host().environment()).isEqualTo("default");
    }

    @Test
    void environmentJoinsMultipleActiveProfilesWithComma() {
        env.setActiveProfiles("prod", "oracle");

        SystemInfoView view = new SystemInfoService(null, jdbc, env).get();

        assertThat(view.host().environment()).isEqualTo("prod,oracle");
    }

    // ── #73 deployedAt / version ─────────────────────────────────────────────

    @Test
    void deployedAtIsNullAndVersionIsDevWithoutBuildProperties() {
        SystemInfoView view = new SystemInfoService(null, jdbc, env).get();

        assertThat(view.deployedAt()).isNull();
        assertThat(view.serverVersion()).isEqualTo("dev");
    }

    @Test
    void deployedAtIsRenderedInKstWhenBuildPropertiesArePresent() {
        // 2026-05-31T00:00:00Z == 2026-05-31T09:00+09:00 (KST)
        Instant built = Instant.parse("2026-05-31T00:00:00Z");
        BuildProperties build = buildProps("1.2.3", built);

        SystemInfoView view = new SystemInfoService(build, jdbc, env).get();

        assertThat(view.serverVersion()).isEqualTo("1.2.3");
        assertThat(view.deployedAt())
                .isEqualTo(OffsetDateTime.ofInstant(built, ZoneOffset.ofHours(9)).toString())
                .startsWith("2026-05-31T09:00")
                .endsWith("+09:00");
        // UI 는 slice(0,10) 으로 날짜만 취한다 — KST 문자열에서도 앞 10자가 날짜다.
        assertThat(view.deployedAt().substring(0, 10)).isEqualTo("2026-05-31");
    }

    // ── #71/#72 DB 배너 ──────────────────────────────────────────────────────

    @Test
    void dbBannerFallsBackToUnknownAndIsRetriedOnEveryCallWhenQueryFails() {
        when(jdbc.queryForObject(any(String.class), eq(String.class)))
                .thenThrow(new DataAccessResourceFailureException("v$version denied"));
        SystemInfoService service = new SystemInfoService(null, jdbc, env);

        assertThat(bannerOf(service.get())).isEqualTo("Oracle (unknown)");
        assertThat(bannerOf(service.get())).isEqualTo("Oracle (unknown)");

        // 실패는 캐시하지 않는다 — 매 요청 재시도.
        verify(jdbc, times(2)).queryForObject(any(String.class), eq(String.class));
    }

    @Test
    void dbBannerIsCachedAfterTheFirstSuccessfulQuery() {
        when(jdbc.queryForObject(any(String.class), eq(String.class)))
                .thenReturn("Oracle Database 19c Enterprise Edition");
        SystemInfoService service = new SystemInfoService(null, jdbc, env);

        assertThat(bannerOf(service.get())).isEqualTo("Oracle Database 19c Enterprise Edition");
        assertThat(bannerOf(service.get())).isEqualTo("Oracle Database 19c Enterprise Edition");

        // 성공값은 캐시 — DB 는 한 번만 조회한다.
        verify(jdbc, times(1)).queryForObject(any(String.class), eq(String.class));
    }

    @Test
    void blankDbBannerIsNotCachedAndKeepsTheUnknownFallback() {
        when(jdbc.queryForObject(any(String.class), eq(String.class))).thenReturn("   ");
        SystemInfoService service = new SystemInfoService(null, jdbc, env);

        assertThat(bannerOf(service.get())).isEqualTo("Oracle (unknown)");
        assertThat(bannerOf(service.get())).isEqualTo("Oracle (unknown)");

        verify(jdbc, times(2)).queryForObject(any(String.class), eq(String.class));
    }

    // ── #69 MDS 스케줄 note ──────────────────────────────────────────────────

    @Test
    void mdsComponentNoteReflectsTheConfiguredFixedDelay() {
        env.setProperty("passkey.mds.fixed-delay", "PT12H");

        SystemInfoView view = new SystemInfoService(null, jdbc, env).get();

        assertThat(noteOf(view, "MDS sync scheduler")).isEqualTo("매 PT12H");
    }

    @Test
    void mdsComponentNoteFallsBackToTheDeclaredDefaultDelay() {
        SystemInfoView view = new SystemInfoService(null, jdbc, env).get();

        assertThat(noteOf(view, "MDS sync scheduler")).isEqualTo("매 PT6H");
    }

    private static String bannerOf(SystemInfoView view) {
        return view.components().stream()
                .filter(c -> "Oracle DB".equals(c.name()))
                .findFirst().orElseThrow().version();
    }

    private static String noteOf(SystemInfoView view, String name) {
        return view.components().stream()
                .filter(c -> name.equals(c.name()))
                .findFirst().orElseThrow().note();
    }
}
