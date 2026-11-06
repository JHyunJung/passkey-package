package com.crosscert.passkey.app.security

import com.crosscert.passkey.app.support.PasskeyAppItSupport.ORACLE_IMAGE
import com.crosscert.passkey.app.support.PasskeyAppItSupport.SYS_PASSWORD
import com.crosscert.passkey.core.config.DbSchemaProperties
import com.crosscert.passkey.core.tenant.TenantContextHolder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.OracleContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.MountableFile
import java.nio.ByteBuffer
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

// ── Fixed deterministic identifiers ──────────────────────────────────────
private val TENANT_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
private val OTHER_TENANT_ID: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
private val API_KEY_ID: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
private const val KEY_PREFIX = "pk_abcd1234"
private const val KEY_HASH = "\$2a\$10\$abcdefghijklmnopqrstuv"

/**
 * Task 3 acceptance gate — proves [ApiKeyLookupService] resolves an API
 * key by its globally-unique prefix using a NATIVE query that works WITHOUT a
 * tenant context, and that `touchLastUsed` keeps tenant isolation via an
 * explicit `tenant_id` WHERE predicate.
 *
 * ## Why PSK_APP_ADMIN_USER
 * The native `findByPrefix` lookup runs as a plain [JdbcTemplate]
 * SELECT — it does NOT pass through Hibernate's `@Filter`. It also must
 * return a row even though no tenant context is set at authentication time.
 * This IT connects as `PSK_APP_ADMIN_USER`, the production runtime user, so
 * the native query observes the row regardless of session context — exactly
 * the production behavior now that Oracle VPD is removed (no DB-kernel row
 * filter exists, so the plain SQL query already sees all rows).
 *
 * ## Seed strategy
 * One tenant + one api_key, inserted directly via [JdbcTemplate] with
 * RAW(16) bindings (UUID → 16 bytes). Verification reads back via JdbcTemplate
 * (last_used_at) so the assertions never depend on the service under test for
 * their own ground truth.
 *
 * Mirrors the Testcontainers Oracle XE 21 / bootstrap-schema.sql /
 * application-test.yml pattern used by `AppLevelIsolationIT`.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class ApiKeyLookupServiceIT @Autowired constructor(
    private val service: ApiKeyLookupService,
    private val jdbc: JdbcTemplate,
) {

    // ── Minimal Spring Boot test app ────────────────────────────────────────
    // @EntityScan/@EnableJpaRepositories on core keep ddl-auto=validate happy
    // (every Flyway table is mapped). The single extra bean is the service
    // under test, wired against the primary DataSource.
    //
    // scanBasePackages is pinned to this class's own (otherwise empty for
    // component purposes) scope so the default component scan does NOT pick up
    // the production security @Components (ApiKeyAuthFilter etc.) that would
    // drag in PasswordEncoder and the rest of the security stack. We only need
    // DataSource + Flyway + the one service under test.
    @SpringBootApplication(scanBasePackages = ["com.crosscert.passkey.app.security.itscope"])
    @EntityScan("com.crosscert.passkey.core.entity")
    @EnableJpaRepositories("com.crosscert.passkey.core.repository")
    @Import(ServiceConfig::class)
    class TestApp

    @Configuration
    class ServiceConfig {
        @Bean
        fun apiKeyLookupService(dataSource: DataSource): ApiKeyLookupService =
            // 이 IT 컨테이너는 아직 구 계정명(PSK_APP_OWNER)으로 부트스트랩된다.
            // 스키마명을 명시해 IT 자체의 SQL(위 seed/verify 쿼리)과 일치시킨다.
            ApiKeyLookupService(dataSource, DbSchemaProperties("PSK_APP_OWNER"))
    }

    companion object {
        // ── Testcontainers Oracle XE ─────────────────────────────────────────
        @Container
        @JvmField
        val ORACLE: OracleContainer = OracleContainer(ORACLE_IMAGE)
            .withUsername("PSK_APP_OWNER")
            .withPassword(SYS_PASSWORD)
            .withCopyFileToContainer(
                MountableFile.forClasspathResource("bootstrap-schema.sql"),
                "/tmp/bootstrap-schema.sql",
            )

        @JvmStatic
        @DynamicPropertySource
        fun registerProps(reg: DynamicPropertyRegistry) {
            val exec = ORACLE.execInContainer(
                "bash", "-c",
                "sqlplus -S sys/$SYS_PASSWORD@localhost:1521/XEPDB1 as sysdba @/tmp/bootstrap-schema.sql",
            )
            check(exec.exitCode == 0) {
                "bootstrap-schema.sql failed (exit=${exec.exitCode})\nSTDOUT:\n${exec.stdout}\nSTDERR:\n${exec.stderr}"
            }
            // Runtime DataSource → PSK_APP_ADMIN_USER, the production runtime user, so
            // the native SELECT mirrors the post-VPD-removal state (no DB row filter).
            // PSK_APP_ADMIN also holds full DML on api_key/tenant for seeding.
            reg.add("spring.datasource.url", ORACLE::getJdbcUrl)
            reg.add("spring.datasource.username") { "PSK_APP_ADMIN_USER" }
            reg.add("spring.datasource.password") { "admin_pw" }
            // Flyway runs as PSK_APP_OWNER (schema owner) — same split as the core ITs.
            reg.add("spring.flyway.url", ORACLE::getJdbcUrl)
            reg.add("spring.flyway.user") { "PSK_APP_OWNER" }
            reg.add("spring.flyway.password") { SYS_PASSWORD }
        }
    }

    // ── Seed + cleanup ───────────────────────────────────────────────────────

    /**
     * Seeds one tenant and one api_key owned by it. id/tenant_id are RAW(16),
     * bound from the UUIDs above. Returns nothing — the constants are reused by
     * the assertions.
     */
    private fun seed() {
        TenantContextHolder.clear()
        // Tenant (FK target for api_key.tenant_id). Minimal NOT NULL columns —
        // allowed_origins/attestation_policy were normalized into child tables
        // (V21); the boolean flags carry DB defaults so they are omitted here.
        jdbc.update(
            "INSERT INTO PSK_APP_OWNER.tenant " +
                "(id, display_name, status, slug, rp_id, rp_name) " +
                "VALUES (?, ?, 'active', ?, ?, ?)",
            PreparedStatementSetter { ps ->
                ps.setBytes(1, toBytes(TENANT_ID))
                ps.setString(2, "ApiKey Lookup Tenant")
                ps.setString(3, "apikey-lookup-tenant")
                ps.setString(4, "localhost")
                ps.setString(5, "ApiKey Lookup RP")
            },
        )
        // api_key. scopes was normalized into api_key_scope (V21); last_used_at
        // left NULL so the touchLastUsed assertions start from a clean slate.
        jdbc.update(
            "INSERT INTO PSK_APP_OWNER.api_key " +
                "(id, tenant_id, key_prefix, key_hash, name) " +
                "VALUES (?, ?, ?, ?, ?)",
            PreparedStatementSetter { ps ->
                ps.setBytes(1, toBytes(API_KEY_ID))
                ps.setBytes(2, toBytes(TENANT_ID))
                ps.setString(3, KEY_PREFIX)
                ps.setString(4, KEY_HASH)
                ps.setString(5, "test-key")
            },
        )
    }

    @AfterEach
    fun cleanup() {
        TenantContextHolder.clear()
        jdbc.update("DELETE FROM PSK_APP_OWNER.api_key")
        jdbc.update("DELETE FROM PSK_APP_OWNER.tenant")
    }

    // ── Test 1: native lookup works WITHOUT tenant context ───────────────────

    /**
     * With [TenantContextHolder] cleared (the authentication-time state),
     * the native `findByPrefix` must still return the seeded row — the
     * JdbcTemplate SELECT does not pass through the Hibernate `@Filter`
     * and (with Oracle VPD removed) no DB-kernel row filter applies, so a
     * globally-unique prefix resolves to exactly one row regardless of context.
     */
    @Test
    fun findByPrefix_returnsRow_withoutTenantContext() {
        seed()
        TenantContextHolder.clear()

        val row = service.findByPrefix(KEY_PREFIX)

        assertThat(row)
            .`as`("native lookup must return the row even with no tenant context")
            .isPresent()
        val found = row.get()
        assertThat(found.id)
            .`as`("id must round-trip from RAW(16)")
            .isEqualTo(API_KEY_ID)
        assertThat(found.tenantId)
            .`as`("tenant_id must round-trip from RAW(16)")
            .isEqualTo(TENANT_ID)
        assertThat(found.keyHash).isEqualTo(KEY_HASH)
        assertThat(found.expiresAt).isNull()
        assertThat(found.revokedAt).isNull()
    }

    // ── Test 2: unknown prefix → empty ───────────────────────────────────────

    @Test
    fun findByPrefix_returnsEmpty_forUnknownPrefix() {
        seed()
        TenantContextHolder.clear()

        val row = service.findByPrefix("pk_doesnotexist")

        assertThat(row)
            .`as`("an unknown prefix must resolve to Optional.empty()")
            .isEmpty()
    }

    // ── Test 3: touchLastUsed isolates by explicit tenant_id ─────────────────

    /**
     * touchLastUsed must constrain its UPDATE to `id AND tenant_id`. A
     * wrong tenantId matches 0 rows (last_used_at stays NULL); the correct
     * tenantId updates the row. This preserves cross-tenant write isolation
     * even though VPD is gone — context absence is never treated as authority.
     */
    @Test
    fun touchLastUsed_updatesOnlyMatchingTenant() {
        seed()
        val now = Instant.now()

        // ── Wrong tenant: must NOT update (0 rows matched) ────────────────
        service.touchLastUsed(API_KEY_ID, OTHER_TENANT_ID, now)
        val afterWrong = jdbc.queryForObject(
            "SELECT last_used_at FROM PSK_APP_OWNER.api_key WHERE id = ?",
            Timestamp::class.java, toBytes(API_KEY_ID),
        )
        assertThat(afterWrong)
            .`as`("touchLastUsed with the wrong tenant_id must leave last_used_at NULL")
            .isNull()

        // ── Correct tenant: must update ───────────────────────────────────
        service.touchLastUsed(API_KEY_ID, TENANT_ID, now)
        val afterRight = jdbc.queryForObject(
            "SELECT last_used_at FROM PSK_APP_OWNER.api_key WHERE id = ?",
            Timestamp::class.java, toBytes(API_KEY_ID),
        )
        assertThat(afterRight)
            .`as`("touchLastUsed with the correct tenant_id must set last_used_at")
            .isNotNull()
    }

    // ── helper ────────────────────────────────────────────────────────────────
    private fun toBytes(uuid: UUID): ByteArray =
        ByteBuffer.allocate(16)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .array()
}
