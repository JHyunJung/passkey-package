package com.crosscert.passkey.admin.operator;

import com.crosscert.passkey.admin.auth.AdminUserDetails;
import com.crosscert.passkey.admin.tenant.TenantAdminDto;
import com.crosscert.passkey.admin.tenant.TenantAdminService;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
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
 * Phase D Task 6 — Admin user invite → accept → login 풀 플로우 IT.
 *
 * <p>시나리오:
 * <ol>
 *   <li>PLATFORM_OPERATOR(alice)가 RP_ADMIN 초대 — tenant 1개에 묶기</li>
 *   <li>InvitationService.check(plaintextToken) → email/role/tenantId 미리보기 (예외 없음)</li>
 *   <li>InvitationService.accept(plaintextToken, password) → user ACTIVE + bcryptHash 설정</li>
 *   <li>AdminUserRepository 에서 확인 — status=ACTIVE, bcryptHash 비어있지 않음</li>
 *   <li>(lockout) 자기 자신 suspend 시도 → IllegalStateException</li>
 *   <li>(lockout) 마지막 ACTIVE PO suspend 시도 → IllegalStateException</li>
 * </ol>
 *
 * <p>서비스 레이어 직접 호출 — HTTP/MockMvc 없이 Oracle TestContainers + Redis 통합 테스트.
 * AaguidPolicyCeremonyIT / WebauthnConfigSnapshotIT 와 동일한 패턴.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class AdminUserInvitationFlowIT {

    // ----------------------------------------------------------------
    // Containers (identical pattern to AaguidPolicyCeremonyIT)
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

    @Autowired AdminUserService adminUserService;
    @Autowired InvitationService invitationService;
    @Autowired AdminUserRepository adminUserRepo;
    @Autowired TenantAdminService tenantAdminService;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired DataSource ds;
    @Autowired RedisConnectionFactory redisFactory;

    JdbcTemplate jdbc;

    // V19 seeded alice UUID: HEXTORAW('00000000000000000000000000000010')
    private static final UUID ALICE_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final String ALICE_EMAIL = "alice@crosscert.com";

    // Fake actor for SecurityContext (PO, not bound to a real DB row — same pattern as other ITs)
    private static final UUID SECURITY_CTX_ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    // ----------------------------------------------------------------
    // State reset — FK-safe DELETE + Redis flush
    // ----------------------------------------------------------------

    @BeforeEach
    void resetState() {
        jdbc = new JdbcTemplate(ds);

        // Delete in FK-safe order — Phase D tables first, then existing Phase C/B
        jdbc.update("DELETE FROM APP_OWNER.admin_user_invitation");
        jdbc.update("DELETE FROM APP_OWNER.tenant_aaguid_policy_entry");
        jdbc.update("DELETE FROM APP_OWNER.tenant_aaguid_policy");
        jdbc.update("DELETE FROM APP_OWNER.tenant_webauthn_snapshot");
        jdbc.update("DELETE FROM APP_OWNER.audit_log");
        jdbc.update("DELETE FROM APP_OWNER.api_key_scope");
        jdbc.update("DELETE FROM APP_OWNER.api_key");
        jdbc.update("DELETE FROM APP_OWNER.credential");
        jdbc.update("DELETE FROM APP_OWNER.tenant_allowed_origin");
        jdbc.update("DELETE FROM APP_OWNER.tenant_accepted_format");
        // Remove any non-seed admin_user rows (PENDING/SUSPENDED created during tests)
        // then reset seed users to PLATFORM_OPERATOR / ACTIVE
        jdbc.update("DELETE FROM APP_OWNER.admin_user WHERE email NOT IN ('alice@crosscert.com','bob@crosscert.com')");
        jdbc.update("UPDATE APP_OWNER.admin_user SET tenant_id = NULL, role = 'PLATFORM_OPERATOR', "
                + "status = 'ACTIVE', suspended_at = NULL, suspended_by = NULL "
                + "WHERE email IN ('alice@crosscert.com','bob@crosscert.com')");
        jdbc.update("DELETE FROM APP_OWNER.tenant");

        // Inject PLATFORM_OPERATOR into SecurityContext so TenantBoundary passes
        AdminUserDetails operator = new AdminUserDetails(
                SECURITY_CTX_ACTOR_ID, ALICE_EMAIL, "{noop}unused",
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
    void inviteAcceptFlow_rpAdmin_fullLifecycle() {
        // ── 1. Tenant 생성 (RP_ADMIN 을 묶을 테넌트) ─────────────────
        TenantAdminDto.TenantCreateRequest createReq = new TenantAdminDto.TenantCreateRequest(
                "invite-it", "Invite IT Tenant", "localhost", "Invite IT RP",
                List.of("http://localhost:9090"),
                Set.of("none"),
                true, false,
                "NONE", 60000);
        TenantAdminDto.TenantView tenant = tenantAdminService.create(createReq, ALICE_ID, ALICE_EMAIL);
        UUID tenantId = tenant.id();

        // ── 2. 초대 생성 — RP_ADMIN 로 tenant 에 묶기 ─────────────────
        AdminUserDto.InviteRequest inviteReq = new AdminUserDto.InviteRequest(
                "newadmin@example.com", "RP_ADMIN", tenantId);
        AdminUserDto.InviteResponse inviteResponse = adminUserService.invite(inviteReq, ALICE_EMAIL);

        assertThat(inviteResponse).isNotNull();
        assertThat(inviteResponse.user().email()).isEqualTo("newadmin@example.com");
        assertThat(inviteResponse.user().status()).isEqualTo("PENDING");
        assertThat(inviteResponse.user().role()).isEqualTo("RP_ADMIN");
        assertThat(inviteResponse.user().tenantId()).isEqualTo(tenantId);
        assertThat(inviteResponse.invitation()).isNotNull();

        String plaintextToken = inviteResponse.invitation().plaintextToken();
        String acceptUrl     = inviteResponse.invitation().acceptUrl();
        assertThat(plaintextToken).startsWith("inv_");
        assertThat(acceptUrl).contains(plaintextToken);

        UUID invitedUserId = inviteResponse.user().id();

        // ── 3. check(plaintextToken) → email/role/tenantId 미리보기 ──
        assertThatCode(() -> {
            AdminUserDto.InvitationCheck check = invitationService.check(plaintextToken);
            assertThat(check.email()).isEqualTo("newadmin@example.com");
            assertThat(check.role()).isEqualTo("RP_ADMIN");
            assertThat(check.tenantId()).isEqualTo(tenantId);
        }).doesNotThrowAnyException();

        // ── 4. accept(plaintextToken, password) ───────────────────────
        String password = "securePassword123!";
        invitationService.accept(plaintextToken, password);

        // ── 5. AdminUserRepository 검증 ───────────────────────────────
        var user = adminUserRepo.findById(invitedUserId)
                .orElseThrow(() -> new AssertionError("invited user not found in DB"));

        assertThat(user.getStatus())
                .as("accept 후 status 는 ACTIVE 여야 한다")
                .isEqualTo("ACTIVE");
        assertThat(user.getBcryptHash())
                .as("accept 후 bcryptHash 가 설정되어야 한다")
                .isNotNull()
                .isNotBlank();
        assertThat(passwordEncoder.matches(password, user.getBcryptHash()))
                .as("bcryptHash 가 전달한 password 와 일치해야 한다")
                .isTrue();

        // ── 6. 이미 사용된 token 재사용 → IllegalStateException ──────
        assertThatThrownBy(() -> invitationService.accept(plaintextToken, "anotherPass123!"))
                .as("이미 수락된 token 은 재사용 불가")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already used");
    }

    @Test
    void suspend_selfSuspend_throwsIllegalState() {
        // alice 가 자기 자신을 suspend 하려는 시도
        // assertNotLockingOut: user.getEmail().equals(byUser) → throws
        assertThatThrownBy(() -> adminUserService.suspend(ALICE_ID, ALICE_EMAIL))
                .as("자기 자신 suspend 시도는 IllegalStateException 이어야 한다")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("yourself");
    }

    @Test
    void suspend_lastActivePlatformOperator_throwsIllegalState() {
        // 이 시점에서 alice 와 bob 은 모두 PLATFORM_OPERATOR / ACTIVE.
        // bob 이 alice 를 suspend 할 때 → 2명이므로 통과.
        // 이후 bob 을 SUSPENDED 로 만들어 alice 만 남은 상태를 만든 다음,
        // alice 가 alice 를 suspend 하면 "self" 에 걸리므로,
        // 제3의 actor(charlie) 가 alice 를 suspend 시도 → lastActivePO 체크.

        // bob 의 DB UUID (V19 seed: 0x...0011)
        UUID bobId = UUID.fromString("00000000-0000-0000-0000-000000000011");
        String bobEmail = "bob@crosscert.com";

        // bob 을 먼저 suspend (alice 가, 2명이므로 통과해야 함)
        assertThatCode(() -> adminUserService.suspend(bobId, ALICE_EMAIL))
                .as("alice 가 bob 을 suspend 하는 것은 2명 중 1명이므로 통과해야 한다")
                .doesNotThrowAnyException();

        // 이제 alice 만 ACTIVE PO — 가상 actor charlie 가 alice 를 suspend 시도
        assertThatThrownBy(() -> adminUserService.suspend(ALICE_ID, "charlie@example.com"))
                .as("마지막 ACTIVE PLATFORM_OPERATOR 를 suspend 하면 IllegalStateException 이어야 한다")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("last active PLATFORM_OPERATOR");
    }
}
