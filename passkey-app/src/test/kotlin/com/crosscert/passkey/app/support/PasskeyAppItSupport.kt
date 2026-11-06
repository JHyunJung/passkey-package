package com.crosscert.passkey.app.support

import com.zaxxer.hikari.HikariDataSource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.OracleContainer
import org.testcontainers.utility.MountableFile

/**
 * passkey-app 통합 테스트 공용 인프라(부트스트랩·테넌트/API Key 시드·정리).
 *
 * 컨테이너와 수명주기(@Testcontainers, @BeforeEach/@AfterEach/@AfterAll)는 각 테스트 클래스가 갖고,
 * 이 객체는 그 안에서 호출하는 절차만 모은다. 절차는 `Fido2EndToEndIT` 와 같다:
 * SYS 로 `bootstrap-schema.sql` 실행 → 런타임 DataSource 는 PSK_APP_RUNTIME_USER,
 * 시드/정리는 별도 PSK_APP_ADMIN_USER 풀(런타임 유저는 tenant 에 SELECT 만 가짐).
 */
object PasskeyAppItSupport {

    const val ORACLE_IMAGE = "gvenzl/oracle-xe:21-slim-faststart"

    /** OracleContainer 가 SYS/SYSTEM 과 PSK_APP_OWNER 에 같은 비밀번호를 쓴다. */
    const val SYS_PASSWORD = "app_owner_pw"

    /** Fido2EndToEndIT 와 같은 설정의 Oracle 컨테이너(부트스트랩 스크립트 포함). */
    fun oracleContainer(): OracleContainer =
        OracleContainer(ORACLE_IMAGE)
            .withUsername("PSK_APP_OWNER")
            .withPassword(SYS_PASSWORD)
            .withCopyFileToContainer(
                MountableFile.forClasspathResource("bootstrap-schema.sql"),
                "/tmp/bootstrap-schema.sql",
            )

    /** docker-compose 와 같은 Redis 7 컨테이너. */
    fun redisContainer(): GenericContainer<*> =
        GenericContainer("redis:7-alpine").apply { withExposedPorts(6379) }

    /**
     * `@DynamicPropertySource` 에서 호출: Spring 이 첫 커넥션을 열기 전에 bootstrap SQL 을
     * 실행하고, 런타임 DataSource(PSK_APP_RUNTIME_USER)·Redis 프로퍼티를 등록한다.
     */
    fun bootstrapAndRegister(reg: DynamicPropertyRegistry, oracle: OracleContainer, redis: GenericContainer<*>) {
        val exec = oracle.execInContainer(
            "bash", "-c",
            "sqlplus -S sys/$SYS_PASSWORD@localhost:1521/XEPDB1 as sysdba @/tmp/bootstrap-schema.sql",
        )
        check(exec.exitCode == 0) {
            "bootstrap-schema.sql failed (exit=${exec.exitCode})\nSTDOUT:\n${exec.stdout}\nSTDERR:\n${exec.stderr}"
        }
        reg.add("spring.datasource.url", oracle::getJdbcUrl)
        reg.add("spring.datasource.username") { "PSK_APP_RUNTIME_USER" }
        reg.add("spring.datasource.password") { "runtime_pw" }
        reg.add("spring.data.redis.host", redis::getHost)
        reg.add("spring.data.redis.port") { redis.getMappedPort(6379) }
    }

    /**
     * PSK_APP_ADMIN_USER 풀로 테넌트 1개 + 허용 origin + 포맷(none/packed) + API Key(registration/
     * authentication scope)를 시드하고 정리한다. 테스트 클래스가 companion 에 보관하고
     * `@AfterAll` 에서 [close] 한다.
     */
    class TenantSeeder(oracle: OracleContainer, poolName: String) : AutoCloseable {

        private val pool = HikariDataSource().apply {
            jdbcUrl = oracle.jdbcUrl
            username = "PSK_APP_ADMIN_USER"
            password = "admin_pw"
            maximumPoolSize = 2
            minimumIdle = 1
            this.poolName = poolName
        }

        /** 시드 외의 검증 쿼리·상태 조작용(PSK_APP_ADMIN_USER 권한). */
        val jdbc = JdbcTemplate(pool)

        /**
         * 테넌트 여러 개가 필요한 시나리오는 테넌트마다 한 번씩 호출한다(prefix 는 테넌트별로 달라야 함).
         *
         * @param tenantHex 32자 hex (RAW(16) id)
         * @param keyHash   api_key.key_hash (호출측 PasswordEncoder 로 인코딩한 값)
         */
        fun seedTenant(
            tenantHex: String,
            slug: String,
            displayName: String,
            origin: String,
            keyPrefix: String,
            keyHash: String?,
        ) {
            jdbc.update(
                "INSERT INTO PSK_APP_OWNER.tenant " +
                    "(id, slug, display_name, status, rp_id, rp_name, " +
                    " require_user_verification, mds_required, created_at, updated_at) " +
                    "VALUES (HEXTORAW(?), ?, ?, 'active', 'localhost', ?, " +
                    "        'Y', 'N', SYSTIMESTAMP, SYSTIMESTAMP)",
                tenantHex, slug, displayName, displayName,
            )
            jdbc.update(
                "INSERT INTO PSK_APP_OWNER.tenant_allowed_origin (id, tenant_id, origin, sort_order) " +
                    "VALUES (SYS_GUID(), HEXTORAW(?), ?, 0)",
                tenantHex, origin,
            )
            for (fmt in listOf("none", "packed")) {
                jdbc.update(
                    "INSERT INTO PSK_APP_OWNER.tenant_accepted_format (id, tenant_id, format) " +
                        "VALUES (SYS_GUID(), HEXTORAW(?), ?)",
                    tenantHex, fmt,
                )
            }
            jdbc.update(
                "INSERT INTO PSK_APP_OWNER.api_key (id, tenant_id, key_prefix, key_hash, name, created_at) " +
                    "VALUES (SYS_GUID(), HEXTORAW(?), ?, ?, 'primary', SYSTIMESTAMP)",
                tenantHex, keyPrefix, keyHash,
            )
            for (scope in listOf("registration", "authentication")) {
                jdbc.update(
                    "INSERT INTO PSK_APP_OWNER.api_key_scope (id, api_key_id, scope) " +
                        "SELECT SYS_GUID(), id, ? FROM PSK_APP_OWNER.api_key WHERE key_prefix = ?",
                    scope, keyPrefix,
                )
            }
        }

        /** FK 순서대로 테넌트 관련 행을 모두 지운다(ceremony_event 는 FK 없는 카운트 테이블). */
        fun wipe() {
            jdbc.update("DELETE FROM PSK_APP_OWNER.credential")
            jdbc.update("DELETE FROM PSK_APP_OWNER.api_key_scope")
            jdbc.update("DELETE FROM PSK_APP_OWNER.api_key")
            jdbc.update("DELETE FROM PSK_APP_OWNER.tenant_allowed_origin")
            jdbc.update("DELETE FROM PSK_APP_OWNER.tenant_accepted_format")
            jdbc.update("DELETE FROM PSK_APP_OWNER.ceremony_event")
            jdbc.update("DELETE FROM PSK_APP_OWNER.admin_user_tenant")
            jdbc.update("UPDATE PSK_APP_OWNER.admin_user SET role = 'PLATFORM_OPERATOR' WHERE role = 'RP_ADMIN'")
            jdbc.update("DELETE FROM PSK_APP_OWNER.tenant")
        }

        override fun close() {
            pool.close()
        }
    }
}
