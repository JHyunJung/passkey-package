package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.AuditLog;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F07-beforeCursor. {@link ActivityRepository#feedPageRaw} paginates backward
 * using a strict {@code createdAt < :before} comparison. Oracle TIMESTAMP
 * columns store microsecond precision, so concurrent bursts (e.g. many
 * ADMIN_LOGIN rows written in the same microsecond) can share an identical
 * {@code createdAt}. Client pagination stores {@code before = <oldest visible
 * row's createdAt>} and requests the next page with it — any row that shares
 * that exact timestamp but was NOT already returned (i.e. has a lower id in
 * the {@code createdAt DESC, id DESC} order) is permanently dropped: it fails
 * {@code createdAt < before} on this page and every subsequent page (they all
 * use even-smaller {@code before} values).
 *
 * <p>The forward-polling {@code feedRaw} (sinceId) path avoids exactly this by
 * comparing the {@code (createdAt, id)} tuple of the cursor row, not just
 * {@code createdAt}. This IT proves {@code feedPageRaw} needs the same tuple
 * treatment via a {@code beforeId} cursor.
 *
 * <p>Uses the same Testcontainers Oracle XE 21 / bootstrap-schema.sql pattern
 * as {@code TenantFilterAspectIT} / {@code AppLevelIsolationIT}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class ActivityRepositoryFeedPageIT {

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
                    .withUsername("APP_OWNER")
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
        reg.add("spring.datasource.username", () -> "APP_ADMIN_USER");
        reg.add("spring.datasource.password", () -> "admin_pw");
        reg.add("spring.flyway.url", ORACLE::getJdbcUrl);
        reg.add("spring.flyway.user", () -> "APP_OWNER");
        reg.add("spring.flyway.password", () -> SYS_PASSWORD);
    }

    @Autowired
    AuditLogRepository auditLogRepository;

    @Autowired
    ActivityRepository activityRepository;

    @Autowired
    JdbcTemplate jdbc;

    /**
     * APP_ADMIN_USER (the runtime datasource) has SELECT+INSERT only on
     * audit_log (V10 design) — DELETE requires the schema-owner pool, same
     * pattern as ActivityControllerIT.ownerJdbc().
     */
    private static HikariDataSource ownerPool;

    @AfterAll
    static void closeOwnerPool() {
        if (ownerPool != null) {
            ownerPool.close();
            ownerPool = null;
        }
    }

    private static synchronized JdbcTemplate ownerJdbc() {
        if (ownerPool == null) {
            HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl(ORACLE.getJdbcUrl());
            ds.setUsername("APP_OWNER");
            ds.setPassword(SYS_PASSWORD);
            ds.setMaximumPoolSize(2);
            ds.setPoolName("activity-repo-feedpage-it-owner");
            ownerPool = ds;
        }
        return new JdbcTemplate(ownerPool);
    }

    @AfterEach
    void cleanup() {
        ownerJdbc().update("DELETE FROM APP_OWNER.audit_log");
    }

    private AuditLog row(byte[] hash, OffsetDateTime createdAt) {
        return new AuditLog(null, hash, null, "actor@example.com",
                "ADMIN_LOGIN", null, null, null, null, null, "{}", createdAt);
    }

    /**
     * Reproduces the boundary-row drop with the legacy (before-only, no beforeId)
     * call path: 3 rows share one microsecond timestamp T. A page of size 2
     * (createdAt DESC, id DESC) returns the two highest-id rows at T; the
     * lowest-id row at T is left over. The client stores before=T (createdAt
     * only — the pre-F07 client never had an id to send) and asks for the next
     * page — strict {@code createdAt < T} excludes the leftover row forever.
     * This documents the legacy behavior that remains for backward
     * compatibility; new callers must use the {@code beforeId} tuple cursor
     * (see {@link #feedPageRaw_tupleCursor_includesBoundaryRow}).
     */
    @Test
    void feedPageRaw_legacyBeforeOnly_stillDropsBoundaryRow() {
        OffsetDateTime t = OffsetDateTime.now(ZoneOffset.UTC).withNano(123_000_000);
        AuditLog r1 = auditLogRepository.save(row(new byte[]{1}, t));
        AuditLog r2 = auditLogRepository.save(row(new byte[]{2}, t));
        AuditLog r3 = auditLogRepository.save(row(new byte[]{3}, t));

        List<UUID> allIdsDesc = List.of(r1.getId(), r2.getId(), r3.getId()).stream()
                .sorted((a, b) -> b.compareTo(a))
                .toList();
        UUID lowestIdAtT = allIdsDesc.get(allIdsDesc.size() - 1);

        // First page of size 2: the two highest ids at T.
        List<AuditLog> firstPage = activityRepository.feedPageRaw(null, null, null, PageRequest.of(0, 2));
        assertThat(firstPage).hasSize(2);
        assertThat(firstPage).extracting(AuditLog::getId).doesNotContain(lowestIdAtT);

        // Legacy client stores before = oldest visible row's createdAt = t (no beforeId available).
        List<AuditLog> nextPage = activityRepository.feedPageRaw(null, t, null, PageRequest.of(0, 50));

        assertThat(nextPage)
                .as("legacy before-only call path keeps the documented drop — "
                        + "this is the pre-fix behavior retained for backward compatibility")
                .extracting(AuditLog::getId)
                .doesNotContain(lowestIdAtT);
    }

    /**
     * F07 fix proof: when the client supplies {@code beforeId} (the id of the
     * last row from the previous page), the boundary row sharing the exact
     * same {@code createdAt} IS included on the next page — the tuple cursor
     * mirrors the forward-polling sinceId subquery approach.
     */
    @Test
    void feedPageRaw_tupleCursor_includesBoundaryRow() {
        OffsetDateTime t = OffsetDateTime.now(ZoneOffset.UTC).withNano(123_000_000);
        AuditLog r1 = auditLogRepository.save(row(new byte[]{1}, t));
        AuditLog r2 = auditLogRepository.save(row(new byte[]{2}, t));
        AuditLog r3 = auditLogRepository.save(row(new byte[]{3}, t));

        List<UUID> allIdsDesc = List.of(r1.getId(), r2.getId(), r3.getId()).stream()
                .sorted((a, b) -> b.compareTo(a))
                .toList();
        UUID lowestIdAtT = allIdsDesc.get(allIdsDesc.size() - 1);

        // First page of size 2: the two highest ids at T.
        List<AuditLog> firstPage = activityRepository.feedPageRaw(null, null, null, PageRequest.of(0, 2));
        assertThat(firstPage).hasSize(2);
        UUID lastOnFirstPage = firstPage.get(firstPage.size() - 1).getId();

        // Client now sends both before (createdAt) and beforeId (id) of the last visible row.
        List<AuditLog> nextPage = activityRepository.feedPageRaw(null, t, lastOnFirstPage, PageRequest.of(0, 50));

        assertThat(nextPage)
                .as("tuple cursor (createdAt, beforeId) must include the boundary row "
                        + "sharing the same microsecond timestamp")
                .extracting(AuditLog::getId)
                .contains(lowestIdAtT);
        assertThat(nextPage)
                .as("tuple cursor must not re-include rows already returned on the first page")
                .extracting(AuditLog::getId)
                .doesNotContain(firstPage.stream().map(AuditLog::getId).toArray(UUID[]::new));
    }
}
