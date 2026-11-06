package com.crosscert.passkey.core.time;

import com.crosscert.passkey.core.config.KstTime;
import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import oracle.jdbc.pool.OracleDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KST 영속화 계약 (Boot 4 / Hibernate 7 업그레이드 안전망).
 *
 * <p>엔티티의 {@code OffsetDateTime} 은 KST({@code +09:00}) 벽시계 값으로 DB 에 저장되고,
 * 재조회 시에도 {@code +09:00} 오프셋으로 돌아와야 한다(감사 해시 체인·화면 표시·일자 버킷이
 * 이 형태에 의존). Hibernate 의 시간대 저장 전략 기본값이 바뀌면(예: UTC 정규화) 이 테스트가 깨진다.
 *
 * <p>대상: {@link AdminUser#getCreatedAt()} — 운영 경로({@code BaseEntity @PrePersist},
 * {@code OffsetDateTime.now(KstTime.ZONE)})로 채워지는 {@code TIMESTAMP(6) WITH TIME ZONE} 컬럼.
 * 컬럼 값은 네이티브 SQL({@code TO_CHAR ... TZH:TZM})로 Hibernate 를 거치지 않고 읽는다.
 *
 * <p>구성은 {@code BaseEntityCallbackIT} 와 같다(Oracle Testcontainers + bootstrap SQL + Flyway).
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class KstPersistenceIT {

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
                            MountableFile.forClasspathResource("bootstrap-schema.sql"),
                            "/tmp/bootstrap-schema.sql");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry reg) throws Exception {
        Container.ExecResult exec = ORACLE.execInContainer(
                "bash", "-c",
                "sqlplus -S sys/" + SYS_PASSWORD + "@localhost:1521/XEPDB1 as sysdba "
                        + "@/tmp/bootstrap-schema.sql");
        if (exec.getExitCode() != 0) {
            throw new IllegalStateException(
                    "bootstrap-schema.sql failed (exit=" + exec.getExitCode() + ")\n"
                            + "STDOUT:\n" + exec.getStdout() + "\n"
                            + "STDERR:\n" + exec.getStderr());
        }
        reg.add("spring.datasource.url", ORACLE::getJdbcUrl);
        reg.add("spring.datasource.username", () -> "PSK_APP_ADMIN_USER");
        reg.add("spring.datasource.password", () -> "admin_pw");
        reg.add("spring.flyway.url", ORACLE::getJdbcUrl);
        reg.add("spring.flyway.user", () -> "PSK_APP_OWNER");
        reg.add("spring.flyway.password", () -> SYS_PASSWORD);
    }

    @Autowired AdminUserRepository adminUsers;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    @PersistenceContext
    EntityManager em;

    @AfterEach
    void cleanup() throws SQLException {
        // PSK_APP_ADMIN 은 admin_user DELETE 권한이 없어 스키마 소유자 연결로 정리한다.
        OracleDataSource ds = new OracleDataSource();
        ds.setURL(ORACLE.getJdbcUrl());
        ds.setUser("PSK_APP_OWNER");
        ds.setPassword(SYS_PASSWORD);
        new JdbcTemplate(ds).update("DELETE FROM PSK_APP_OWNER.admin_user");
    }

    @Test
    void offsetDateTime_isStoredAsKstWallClock_andReloadsWithPlus9Offset() {
        AdminUser saved = adminUsers.saveAndFlush(new AdminUser(
                "kst-" + UUID.randomUUID() + "@example.com", "$2a$10$" + "x".repeat(53), "PLATFORM_OPERATOR"));
        OffsetDateTime inMemory = saved.getCreatedAt();
        assertThat(inMemory.getOffset()).isEqualTo(KstTime.OFFSET);

        // 1) DB 에 저장된 값 자체: KST 벽시계 + 오프셋 +09:00 (Hibernate 를 거치지 않는 네이티브 조회).
        String stored = jdbc.queryForObject(
                "SELECT TO_CHAR(created_at, 'YYYY-MM-DD\"T\"HH24:MI:SS.FF6 TZH:TZM') "
                        + "FROM PSK_APP_OWNER.admin_user WHERE id = HEXTORAW(?)",
                String.class, saved.getId().toString().replace("-", ""));
        // 문자열에 오프셋이 그대로 남아 있어야 한다(UTC 정규화 저장이면 +00:00 이 된다).
        assertThat(stored).endsWith(" +09:00");
        OffsetDateTime storedValue = OffsetDateTime.parse(stored,
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS xxx"));
        // 벽시계 값 = 메모리상의 KST 값(TIMESTAMP(6) 은 마이크로초까지라 1µs 미만 차이는 반올림 허용).
        assertThat(Duration.between(inMemory.toLocalDateTime(), storedValue.toLocalDateTime()).abs())
                .isLessThan(Duration.ofNanos(1_000));

        // 2) 재조회: 영속성 컨텍스트를 비워 DB 왕복 후에도 +09:00 오프셋·같은 순간.
        OffsetDateTime reloaded = tx.execute(status -> {
            em.clear();
            return adminUsers.findById(saved.getId()).orElseThrow().getCreatedAt();
        });
        assertThat(reloaded.getOffset()).isEqualTo(KstTime.OFFSET);
        assertThat(reloaded.toLocalDateTime()).isEqualTo(storedValue.toLocalDateTime());
    }
}
