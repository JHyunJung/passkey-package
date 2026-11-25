package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.ceremony.CeremonyAction;
import com.crosscert.passkey.core.entity.UsageDaily;
import com.crosscert.passkey.core.entity.UsageDailyId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사용량 요약 {@link UsageDailyRepository} 와 장부 쿼리의 수용 테스트.
 * ceremony_event 행은 created_at 을 지정해야 해서 JdbcTemplate 으로 직접 넣는다.
 * PSK_APP_ADMIN_USER 에는 usage_daily DELETE 권한이 없으므로, 테스트마다 새 tenant UUID 로 격리한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class UsageDailyRepositoryIT {

    @SpringBootApplication
    @EntityScan("com.crosscert.passkey.core.entity")
    @EnableJpaRepositories("com.crosscert.passkey.core.repository")
    static class TestApp {
    }

    private static final String ORACLE_IMAGE = "gvenzl/oracle-xe:21-slim-faststart";
    private static final String SYS_PASSWORD = "app_owner_pw";

    @org.testcontainers.junit.jupiter.Container
    static final OracleContainer ORACLE =
            new OracleContainer(ORACLE_IMAGE)
                    .withUsername("PSK_APP_OWNER")
                    .withPassword(SYS_PASSWORD)
                    .withCopyFileToContainer(
                            MountableFile.forClasspathResource("test-db-init.sql"),
                            "/tmp/test-db-init.sql");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry reg) throws Exception {
        Container.ExecResult exec = ORACLE.execInContainer(
                "bash", "-c",
                "sqlplus -S sys/" + SYS_PASSWORD + "@localhost:1521/XEPDB1 as sysdba "
                        + "@/tmp/test-db-init.sql");
        if (exec.getExitCode() != 0) {
            throw new IllegalStateException(
                    "test-db-init.sql failed (exit=" + exec.getExitCode() + ")\n"
                            + "STDOUT:\n" + exec.getStdout() + "\n"
                            + "STDERR:\n" + exec.getStderr());
        }
        reg.add("spring.datasource.url", ORACLE::getJdbcUrl);
        reg.add("spring.datasource.username", () -> "PSK_APP_ADMIN_USER");
        reg.add("spring.datasource.password", () -> "admin_pw");
    }

    private static final String OCT31 = "2026-10-31";
    private static final LocalDate D_OCT31 = LocalDate.of(2026, 10, 31);
    private static final LocalDate D_NOV01 = LocalDate.of(2026, 11, 1);
    private static final OffsetDateTime FROM = OffsetDateTime.parse("2026-10-31T00:00:00+09:00");
    private static final OffsetDateTime TO = OffsetDateTime.parse("2026-11-02T00:00:00+09:00");

    @Autowired UsageDailyRepository repo;
    @Autowired CeremonyEventRepository events;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clearLedger() {
        jdbc.update("DELETE FROM PSK_APP_OWNER.ceremony_event");
    }

    private void event(UUID tenant, String action, String isoKst) {
        OffsetDateTime at = OffsetDateTime.parse(isoKst);
        jdbc.update("INSERT INTO PSK_APP_OWNER.ceremony_event (id, tenant_id, action, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                uuidBytes(UUID.randomUUID()), uuidBytes(tenant), action, at, at);
    }

    private static byte[] uuidBytes(UUID u) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(16);
        b.putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits());
        return b.array();
    }

    /** tenant A 의 10/31(KST 23:59:59) 3종 + 11/01 00:00:00 인증 성공 1건을 넣는다. */
    private UUID seedBoundaryCase() {
        UUID a = UUID.randomUUID();
        event(a, CeremonyAction.REGISTRATION_SUCCESS, "2026-10-31T23:59:59+09:00");
        event(a, CeremonyAction.AUTHENTICATION_SUCCESS, "2026-10-31T23:59:59+09:00");
        event(a, CeremonyAction.AUTHENTICATION_SUCCESS, "2026-10-31T23:59:59+09:00");
        event(a, CeremonyAction.AUTHENTICATION_BEGIN, "2026-10-31T23:59:59+09:00");
        event(a, CeremonyAction.AUTHENTICATION_BEGIN, "2026-10-31T23:59:59+09:00");
        event(a, CeremonyAction.AUTHENTICATION_BEGIN, "2026-10-31T23:59:59+09:00");
        event(a, CeremonyAction.AUTHENTICATION_SUCCESS, "2026-11-01T00:00:00+09:00");
        return a;
    }

    private UsageDaily row(UUID tenant, LocalDate day) {
        return repo.findById(new UsageDailyId(tenant, day)).orElseThrow();
    }

    @Test
    void merge_countsSuccessAndAttemptPerTenantPerKstDay() {
        UUID a = seedBoundaryCase();

        repo.mergeFromLedger(FROM, TO, D_NOV01, OffsetDateTime.now());

        UsageDaily oct = row(a, D_OCT31);
        assertThat(oct.getRegSuccess()).isEqualTo(1);
        assertThat(oct.getAuthSuccess()).isEqualTo(2);
        assertThat(oct.getAuthAttempt()).isEqualTo(3);
        assertThat(oct.getRegAttempt()).isZero();
        assertThat(oct.getBasis()).isEqualTo("LEGACY");

        UsageDaily nov = row(a, D_NOV01);
        assertThat(nov.getAuthSuccess()).isEqualTo(1);
        assertThat(nov.getBasis()).isEqualTo("LEDGER");
    }

    @Test
    void merge_isIdempotent() {
        UUID a = seedBoundaryCase();
        OffsetDateTime now = OffsetDateTime.now();

        repo.mergeFromLedger(FROM, TO, D_NOV01, now);
        repo.mergeFromLedger(FROM, TO, D_NOV01, now);

        assertThat(repo.findAll().stream().filter(u -> u.getTenantId().equals(a))).hasSize(2);
        UsageDaily oct = row(a, D_OCT31);
        assertThat(oct.getRegSuccess()).isEqualTo(1);
        assertThat(oct.getAuthSuccess()).isEqualTo(2);
        assertThat(oct.getAuthAttempt()).isEqualTo(3);
    }

    @Test
    void merge_overwritesWhenLedgerGrows() {
        UUID a = seedBoundaryCase();
        repo.mergeFromLedger(FROM, TO, D_NOV01, OffsetDateTime.now());
        assertThat(row(a, D_OCT31).getAuthSuccess()).isEqualTo(2);

        event(a, CeremonyAction.AUTHENTICATION_SUCCESS, "2026-10-31T12:00:00+09:00");
        repo.mergeFromLedger(FROM, TO, D_NOV01, OffsetDateTime.now());

        assertThat(row(a, D_OCT31).getAuthSuccess()).isEqualTo(3);
    }

    @Test
    void merge_sentinelLedgerStart_marksAllLegacy() {
        UUID a = seedBoundaryCase();

        repo.mergeFromLedger(FROM, TO, LocalDate.of(9999, 12, 31), OffsetDateTime.now());

        assertThat(row(a, D_OCT31).getBasis()).isEqualTo("LEGACY");
        assertThat(row(a, D_NOV01).getBasis()).isEqualTo("LEGACY");
    }

    @Test
    void sumByTenant_sumsWithinRangeOnly() {
        UUID a = UUID.randomUUID();
        event(a, CeremonyAction.AUTHENTICATION_SUCCESS, "2026-10-15T10:00:00+09:00");
        event(a, CeremonyAction.AUTHENTICATION_SUCCESS, "2026-10-16T10:00:00+09:00");
        event(a, CeremonyAction.REGISTRATION_SUCCESS, "2026-10-16T10:00:00+09:00");
        event(a, CeremonyAction.AUTHENTICATION_SUCCESS, "2026-11-01T10:00:00+09:00");
        repo.mergeFromLedger(OffsetDateTime.parse("2026-10-01T00:00:00+09:00"),
                OffsetDateTime.parse("2026-12-01T00:00:00+09:00"), D_NOV01, OffsetDateTime.now());

        List<Object[]> rows = repo.sumByTenant(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1));

        Object[] mine = rows.stream().filter(r -> a.equals(r[0])).findFirst().orElseThrow();
        assertThat(mine).hasSize(5);
        assertThat(((Number) mine[1]).longValue()).isEqualTo(1);   // reg_success
        assertThat(((Number) mine[2]).longValue()).isEqualTo(2);   // auth_success (11월분 제외)
        assertThat(((Number) mine[3]).longValue()).isZero();       // reg_attempt
        assertThat(((Number) mine[4]).longValue()).isZero();       // auth_attempt
    }

    @Test
    void usageDateBounds_andMaxAggregatedAt() {
        // 다른 테스트 행과 겹치지 않도록 이 테스트만 쓰는 먼 미래 날짜를 쓴다(usage_daily 는 삭제 권한이 없다).
        UUID a = UUID.randomUUID();
        event(a, CeremonyAction.AUTHENTICATION_SUCCESS, "2031-03-10T10:00:00+09:00");
        event(a, CeremonyAction.AUTHENTICATION_SUCCESS, "2031-03-11T10:00:00+09:00");
        OffsetDateTime stamp = OffsetDateTime.parse("2031-03-12T01:00:00+09:00");
        repo.mergeFromLedger(OffsetDateTime.parse("2031-03-10T00:00:00+09:00"),
                OffsetDateTime.parse("2031-03-12T00:00:00+09:00"), LocalDate.of(2031, 3, 1), stamp);

        assertThat(repo.findMinUsageDate()).hasValueSatisfying(d -> assertThat(d).isBeforeOrEqualTo(D_OCT31.plusYears(5)));
        assertThat(repo.findMaxUsageDate()).hasValue(LocalDate.of(2031, 3, 11));
        assertThat(repo.findMaxAggregatedAt(LocalDate.of(2031, 3, 10), LocalDate.of(2031, 3, 12)))
                .hasValueSatisfying(t -> assertThat(t.toInstant()).isEqualTo(stamp.toInstant()));
        assertThat(repo.findMaxAggregatedAt(LocalDate.of(2000, 1, 1), LocalDate.of(2000, 1, 2))).isEmpty();
    }

    @Test
    void findMinCreatedAt_returnsOldestLedgerInstant() {
        assertThat(events.findMinCreatedAt()).isEmpty();
        UUID a = UUID.randomUUID();
        event(a, CeremonyAction.AUTHENTICATION_SUCCESS, "2026-10-31T23:59:59+09:00");
        event(a, CeremonyAction.AUTHENTICATION_SUCCESS, "2026-11-01T00:00:00+09:00");

        assertThat(events.findMinCreatedAt()).hasValueSatisfying(t ->
                assertThat(t.toInstant()).isEqualTo(OffsetDateTime.parse("2026-10-31T23:59:59+09:00").toInstant()));
    }
}
