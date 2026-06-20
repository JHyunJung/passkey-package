package com.crosscert.passkey.core.entity;

import com.crosscert.passkey.core.repository.AdminUserRepository;
import com.crosscert.passkey.core.repository.AuditLogRepository;
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
import org.testcontainers.containers.Container;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 8 T3 acceptance gate for the new {@link BaseEntity} superclass.
 *
 * <p>Boots a real Testcontainers Oracle XE 21, runs {@code scripts/bootstrap-vpd.sql}
 * via {@code sqlplus} as SYS, lets Flyway apply V1–V22 (V22 adds the
 * {@code updated_at} columns required by Hibernate's {@code validate} mode),
 * then exercises the JPA lifecycle callbacks through {@link AdminUser} —
 * the first entity migrated to extend {@link BaseEntity}.
 *
 * <p>Scenarios:
 * <ol>
 *   <li>{@code @PrePersist} populates {@code createdAt = updatedAt} at insert.</li>
 *   <li>{@code @PreUpdate} advances only {@code updatedAt}; {@code createdAt} is immutable.</li>
 *   <li>TIME-ordered {@code @UuidGenerator} assigns non-null, distinct UUIDs per row.</li>
 * </ol>
 *
 * <p>We deliberately follow the {@code @SpringBootTest + @Testcontainers}
 * shape of {@code VpdIsolationIT} rather than {@code @DataJpaTest} so the
 * bootstrap-as-SYS step and full {@code application-test.yml} (Flyway +
 * Hibernate validate + Redis exclusion) apply identically across :core ITs.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class BaseEntityCallbackIT {

    /**
     * Minimal Spring Boot test app, mirroring {@code VpdIsolationIT.TestApp}.
     * :core is a library subproject with no main class; the IT brings up its
     * own context with @EntityScan + @EnableJpaRepositories pointed at the
     * production packages so repositories and entities resolve normally.
     */
    @SpringBootApplication
    @EntityScan("com.crosscert.passkey.core.entity")
    @EnableJpaRepositories("com.crosscert.passkey.core.repository")
    static class TestApp {
    }

    // Pinned image tag matches docker-compose.yml + VpdIsolationIT so all
    // ITs exercise the same Oracle binary.
    private static final String ORACLE_IMAGE = "gvenzl/oracle-xe:21-slim-faststart";

    // OracleContainer.configure() pushes withPassword(...) to BOTH
    // ORACLE_PASSWORD (SYS/SYSTEM) and APP_USER_PASSWORD (gvenzl APP_OWNER),
    // so the same secret authenticates the bootstrap SYS connect.
    private static final String SYS_PASSWORD = "app_owner_pw";

    @org.testcontainers.junit.jupiter.Container
    static final OracleContainer ORACLE =
            new OracleContainer(ORACLE_IMAGE)
                    .withUsername("APP_OWNER")
                    .withPassword(SYS_PASSWORD)
                    .withCopyFileToContainer(
                            MountableFile.forClasspathResource("bootstrap-vpd.sql"),
                            "/tmp/bootstrap-vpd.sql");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry reg) throws Exception {
        // Bootstrap MUST complete before Spring opens a connection — Spring
        // is told to connect as APP_ADMIN_USER which the bootstrap creates.
        Container.ExecResult exec = ORACLE.execInContainer(
                "bash", "-c",
                "sqlplus -S sys/" + SYS_PASSWORD + "@localhost:1521/XEPDB1 as sysdba "
                        + "@/tmp/bootstrap-vpd.sql");
        if (exec.getExitCode() != 0) {
            throw new IllegalStateException(
                    "bootstrap-vpd.sql failed (exit=" + exec.getExitCode() + ")\n"
                            + "STDOUT:\n" + exec.getStdout() + "\n"
                            + "STDERR:\n" + exec.getStderr());
        }

        // Connect as APP_ADMIN_USER (EXEMPT ACCESS POLICY) — runtime user.
        // V22 (and subsequent migrations) will have run before Hibernate
        // validates ADMIN_USER's new updated_at column.
        reg.add("spring.datasource.url", ORACLE::getJdbcUrl);
        reg.add("spring.datasource.username", () -> "APP_ADMIN_USER");
        reg.add("spring.datasource.password", () -> "admin_pw");
        // Finding #3 (Approach A): Flyway runs as APP_OWNER (schema owner)
        // so that migrations can GRANT on APP_OWNER objects without error.
        reg.add("spring.flyway.url", ORACLE::getJdbcUrl);
        reg.add("spring.flyway.user", () -> "APP_OWNER");
        reg.add("spring.flyway.password", () -> SYS_PASSWORD);
    }

    @Autowired
    AdminUserRepository adminUsers;

    @Autowired
    AuditLogRepository auditLogs;

    @PersistenceContext
    EntityManager em;

    /**
     * Returns a JdbcTemplate connected as APP_OWNER (schema owner) for
     * test cleanup that requires privileges beyond APP_ADMIN_USER's runtime
     * grants (e.g. DELETE on admin_user). Finding #3 removed GRANT ALL
     * PRIVILEGES from APP_ADMIN_USER; cleanup that needs broader rights must
     * use the owner connection, as done in AdminFlowIT.resetState().
     */
    private JdbcTemplate ownerJdbc() throws SQLException {
        OracleDataSource ds = new OracleDataSource();
        ds.setURL(ORACLE.getJdbcUrl());
        ds.setUser("APP_OWNER");
        ds.setPassword(SYS_PASSWORD);
        return new JdbcTemplate(ds);
    }

    @AfterEach
    void cleanup() throws SQLException {
        // APP_ADMIN role has no DELETE on admin_user (never needed at runtime).
        // Use the schema-owner connection for teardown — same pattern as
        // AdminFlowIT.resetState() after finding #3 removed GRANT ALL.
        ownerJdbc().update("DELETE FROM APP_OWNER.admin_user");
        ownerJdbc().update("DELETE FROM APP_OWNER.audit_log");
    }

    /** BCrypt-shaped hash exactly 60 chars long (matches V9 column width). */
    private static String bcryptHash() {
        return "$2a$10$" + "x".repeat(53);
    }

    /** Unique email per insert so the UNIQUE(email) constraint never collides. */
    private static String uniqueEmail(String tag) {
        return tag + "-" + UUID.randomUUID() + "@example.com";
    }

    /**
     * Scenario 1: @PrePersist seeds createdAt and updatedAt to the same Instant.
     * Verifies the BaseEntity callback runs on insert and that the column
     * defaults from V22 don't shadow the JPA-managed values.
     */
    @Test
    void onPersist_setsCreatedAtAndUpdatedAt_toSameInstant() {
        AdminUser fresh = new AdminUser(uniqueEmail("persist"), bcryptHash(), "PLATFORM_OPERATOR");
        assertThat(fresh.getCreatedAt())
                .as("pre-persist: callback has not fired yet")
                .isNull();
        assertThat(fresh.getUpdatedAt())
                .as("pre-persist: callback has not fired yet")
                .isNull();

        AdminUser saved = adminUsers.saveAndFlush(fresh);

        assertThat(saved.getId())
                .as("@UuidGenerator must assign a non-null UUID at persist")
                .isNotNull();
        assertThat(saved.getCreatedAt())
                .as("@PrePersist must populate createdAt")
                .isNotNull();
        assertThat(saved.getUpdatedAt())
                .as("@PrePersist must populate updatedAt")
                .isNotNull();
        assertThat(saved.getUpdatedAt())
                .as("@PrePersist must set createdAt == updatedAt on insert")
                .isEqualTo(saved.getCreatedAt());
    }

    /**
     * Scenario 2: @PreUpdate advances updatedAt but leaves createdAt untouched.
     * Uses Thread.sleep(50) to guarantee the second Instant.now() is strictly
     * greater than the first even on systems with coarse-grained clocks; 50ms
     * is well under any test-flake threshold and well over the ~1ms minimum
     * resolution of java.time.Instant on the JVMs we target.
     */
    @Test
    void onUpdate_advancesUpdatedAt_butNotCreatedAt() throws InterruptedException {
        AdminUser saved = adminUsers.saveAndFlush(
                new AdminUser(uniqueEmail("update"), bcryptHash(), "PLATFORM_OPERATOR"));
        OffsetDateTime originalCreatedAt = saved.getCreatedAt();
        OffsetDateTime originalUpdatedAt = saved.getUpdatedAt();

        // Sleep long enough that the next Instant.now() is strictly later
        // than the pre-persist one. 50ms >> system clock granularity.
        Thread.sleep(50);

        saved.recordLogin(Instant.now());
        AdminUser merged = adminUsers.saveAndFlush(saved);

        assertThat(merged.getCreatedAt())
                .as("@PreUpdate must NOT alter createdAt (updatable=false)")
                .isEqualTo(originalCreatedAt);
        assertThat(merged.getUpdatedAt())
                .as("@PreUpdate must advance updatedAt past the insert timestamp")
                .isAfter(originalUpdatedAt);
    }

    /**
     * Scenario 4 (T7): AuditLog's caller-supplied createdAt must survive
     * @PrePersist so the hash chain remains verifiable after a DB round-trip.
     *
     * <p>The hash is computed over the caller's timestamp BEFORE persist
     * (see {@code AuditLogService.computeHash}); if BaseEntity's @PrePersist
     * overwrote that value with {@code Instant.now()}, the stored hash would
     * no longer recompute from the reloaded row. This test guards against
     * that regression directly at the JPA layer (which mock-based unit tests
     * cannot cover because {@code repo.save()} on a Mockito repo never fires
     * lifecycle callbacks).
     */
    @Test
    void auditLog_preservesCallerSuppliedCreatedAt_acrossPersistAndReload() {
        Instant caller = Instant.parse("2026-05-01T12:34:56.789012Z");
        AuditLog row = new AuditLog(
                null, new byte[]{1, 2, 3},
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "alice@example.com", "ADMIN_LOGIN", null, null, null, null, null, "{}", caller);

        auditLogs.saveAndFlush(row);
        // Evict from the persistence context so findById round-trips through
        // the DB rather than returning the in-memory instance — otherwise we
        // would not detect Hibernate overwriting the field during INSERT.
        em.clear();

        AuditLog reloaded = auditLogs.findById(row.getId()).orElseThrow();
        assertThat(reloaded.getCreatedAt())
                .as("caller-supplied createdAt must survive @PrePersist "
                        + "— hash chain integrity depends on this")
                .isEqualTo(caller);
        assertThat(reloaded.getUpdatedAt())
                .as("updatedAt is seeded to createdAt at insert "
                        + "(audit_log is append-only — never advances)")
                .isEqualTo(caller);
    }

    /**
     * Scenario 3: TIME-ordered UUIDs are non-null and unique across inserts.
     * Smoke-tests the @UuidGenerator(style=TIME) wiring inherited from
     * BaseEntity — strict monotonicity is not in scope (Hibernate's TIME UUID
     * uses 60-bit timestamp + counter, and clocks can collide within a tick).
     */
    @Test
    void persistedUuid_isTimeOrdered() {
        AdminUser first = adminUsers.saveAndFlush(
                new AdminUser(uniqueEmail("uuid1"), bcryptHash(), "PLATFORM_OPERATOR"));
        AdminUser second = adminUsers.saveAndFlush(
                new AdminUser(uniqueEmail("uuid2"), bcryptHash(), "PLATFORM_OPERATOR"));

        assertThat(first.getId()).as("first UUID assigned").isNotNull();
        assertThat(second.getId()).as("second UUID assigned").isNotNull();
        assertThat(first.getId())
                .as("each row gets its own UUID")
                .isNotEqualTo(second.getId());
    }
}
