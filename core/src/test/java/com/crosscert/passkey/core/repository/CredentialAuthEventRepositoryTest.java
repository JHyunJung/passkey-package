package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.ceremony.CredentialAuthFailureReason;
import com.crosscert.passkey.core.ceremony.CredentialAuthResult;
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

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CredentialAuthEventRepository} acceptance gate.
 *
 * <p>:core has no embedded DB on the test classpath — repository tests boot a
 * real Testcontainers Oracle XE 21 and run {@code bootstrap-schema.sql} as SYS,
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
    void findsByCredentialIdNewestFirst() throws InterruptedException {
        UUID credA = seedCredential("evt-a", "cred-id-A".getBytes());
        UUID credB = seedCredential("evt-b", "cred-id-B".getBytes());
        UUID tenant = UUID.randomUUID(); // tenant_id column is a free metric field, no FK

        // createdAt 은 BaseEntity @PrePersist 가 Instant.now() 로 채운다(setter 없음).
        // 정렬(DESC)을 관측 가능하게 하려고 save 사이에 짧은 sleep 으로 timestamp 를 벌린다
        // (BaseEntityCallbackIT 의 Thread.sleep 패턴). signCount 로 어느 행인지 식별한다.
        repo.saveAndFlush(new CredentialAuthEvent(credA, tenant, CredentialAuthResult.SUCCESS, null, 10));
        Thread.sleep(20);
        repo.saveAndFlush(new CredentialAuthEvent(credA, tenant,
                CredentialAuthResult.FAILED, CredentialAuthFailureReason.SIGN_COUNT_REPLAY, 20));
        Thread.sleep(20);
        repo.saveAndFlush(new CredentialAuthEvent(credA, tenant, CredentialAuthResult.SUCCESS, null, 30));
        // 다른 credential 의 이벤트 — 결과에 섞이면 안 된다.
        repo.saveAndFlush(new CredentialAuthEvent(credB, tenant, CredentialAuthResult.SUCCESS, null, 99));

        Page<CredentialAuthEvent> page =
                repo.findByTenantIdAndCredentialIdOrderByCreatedAtDesc(tenant, credA, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).allMatch(e -> e.getCredentialId().equals(credA));
        // 최신순(DESC) 검증: signCount 가 30 → 20 → 10 (insert 역순) 으로 나와야 한다.
        List<CredentialAuthEvent> content = page.getContent();
        assertThat(content).extracting(CredentialAuthEvent::getSignCount)
                .containsExactly(30L, 20L, 10L);
        // createdAt 도 단조 감소(DESC)임을 직접 확인.
        assertThat(content).isSortedAccordingTo(
                Comparator.comparing(CredentialAuthEvent::getCreatedAt).reversed());

        // defense-in-depth: 같은 credentialId 라도 다른 tenant 로 조회하면 0건이어야 한다
        // (술어에 tenant_id 포함 — 오기록/오염 행 cross-tenant 누출 방지).
        assertThat(repo.findByTenantIdAndCredentialIdOrderByCreatedAtDesc(
                UUID.randomUUID(), credA, PageRequest.of(0, 10)).getTotalElements()).isZero();
    }

    @Test
    void deleteCreatedBeforeRemovesOldRows() {
        UUID cred = seedCredential("evt-purge", "cred-id-purge".getBytes());
        UUID tenant = UUID.randomUUID();
        repo.save(new CredentialAuthEvent(cred, tenant, CredentialAuthResult.SUCCESS, null, 1));
        repo.save(new CredentialAuthEvent(cred, tenant, CredentialAuthResult.SUCCESS, null, 2));

        // cutoff far in the future → both rows are "before" it and get purged.
        OffsetDateTime cutoff = OffsetDateTime.now().plus(1, ChronoUnit.DAYS);
        int deleted = repo.deleteCreatedBefore(cutoff, 100);

        assertThat(deleted).isEqualTo(2);
        assertThat(repo.count()).isZero();
    }

    /**
     * FK fk_cred_auth_event_credential ON DELETE CASCADE (V43): credential 삭제 시
     * 그 credential 의 인증 이벤트가 DB 레벨에서 동반 삭제됨을 검증한다. admin revoke
     * (credential DELETE) 가 고아 이벤트 행을 남기지 않는다는 spec 보장의 직접 가드.
     */
    @Test
    void deletingCredentialCascadesAuthEvents() {
        UUID credA = seedCredential("evt-cascade-a", "cred-id-cascade-a".getBytes());
        UUID credB = seedCredential("evt-cascade-b", "cred-id-cascade-b".getBytes());
        UUID tenant = UUID.randomUUID();

        repo.saveAndFlush(new CredentialAuthEvent(credA, tenant, CredentialAuthResult.SUCCESS, null, 1));
        repo.saveAndFlush(new CredentialAuthEvent(credA, tenant,
                CredentialAuthResult.FAILED, CredentialAuthFailureReason.SIGNATURE_INVALID, 1));
        repo.saveAndFlush(new CredentialAuthEvent(credB, tenant, CredentialAuthResult.SUCCESS, null, 2));
        assertThat(repo.count()).isEqualTo(3);

        // credA 삭제 → credA 의 이벤트 2건이 FK CASCADE 로 동반 삭제, credB 의 1건은 보존.
        credentials.deleteById(credA);
        credentials.flush();

        assertThat(repo.findByTenantIdAndCredentialIdOrderByCreatedAtDesc(tenant, credA, PageRequest.of(0, 10))
                .getTotalElements()).isZero();
        assertThat(repo.findByTenantIdAndCredentialIdOrderByCreatedAtDesc(tenant, credB, PageRequest.of(0, 10))
                .getTotalElements()).isEqualTo(1);
        assertThat(repo.count()).isEqualTo(1);
    }
}
