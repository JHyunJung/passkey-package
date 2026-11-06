package com.crosscert.passkey.app.support;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.utility.MountableFile;

/**
 * passkey-app 통합 테스트 공용 인프라(부트스트랩·테넌트/API Key 시드·정리).
 *
 * <p>컨테이너와 수명주기(@Testcontainers, @BeforeEach/@AfterEach/@AfterAll)는 각 테스트 클래스가 갖고,
 * 이 클래스는 그 안에서 호출하는 절차만 모은다. 절차는 {@code Fido2EndToEndIT} 와 같다:
 * SYS 로 {@code bootstrap-schema.sql} 실행 → 런타임 DataSource 는 PSK_APP_RUNTIME_USER,
 * 시드/정리는 별도 PSK_APP_ADMIN_USER 풀(런타임 유저는 tenant 에 SELECT 만 가짐).
 */
public final class PasskeyAppItSupport {

    public static final String ORACLE_IMAGE = "gvenzl/oracle-xe:21-slim-faststart";
    /** OracleContainer 가 SYS/SYSTEM 과 PSK_APP_OWNER 에 같은 비밀번호를 쓴다. */
    public static final String SYS_PASSWORD = "app_owner_pw";

    private PasskeyAppItSupport() {}

    /** Fido2EndToEndIT 와 같은 설정의 Oracle 컨테이너(부트스트랩 스크립트 포함). */
    public static OracleContainer oracleContainer() {
        return new OracleContainer(ORACLE_IMAGE)
                .withUsername("PSK_APP_OWNER")
                .withPassword(SYS_PASSWORD)
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("bootstrap-schema.sql"),
                        "/tmp/bootstrap-schema.sql");
    }

    /** docker-compose 와 같은 Redis 7 컨테이너. */
    public static GenericContainer<?> redisContainer() {
        return new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    }

    /**
     * {@code @DynamicPropertySource} 에서 호출: Spring 이 첫 커넥션을 열기 전에 bootstrap SQL 을
     * 실행하고, 런타임 DataSource(PSK_APP_RUNTIME_USER)·Redis 프로퍼티를 등록한다.
     */
    public static void bootstrapAndRegister(DynamicPropertyRegistry reg,
                                            OracleContainer oracle,
                                            GenericContainer<?> redis) throws Exception {
        Container.ExecResult exec = oracle.execInContainer(
                "bash", "-c",
                "sqlplus -S sys/" + SYS_PASSWORD + "@localhost:1521/XEPDB1 as sysdba "
                        + "@/tmp/bootstrap-schema.sql");
        if (exec.getExitCode() != 0) {
            throw new IllegalStateException(
                    "bootstrap-schema.sql failed (exit=" + exec.getExitCode() + ")\n"
                            + "STDOUT:\n" + exec.getStdout() + "\n"
                            + "STDERR:\n" + exec.getStderr());
        }
        reg.add("spring.datasource.url", oracle::getJdbcUrl);
        reg.add("spring.datasource.username", () -> "PSK_APP_RUNTIME_USER");
        reg.add("spring.datasource.password", () -> "runtime_pw");
        reg.add("spring.data.redis.host", redis::getHost);
        reg.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    /**
     * PSK_APP_ADMIN_USER 풀로 테넌트 1개 + 허용 origin + 포맷(none/packed) + API Key(registration/
     * authentication scope)를 시드하고 정리한다. 테스트 클래스가 static 으로 보관하고
     * {@code @AfterAll} 에서 {@link #close()} 한다.
     */
    public static final class TenantSeeder implements AutoCloseable {

        private final HikariDataSource pool;
        private final JdbcTemplate jdbc;

        public TenantSeeder(OracleContainer oracle, String poolName) {
            pool = new HikariDataSource();
            pool.setJdbcUrl(oracle.getJdbcUrl());
            pool.setUsername("PSK_APP_ADMIN_USER");
            pool.setPassword("admin_pw");
            pool.setMaximumPoolSize(2);
            pool.setMinimumIdle(1);
            pool.setPoolName(poolName);
            jdbc = new JdbcTemplate(pool);
        }

        /**
         * @param tenantHex   32자 hex (RAW(16) id)
         * @param keyHash     api_key.key_hash (호출측 PasswordEncoder 로 인코딩한 값)
         */
        public void seedTenant(String tenantHex, String slug, String displayName,
                               String origin, String keyPrefix, String keyHash) {
            jdbc.update(
                    "INSERT INTO PSK_APP_OWNER.tenant "
                            + "(id, slug, display_name, status, rp_id, rp_name, "
                            + " require_user_verification, mds_required, created_at, updated_at) "
                            + "VALUES (HEXTORAW(?), ?, ?, 'active', 'localhost', ?, "
                            + "        'Y', 'N', SYSTIMESTAMP, SYSTIMESTAMP)",
                    tenantHex, slug, displayName, displayName);
            jdbc.update(
                    "INSERT INTO PSK_APP_OWNER.tenant_allowed_origin (id, tenant_id, origin, sort_order) "
                            + "VALUES (SYS_GUID(), HEXTORAW(?), ?, 0)",
                    tenantHex, origin);
            for (String fmt : new String[]{"none", "packed"}) {
                jdbc.update(
                        "INSERT INTO PSK_APP_OWNER.tenant_accepted_format (id, tenant_id, format) "
                                + "VALUES (SYS_GUID(), HEXTORAW(?), ?)",
                        tenantHex, fmt);
            }
            jdbc.update(
                    "INSERT INTO PSK_APP_OWNER.api_key (id, tenant_id, key_prefix, key_hash, name, created_at) "
                            + "VALUES (SYS_GUID(), HEXTORAW(?), ?, ?, 'primary', SYSTIMESTAMP)",
                    tenantHex, keyPrefix, keyHash);
            for (String scope : new String[]{"registration", "authentication"}) {
                jdbc.update(
                        "INSERT INTO PSK_APP_OWNER.api_key_scope (id, api_key_id, scope) "
                                + "SELECT SYS_GUID(), id, ? FROM PSK_APP_OWNER.api_key WHERE key_prefix = ?",
                        scope, keyPrefix);
            }
        }

        /** FK 순서대로 테넌트 관련 행을 모두 지운다(ceremony_event 는 FK 없는 카운트 테이블). */
        public void wipe() {
            jdbc.update("DELETE FROM PSK_APP_OWNER.credential");
            jdbc.update("DELETE FROM PSK_APP_OWNER.api_key_scope");
            jdbc.update("DELETE FROM PSK_APP_OWNER.api_key");
            jdbc.update("DELETE FROM PSK_APP_OWNER.tenant_allowed_origin");
            jdbc.update("DELETE FROM PSK_APP_OWNER.tenant_accepted_format");
            jdbc.update("DELETE FROM PSK_APP_OWNER.ceremony_event");
            jdbc.update("DELETE FROM PSK_APP_OWNER.admin_user_tenant");
            jdbc.update("UPDATE PSK_APP_OWNER.admin_user SET role = 'PLATFORM_OPERATOR' WHERE role = 'RP_ADMIN'");
            jdbc.update("DELETE FROM PSK_APP_OWNER.tenant");
        }

        @Override
        public void close() {
            pool.close();
        }
    }
}
