package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.Credential;
import com.crosscert.passkey.core.entity.CredentialAuthEvent;
import com.crosscert.passkey.core.entity.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CredentialAuthEventRepository} acceptance gate.
 *
 * <p>:core has no embedded DB on the test classpath — repository tests boot a
 * real Testcontainers Oracle XE 21 and run {@code bootstrap-vpd.sql} as SYS,
 * then let Flyway apply V1–V43 (V43 creates {@code credential_auth_event}).
 * We follow the {@code @SpringBootTest + @Testcontainers} shape of
 * {@link com.crosscert.passkey.core.entity.BaseEntityCallbackIT} rather than
 * {@code @DataJpaTest} because the FK to {@code credential(id)} requires the
 * real schema (a random credentialId would violate the constraint on Oracle).
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class CredentialAuthEventRepositoryTest {

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
        reg.add("spring.datasource.url", ORACLE::getJdbcUrl);
        reg.add("spring.datasource.username", () -> "APP_ADMIN_USER");
        reg.add("spring.datasource.password", () -> "admin_pw");
    }

    @Autowired CredentialAuthEventRepository repo;
    @Autowired CredentialRepository credentials;
    @Autowired TenantRepository tenants;

    @AfterEach
    void cleanup() {
        repo.deleteAll();
        credentials.deleteAll();
        tenants.deleteAll();
    }

    /** Seeds one tenant + one credential and returns the credential's internal PK. */
    private UUID seedCredential(String slug, byte[] credentialId) {
        Tenant t = new Tenant(slug, "Auth Event " + slug, "localhost", "Auth Event RP " + slug);
        t.addAllowedOrigin("http://localhost", 0);
        t.addAcceptedFormat("none");
        t = tenants.save(t);
        Credential c = credentials.save(
                new Credential(t.getId(), ("uh-" + slug).getBytes(), credentialId, ("pk-" + slug).getBytes(), null));
        return c.getId();
    }

    @Test
    void findsByCredentialIdNewestFirst() {
        UUID credA = seedCredential("evt-a", "cred-id-A".getBytes());
        UUID credB = seedCredential("evt-b", "cred-id-B".getBytes());
        UUID tenant = UUID.randomUUID(); // tenant_id column is a free metric field, no FK

        repo.save(new CredentialAuthEvent(credA, tenant, "SUCCESS", null, 1));
        repo.save(new CredentialAuthEvent(credA, tenant, "FAILED", "SIGN_COUNT_REPLAY", 1));
        repo.save(new CredentialAuthEvent(credB, tenant, "SUCCESS", null, 2)); // other cred

        Page<CredentialAuthEvent> page =
                repo.findByCredentialIdOrderByCreatedAtDesc(credA, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).allMatch(e -> e.getCredentialId().equals(credA));
    }

    @Test
    void deleteCreatedBeforeRemovesOldRows() {
        UUID cred = seedCredential("evt-purge", "cred-id-purge".getBytes());
        UUID tenant = UUID.randomUUID();
        repo.save(new CredentialAuthEvent(cred, tenant, "SUCCESS", null, 1));
        repo.save(new CredentialAuthEvent(cred, tenant, "SUCCESS", null, 2));

        // cutoff far in the future → both rows are "before" it and get purged.
        Instant cutoff = Instant.now().plus(1, ChronoUnit.DAYS);
        int deleted = repo.deleteCreatedBefore(cutoff, 100);

        assertThat(deleted).isEqualTo(2);
        assertThat(repo.count()).isZero();
    }
}
