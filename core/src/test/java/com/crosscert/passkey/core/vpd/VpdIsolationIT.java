package com.crosscert.passkey.core.vpd;

import com.crosscert.passkey.core.entity.Credential;
import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.CredentialRepository;
import com.crosscert.passkey.core.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
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
 * Phase 0 acceptance gate. Boots a real Testcontainers Oracle XE 21,
 * runs scripts/bootstrap-vpd.sql via {@code sqlplus} as SYS, lets Flyway
 * apply V1–V5, then proves that the V3 VPD policy enforces tenant
 * isolation as designed across five scenarios:
 *
 * <ol>
 *   <li>APP_ADMIN_USER (EXEMPT ACCESS POLICY) — sees all rows.</li>
 *   <li>APP_RUNTIME with APP_CTX=T_A — sees only T_A's row.</li>
 *   <li>APP_RUNTIME with APP_CTX=T_B — sees only T_B's row.</li>
 *   <li>APP_RUNTIME with no APP_CTX — sees zero rows (safe default).</li>
 *   <li>APP_RUNTIME with APP_CTX=T_A attempting INSERT with tenant_id=T_B
 *       — policy update_check=TRUE rejects with ORA-28115.</li>
 * </ol>
 *
 * <p>Bootstrap notes:
 * <ul>
 *   <li>scripts/bootstrap-vpd.sql uses SQL*Plus syntax ({@code /}, EXIT,
 *       WHENEVER), so it cannot be run through JDBC withInitScript. Instead
 *       we copy it into the container and exec {@code sqlplus} as SYS.</li>
 *   <li>{@code OracleContainer.withPassword(...)} sets BOTH the APP_USER
 *       password AND {@code ORACLE_PASSWORD} (SYS/SYSTEM) to the same
 *       value, because Testcontainers' {@code OracleContainer.configure()}
 *       writes both env vars from the single {@code password} field.
 *       That means sqlplus runs as {@code sys/<SYS_PASSWORD>} where
 *       SYS_PASSWORD equals what we passed to {@code .withPassword()}.</li>
 *   <li>JDBC URL/username/password are registered via
 *       {@link DynamicPropertySource} so the test does not assume the
 *       running docker-compose Oracle on host port 1521.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class VpdIsolationIT {

    /**
     * Minimal Spring Boot test app. Lives inside the IT so :core does not
     * have to ship a main class for its production jar — :core is a
     * library subproject consumed by passkey-app and admin-app.
     */
    @SpringBootApplication
    @EntityScan("com.crosscert.passkey.core.entity")
    @EnableJpaRepositories("com.crosscert.passkey.core.repository")
    static class TestApp {
    }

    // Pinned image tag matches docker-compose.yml so dev and CI exercise
    // the same Oracle binary. faststart variant trims cold-boot from
    // ~3 min to ~60-90 s on Apple Silicon under amd64 emulation.
    private static final String ORACLE_IMAGE = "gvenzl/oracle-xe:21-slim-faststart";

    // In OracleContainer's configure() the value of withPassword(...) is
    // pushed to BOTH the ORACLE_PASSWORD env (SYS/SYSTEM) and
    // APP_USER_PASSWORD env (the gvenzl-created APP_OWNER user). We
    // therefore use the same secret for both so the bootstrap SQL can
    // connect as SYS-AS-SYSDBA with the password we know.
    private static final String SYS_PASSWORD = "app_owner_pw";

    // Phase 6: tenant IDs are UUIDs. T6 will migrate entity constructors;
    // for now RuntimeDsHelper calls use these UUID fixtures while entity
    // constructors still accept String (pre-T6 entities).
    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final String TENANT_A_HEX = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String TENANT_B_HEX = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @org.testcontainers.junit.jupiter.Container
    static final OracleContainer ORACLE =
            new OracleContainer(ORACLE_IMAGE)
                    // OracleContainer reserves "xepdb1" as the pluggable DB
                    // name baked into gvenzl/oracle-xe and rejects attempts
                    // to override it. The wrapper uses XEPDB1 by default,
                    // matching docker-compose.yml, so we leave it implicit.
                    .withUsername("APP_OWNER")
                    .withPassword(SYS_PASSWORD)
                    .withCopyFileToContainer(
                            MountableFile.forClasspathResource("bootstrap-vpd.sql"),
                            "/tmp/bootstrap-vpd.sql");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry reg) throws Exception {
        // @Container starts ORACLE before this method runs (DynamicPropertySource
        // is invoked at ApplicationContext refresh, which Spring schedules
        // after the JUnit extension brings the container up). The bootstrap
        // must complete BEFORE Spring tries to open a connection, otherwise
        // APP_ADMIN_USER does not yet exist and DataSource init blows up.
        Container.ExecResult exec = ORACLE.execInContainer(
                "bash", "-c",
                "sqlplus -S sys/" + SYS_PASSWORD + "@localhost:1521/XEPDB1 as sysdba "
                        + "@/tmp/bootstrap-vpd.sql");
        if (exec.getExitCode() != 0) {
            // Include stdout — sqlplus emits "ORA-xxxxx" lines there, not
            // on stderr — so a diagnostic failure message is actionable.
            throw new IllegalStateException(
                    "bootstrap-vpd.sql failed (exit=" + exec.getExitCode() + ")\n"
                            + "STDOUT:\n" + exec.getStdout() + "\n"
                            + "STDERR:\n" + exec.getStderr());
        }

        // Now register the connection coordinates. Spring will open the
        // pool as APP_ADMIN_USER, which the bootstrap just created.
        reg.add("spring.datasource.url", ORACLE::getJdbcUrl);
        reg.add("spring.datasource.username", () -> "APP_ADMIN_USER");
        reg.add("spring.datasource.password", () -> "admin_pw");
    }

    @Autowired
    TenantRepository tenants;

    @Autowired
    CredentialRepository credentials;

    @Autowired
    RuntimeDsHelper runtime;

    @BeforeEach
    void seed() {
        // APP_ADMIN holds EXEMPT ACCESS POLICY so it can read/write any
        // tenant's row directly through JPA. We do NOT set APP_CTX here —
        // setting it would prove nothing for the admin bypass scenario.
        TenantContextHolder.clear();
        tenants.save(new Tenant("T_A", "Tenant A"));
        tenants.save(new Tenant("T_B", "Tenant B"));
        credentials.save(new Credential(
                TENANT_A,
                "user_a".getBytes(),
                "cred_a".getBytes(),
                "pk_a".getBytes(),
                null));
        credentials.save(new Credential(
                TENANT_B,
                "user_b".getBytes(),
                "cred_b".getBytes(),
                "pk_b".getBytes(),
                null));
    }

    @AfterEach
    void cleanup() {
        TenantContextHolder.clear();
        credentials.deleteAll();
        tenants.deleteAll();
    }

    /** Scenario 1: APP_ADMIN_USER bypasses VPD via EXEMPT ACCESS POLICY. */
    @Test
    void appAdminBypassSeesAllRows() {
        TenantContextHolder.clear();
        assertThat(credentials.findAll())
                .as("APP_ADMIN with EXEMPT ACCESS POLICY must see both tenants' rows")
                .hasSize(2);
    }

    /** Scenario 2: APP_RUNTIME with APP_CTX=T_A → only T_A row. */
    @Test
    void runtimeWithTenantASeesOnlyTenantA() {
        var rows = runtime.selectAllCredentialsAsRuntime(TENANT_A);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[1])
                .as("VPD predicate must restrict APP_RUNTIME to APP_CTX tenant")
                .isEqualTo(TENANT_A_HEX);
    }

    /** Scenario 3: APP_RUNTIME with APP_CTX=T_B → only T_B row. */
    @Test
    void runtimeWithTenantBSeesOnlyTenantB() {
        var rows = runtime.selectAllCredentialsAsRuntime(TENANT_B);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[1])
                .as("VPD predicate must restrict APP_RUNTIME to APP_CTX tenant")
                .isEqualTo(TENANT_B_HEX);
    }

    /** Scenario 4: APP_RUNTIME with no APP_CTX → safe default (zero rows). */
    @Test
    void runtimeWithNoContextSeesNoRows() {
        // No APP_CTX value → SYS_CONTEXT returns NULL → predicate
        // "tenant_id = NULL" is UNKNOWN for every row → Oracle filters
        // them all out. This is the security default that prevents
        // tenant data leakage when a request forgets to set the context.
        var rows = runtime.selectAllCredentialsAsRuntime(null);
        assertThat(rows)
                .as("Missing APP_CTX must hide every credential row")
                .isEmpty();
    }

    /** Scenario 5: APP_RUNTIME cross-tenant INSERT rejected by update_check. */
    @Test
    void runtimeCannotInsertCrossTenant() {
        // APP_CTX=T_A but we try to write a row whose tenant_id=T_B.
        // V3's DBMS_RLS.ADD_POLICY uses update_check=TRUE, so Oracle
        // evaluates the predicate against the NEW row and raises
        // ORA-28115 when it does not match.
        //
        // Raw JDBC (RuntimeDsHelper.insertCredentialAs) is intentional:
        // a JPA save() would queue the INSERT for flush time, so the
        // ORA-28115 would fire OUTSIDE the assertThatThrownBy lambda
        // and the test would either pass for the wrong reason or fail
        // with a TransactionSystemException wrapping the real cause.
        assertThatThrownBy(() -> runtime.insertCredentialAs(
                TENANT_A, TENANT_B_HEX, "user_x", "cred_x", "pk_x"))
                .as("VPD update_check=TRUE must reject cross-tenant INSERT")
                // Match the specific Oracle code for "policy with check
                // option violation" so the test cannot pass on an
                // unrelated error (missing grant, bad SQL, sequence
                // issue, etc.). ORA-28115 is the documented code for
                // DBMS_RLS update_check rejection.
                .hasMessageContaining("ORA-28115");
    }
}
