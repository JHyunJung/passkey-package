package com.crosscert.passkey.core.vpd;

import com.crosscert.passkey.core.config.CoreDataSourceConfig;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 5 acceptance gate — VPD toggle via {@code passkey.vpd.enabled}.
 *
 * <p>Two nested test classes share one Oracle Testcontainers instance
 * (declared in the outer class) and each start their own Spring context
 * with a different value of {@code passkey.vpd.enabled}:
 *
 * <ul>
 *   <li>{@link VpdDisabled} — {@code passkey.vpd.enabled=false} (default):
 *       the autowired {@link DataSource} bean must NOT be a
 *       {@link TenantAwareDataSource}.</li>
 *   <li>{@link VpdEnabled} — {@code passkey.vpd.enabled=true}:
 *       the bean MUST be a {@link TenantAwareDataSource}.</li>
 * </ul>
 *
 * <p>Uses a hyper-minimal {@link ToggleTestApp} that loads ONLY the
 * {@link DataSourceAutoConfiguration} (for {@link DataSourceProperties})
 * and explicitly imports {@link CoreDataSourceConfig}. No JPA, no Flyway,
 * no Redis — this context is the narrowest possible proof that the toggle
 * works. {@link DirtiesContext} on each nested class prevents the lightweight
 * context from polluting the Spring test-context cache and affecting the
 * heavyweight {@link VpdIsolationIT} context that runs in the same JVM.
 */
@Testcontainers
class VpdToggleIT {

    // ── Shared Oracle container ──────────────────────────────────────────────

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

    // ── Shared bootstrap helper ──────────────────────────────────────────────

    static void bootstrapAndRegister(DynamicPropertyRegistry reg) throws Exception {
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
        reg.add("spring.datasource.url", ORACLE::getJdbcUrl);
        reg.add("spring.datasource.username", () -> "APP_ADMIN_USER");
        reg.add("spring.datasource.password", () -> "admin_pw");
    }

    // ── Hyper-minimal test application ──────────────────────────────────────

    /**
     * Loads only the auto-configurations necessary to provide
     * {@link DataSourceProperties} (needed by
     * {@link CoreDataSourceConfig#physicalDataSource}) and registers
     * {@link CoreDataSourceConfig} via {@link Import}. Nothing else —
     * no JPA, no Flyway, no Redis. This prevents
     * {@link org.springframework.boot.autoconfigure.EnableAutoConfiguration}
     * exclusion annotations from leaking into other test contexts in the
     * same JVM (a known Spring Boot test issue when multiple
     * {@link org.springframework.boot.autoconfigure.SpringBootApplication}
     * classes co-exist in the same test package).
     */
    @Configuration
    @ImportAutoConfiguration(DataSourceAutoConfiguration.class)
    @Import(CoreDataSourceConfig.class)
    static class ToggleTestApp {
    }

    // ── Nested: VPD disabled (default) ──────────────────────────────────────

    /**
     * With {@code passkey.vpd.enabled=false} the dataSource bean must NOT be
     * wrapped in {@link TenantAwareDataSource}. The physical HikariDataSource
     * is returned as-is; isolation is the responsibility of the Hibernate
     * {@code @Filter} ({@link TenantFilterAspect}).
     */
    @Nested
    @SpringBootTest(classes = ToggleTestApp.class)
    @ActiveProfiles("test")
    @TestPropertySource(properties = "passkey.vpd.enabled=false")
    @DirtiesContext
    class VpdDisabled {

        @DynamicPropertySource
        static void registerProps(DynamicPropertyRegistry reg) throws Exception {
            bootstrapAndRegister(reg);
        }

        @Autowired
        DataSource dataSource;

        @Test
        void dataSourceIsNotTenantAwareWhenVpdDisabled() {
            assertThat(dataSource)
                    .as("passkey.vpd.enabled=false → DataSource bean must NOT be "
                            + "TenantAwareDataSource (VPD wrapping must be skipped)")
                    .isNotInstanceOf(TenantAwareDataSource.class);
        }
    }

    // ── Nested: VPD enabled ──────────────────────────────────────────────────

    /**
     * With {@code passkey.vpd.enabled=true} the dataSource bean MUST be a
     * {@link TenantAwareDataSource}, providing the DB-layer isolation via
     * Oracle VPD session context ({@code ctx_pkg.set_tenant}).
     */
    @Nested
    @SpringBootTest(classes = ToggleTestApp.class)
    @ActiveProfiles("test")
    @TestPropertySource(properties = "passkey.vpd.enabled=true")
    @DirtiesContext
    class VpdEnabled {

        @DynamicPropertySource
        static void registerProps(DynamicPropertyRegistry reg) throws Exception {
            bootstrapAndRegister(reg);
        }

        @Autowired
        DataSource dataSource;

        @Test
        void dataSourceIsTenantAwareWhenVpdEnabled() {
            assertThat(dataSource)
                    .as("passkey.vpd.enabled=true → DataSource bean MUST be "
                            + "TenantAwareDataSource (VPD wrapping must be active)")
                    .isInstanceOf(TenantAwareDataSource.class);
        }
    }
}
