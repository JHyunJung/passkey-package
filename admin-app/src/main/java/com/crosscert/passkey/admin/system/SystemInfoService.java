package com.crosscert.passkey.admin.system;

import com.crosscert.passkey.core.config.DbSchemaProperties;
import com.crosscert.passkey.core.entity.MdsBlobCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class SystemInfoService {

    /** 컴포넌트 상태 표시값. */
    private static final String STATUS_OK = "OK";
    private static final String STATUS_DEGRADED = "DEGRADED";
    private static final String STATUS_DOWN = "DOWN";
    private static final String NOTE_SPRING_BOOT = "Spring Boot";
    private static final String NOTE_DB_PRIMARY = "primary";
    private static final String NOTE_MDS_NOT_SYNCED_YET = "아직 동기화 전";
    private static final String DB_BANNER_UNKNOWN = "Oracle (unknown)";
    /** MdsSyncJob 의 @Scheduled(fixedDelayString) 과 동일한 키·기본값. */
    private static final String MDS_FIXED_DELAY_KEY = "passkey.mds.fixed-delay";
    private static final String MDS_FIXED_DELAY_DEFAULT = "PT6H";
    /** 프로젝트 전역 KST 규약(OffsetDateTime +09:00). */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String SINGLETON_HEX =
            MdsBlobCache.SINGLETON_ID.toString().replace("-", "");

    @Nullable private final BuildProperties build;
    private final JdbcTemplate jdbc;
    private final Environment env;
    private final String schema;

    /**
     * v$version 배너는 한 번 성공하면 프로세스 수명 동안 바뀌지 않으므로 캐시한다.
     * 실패는 캐시하지 않아 권한 부여·연결 복구 후 다음 요청에서 다시 시도된다.
     */
    private volatile String cachedDbBanner;

    /** DB 배너 조회 실패 로그를 요청마다 WARN 으로 반복하지 않기 위한 1회화 플래그. */
    private final AtomicBoolean bannerFailureLogged = new AtomicBoolean();

    public SystemInfoService(@Nullable BuildProperties build, JdbcTemplate jdbc, Environment env,
                              DbSchemaProperties dbSchema) {
        this.build = build;
        this.jdbc = jdbc;
        this.env = env;
        this.schema = dbSchema.schema();
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

        DbProbe db = dbBanner();
        MdsProbe mds = mdsStatus();

        List<SystemInfoView.Component> components = new ArrayList<>();
        components.add(new SystemInfoView.Component(
                "admin-app", version, STATUS_OK, NOTE_SPRING_BOOT));
        components.add(new SystemInfoView.Component(
                "Oracle DB", db.banner(), db.status(), NOTE_DB_PRIMARY));
        components.add(new SystemInfoView.Component(
                "MDS sync scheduler", version, mds.status(), mds.note()));

        return new SystemInfoView(version, deployedAt, uptimeDays, host, components);
    }

    private record DbProbe(String banner, String status) {}

    private record MdsProbe(String status, String note) {}

    /**
     * DB 배너·상태 조회. 배너 조회에 실패해도 기본값을 돌려준다 — 계정에서 v$version SELECT
     * 권한이 회수됐거나 DB 연결이 일시적으로 끊긴 경우이며, 어느 쪽도 화면을 막을 사유는 아니다.
     * 상태는 실패 종류로 구분한다: 연결 자체가 안 되면 DOWN, 그 외(권한 등)는 DEGRADED.
     */
    private DbProbe dbBanner() {
        String cached = cachedDbBanner;
        if (cached != null) return new DbProbe(cached, STATUS_OK);
        try {
            String b = jdbc.queryForObject("SELECT banner FROM v$version WHERE ROWNUM=1", String.class);
            // 쿼리 자체는 성공(예외 없음)했으므로 다음 실패는 다시 WARN 으로 보고한다.
            bannerFailureLogged.set(false);
            if (b != null && !b.isBlank()) {
                cachedDbBanner = b;
                return new DbProbe(b, STATUS_OK);
            }
            return new DbProbe(DB_BANNER_UNKNOWN, STATUS_OK);
        } catch (CannotGetJdbcConnectionException e) {
            logBannerFailure(e);
            return new DbProbe(DB_BANNER_UNKNOWN, STATUS_DOWN);
        } catch (DataAccessException e) {
            logBannerFailure(e);
            return new DbProbe(DB_BANNER_UNKNOWN, STATUS_DEGRADED);
        }
    }

    /** 첫 실패는 WARN, 이후 연속 실패는 DEBUG로 로그 노이즈를 줄인다. 성공 시 리셋(dbBanner 참고). */
    private void logBannerFailure(DataAccessException e) {
        if (bannerFailureLogged.compareAndSet(false, true)) {
            log.warn("DB 배너 조회 실패: {}", e.getMessage());
        } else {
            log.debug("DB 배너 조회 실패: {}", e.getMessage());
        }
    }

    /**
     * MDS 동기화 상태. mds_blob_cache 싱글턴 행의 fetched_at 을 MdsAdminController.status() 와
     * 같은 쿼리로 읽어 판정한다.
     *
     * <p>"행 없음"(아직 동기화 전)과 "쿼리 실패"(DB 장애 등)는 서로 다른 상황이므로 note 를
     * 구분한다: 행이 없거나 fetched_at 이 null 이면 note "아직 동기화 전"(DEGRADED) — 실제
     * {@code JdbcTemplate.queryForObject} 는 결과 행이 0건이면 값을 null 로 주는 대신
     * {@link EmptyResultDataAccessException} 을 던지므로 이를 명시적으로 잡는다. 그 외
     * {@link DataAccessException}(연결 장애·권한 등)은 "아직 동기화 전"이 아니라 평소
     * 스케줄 표기를 유지한 채 DEGRADED 로만 표시한다 — 언제 동기화됐는지 몰라서 못 보여줄
     * 뿐이지 "동기화 이력이 없다"는 확정적 사실이 아니기 때문이다. 마지막 동기화가 주기의
     * 2배를 넘게 지났으면 지연(DEGRADED), 그 외 OK.
     */
    private MdsProbe mdsStatus() {
        String note = mdsScheduleNote();
        Duration fixedDelay = parseFixedDelay();
        Timestamp fetchedAt;
        try {
            fetchedAt = jdbc.queryForObject(
                    "SELECT fetched_at AS \"fetchedAt\" FROM " + schema
                            + ".mds_blob_cache WHERE id=HEXTORAW('" + SINGLETON_HEX + "')",
                    Timestamp.class);
        } catch (EmptyResultDataAccessException e) {
            return new MdsProbe(STATUS_DEGRADED, NOTE_MDS_NOT_SYNCED_YET);
        } catch (DataAccessException e) {
            return new MdsProbe(STATUS_DEGRADED, note);
        }
        if (fetchedAt == null) {
            return new MdsProbe(STATUS_DEGRADED, NOTE_MDS_NOT_SYNCED_YET);
        }
        OffsetDateTime last = fetchedAt.toInstant().atZone(KST).toOffsetDateTime();
        Duration since = Duration.between(last, OffsetDateTime.now(KST));
        if (since.compareTo(fixedDelay.multipliedBy(2)) > 0) {
            return new MdsProbe(STATUS_DEGRADED, note);
        }
        return new MdsProbe(STATUS_OK, note);
    }

    private Duration parseFixedDelay() {
        String raw = env.getProperty(MDS_FIXED_DELAY_KEY, MDS_FIXED_DELAY_DEFAULT);
        try {
            return DurationStyle.detectAndParse(raw);
        } catch (IllegalArgumentException e) {
            return DurationStyle.detectAndParse(MDS_FIXED_DELAY_DEFAULT);
        }
    }

    /** MDS 동기화 주기 표시값 — 실제 스케줄(fixedDelayString)과 같은 프로퍼티에서 읽는다. */
    private String mdsScheduleNote() {
        String raw = env.getProperty(MDS_FIXED_DELAY_KEY, MDS_FIXED_DELAY_DEFAULT);
        try {
            return "매 " + humanize(DurationStyle.detectAndParse(raw));
        } catch (IllegalArgumentException e) {
            return "매 " + raw;
        }
    }

    /** Duration 을 "1일", "6시간", "1시간 30분" 같은 사람이 읽는 한국어 표기로 바꾼다. */
    private static String humanize(Duration duration) {
        long totalMinutes = duration.toMinutes();
        long days = totalMinutes / (24 * 60);
        long hours = (totalMinutes % (24 * 60)) / 60;
        long minutes = totalMinutes % 60;

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append('일');
        if (hours > 0) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(hours).append("시간");
        }
        if (minutes > 0) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(minutes).append('분');
        }
        if (sb.length() == 0) sb.append(0).append('분');
        return sb.toString();
    }
}
