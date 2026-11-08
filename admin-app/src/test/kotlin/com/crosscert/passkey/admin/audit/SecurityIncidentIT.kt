package com.crosscert.passkey.admin.audit

import com.crosscert.passkey.core.entity.SecurityIncident
import com.crosscert.passkey.core.repository.SecurityIncidentRepository
import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.OracleContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.MountableFile
import java.util.UUID
import javax.sql.DataSource

/**
 * Phase B Task 7 — SecurityIncident 통합 검증 IT (실DB, mock 아님).
 *
 * Codex 리뷰가 "통합 테스트로 커버 예정"으로 미룬 실DB 항목을 Testcontainers Oracle 로 검증한다:
 *  - **위변조→생성 e2e**: audit_log 를 실제로 깨고(payload tamper) [SecurityIncidentService.create]
 *    가 위변조를 재검증→incident INSERT→audit_log 기록까지 수행하는지.
 *  - **V50 부분 유니크 인덱스**: 테넌트당 OPEN 1건을 DB 레벨에서 강제하는지(선체크 우회한 raw INSERT 도 거부).
 *  - **ck_security_incident_resolution CHECK + resolveIfOpen 원자 UPDATE**: resolve 가 status='OPEN'
 *    만 전이하고 resolved_at/by/note 를 채워 CHECK 제약을 충족하는지.
 *  - **OPEN 1건의 정확한 의미**: RESOLVED 후엔(부분 인덱스가 OPEN 만 막으므로) 같은 테넌트에 새 incident 생성 가능.
 *
 * 위변조 유발은 [AuditChainPerTenantIT] 와 동일하게 [AuditLogService.append] 로 행을 시드한 뒤
 * PSK_APP_OWNER 풀로 payload 컬럼을 UPDATE 하여 체인 hash 를 깬다(PSK_APP_ADMIN 은 audit_log UPDATE 불가, V10).
 * 깨진 row id 는 `verifyTenant().brokenAt()` 이고, 서버는 이를 incident.tamperedEntryId 로 도출한다.
 *
 * 테넌트 격리는 앱 레벨(Hibernate @Filter)이 담당하므로 DB 커널 차원의 별도
 * tenant 컨텍스트 설정이 불필요하다(템플릿 두 IT 와 동일).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class SecurityIncidentIT {

    companion object {
        // ----------------------------------------------------------------
        // Containers (identical pattern to AuditChainPerTenantIT)
        // ----------------------------------------------------------------

        private const val ORACLE_IMAGE = "gvenzl/oracle-xe:21-slim-faststart"
        private const val SYS_PASSWORD = "app_owner_pw"

        @Container
        @JvmField
        val ORACLE: OracleContainer = OracleContainer(ORACLE_IMAGE)
            .withUsername("PSK_APP_OWNER")
            .withPassword(SYS_PASSWORD)
            .withCopyFileToContainer(
                MountableFile.forClasspathResource("bootstrap-schema.sql"),
                "/tmp/bootstrap-schema.sql",
            )

        @Container
        @JvmField
        val REDIS: GenericContainer<*> = GenericContainer("redis:7-alpine").apply { withExposedPorts(6379) }

        @JvmStatic
        @DynamicPropertySource
        fun registerProps(reg: DynamicPropertyRegistry) {
            val exec = ORACLE.execInContainer(
                "bash", "-c",
                "sqlplus -S sys/" + SYS_PASSWORD + "@localhost:1521/XEPDB1 as sysdba " +
                    "@/tmp/bootstrap-schema.sql",
            )
            if (exec.exitCode != 0) {
                throw IllegalStateException(
                    "bootstrap-schema.sql failed (exit=" + exec.exitCode + ")\n" +
                        "STDOUT:\n" + exec.stdout + "\n" +
                        "STDERR:\n" + exec.stderr,
                )
            }
            reg.add("spring.datasource.url", ORACLE::getJdbcUrl)
            reg.add("spring.datasource.username") { "PSK_APP_ADMIN_USER" }
            reg.add("spring.datasource.password") { "admin_pw" }
            reg.add("spring.data.redis.host", REDIS::getHost)
            reg.add("spring.data.redis.port") { REDIS.getMappedPort(6379) }
        }

        // Fixed tenant/actor UUIDs for reproducible FK inserts
        private val TAMPERED_TENANT_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000C1")
        private val INTACT_TENANT_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000C2")
        private val ACTOR_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000003")
        private const val ACTOR_EMAIL = "alice@crosscert.com"

        /** PSK_APP_OWNER (schema owner) pool — owner-only table cleanup + audit_log tamper (PSK_APP_ADMIN 은 UPDATE 불가). */
        private var ownerPool: HikariDataSource? = null

        @JvmStatic
        @AfterAll
        fun closeOwnerPool() {
            ownerPool?.close()
            ownerPool = null
        }

        @Synchronized
        private fun ownerJdbc(): JdbcTemplate {
            val pool = ownerPool ?: HikariDataSource().apply {
                jdbcUrl = ORACLE.jdbcUrl
                username = "PSK_APP_OWNER"
                password = SYS_PASSWORD
                maximumPoolSize = 2
                poolName = "security-incident-it-owner"
            }.also { ownerPool = it }
            return JdbcTemplate(pool)
        }

        /** Convert UUID to 32-char hex (no hyphens) for Oracle HEXTORAW binding. */
        private fun uuidToHex(uuid: UUID): String = uuid.toString().replace("-", "").uppercase()

        private val INT: Class<Int> = Int::class.javaObjectType
    }

    // ----------------------------------------------------------------
    // Wiring — 실빈 주입(mock 아님)
    // ----------------------------------------------------------------

    @Autowired
    lateinit var incidentService: SecurityIncidentService

    @Autowired
    lateinit var incidentRepo: SecurityIncidentRepository

    @Autowired
    lateinit var auditLogService: AuditLogService

    @Autowired
    lateinit var verifier: AuditChainVerifier

    @Autowired
    lateinit var ds: DataSource

    @Autowired
    lateinit var redisFactory: RedisConnectionFactory

    lateinit var jdbc: JdbcTemplate

    // ----------------------------------------------------------------
    // State reset — clean tables + seed one tampered tenant + one intact tenant
    // ----------------------------------------------------------------

    @BeforeEach
    fun resetState() {
        jdbc = JdbcTemplate(ds)

        // FK-safe delete order (mirrors AuditChainPerTenantIT) + security_incident.
        ownerJdbc().update("DELETE FROM PSK_APP_OWNER.security_incident")
        ownerJdbc().update("DELETE FROM PSK_APP_OWNER.audit_log")
        jdbc.update("DELETE FROM PSK_APP_OWNER.api_key_scope")
        jdbc.update("DELETE FROM PSK_APP_OWNER.api_key")
        jdbc.update("DELETE FROM PSK_APP_OWNER.credential")
        jdbc.update("DELETE FROM PSK_APP_OWNER.tenant_allowed_origin")
        jdbc.update("DELETE FROM PSK_APP_OWNER.tenant_accepted_format")
        jdbc.update("DELETE FROM PSK_APP_OWNER.admin_user_tenant")
        jdbc.update("UPDATE PSK_APP_OWNER.admin_user SET role = 'PLATFORM_OPERATOR' WHERE role = 'RP_ADMIN'")
        jdbc.update("DELETE FROM PSK_APP_OWNER.tenant")

        seedTenant(TAMPERED_TENANT_ID, "tenant-tampered", "Tampered Tenant")
        seedTenant(INTACT_TENANT_ID, "tenant-intact", "Intact Tenant")

        // Flush Redis session data
        redisFactory.connection.use { it.serverCommands().flushAll() }
    }

    private fun seedTenant(id: UUID, slug: String, displayName: String) {
        jdbc.update(
            """
            INSERT INTO PSK_APP_OWNER.tenant (id, slug, display_name, rp_id, rp_name, status,
                require_user_verification, mds_required, created_at, updated_at)
            VALUES (HEXTORAW(?), ?, ?, 'localhost', ?, 'active', 'Y', 'N', SYSTIMESTAMP, SYSTIMESTAMP)
            """.trimIndent(),
            uuidToHex(id), slug, displayName, displayName,
        )
        jdbc.update(
            """
            INSERT INTO PSK_APP_OWNER.tenant_allowed_origin (id, tenant_id, origin, sort_order)
            VALUES (SYS_GUID(), HEXTORAW(?), 'http://localhost:9090', 0)
            """.trimIndent(),
            uuidToHex(id),
        )
        jdbc.update(
            """
            INSERT INTO PSK_APP_OWNER.tenant_accepted_format (id, tenant_id, format)
            VALUES (SYS_GUID(), HEXTORAW(?), 'none')
            """.trimIndent(),
            uuidToHex(id),
        )
    }

    /**
     * 한 테넌트의 audit_log 를 위변조 상태로 만든다(AuditChainPerTenantIT 와 동일 방식):
     * 3행을 append 한 뒤 2번째 행의 payload 를 PSK_APP_OWNER 풀로 UPDATE 해 체인 hash 를 깬다.
     *
     * @return 깨진 row id (= verifyTenant().brokenAt() = 서버가 도출할 tamperedEntryId)
     */
    private fun tamperTenant(tenantId: UUID): UUID {
        auditLogService.append(
            AuditAppendRequest(
                ACTOR_ID, ACTOR_EMAIL, "TENANT_CREATE",
                "TENANT", tenantId.toString(), tenantId, mapOf("seq" to 1),
            ),
        )
        val row2 = checkNotNull(
            auditLogService.append(
                AuditAppendRequest(
                    ACTOR_ID, ACTOR_EMAIL, "TENANT_UPDATE",
                    "TENANT", tenantId.toString(), tenantId, mapOf("seq" to 2),
                ),
            ),
        )
        auditLogService.append(
            AuditAppendRequest(
                ACTOR_ID, ACTOR_EMAIL, "TENANT_UPDATE",
                "TENANT", tenantId.toString(), tenantId, mapOf("seq" to 3),
            ),
        )

        val tamperTargetId = row2.id
        val updated = ownerJdbc().update(
            "UPDATE PSK_APP_OWNER.audit_log SET payload = '{\"tampered\":true}' WHERE id = HEXTORAW(?)",
            uuidToHex(tamperTargetId),
        )
        assertThat(updated).`as`("tamper UPDATE should affect exactly 1 row").isEqualTo(1)

        // Sanity: chain is now genuinely broken at the tampered row.
        val r = verifier.verifyTenant(tenantId)
        assertThat(r.ok).`as`("tenant chain must be broken after payload tamper").isFalse()
        assertThat(r.brokenAt).`as`("brokenAt should point to the tampered row").isEqualTo(tamperTargetId)
        return tamperTargetId
    }

    /** intact 테넌트: 깨지 않은 정상 행만 append. */
    private fun seedIntactChain(tenantId: UUID) {
        auditLogService.append(
            AuditAppendRequest(
                ACTOR_ID, ACTOR_EMAIL, "TENANT_CREATE",
                "TENANT", tenantId.toString(), tenantId, mapOf("seq" to 1),
            ),
        )
        auditLogService.append(
            AuditAppendRequest(
                ACTOR_ID, ACTOR_EMAIL, "TENANT_UPDATE",
                "TENANT", tenantId.toString(), tenantId, mapOf("seq" to 2),
            ),
        )
        val r = verifier.verifyTenant(tenantId)
        assertThat(r.ok).`as`("intact tenant chain must verify ok").isTrue()
    }

    // ----------------------------------------------------------------
    // 1. create happy path — 위변조→생성→audit 기록 e2e
    // ----------------------------------------------------------------

    @Test
    fun create_happyPath_persistsIncidentAndAuditRow() {
        val brokenRowId = tamperTenant(TAMPERED_TENANT_ID)

        val incident = incidentService.create(TAMPERED_TENANT_ID, ACTOR_ID, ACTOR_EMAIL)

        // 반환 incident 상태
        assertThat(incident.status).isEqualTo(SecurityIncident.STATUS_OPEN)
        assertThat(incident.severity).isEqualTo(SecurityIncident.SEVERITY_CRITICAL)
        assertThat(incident.tenantId).isEqualTo(TAMPERED_TENANT_ID)
        // tamperedEntryId 는 caller 입력이 아니라 서버가 verifyTenant 에서 도출한 깨진 행 id 와 일치해야 한다.
        assertThat(incident.tamperedEntryId)
            .`as`("tamperedEntryId must be derived from verifyTenant().brokenAt()")
            .isEqualTo(brokenRowId)

        // DB 에 security_incident 행이 실제로 INSERT 됐는지(PSK_APP_ADMIN SELECT 경로).
        val incidentRows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM PSK_APP_OWNER.security_incident WHERE id = HEXTORAW(?) AND status = 'OPEN'",
            INT, uuidToHex(incident.id),
        )
        assertThat(incidentRows).`as`("OPEN security_incident row must be persisted").isEqualTo(1)

        // audit_log 에 AUDIT_CHAIN_INCIDENT_CREATED 행이 추가됐는지.
        val createdAuditRows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM PSK_APP_OWNER.audit_log WHERE action = 'AUDIT_CHAIN_INCIDENT_CREATED'" +
                " AND target_id = ?",
            INT, incident.id.toString(),
        )
        assertThat(createdAuditRows).`as`("AUDIT_CHAIN_INCIDENT_CREATED audit row must be appended").isEqualTo(1)
    }

    // ----------------------------------------------------------------
    // 2. create on intact tenant → 422 (IncidentNotTamperedException)
    // ----------------------------------------------------------------

    @Test
    fun create_whenNotTampered_throwsNotTampered() {
        seedIntactChain(INTACT_TENANT_ID)

        assertThatThrownBy { incidentService.create(INTACT_TENANT_ID, ACTOR_ID, ACTOR_EMAIL) }
            .isInstanceOf(IncidentNotTamperedException::class.java)

        // 위변조가 아니므로 incident 가 만들어지면 안 된다.
        assertThat(incidentRepo.existsByTenantIdAndStatus(INTACT_TENANT_ID, SecurityIncident.STATUS_OPEN))
            .isFalse()
    }

    // ----------------------------------------------------------------
    // 3. duplicate create → 409, and V50 부분 유니크 인덱스 DB 레벨 강제 검증
    // ----------------------------------------------------------------

    @Test
    fun create_duplicate_blockedByPrecheckAndUniqueIndex() {
        tamperTenant(TAMPERED_TENANT_ID)

        val first = incidentService.create(TAMPERED_TENANT_ID, ACTOR_ID, ACTOR_EMAIL)
        assertThat(first.status).isEqualTo(SecurityIncident.STATUS_OPEN)

        // (a) 서비스 선체크 경로: 같은 테넌트 두 번째 create → 친절한 409.
        assertThatThrownBy { incidentService.create(TAMPERED_TENANT_ID, ACTOR_ID, ACTOR_EMAIL) }
            .isInstanceOf(IncidentConflictException::class.java)

        // (b) V50 부분 유니크 인덱스 DB 레벨 강제: 선체크를 우회한 raw OPEN INSERT 도 거부돼야 한다.
        //     ux_incident_open_per_tenant ON (CASE WHEN status='OPEN' THEN tenant_id END) 가
        //     같은 테넌트의 두 번째 OPEN 행을 막는지 직접 검증한다(ORA-00001 unique constraint).
        assertThatThrownBy {
            ownerJdbc().update(
                """
                INSERT INTO PSK_APP_OWNER.security_incident
                    (id, tenant_id, type, severity, status, created_at, created_by)
                VALUES (SYS_GUID(), HEXTORAW(?), 'AUDIT_CHAIN_TAMPER', 'CRITICAL', 'OPEN', SYSTIMESTAMP, HEXTORAW(?))
                """.trimIndent(),
                uuidToHex(TAMPERED_TENANT_ID), uuidToHex(ACTOR_ID),
            )
        }
            .`as`("partial unique index must reject a 2nd OPEN row for the same tenant")
            .isInstanceOf(DataAccessException::class.java)

        // 정확히 1건의 OPEN 행만 존재.
        val openRows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM PSK_APP_OWNER.security_incident WHERE tenant_id = HEXTORAW(?) AND status = 'OPEN'",
            INT, uuidToHex(TAMPERED_TENANT_ID),
        )
        assertThat(openRows).`as`("exactly one OPEN incident per tenant").isEqualTo(1)
    }

    // ----------------------------------------------------------------
    // 4. resolve happy path — resolveIfOpen 원자 UPDATE + ck_security_incident_resolution
    // ----------------------------------------------------------------

    @Test
    fun resolve_happyPath_atomicUpdateSatisfiesCheckConstraint() {
        tamperTenant(TAMPERED_TENANT_ID)
        val open = incidentService.create(TAMPERED_TENANT_ID, ACTOR_ID, ACTOR_EMAIL)

        val note = "DBA 복구 완료 — 백업 복원 후 체인 재계산"
        val resolved = incidentService.resolve(open.id, note, ACTOR_ID, ACTOR_EMAIL)

        // 반환값
        assertThat(resolved.status).isEqualTo(SecurityIncident.STATUS_RESOLVED)
        assertThat(resolved.resolutionNote).isEqualTo(note)
        assertThat(resolved.resolvedAt).isNotNull()
        assertThat(resolved.resolvedBy).isEqualTo(ACTOR_ID)

        // DB 상태 — resolveIfOpen 원자 UPDATE 가 실DB 에 반영됐고 CHECK 제약(RESOLVED 시 resolved_* NOT NULL)을 충족.
        val dbRow = jdbc.queryForMap(
            "SELECT status, resolved_at, resolved_by, resolution_note" +
                " FROM PSK_APP_OWNER.security_incident WHERE id = HEXTORAW(?)",
            uuidToHex(open.id),
        )
        assertThat(dbRow["STATUS"]).isEqualTo("RESOLVED")
        assertThat(dbRow["RESOLVED_AT"]).`as`("resolved_at must be set (ck_security_incident_resolution)").isNotNull()
        assertThat(dbRow["RESOLVED_BY"]).`as`("resolved_by must be set").isNotNull()
        assertThat(dbRow["RESOLUTION_NOTE"]).isEqualTo(note)

        // audit_log 에 AUDIT_CHAIN_INCIDENT_RESOLVED 행.
        val resolvedAuditRows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM PSK_APP_OWNER.audit_log WHERE action = 'AUDIT_CHAIN_INCIDENT_RESOLVED'" +
                " AND target_id = ?",
            INT, open.id.toString(),
        )
        assertThat(resolvedAuditRows).`as`("AUDIT_CHAIN_INCIDENT_RESOLVED audit row must be appended").isEqualTo(1)
    }

    // ----------------------------------------------------------------
    // 5. resolve guards — already-resolved → 409, not-found → 404
    // ----------------------------------------------------------------

    @Test
    fun resolve_alreadyResolved_throwsConflict() {
        tamperTenant(TAMPERED_TENANT_ID)
        val open = incidentService.create(TAMPERED_TENANT_ID, ACTOR_ID, ACTOR_EMAIL)
        incidentService.resolve(open.id, "first resolve", ACTOR_ID, ACTOR_EMAIL)

        // resolveIfOpen 가 0행(이미 RESOLVED) → 존재하므로 409. 원자 UPDATE 가 OPEN 만 전이함을 실DB 로 검증.
        assertThatThrownBy { incidentService.resolve(open.id, "second resolve", ACTOR_ID, ACTOR_EMAIL) }
            .isInstanceOf(IncidentConflictException::class.java)
    }

    @Test
    fun resolve_notFound_throwsNotFound() {
        val missing = UUID.randomUUID()
        assertThatThrownBy { incidentService.resolve(missing, "note", ACTOR_ID, ACTOR_EMAIL) }
            .isInstanceOf(IncidentNotFoundException::class.java)
    }

    // ----------------------------------------------------------------
    // 6. RESOLVED 후 재생성 — 부분 유니크 인덱스는 OPEN 만 막으므로 새 OPEN 생성 가능
    //    ("테넌트당 OPEN 1건"의 정확한 의미 검증)
    // ----------------------------------------------------------------

    @Test
    fun resolveThenCreateAgain_succeeds_whenStillTampered() {
        val brokenRowId = tamperTenant(TAMPERED_TENANT_ID)

        val first = incidentService.create(TAMPERED_TENANT_ID, ACTOR_ID, ACTOR_EMAIL)
        incidentService.resolve(first.id, "복구 시도 1", ACTOR_ID, ACTOR_EMAIL)

        // 테넌트는 여전히 위변조 상태(audit_log tamper 가 그대로 남아 있음) → 새 OPEN incident 생성 가능.
        val second = incidentService.create(TAMPERED_TENANT_ID, ACTOR_ID, ACTOR_EMAIL)
        assertThat(second.status).isEqualTo(SecurityIncident.STATUS_OPEN)
        assertThat(second.id).`as`("a fresh incident is created").isNotEqualTo(first.id)
        assertThat(second.tamperedEntryId).isEqualTo(brokenRowId)

        // 이제 행은 2개: RESOLVED 1 + OPEN 1. OPEN 은 정확히 1건.
        val total = jdbc.queryForObject(
            "SELECT COUNT(*) FROM PSK_APP_OWNER.security_incident WHERE tenant_id = HEXTORAW(?)",
            INT, uuidToHex(TAMPERED_TENANT_ID),
        )
        assertThat(total).`as`("one RESOLVED + one OPEN").isEqualTo(2)
        val openRows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM PSK_APP_OWNER.security_incident WHERE tenant_id = HEXTORAW(?) AND status = 'OPEN'",
            INT, uuidToHex(TAMPERED_TENANT_ID),
        )
        assertThat(openRows).`as`("still exactly one OPEN after re-create").isEqualTo(1)
    }
}
