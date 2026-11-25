package com.crosscert.passkey.core.ceremony;

import com.crosscert.passkey.core.repository.CeremonyEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 성공 행이 청구 장부로 쓰이므로 {@link CeremonyEventRecorder#recordInTransaction} 이
 * 호출 측 트랜잭션과 운명을 같이하는지(롤백 시 소멸, 커밋 시 확정, 동시 기록 유실 없음) 실제 Oracle 로 확인한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class CeremonyEventLedgerIT {

    @SpringBootApplication
    @EntityScan("com.crosscert.passkey.core.entity")
    @EnableJpaRepositories("com.crosscert.passkey.core.repository")
    static class TestApp {
    }

    private static final String SYS_PASSWORD = "app_owner_pw";

    @org.testcontainers.junit.jupiter.Container
    static final OracleContainer ORACLE =
            new OracleContainer("gvenzl/oracle-xe:21-slim-faststart")
                    .withUsername("PSK_APP_OWNER")
                    .withPassword(SYS_PASSWORD)
                    .withCopyFileToContainer(
                            MountableFile.forClasspathResource("test-db-init.sql"),
                            "/tmp/test-db-init.sql");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry reg) throws Exception {
        Container.ExecResult exec = ORACLE.execInContainer(
                "bash", "-c",
                "sqlplus -S sys/" + SYS_PASSWORD + "@localhost:1521/XEPDB1 as sysdba "
                        + "@/tmp/test-db-init.sql");
        if (exec.getExitCode() != 0) {
            throw new IllegalStateException(
                    "test-db-init.sql failed (exit=" + exec.getExitCode() + ")\n"
                            + "STDOUT:\n" + exec.getStdout() + "\n"
                            + "STDERR:\n" + exec.getStderr());
        }
        reg.add("spring.datasource.url", ORACLE::getJdbcUrl);
        reg.add("spring.datasource.username", () -> "PSK_APP_ADMIN_USER");
        reg.add("spring.datasource.password", () -> "admin_pw");
    }

    @Autowired CeremonyEventRecorder recorder;
    @Autowired CeremonyEventRepository repo;
    @Autowired PlatformTransactionManager txManager;

    private long count(UUID tenant, String action) {
        return repo.countByTenantIdAndActionAndCreatedAtAfter(tenant, action, OffsetDateTime.now().minusDays(1));
    }

    @Test
    void rollback_removesRow() {
        UUID tenant = UUID.randomUUID();
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            recorder.recordInTransaction(tenant, CeremonyAction.REGISTRATION_SUCCESS);
            status.setRollbackOnly();
        });
        assertThat(count(tenant, CeremonyAction.REGISTRATION_SUCCESS)).isZero();
    }

    @Test
    void commit_persistsRow() {
        UUID tenant = UUID.randomUUID();
        new TransactionTemplate(txManager).executeWithoutResult(status ->
                recorder.recordInTransaction(tenant, CeremonyAction.REGISTRATION_SUCCESS));
        assertThat(count(tenant, CeremonyAction.REGISTRATION_SUCCESS)).isEqualTo(1);
    }

    @Test
    void concurrentCommits_loseNoRows() throws Exception {
        UUID tenant = UUID.randomUUID();
        int threads = 8, each = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    TransactionTemplate tt = new TransactionTemplate(txManager);
                    for (int i = 0; i < each; i++) {
                        tt.executeWithoutResult(status ->
                                recorder.recordInTransaction(tenant, CeremonyAction.AUTHENTICATION_SUCCESS));
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdown();
        }
        assertThat(count(tenant, CeremonyAction.AUTHENTICATION_SUCCESS)).isEqualTo(threads * each);
    }

    @Test
    void outsideTransaction_throwsAndWritesNothing() {
        UUID tenant = UUID.randomUUID();
        assertThatThrownBy(() -> recorder.recordInTransaction(tenant, CeremonyAction.REGISTRATION_SUCCESS))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count(tenant, CeremonyAction.REGISTRATION_SUCCESS)).isZero();
    }
}
