package com.crosscert.passkey.core.db;

import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V48 acceptance gate: {@code tenant_allowed_origin.ck_tao_origin_format} must
 * accept the Android native-app origin form
 * {@code android:apk-key-hash:<43-char base64url>} in addition to the existing
 * web {@code https?://} origin form, and must keep rejecting everything else.
 *
 * <p>WebAuthn Android clients send {@code clientData.origin} as
 * {@code android:apk-key-hash:<base64url(SHA-256(signing cert))>} — a 43-char
 * (unpadded) base64url string. V48 relaxes the V21 CHECK with an additional
 * {@code OR REGEXP_LIKE(... android ...)} branch while keeping the constraint
 * name {@code ck_tao_origin_format} unchanged.
 *
 * <p>Follows the {@code @SpringBootTest + @Testcontainers} shape of
 * {@code VpdIsolationIT} / {@code BaseEntityCallbackIT}: real Oracle XE 21,
 * {@code bootstrap-vpd.sql} run as SYS, Flyway applies V1–V48 as APP_OWNER, and
 * the Spring datasource connects as APP_ADMIN_USER (EXEMPT ACCESS POLICY +
 * INSERT grant on the table), so direct JDBC INSERTs exercise only the CHECK
 * constraint and are not filtered by the V35 VPD policy.
 *
 * <p>A real {@link Tenant} is seeded via JPA so its {@code @UuidGenerator}
 * RAW(16) id satisfies the {@code fk_tao_tenant} foreign key; the three
 * INSERTs below target that tenant. Raw JDBC (not JPA) is intentional so the
 * ORA constraint error fires synchronously inside {@code assertThatThrownBy}
 * rather than at flush time — the same rationale as
 * {@code VpdIsolationIT.runtimeCannotInsertCrossTenant}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class TenantAllowedOriginAndroidCheckIT {

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

    // Pinned image tag matches docker-compose.yml + the other :core ITs so all
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
        // Bootstrap MUST complete before Spring opens a connection — Spring is
        // told to connect as APP_ADMIN_USER which the bootstrap creates.
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

        // Runtime datasource = APP_ADMIN_USER (EXEMPT ACCESS POLICY).
        reg.add("spring.datasource.url", ORACLE::getJdbcUrl);
        reg.add("spring.datasource.username", () -> "APP_ADMIN_USER");
        reg.add("spring.datasource.password", () -> "admin_pw");
        // Flyway runs as the schema OWNER (APP_OWNER) so migrations can GRANT
        // on APP_OWNER objects. APP_OWNER pw == SYS_PASSWORD in the container.
        reg.add("spring.flyway.url", ORACLE::getJdbcUrl);
        reg.add("spring.flyway.user", () -> "APP_OWNER");
        reg.add("spring.flyway.password", () -> SYS_PASSWORD);
    }

    @Autowired
    TenantRepository tenants;

    @Autowired
    JdbcTemplate jdbc;

    // RAW(16) id of the seeded tenant, as uppercase hex for HEXTORAW(?).
    private String tenantHex;

    @BeforeEach
    void seedTenant() {
        // Clean any tenant rows from prior tests / migration seeds so the FK
        // and UNIQUE(tenant_id, origin) start from a known state.
        resetState();
        // APP_ADMIN holds EXEMPT ACCESS POLICY → JPA save writes the tenant row
        // and its @UuidGenerator assigns a RAW(16) id we can FK against.
        Tenant tenant = tenants.save(
                new Tenant("android-check", "Android Check", "localhost", "Android Check RP"));
        UUID id = tenant.getId();
        assertThat(id).as("seeded tenant must receive a generated UUID").isNotNull();
        tenantHex = id.toString().replace("-", "").toUpperCase();
    }

    @AfterEach
    void cleanup() {
        resetState();
    }

    private void resetState() {
        // V23 fk_admin_user_tenant points admin_user.tenant_id → tenant; null it
        // (and demote RP_ADMIN to satisfy CK_ADMIN_USER_ROLE_TENANT) before the
        // tenant DELETE, mirroring VpdIsolationIT.resetState(). fk_tao_tenant has
        // ON DELETE CASCADE, so deleting the tenant clears our allowed_origin rows.
        jdbc.update("UPDATE APP_OWNER.admin_user SET tenant_id = NULL, role = 'PLATFORM_OPERATOR' WHERE tenant_id IS NOT NULL");
        jdbc.update("DELETE FROM APP_OWNER.tenant");
    }

    /** Direct JDBC INSERT of a tenant_allowed_origin row with the given origin. */
    private void insertOrigin(String origin) {
        jdbc.update(
                "INSERT INTO APP_OWNER.tenant_allowed_origin (tenant_id, origin, sort_order) "
                        + "VALUES (HEXTORAW(?), ?, 0)",
                tenantHex, origin);
    }

    private long countOrigins() {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM APP_OWNER.tenant_allowed_origin WHERE tenant_id = HEXTORAW(?)",
                Long.class, tenantHex);
        return n == null ? 0L : n;
    }

    /**
     * Scenario 1: the new Android origin form is accepted.
     * Prefix + exactly 43 'A' chars = a valid unpadded base64url SHA-256 hash.
     */
    @Test
    void acceptsAndroidApkKeyHashOrigin() {
        String origin = "android:apk-key-hash:" + "A".repeat(43);
        insertOrigin(origin);
        assertThat(countOrigins())
                .as("V48: android:apk-key-hash:<43 chars> must satisfy ck_tao_origin_format")
                .isEqualTo(1L);
    }

    /**
     * Scenario 2: an apk-key-hash whose body is the wrong length is rejected.
     * "TOOSHORT" is 8 chars, not 43, so the android branch must not match and
     * the web branch never matches an {@code android:} prefix → CHECK violated.
     */
    @Test
    void rejectsWrongLengthApkKeyHash() {
        String origin = "android:apk-key-hash:TOOSHORT";
        assertThatThrownBy(() -> insertOrigin(origin))
                .as("wrong-length apk-key-hash must violate ck_tao_origin_format")
                // ORA-02290: check constraint violated — pin the specific code so
                // the test cannot pass on an unrelated error (FK, grant, etc.).
                .hasMessageContaining("ORA-02290");
    }

    /** Scenario 3: a non-URL, non-android origin (ftp://) is still rejected. */
    @Test
    void stillRejectsNonUrlNonAndroidOrigin() {
        assertThatThrownBy(() -> insertOrigin("ftp://example.com"))
                .as("ftp:// must still violate ck_tao_origin_format after V48")
                .hasMessageContaining("ORA-02290");
    }
}
