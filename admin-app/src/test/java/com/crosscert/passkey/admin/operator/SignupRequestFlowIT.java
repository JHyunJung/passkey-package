package com.crosscert.passkey.admin.operator;

import com.crosscert.passkey.admin.AdminApplication;
import com.crosscert.passkey.admin.auth.AdminUserDetails;
import com.crosscert.passkey.admin.tenant.TenantAdminDto;
import com.crosscert.passkey.admin.tenant.TenantAdminService;
import com.crosscert.passkey.core.repository.AdminSignupRequestRepository;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 가입 요청 → 목록 → 승인 → 로그인 가능 상태 풀 플로우 IT (Oracle Testcontainers + Redis).
 * V5 마이그레이션(테이블 생성·GRANT·초대 테이블 DROP)이 실제로 적용되는지도 여기서 확인된다.
 * 서비스 레이어 직접 호출 — 삭제된 AdminUserInvitationFlowIT 와 같은 패턴.
 */
// operator 패키지에 슬라이스용 중첩 @SpringBootConfiguration 이 있어 부트 클래스를 명시한다.
@SpringBootTest(classes = AdminApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class SignupRequestFlowIT {

    private static final String ORACLE_IMAGE = "gvenzl/oracle-xe:21-slim-faststart";
    private static final String SYS_PASSWORD = "app_owner_pw";

    @org.testcontainers.junit.jupiter.Container
    static final OracleContainer ORACLE = new OracleContainer(ORACLE_IMAGE)
            .withUsername("PSK_APP_OWNER")
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
            throw new IllegalStateException("bootstrap-schema.sql failed (exit=" + exec.getExitCode() + ")\n"
                    + "STDOUT:\n" + exec.getStdout() + "\nSTDERR:\n" + exec.getStderr());
        }
        reg.add("spring.datasource.url", ORACLE::getJdbcUrl);
        reg.add("spring.datasource.username", () -> "PSK_APP_ADMIN_USER");
        reg.add("spring.datasource.password", () -> "admin_pw");
        reg.add("spring.data.redis.host", REDIS::getHost);
        reg.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired SignupRequestService signupService;
    @Autowired AdminSignupRequestRepository signupRepo;
    @Autowired AdminUserRepository adminUserRepo;
    @Autowired TenantAdminService tenantAdminService;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired DataSource ds;
    @Autowired RedisConnectionFactory redisFactory;

    JdbcTemplate jdbc;

    private static final UUID ALICE_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final String ALICE_EMAIL = "alice@crosscert.com";
    private static final UUID SECURITY_CTX_ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static HikariDataSource ownerPool;

    @AfterAll
    static void closeOwnerPool() {
        if (ownerPool != null) { ownerPool.close(); ownerPool = null; }
    }

    private static synchronized JdbcTemplate ownerJdbc() {
        if (ownerPool == null) {
            HikariDataSource p = new HikariDataSource();
            p.setJdbcUrl(ORACLE.getJdbcUrl());
            p.setUsername("PSK_APP_OWNER");
            p.setPassword(SYS_PASSWORD);
            p.setMaximumPoolSize(2);
            p.setPoolName("signup-flow-it-owner");
            ownerPool = p;
        }
        return new JdbcTemplate(ownerPool);
    }

    @BeforeEach
    void resetState() {
        jdbc = new JdbcTemplate(ds);
        jdbc.update("DELETE FROM PSK_APP_OWNER.admin_signup_request");
        jdbc.update("DELETE FROM PSK_APP_OWNER.tenant_aaguid_policy_entry");
        jdbc.update("DELETE FROM PSK_APP_OWNER.tenant_aaguid_policy");
        ownerJdbc().update("DELETE FROM PSK_APP_OWNER.tenant_webauthn_snapshot");
        ownerJdbc().update("DELETE FROM PSK_APP_OWNER.audit_log");
        jdbc.update("DELETE FROM PSK_APP_OWNER.api_key_scope");
        jdbc.update("DELETE FROM PSK_APP_OWNER.api_key");
        jdbc.update("DELETE FROM PSK_APP_OWNER.credential");
        jdbc.update("DELETE FROM PSK_APP_OWNER.tenant_allowed_origin");
        jdbc.update("DELETE FROM PSK_APP_OWNER.tenant_accepted_format");
        jdbc.update("DELETE FROM PSK_APP_OWNER.admin_user_tenant");
        ownerJdbc().update("DELETE FROM PSK_APP_OWNER.admin_user WHERE email NOT IN ('alice@crosscert.com','bob@crosscert.com')");
        jdbc.update("DELETE FROM PSK_APP_OWNER.tenant");

        AdminUserDetails operator = new AdminUserDetails(
                SECURITY_CTX_ACTOR_ID, ALICE_EMAIL, "{noop}unused",
                "PLATFORM_OPERATOR", Set.of(), true, null, java.time.Clock.systemUTC());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(operator, null, operator.getAuthorities()));

        var conn = redisFactory.getConnection();
        try { conn.serverCommands().flushAll(); } finally { conn.close(); }
    }

    @Test
    void requestListApproveFlow_rpAdmin() {
        TenantAdminDto.TenantCreateRequest createReq = new TenantAdminDto.TenantCreateRequest(
                "signup-it", "Signup IT Tenant", "localhost", "Signup IT RP",
                List.of("http://localhost:9090"), Set.of("none"), true, false, "NONE", 60000);
        UUID tenantId = tenantAdminService.create(createReq, ALICE_ID, ALICE_EMAIL).id();

        // 1. 공개 요청
        String password = "securePassword123!";
        signupService.request(new SignupRequestDto.Create("NewAdmin@Example.com", password, "RP IAM 담당"));

        // 2. 목록 — 소문자 정규화된 이메일 1건
        List<SignupRequestDto.View> pending = signupService.list();
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).email()).isEqualTo("newadmin@example.com");
        assertThat(pending.get(0).reason()).isEqualTo("RP IAM 담당");
        UUID requestId = pending.get(0).id();

        // 3. 같은 이메일 재요청은 조용히 무시
        signupService.request(new SignupRequestDto.Create("newadmin@example.com", password, null));
        assertThat(signupRepo.count()).isEqualTo(1);

        // 4. 승인 — RP_ADMIN + 테넌트 1개
        AdminUserDto.View view = signupService.approve(requestId,
                new SignupRequestDto.Approve("RP_ADMIN", List.of(tenantId)), ALICE_ID, ALICE_EMAIL);
        assertThat(view.status()).isEqualTo("ACTIVE");
        assertThat(view.role()).isEqualTo("RP_ADMIN");
        assertThat(view.tenantIds()).containsExactly(tenantId);

        // 5. 계정은 로그인 가능 상태이고 비밀번호는 요청 때 것
        var user = adminUserRepo.findByEmail("newadmin@example.com").orElseThrow();
        assertThat(user.isEnabled()).isTrue();
        assertThat(user.getStatus()).isEqualTo("ACTIVE");
        assertThat(user.getCreatedBy()).isEqualTo(ALICE_EMAIL);
        assertThat(passwordEncoder.matches(password, user.getBcryptHash())).isTrue();

        // 6. 요청은 소비됨 — 재승인은 404
        assertThat(signupService.list()).isEmpty();
        assertThatThrownBy(() -> signupService.approve(requestId,
                new SignupRequestDto.Approve("RP_ADMIN", List.of(tenantId)), ALICE_ID, ALICE_EMAIL))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        // 7. 승인 감사 행 존재
        Integer audits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM PSK_APP_OWNER.audit_log WHERE action = 'ADMIN_SIGNUP_APPROVE'", Integer.class);
        assertThat(audits).isEqualTo(1);
    }

    @Test
    void rejectFlow_removesRequestAndAudits() {
        signupService.request(new SignupRequestDto.Create("reject@example.com", "securePassword123!", null));
        UUID requestId = signupService.list().get(0).id();

        signupService.reject(requestId, ALICE_ID, ALICE_EMAIL);

        assertThat(signupService.list()).isEmpty();
        assertThat(adminUserRepo.findByEmail("reject@example.com")).isEmpty();
        Integer audits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM PSK_APP_OWNER.audit_log WHERE action = 'ADMIN_SIGNUP_REJECT'", Integer.class);
        assertThat(audits).isEqualTo(1);

        // 거절 후 같은 이메일로 재요청 가능
        signupService.request(new SignupRequestDto.Create("reject@example.com", "securePassword123!", null));
        assertThat(signupService.list()).hasSize(1);
    }

    @Test
    void existingAdminEmail_requestIsSilentlyIgnored() {
        signupService.request(new SignupRequestDto.Create(ALICE_EMAIL, "securePassword123!", null));
        assertThat(signupService.list()).isEmpty();
    }
}
