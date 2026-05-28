package com.crosscert.passkey.admin.tenant;

import com.crosscert.passkey.admin.auth.AdminUserDetails;
import com.crosscert.passkey.core.entity.TenantWebauthnSnapshot;
import com.crosscert.passkey.core.repository.TenantWebauthnSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import javax.sql.DataSource;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase C Task 6 — WebAuthn config 자동 snapshot 통합 테스트.
 *
 * <p>시나리오:
 * <ol>
 *   <li>Tenant 생성 → 초기 snapshot 1건 자동 생성 검증 (rpId = 생성 시 rpId)</li>
 *   <li>TenantAdminService.update() 호출 → snapshot 2건 (변경 직전 보존)</li>
 *   <li>최신 snapshot 의 rpId == 생성 시 rpId (update 전 값 보존 확인)</li>
 * </ol>
 *
 * <p>TenantAdminService 를 직접 호출하여 HTTP 레이어 없이 검증한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class WebauthnConfigSnapshotIT {

    // ----------------------------------------------------------------
    // Containers (identical pattern to AuditChainPerTenantIT)
    // ----------------------------------------------------------------

    private static final String ORACLE_IMAGE = "gvenzl/oracle-xe:21-slim-faststart";
    private static final String SYS_PASSWORD = "app_owner_pw";

    @org.testcontainers.junit.jupiter.Container
    static final OracleContainer ORACLE = new OracleContainer(ORACLE_IMAGE)
            .withUsername("APP_OWNER")
            .withPassword(SYS_PASSWORD)
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("bootstrap-vpd.sql"),
                    "/tmp/bootstrap-vpd.sql");

    @org.testcontainers.junit.jupiter.Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

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
        reg.add("spring.data.redis.host", REDIS::getHost);
        reg.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    // ----------------------------------------------------------------
    // Wiring
    // ----------------------------------------------------------------

    @Autowired TenantAdminService tenantAdminService;
    @Autowired TenantWebauthnSnapshotRepository snapshotRepo;
    @Autowired DataSource ds;
    @Autowired RedisConnectionFactory redisFactory;

    JdbcTemplate jdbc;

    private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String ACTOR_EMAIL = "alice@crosscert.com";

    // ----------------------------------------------------------------
    // State reset — FK-safe DELETE + Redis flush
    // ----------------------------------------------------------------

    @BeforeEach
    void resetState() {
        jdbc = new JdbcTemplate(ds);

        // Delete in FK-safe order — new Phase C tables first
        jdbc.update("DELETE FROM APP_OWNER.tenant_aaguid_policy_entry");
        jdbc.update("DELETE FROM APP_OWNER.tenant_aaguid_policy");
        jdbc.update("DELETE FROM APP_OWNER.tenant_webauthn_snapshot");
        jdbc.update("DELETE FROM APP_OWNER.audit_log");
        jdbc.update("DELETE FROM APP_OWNER.api_key_scope");
        jdbc.update("DELETE FROM APP_OWNER.api_key");
        jdbc.update("DELETE FROM APP_OWNER.credential");
        jdbc.update("DELETE FROM APP_OWNER.tenant_allowed_origin");
        jdbc.update("DELETE FROM APP_OWNER.tenant_accepted_format");
        jdbc.update("UPDATE APP_OWNER.admin_user SET tenant_id = NULL, role = 'PLATFORM_OPERATOR' WHERE tenant_id IS NOT NULL");
        jdbc.update("DELETE FROM APP_OWNER.tenant");

        // Inject PLATFORM_OPERATOR into SecurityContext so TenantBoundary passes
        AdminUserDetails operator = new AdminUserDetails(
                ACTOR_ID, ACTOR_EMAIL, "{noop}unused",
                "PLATFORM_OPERATOR", null, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(operator, null, operator.getAuthorities()));

        // Flush Redis
        var conn = redisFactory.getConnection();
        try {
            conn.serverCommands().flushAll();
        } finally {
            conn.close();
        }
    }

    // ----------------------------------------------------------------
    // Tests
    // ----------------------------------------------------------------

    @Test
    void snapshotIsCreatedOnTenantCreate_andPreservedOnUpdate() {
        // ── 1. Tenant 생성 → 초기 snapshot 1건 자동 생성 ─────────────
        String initialRpId = "initial.example.com";
        TenantAdminDto.TenantCreateRequest createReq = new TenantAdminDto.TenantCreateRequest(
                "snap-it", "Snapshot IT Tenant", initialRpId, "Snapshot IT RP",
                List.of("http://localhost:9090"),
                Set.of("none"),
                true, false);
        TenantAdminDto.TenantView tenant = tenantAdminService.create(createReq, ACTOR_ID, ACTOR_EMAIL);
        UUID tenantId = tenant.id();

        List<TenantWebauthnSnapshot> snapshotsAfterCreate =
                snapshotRepo.findByTenantIdOrderByTakenAtDesc(tenantId);
        assertThat(snapshotsAfterCreate)
                .as("tenant 생성 후 snapshot 1건이 자동 생성되어야 한다")
                .hasSize(1);
        assertThat(snapshotsAfterCreate.get(0).getRpId())
                .as("초기 snapshot 의 rpId 는 생성 시 rpId 여야 한다")
                .isEqualTo(initialRpId);

        // ── 2. TenantAdminService.update() 호출 ───────────────────────
        // 주의: update() 는 rpId 변경을 silent ignore 하므로 (spec § 6.1),
        //       displayName 을 변경해서 실제 변경이 일어나도록 한다.
        // snapshot 은 "변경 직전" 값으로 INSERT 되므로 initialRpId 가 보존되어야 함.
        TenantAdminDto.TenantUpdateRequest updateReq = new TenantAdminDto.TenantUpdateRequest(
                "Updated Snapshot IT Tenant",   // displayName 변경
                initialRpId,                    // rpId (ignored by service, but must be non-null)
                "Snapshot IT RP Updated",       // rpName 변경
                List.of("http://localhost:9090"),
                Set.of("none"),
                true, false);
        tenantAdminService.update("snap-it", updateReq, ACTOR_ID, ACTOR_EMAIL);

        // ── 3. snapshot 2건 검증 ──────────────────────────────────────
        List<TenantWebauthnSnapshot> snapshotsAfterUpdate =
                snapshotRepo.findByTenantIdOrderByTakenAtDesc(tenantId);
        assertThat(snapshotsAfterUpdate)
                .as("update 후 snapshot 은 총 2건이어야 한다 (create 1 + update 직전 1)")
                .hasSize(2);

        // ── 4. 가장 최근 snapshot (taken_at DESC 첫 번째) 의 rpId 검증 ──
        // update() 가 "변경 직전" 값으로 INSERT 하므로 최신 snapshot = update 직전 상태 = initialRpId
        TenantWebauthnSnapshot latestSnapshot = snapshotsAfterUpdate.get(0);
        assertThat(latestSnapshot.getRpId())
                .as("update 후 가장 최근 snapshot 의 rpId 는 변경 전 값(initialRpId)을 보존해야 한다")
                .isEqualTo(initialRpId);
    }
}
