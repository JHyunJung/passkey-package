package com.crosscert.passkey.app.security;

import com.crosscert.passkey.core.tenant.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import javax.sql.DataSource;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 3 acceptance gate — proves {@link ApiKeyLookupService} resolves an API
 * key by its globally-unique prefix using a NATIVE query that works WITHOUT a
 * tenant context, and that {@code touchLastUsed} keeps tenant isolation via an
 * explicit {@code tenant_id} WHERE predicate.
 *
 * <h2>Why APP_ADMIN_USER + vpd.enabled=false</h2>
 * The native {@code findByPrefix} lookup runs as a plain {@link JdbcTemplate}
 * SELECT — it does NOT pass through Hibernate's {@code @Filter}. It also must
 * return a row even though no tenant context is set at authentication time.
 * To model the post-VPD-removal runtime (where the DB no longer carries a
 * {@code tenant_id = SYS_CONTEXT(...)} VPD predicate), this IT connects as
 * {@code APP_ADMIN_USER}, which holds {@code EXEMPT ACCESS POLICY} and so is
 * not constrained by the V8/V20 DBMS_RLS policies that Flyway still installs
 * (their DB removal happens later in Task 7). Combined with
 * {@code passkey.vpd.enabled=false} this makes the native query observe the
 * row regardless of session context — exactly the production behavior once
 * VPD is gone.
 *
 * <h2>Seed strategy</h2>
 * One tenant + one api_key, inserted directly via {@link JdbcTemplate} with
 * RAW(16) bindings (UUID → 16 bytes). Verification reads back via JdbcTemplate
 * (last_used_at) so the assertions never depend on the service under test for
 * their own ground truth.
 *
 * <p>Mirrors the Testcontainers Oracle XE 21 / bootstrap-vpd.sql /
 * application-test.yml pattern used by {@code AppLevelIsolationIT}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@TestPropertySource(properties = "passkey.vpd.enabled=false")
class ApiKeyLookupServiceIT {

    // ── Minimal Spring Boot test app ────────────────────────────────────────
    // @EntityScan/@EnableJpaRepositories on core keep ddl-auto=validate happy
    // (every Flyway table is mapped). The single extra bean is the service
    // under test, wired against the primary DataSource.
    //
    // scanBasePackages is pinned to this class's own (otherwise empty for
    // component purposes) scope so the default component scan does NOT pick up
    // the production security @Components (ApiKeyAuthFilter etc.) that would
    // drag in PasswordEncoder and the rest of the security stack. We only need
    // DataSource + Flyway + the one service under test.
    @SpringBootApplication(scanBasePackages = "com.crosscert.passkey.app.security.itscope")
    @EntityScan("com.crosscert.passkey.core.entity")
    @EnableJpaRepositories("com.crosscert.passkey.core.repository")
    @Import(ApiKeyLookupServiceIT.ServiceConfig.class)
    static class TestApp {
    }

    @Configuration
    static class ServiceConfig {
        @Bean
        ApiKeyLookupService apiKeyLookupService(DataSource dataSource) {
            return new ApiKeyLookupService(dataSource);
        }
    }

