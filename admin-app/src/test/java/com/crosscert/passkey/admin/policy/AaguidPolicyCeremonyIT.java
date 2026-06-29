package com.crosscert.passkey.admin.policy;

import com.crosscert.passkey.admin.auth.AdminUserDetails;
import com.crosscert.passkey.admin.tenant.TenantAdminDto;
import com.crosscert.passkey.admin.tenant.TenantAdminService;
import com.crosscert.passkey.core.entity.TenantAaguidPolicy;
import com.crosscert.passkey.core.policy.AaguidPolicyViolationException;
import com.crosscert.passkey.core.policy.DefaultAaguidPolicyChecker;
import com.crosscert.passkey.core.repository.TenantAaguidPolicyRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase C Task 6 — AAGUID 정책 분기 통합 테스트.
 *
 * <p>시나리오:
 * <ol>
 *   <li>Tenant 생성 → mode=ANY 정책 자동 생성 검증</li>
 *   <li>ALLOWLIST 설정 → aaguidA 통과, aaguidB 차단</li>
 *   <li>DENYLIST 설정 → aaguidA 차단, aaguidB 통과</li>
 * </ol>
 *
 * <p>{@link DefaultAaguidPolicyChecker} 를 직접 호출(서비스 레이어 bypass 없이)하여
 * 정책 분기 로직을 DB 연동 상태에서 검증한다. HTTP 레이어 없이 core 단위 통합 테스트.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class AaguidPolicyCeremonyIT {

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
                    MountableFile.forClasspathResource("bootstrap-schema.sql"),
                    "/tmp/bootstrap-schema.sql");

    @org.testcontainers.junit.jupiter.Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

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
        reg.add("spring.data.redis.host", REDIS::getHost);
        reg.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    // ----------------------------------------------------------------
    // Wiring
    // ----------------------------------------------------------------

    @Autowired TenantAdminService tenantAdminService;
    @Autowired AaguidPolicyService aaguidPolicyService;
    @Autowired DefaultAaguidPolicyChecker policyChecker;
    @Autowired TenantAaguidPolicyRepository policyRepo;
    @Autowired DataSource ds;
    @Autowired RedisConnectionFactory redisFactory;

    JdbcTemplate jdbc;

    private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String ACTOR_EMAIL = "alice@crosscert.com";

    // Two test AAGUIDs
    private static final UUID AAGUID_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID AAGUID_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    /** APP_OWNER (schema owner) pool — used only for owner-only table cleanup in resetState(). */
    private static HikariDataSource ownerPool;

    @AfterAll
    static void closeOwnerPool() {
        if (ownerPool != null) {
            ownerPool.close();
            ownerPool = null;
        }
    }

    private static synchronized JdbcTemplate ownerJdbc() {
        if (ownerPool == null) {
            HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl(ORACLE.getJdbcUrl());
            ds.setUsername("APP_OWNER");
            ds.setPassword(SYS_PASSWORD);
            ds.setMaximumPoolSize(2);
            ds.setPoolName("aaguid-policy-it-owner");
            ownerPool = ds;
        }
        return new JdbcTemplate(ownerPool);
    }

    // ----------------------------------------------------------------
    // State reset — FK-safe DELETE + Redis flush
    // ----------------------------------------------------------------

    @BeforeEach
    void resetState() {
        jdbc = new JdbcTemplate(ds);

        // Delete in FK-safe order — new Phase C tables first
        jdbc.update("DELETE FROM APP_OWNER.tenant_aaguid_policy_entry");
        jdbc.update("DELETE FROM APP_OWNER.tenant_aaguid_policy");
        // tenant_webauthn_snapshot: APP_ADMIN has SELECT+INSERT only (V27) — use schema-owner pool.
        ownerJdbc().update("DELETE FROM APP_OWNER.tenant_webauthn_snapshot");
        // audit_log: APP_ADMIN has SELECT+INSERT only (V10 design) — use schema-owner pool.
        ownerJdbc().update("DELETE FROM APP_OWNER.audit_log");
        jdbc.update("DELETE FROM APP_OWNER.api_key_scope");
        jdbc.update("DELETE FROM APP_OWNER.api_key");
        jdbc.update("DELETE FROM APP_OWNER.credential");
        jdbc.update("DELETE FROM APP_OWNER.tenant_allowed_origin");
        jdbc.update("DELETE FROM APP_OWNER.tenant_accepted_format");
        jdbc.update("DELETE FROM APP_OWNER.admin_user_tenant");
        jdbc.update("UPDATE APP_OWNER.admin_user SET role = 'PLATFORM_OPERATOR' WHERE role = 'RP_ADMIN'");
        jdbc.update("DELETE FROM APP_OWNER.tenant");

        // Inject PLATFORM_OPERATOR into SecurityContext so TenantBoundary passes
        AdminUserDetails operator = new AdminUserDetails(
                ACTOR_ID, ACTOR_EMAIL, "{noop}unused",
                "PLATFORM_OPERATOR", null, true, null, java.time.Clock.systemUTC());
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
    void aaguidPolicyCeremony_anyAllowlistDenylist() {
        // ── 1. Tenant 생성 → mode=ANY 정책 자동 생성 ──────────────────
        TenantAdminDto.TenantCreateRequest createReq = new TenantAdminDto.TenantCreateRequest(
                "policy-it", "Policy IT Tenant", "localhost", "Policy IT",
                List.of("http://localhost:9090"),
                Set.of("none"),
                true, false,
                "NONE", 60000);
        TenantAdminDto.TenantView tenant = tenantAdminService.create(createReq, ACTOR_ID, ACTOR_EMAIL);
        UUID tenantId = tenant.id();

        // policy row 자동 생성 검증
        TenantAaguidPolicy policy = policyRepo.findById(tenantId)
                .orElseThrow(() -> new AssertionError("aaguid policy row not found after tenant create"));
        assertThat(policy.getMode())
                .as("초기 mode 는 ANY 여야 한다")
                .isEqualTo(TenantAaguidPolicy.Mode.ANY);
        assertThat(policy.isMdsStrict())
                .as("초기 mdsStrict 는 false 여야 한다")
                .isFalse();

        // ── 2. ALLOWLIST 설정: entries=[aaguidA] ──────────────────────
        AaguidPolicyDto.UpdateRequest allowlistReq = new AaguidPolicyDto.UpdateRequest(
                TenantAaguidPolicy.Mode.ALLOWLIST,
                false,
                List.of(new AaguidPolicyDto.EntryInput(AAGUID_A, "test-aaguid-a")));
        aaguidPolicyService.update(tenantId, allowlistReq, ACTOR_EMAIL);

        // ── 3. check(aaguidA) → 통과 (예외 없음) ─────────────────────
        assertThatCode(() -> policyChecker.check(tenantId, AAGUID_A))
                .as("ALLOWLIST: aaguidA 는 허용 목록에 있으므로 예외 없어야 한다")
                .doesNotThrowAnyException();

        // ── 4. check(aaguidB) → AaguidPolicyViolationException ───────
        assertThatThrownBy(() -> policyChecker.check(tenantId, AAGUID_B))
                .as("ALLOWLIST: aaguidB 는 허용 목록에 없으므로 예외 발생해야 한다")
                .isInstanceOf(AaguidPolicyViolationException.class);

        // ── 5. DENYLIST 설정: entries=[aaguidA] ───────────────────────
        AaguidPolicyDto.UpdateRequest denylistReq = new AaguidPolicyDto.UpdateRequest(
                TenantAaguidPolicy.Mode.DENYLIST,
                false,
                List.of(new AaguidPolicyDto.EntryInput(AAGUID_A, "deny-aaguid-a")));
        aaguidPolicyService.update(tenantId, denylistReq, ACTOR_EMAIL);

        // ── 6. check(aaguidA) → 예외 (DENIED) ────────────────────────
        assertThatThrownBy(() -> policyChecker.check(tenantId, AAGUID_A))
                .as("DENYLIST: aaguidA 는 차단 목록에 있으므로 예외 발생해야 한다")
                .isInstanceOf(AaguidPolicyViolationException.class);

        // ── 7. check(aaguidB) → 통과 ─────────────────────────────────
        assertThatCode(() -> policyChecker.check(tenantId, AAGUID_B))
                .as("DENYLIST: aaguidB 는 차단 목록에 없으므로 예외 없어야 한다")
                .doesNotThrowAnyException();
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    /** Convert UUID to 32-char hex (no hyphens) for Oracle HEXTORAW binding. */
    @SuppressWarnings("unused")
    private static String uuidToHex(UUID uuid) {
        return uuid.toString().replace("-", "").toUpperCase();
    }
}
