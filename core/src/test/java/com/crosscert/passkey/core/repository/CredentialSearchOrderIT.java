package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.Credential;
import com.crosscert.passkey.core.entity.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CredentialRepository#searchByTenantId} 정렬 — 검색 결과는 일반 목록({@code findAllByTenantId})과 같은
 * {@code lastUsedAt DESC NULLS LAST, id DESC} 여야 하고, ORDER BY 없는 OFFSET/FETCH 처럼 페이지 경계에서 행이 겹치거나
 * 빠지면 안 된다. native 쿼리라 Pageable Sort 가 SQL 로 전달되지 않으므로 쿼리 본문의 ORDER BY 가 유일한 근거다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class CredentialSearchOrderIT {

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

    @Autowired CredentialRepository credentials;
    @Autowired TenantRepository tenants;

    @AfterEach
    void cleanup() {
        credentials.deleteAll();
        tenants.deleteAll();
    }

    @Test
    void searchOrdersLikeListAndPagesWithoutOverlap() {
        Tenant t = new Tenant("search-order", "Search Order", "localhost", "Search Order RP");
        t.addAllowedOrigin("http://localhost", 0);
        t.addAcceptedFormat("none");
        UUID tid = tenants.save(t).getId();

        OffsetDateTime base = OffsetDateTime.of(2026, 11, 1, 0, 0, 0, 0, ZoneOffset.ofHours(9));
        // 일부는 lastUsedAt 이 같고(동률 → id DESC), 일부는 null(NULLS LAST). credentialId 는 모두 0xAB 로 시작 → hex "ab".
        Integer[] minutes = {5, null, 30, 5, null, 10, 30};
        for (int i = 0; i < minutes.length; i++) {
            Credential c = new Credential(tid, ("uh-" + i).getBytes(), new byte[]{(byte) 0xAB, (byte) i},
                    ("pk-" + i).getBytes(), null);
            if (minutes[i] != null) {
                c.recordAuthentication(1, base.plusMinutes(minutes[i]));
            }
            credentials.save(c);
        }
        credentials.flush();

        List<UUID> expected = credentials.findAllByTenantId(tid, PageRequest.of(0, 100))
                .map(Credential::getId).getContent();
        assertThat(expected).hasSize(minutes.length);

        List<UUID> searchedAll = credentials.searchByTenantId(tid, "ab", PageRequest.of(0, 100))
                .map(Credential::getId).getContent();
        assertThat(searchedAll).as("검색 결과 순서 = 일반 목록 순서").containsExactlyElementsOf(expected);

        List<UUID> paged = new ArrayList<>();
        for (int p = 0; p < 4; p++) {
            Page<Credential> page = credentials.searchByTenantId(tid, "ab", PageRequest.of(p, 2));
            assertThat(page.getTotalElements()).isEqualTo(minutes.length);
            page.forEach(c -> paged.add(c.getId()));
        }
        assertThat(paged).as("페이지 경계 겹침·누락 없음").containsExactlyElementsOf(expected);
    }
}
