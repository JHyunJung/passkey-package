package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.entity.AdminUserTenant;
import com.crosscert.passkey.core.entity.Tenant;
import org.junit.jupiter.api.AfterEach;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AdminUserTenantRepository} acceptance gate.
 *
 * <p>Boots a real Testcontainers Oracle XE 21, runs bootstrap-schema.sql as SYS,
 * then lets Flyway apply all migrations. Verifies CRUD and the custom queries
 * on the admin_user_tenant join table (composite PK: admin_user_id, tenant_id).
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class AdminUserTenantRepositoryTest {

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
    }

    @Autowired AdminUserTenantRepository repo;
    @Autowired AdminUserRepository adminUsers;
    @Autowired TenantRepository tenants;

    @AfterEach
    void cleanup() {
        repo.deleteAll();
        adminUsers.deleteAll();
        tenants.deleteAll();
    }

    /** Saves a Tenant with the minimal fields required by the schema. */
    private Tenant seedTenant(String slug) {
        Tenant t = new Tenant(slug, "Test RP " + slug, "localhost", "Test RP Display " + slug);
        t.addAllowedOrigin("http://localhost", 0);
        t.addAcceptedFormat("none");
        return tenants.save(t);
    }

    /** Saves an AdminUser with enabled='Y'/status='ACTIVE'. */
    private AdminUser seedAdminUser(String email) {
        AdminUser u = AdminUser.create();
        u.setEmail(email);
        u.setRole("RP_ADMIN");
        u.setBcryptHash("$2a$10$placeholder");
        return adminUsers.save(u);
    }

    @Test
    void saveAndQueryByAdminUser() {
        Tenant t1e = seedTenant("aut-t1");
        Tenant t2e = seedTenant("aut-t2");
        AdminUser admin = seedAdminUser("aut-rp@x.com");

        UUID adminId = admin.getId();
        UUID t1 = t1e.getId();
        UUID t2 = t2e.getId();

        repo.save(AdminUserTenant.of(adminId, t1, "tester"));
        repo.save(AdminUserTenant.of(adminId, t2, "tester"));

        List<UUID> tenantIds = repo.findTenantIdsByAdminUserId(adminId);
        assertThat(tenantIds).containsExactlyInAnyOrder(t1, t2);
        assertThat(repo.countByAdminUserId(adminId)).isEqualTo(2);
        assertThat(repo.existsByAdminUserIdAndTenantId(adminId, t1)).isTrue();
        assertThat(repo.existsByAdminUserIdAndTenantId(adminId, UUID.randomUUID())).isFalse();

        long deleted = repo.deleteByAdminUserIdAndTenantId(adminId, t1);
        assertThat(deleted).isEqualTo(1);
        assertThat(repo.findTenantIdsByAdminUserId(adminId)).containsExactly(t2);
        assertThat(repo.countByAdminUserId(adminId)).isEqualTo(1);
    }

    @Test
    void findAdminUserIdsByTenantId() {
        Tenant tenant = seedTenant("aut-shared");
        AdminUser admin1 = seedAdminUser("aut-a1@x.com");
        AdminUser admin2 = seedAdminUser("aut-a2@x.com");

        repo.save(AdminUserTenant.of(admin1.getId(), tenant.getId(), "tester"));
        repo.save(AdminUserTenant.of(admin2.getId(), tenant.getId(), "tester"));

        List<UUID> adminIds = repo.findAdminUserIdsByTenantId(tenant.getId());
        assertThat(adminIds).containsExactlyInAnyOrder(admin1.getId(), admin2.getId());
    }
}
