package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.entity.AdminUserTenant;
import com.crosscert.passkey.core.entity.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * write-skew 방지 락의 실 Oracle 실증(zero-base G06·G08). 단위 테스트는 "락 메서드를 부른다" 까지만 보므로,
 * 두 트랜잭션이 동시에 가드를 통과해 불변식을 깨지 않는지 — 두 번째가 첫 번째 커밋까지 실제로 기다리는지 — 를
 * 런타임 유저(PSK_APP_ADMIN_USER, 운영과 같은 권한)로 확인한다. 가드 로직은 AdminUserService 와 같은 모양으로 재현한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class AdminLockWriteSkewIT {

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
                            MountableFile.forClasspathResource("bootstrap-schema.sql"),
                            "/tmp/bootstrap-schema.sql");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry reg) throws Exception {
        Container.ExecResult exec = ORACLE.execInContainer(
                "bash", "-c",
                "sqlplus -S sys/" + SYS_PASSWORD + "@localhost:1521/XEPDB1 as sysdba @/tmp/bootstrap-schema.sql");
        if (exec.getExitCode() != 0) {
            throw new IllegalStateException("bootstrap-schema.sql failed\n" + exec.getStdout() + "\n" + exec.getStderr());
        }
        reg.add("spring.datasource.url", ORACLE::getJdbcUrl);
        reg.add("spring.datasource.username", () -> "PSK_APP_ADMIN_USER");
        reg.add("spring.datasource.password", () -> "admin_pw");
        reg.add("spring.flyway.url", ORACLE::getJdbcUrl);
        reg.add("spring.flyway.user", () -> "PSK_APP_OWNER");
        reg.add("spring.flyway.password", () -> SYS_PASSWORD);
    }

    private static final long HOLD_MS = 800;

    @Autowired AdminUserRepository users;
    @Autowired AdminUserTenantRepository mappings;
    @Autowired TenantRepository tenants;
    @Autowired PlatformTransactionManager txm;

    @AfterEach
    void cleanup() throws Exception {
        mappings.deleteAll();
        // admin_user DELETE 는 런타임 롤에 없다 — OWNER 연결로 정리.
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(ORACLE.getJdbcUrl(), "PSK_APP_OWNER", SYS_PASSWORD);
             java.sql.Statement st = c.createStatement()) {
            st.executeUpdate("DELETE FROM PSK_APP_OWNER.admin_user");
        }
        tenants.deleteAll();
    }

    private AdminUser user(String email, String role) {
        AdminUser u = AdminUser.create();
        u.setEmail(email);
        u.setRole(role);
        u.setBcryptHash("$2a$10$placeholder");
        return users.save(u);
    }

    private UUID tenant(String slug) {
        Tenant t = new Tenant(slug, "Lock " + slug, "localhost", "Lock RP " + slug);
        t.addAllowedOrigin("http://localhost", 0);
        t.addAcceptedFormat("none");
        return tenants.save(t).getId();
    }

    /** 결과: true=가드 통과·변경 커밋, false=가드가 거부. 락 획득 시각(nanoTime)을 기록한다. */
    private record Outcome(boolean applied, long lockedAtNanos) {}

    @Test
    void lastPlatformOperatorGuardSerializesConcurrentSuspends() throws Exception {
        UUID a = user("lock-po-a@x.com", "PLATFORM_OPERATOR").getId();
        UUID b = user("lock-po-b@x.com", "PLATFORM_OPERATOR").getId();
        TransactionTemplate tx = new TransactionTemplate(txm);
        CountDownLatch firstLocked = new CountDownLatch(1);

        // T1: a 가 b 를 정지 — 락을 잡은 뒤 HOLD_MS 동안 붙잡고 커밋. T2: b 가 a 를 정지 — T1 락 이후 시도.
        CompletableFuture<Outcome> t1 = CompletableFuture.supplyAsync(() -> {
            Outcome o = tx.execute(s -> {
                int active = users.findActivePlatformOperatorsForUpdate().size();
                long locked = System.nanoTime();
                firstLocked.countDown();
                sleep(HOLD_MS);
                if (active <= 1) return new Outcome(false, locked);
                AdminUser target = users.findById(b).orElseThrow();
                target.setStatus("SUSPENDED");
                target.setEnabled(false);
                users.saveAndFlush(target);
                return new Outcome(true, locked);
            });
            return o;
        });
        CompletableFuture<Outcome> t2 = CompletableFuture.supplyAsync(() -> {
            await(firstLocked);
            return tx.execute(s -> {
                int active = users.findActivePlatformOperatorsForUpdate().size();
                long locked = System.nanoTime();
                if (active <= 1) return new Outcome(false, locked);
                AdminUser target = users.findById(a).orElseThrow();
                target.setStatus("SUSPENDED");
                target.setEnabled(false);
                users.saveAndFlush(target);
                return new Outcome(true, locked);
            });
        });

        Outcome o1 = t1.get(60, TimeUnit.SECONDS);
        Outcome o2 = t2.get(60, TimeUnit.SECONDS);
        assertThat(o1.applied()).isTrue();
        assertThat(o2.applied()).as("두 번째는 커밋된 상태(1명)를 보고 거부").isFalse();
        // 두 번째 락 획득은 첫 트랜잭션이 락을 쥔 HOLD_MS 이후 — 실제로 대기했다는 증거(스케줄링 지연에 둔감한 비교).
        assertThat(o2.lockedAtNanos() - o1.lockedAtNanos()).as("두 번째는 첫 락 보유 시간만큼 대기")
                .isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(HOLD_MS - 100));
        Integer remaining = new TransactionTemplate(txm).execute(s -> users.findActivePlatformOperatorsForUpdate().size());
        assertThat(remaining).isEqualTo(1);
    }

    @Test
    void lastTenantGuardSerializesConcurrentRemovals() throws Exception {
        UUID admin = user("lock-rp@x.com", "RP_ADMIN").getId();
        UUID t1 = tenant("lock-t1");
        UUID t2 = tenant("lock-t2");
        mappings.save(AdminUserTenant.of(admin, t1, "tester"));
        mappings.save(AdminUserTenant.of(admin, t2, "tester"));
        TransactionTemplate tx = new TransactionTemplate(txm);
        CountDownLatch firstLocked = new CountDownLatch(1);

        CompletableFuture<Outcome> r1 = CompletableFuture.supplyAsync(() -> {
            Outcome o = tx.execute(s -> {
                int n = mappings.findByAdminUserIdForUpdate(admin).size();
                long locked = System.nanoTime();
                firstLocked.countDown();
                sleep(HOLD_MS);
                if (n <= 1) return new Outcome(false, locked);
                mappings.deleteByAdminUserIdAndTenantId(admin, t1);
                return new Outcome(true, locked);
            });
            return o;
        });
        CompletableFuture<Outcome> r2 = CompletableFuture.supplyAsync(() -> {
            await(firstLocked);
            return tx.execute(s -> {
                int n = mappings.findByAdminUserIdForUpdate(admin).size();
                long locked = System.nanoTime();
                if (n <= 1) return new Outcome(false, locked);
                mappings.deleteByAdminUserIdAndTenantId(admin, t2);
                return new Outcome(true, locked);
            });
        });

        Outcome o1 = r1.get(60, TimeUnit.SECONDS);
        Outcome o2 = r2.get(60, TimeUnit.SECONDS);
        assertThat(o1.applied()).isTrue();
        assertThat(o2.applied()).as("두 번째는 남은 1개를 보고 거부").isFalse();
        // 두 번째 락 획득은 첫 트랜잭션이 락을 쥔 HOLD_MS 이후 — 실제로 대기했다는 증거(스케줄링 지연에 둔감한 비교).
        assertThat(o2.lockedAtNanos() - o1.lockedAtNanos()).as("두 번째는 첫 락 보유 시간만큼 대기")
                .isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(HOLD_MS - 100));
        assertThat(mappings.findTenantIdsByAdminUserId(admin)).containsExactly(t2);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }

    private static void await(CountDownLatch l) {
        try {
            if (!l.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("first tx never locked");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
}
