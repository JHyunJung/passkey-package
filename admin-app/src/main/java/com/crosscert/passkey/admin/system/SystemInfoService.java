package com.crosscert.passkey.admin.system;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class SystemInfoService {

    /** 컴포넌트 상태 표시값 — 현재는 실측 없이 고정값이다. */
    private static final String STATUS_OK = "OK";
    private static final String NOTE_SPRING_BOOT = "Spring Boot";
    private static final String NOTE_DB_PRIMARY = "primary";
    private static final String DB_BANNER_UNKNOWN = "Oracle (unknown)";
    /** MdsSyncJob 의 @Scheduled(fixedDelayString) 과 동일한 키·기본값. */
    private static final String MDS_FIXED_DELAY_KEY = "passkey.mds.fixed-delay";
    private static final String MDS_FIXED_DELAY_DEFAULT = "PT6H";
    /** 프로젝트 전역 KST 규약(OffsetDateTime +09:00). */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Nullable private final BuildProperties build;
    private final JdbcTemplate jdbc;
    private final Environment env;

    /**
     * v$version 배너는 한 번 성공하면 프로세스 수명 동안 바뀌지 않으므로 캐시한다.
     * 실패는 캐시하지 않아 권한 부여·연결 복구 후 다음 요청에서 다시 시도된다.
     */
    private volatile String cachedDbBanner;

    public SystemInfoService(@Nullable BuildProperties build, JdbcTemplate jdbc, Environment env) {
        this.build = build;
        this.jdbc = jdbc;
        this.env = env;
    }

    public SystemInfoView get() {
        String version = build != null ? build.getVersion() : "dev";
        String deployedAt = build != null && build.getTime() != null
                ? OffsetDateTime.ofInstant(build.getTime(), KST).toString() : null;

        long uptimeMillis = ManagementFactory.getRuntimeMXBean().getUptime();
        long uptimeDays = Duration.ofMillis(uptimeMillis).toDays();

        String[] profiles = env.getActiveProfiles();
        String environment = profiles.length == 0 ? "default" : String.join(",", profiles);
        SystemInfoView.Host host = new SystemInfoView.Host(environment);

        List<SystemInfoView.Component> components = new ArrayList<>();
        components.add(new SystemInfoView.Component(
                "admin-app", version, STATUS_OK, NOTE_SPRING_BOOT));
        components.add(new SystemInfoView.Component(
                "passkey-app", version, STATUS_OK, NOTE_SPRING_BOOT));
        components.add(new SystemInfoView.Component(
                "Oracle DB", dbBanner(), STATUS_OK, NOTE_DB_PRIMARY));
        components.add(new SystemInfoView.Component(
                "MDS sync scheduler", version, STATUS_OK, mdsScheduleNote()));

        return new SystemInfoView(version, deployedAt, uptimeDays, host, components);
    }

    /**
     * DB 배너 조회. 조회에 실패하면 기본값을 돌려준다 — 계정에서 v$version SELECT 권한이
     * 회수됐거나 DB 연결이 일시적으로 끊긴 경우이며, 어느 쪽도 화면을 막을 사유는 아니다.
     */
    private String dbBanner() {
        String cached = cachedDbBanner;
        if (cached != null) return cached;
        try {
            String b = jdbc.queryForObject("SELECT banner FROM v$version WHERE ROWNUM=1", String.class);
            if (b != null && !b.isBlank()) {
                cachedDbBanner = b;
                return b;
            }
        } catch (DataAccessException e) {
            log.warn("DB 배너 조회 실패: {}", e.getMessage());
        }
        return DB_BANNER_UNKNOWN;
    }

    /** MDS 동기화 주기 표시값 — 실제 스케줄(fixedDelayString)과 같은 프로퍼티에서 읽는다. */
    private String mdsScheduleNote() {
        return "매 " + env.getProperty(MDS_FIXED_DELAY_KEY, MDS_FIXED_DELAY_DEFAULT);
    }
}