    // ── Testcontainers Oracle XE ─────────────────────────────────────────────
    private static final String ORACLE_IMAGE = "gvenzl/oracle-xe:21-slim-faststart";
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
        // Runtime DataSource → APP_ADMIN_USER (EXEMPT ACCESS POLICY) so the
        // native SELECT is not VPD-filtered, mirroring the post-removal state.
        // APP_ADMIN also holds full DML on api_key/tenant for seeding.
        reg.add("spring.datasource.url", ORACLE::getJdbcUrl);
        reg.add("spring.datasource.username", () -> "APP_ADMIN_USER");
        reg.add("spring.datasource.password", () -> "admin_pw");
        // Flyway runs as APP_OWNER (schema owner) — same split as the core ITs.
        reg.add("spring.flyway.url", ORACLE::getJdbcUrl);
        reg.add("spring.flyway.user", () -> "APP_OWNER");
        reg.add("spring.flyway.password", () -> SYS_PASSWORD);
    }

    // ── Test dependencies ────────────────────────────────────────────────────
    @Autowired
    ApiKeyLookupService service;

    @Autowired
    JdbcTemplate jdbc;

    // ── Fixed deterministic identifiers ──────────────────────────────────────
    private static final UUID TENANT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_TENANT_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID API_KEY_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String KEY_PREFIX = "pk_abcd1234";
    private static final String KEY_HASH = "$2a$10$abcdefghijklmnopqrstuv";

    // ── Seed + cleanup ───────────────────────────────────────────────────────

    /**
     * Seeds one tenant and one api_key owned by it. id/tenant_id are RAW(16),
     * bound from the UUIDs above. Returns nothing — the constants are reused by
     * the assertions.
     */
    private void seed() {
        TenantContextHolder.clear();
        // Tenant (FK target for api_key.tenant_id). Minimal NOT NULL columns —
        // allowed_origins/attestation_policy were normalized into child tables
        // (V21); the boolean flags carry DB defaults so they are omitted here.
        jdbc.update(
                "INSERT INTO APP_OWNER.tenant "
                        + "(id, display_name, status, slug, rp_id, rp_name) "
                        + "VALUES (?, ?, 'active', ?, ?, ?)",
                ps -> {
                    ps.setBytes(1, toBytes(TENANT_ID));
                    ps.setString(2, "ApiKey Lookup Tenant");
                    ps.setString(3, "apikey-lookup-tenant");
                    ps.setString(4, "localhost");
                    ps.setString(5, "ApiKey Lookup RP");
                });
        // api_key. scopes was normalized into api_key_scope (V21); last_used_at
        // left NULL so the touchLastUsed assertions start from a clean slate.
        jdbc.update(
                "INSERT INTO APP_OWNER.api_key "
                        + "(id, tenant_id, key_prefix, key_hash, name) "
                        + "VALUES (?, ?, ?, ?, ?)",
                ps -> {
                    ps.setBytes(1, toBytes(API_KEY_ID));
                    ps.setBytes(2, toBytes(TENANT_ID));
                    ps.setString(3, KEY_PREFIX);
                    ps.setString(4, KEY_HASH);
                    ps.setString(5, "test-key");
                });
    }

    @AfterEach
    void cleanup() {
        TenantContextHolder.clear();
        jdbc.update("DELETE FROM APP_OWNER.api_key");
        jdbc.update("DELETE FROM APP_OWNER.tenant");
    }

    // ── Test 1: native lookup works WITHOUT tenant context ───────────────────

    /**
     * With {@link TenantContextHolder} cleared (the authentication-time state),
     * the native {@code findByPrefix} must still return the seeded row — the
     * JdbcTemplate SELECT does not pass through the Hibernate {@code @Filter},
     * and APP_ADMIN_USER bypasses DB VPD, so a globally-unique prefix resolves
     * to exactly one row regardless of context.
     */
    @Test
    void findByPrefix_returnsRow_withoutTenantContext() {
        seed();
        TenantContextHolder.clear();

        Optional<ApiKeyLookupService.ApiKeyAuthRow> row = service.findByPrefix(KEY_PREFIX);

        assertThat(row)
                .as("native lookup must return the row even with no tenant context")
                .isPresent();
        assertThat(row.get().id())
                .as("id must round-trip from RAW(16)")
                .isEqualTo(API_KEY_ID);
        assertThat(row.get().tenantId())
                .as("tenant_id must round-trip from RAW(16)")
                .isEqualTo(TENANT_ID);
        assertThat(row.get().keyHash()).isEqualTo(KEY_HASH);
        assertThat(row.get().expiresAt()).isNull();
        assertThat(row.get().revokedAt()).isNull();
    }

    // ── Test 2: unknown prefix → empty ───────────────────────────────────────

    @Test
    void findByPrefix_returnsEmpty_forUnknownPrefix() {
        seed();
        TenantContextHolder.clear();

        Optional<ApiKeyLookupService.ApiKeyAuthRow> row =
                service.findByPrefix("pk_doesnotexist");

        assertThat(row)
                .as("an unknown prefix must resolve to Optional.empty()")
                .isEmpty();
    }

    // ── Test 3: touchLastUsed isolates by explicit tenant_id ─────────────────

    /**
     * touchLastUsed must constrain its UPDATE to {@code id AND tenant_id}. A
     * wrong tenantId matches 0 rows (last_used_at stays NULL); the correct
     * tenantId updates the row. This preserves cross-tenant write isolation
     * even though VPD is gone — context absence is never treated as authority.
     */
    @Test
    void touchLastUsed_updatesOnlyMatchingTenant() {
        seed();
        Instant now = Instant.now();

        // ── Wrong tenant: must NOT update (0 rows matched) ────────────────
        service.touchLastUsed(API_KEY_ID, OTHER_TENANT_ID, now);
        Timestamp afterWrong = jdbc.queryForObject(
                "SELECT last_used_at FROM APP_OWNER.api_key WHERE id = ?",
                Timestamp.class, toBytes(API_KEY_ID));
        assertThat(afterWrong)
                .as("touchLastUsed with the wrong tenant_id must leave last_used_at NULL")
                .isNull();

        // ── Correct tenant: must update ───────────────────────────────────
        service.touchLastUsed(API_KEY_ID, TENANT_ID, now);
        Timestamp afterRight = jdbc.queryForObject(
                "SELECT last_used_at FROM APP_OWNER.api_key WHERE id = ?",
                Timestamp.class, toBytes(API_KEY_ID));
        assertThat(afterRight)
                .as("touchLastUsed with the correct tenant_id must set last_used_at")
                .isNotNull();
    }

    // ── helper ────────────────────────────────────────────────────────────────
    private static byte[] toBytes(UUID uuid) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(uuid.getMostSignificantBits());
        bb.putLong(uuid.getLeastSignificantBits());
        return bb.array();
    }
}
