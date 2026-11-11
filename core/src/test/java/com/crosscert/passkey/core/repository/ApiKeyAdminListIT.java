package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.ApiKey;
import com.crosscert.passkey.core.entity.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ApiKeyRepository#findAllForAdminList()}·{@link ApiKeyRepository#findAllForAdminListByTenantId} — 관리 콘솔 키 목록.
 * 최신 발급순(createdAt DESC, id DESC), 테넌트 필터, scopes 를 같은 쿼리로 읽어(트랜잭션 밖 접근에도 lazy 예외 없음)
 * 키가 scope 수만큼 중복되지 않는지 실 Oracle 에서 고정한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class ApiKeyAdminListIT {

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

    @Autowired ApiKeyRepository keys;
    @Autowired TenantRepository tenants;

    @AfterEach
    void cleanup() {
        keys.deleteAll();
        tenants.deleteAll();
    }

    private UUID tenant(String slug) {
        Tenant t = new Tenant(slug, "Key List " + slug, "localhost", "Key List RP " + slug);
        t.addAllowedOrigin("http://localhost", 0);
        t.addAcceptedFormat("none");
        return tenants.save(t).getId();
    }

    private ApiKey save(UUID tid, String prefix, String... scopes) throws InterruptedException {
        ApiKey k = new ApiKey(tid, prefix, "hash-" + prefix, "name-" + prefix);
        for (String s : scopes) k.addScope(s);
        ApiKey saved = keys.saveAndFlush(k);
        Thread.sleep(15); // createdAt(@PrePersist now) 을 벌려 정렬을 관측 가능하게
        return saved;
    }

    @Test
    void listsNewestFirstWithScopesLoadedAndNoDuplicates() throws Exception {
        UUID a = tenant("keys-a");
        UUID b = tenant("keys-b");
        save(a, "pk_aaaa0001", "registration", "authentication");
        save(b, "pk_bbbb0001", "registration");
        save(a, "pk_aaaa0002", "registration", "authentication", "admin");
        save(a, "pk_aaaa0003");

        List<ApiKey> all = keys.findAllForAdminList(); // 트랜잭션 밖 — scopes 가 이미 로드돼 있어야 한다
        assertThat(all).extracting(ApiKey::getKeyPrefix)
                .as("최신 발급순 · scope 수만큼 중복 없음")
                .containsExactly("pk_aaaa0003", "pk_aaaa0002", "pk_bbbb0001", "pk_aaaa0001");
        assertThat(all).isSortedAccordingTo(Comparator.comparing(ApiKey::getCreatedAt).reversed());
        assertThat(all.get(1).getScopeValues()).containsExactlyInAnyOrder("registration", "authentication", "admin");
        assertThat(all.get(0).getScopeValues()).isEmpty();

        List<ApiKey> onlyA = keys.findAllForAdminListByTenantId(a);
        assertThat(onlyA).extracting(ApiKey::getKeyPrefix).containsExactly("pk_aaaa0003", "pk_aaaa0002", "pk_aaaa0001");
        assertThat(onlyA.get(2).getScopeValues()).containsExactlyInAnyOrder("registration", "authentication");
        assertThat(keys.findAllForAdminListByTenantId(UUID.randomUUID())).isEmpty();
    }
}
