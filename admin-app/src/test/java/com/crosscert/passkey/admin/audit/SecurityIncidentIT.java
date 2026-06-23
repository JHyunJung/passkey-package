package com.crosscert.passkey.admin.audit;

import com.crosscert.passkey.core.entity.SecurityIncident;
import com.crosscert.passkey.core.repository.SecurityIncidentRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import javax.sql.DataSource;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase B Task 7 — SecurityIncident 통합 검증 IT (실DB, mock 아님).
 *
 * <p>Codex 리뷰가 "통합 테스트로 커버 예정"으로 미룬 실DB 항목을 Testcontainers Oracle 로 검증한다:
 * <ul>
 *   <li><b>위변조→생성 e2e</b>: audit_log 를 실제로 깨고(payload tamper) {@link SecurityIncidentService#create}
 *       가 위변조를 재검증→incident INSERT→audit_log 기록까지 수행하는지.</li>
 *   <li><b>V50 부분 유니크 인덱스</b>: 테넌트당 OPEN 1건을 DB 레벨에서 강제하는지(선체크 우회한 raw INSERT 도 거부).</li>
 *   <li><b>ck_security_incident_resolution CHECK + resolveIfOpen 원자 UPDATE</b>: resolve 가 status='OPEN'
 *       만 전이하고 resolved_at/by/note 를 채워 CHECK 제약을 충족하는지.</li>
 *   <li><b>OPEN 1건의 정확한 의미</b>: RESOLVED 후엔(부분 인덱스가 OPEN 만 막으므로) 같은 테넌트에 새 incident 생성 가능.</li>
 * </ul>
 *
 * <p>위변조 유발은 {@link AuditChainPerTenantIT} 와 동일하게 {@link AuditLogService#append} 로 행을 시드한 뒤
 * APP_OWNER 풀로 payload 컬럼을 UPDATE 하여 체인 hash 를 깬다(APP_ADMIN 은 audit_log UPDATE 불가, V10).
 * 깨진 row id 는 {@code verifyTenant().brokenAt()} 이고, 서버는 이를 incident.tamperedEntryId 로 도출한다.
 *
 * <p>테넌트 격리는 앱 레벨(Hibernate @Filter)이 담당하므로 DB 커널 차원의 별도
 * tenant 컨텍스트 설정이 불필요하다(템플릿 두 IT 와 동일).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class SecurityIncidentIT {

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
    // Wiring — 실빈 주입(mock 아님)
    // ----------------------------------------------------------------

    @Autowired SecurityIncidentService incidentService;
    @Autowired SecurityIncidentRepository incidentRepo;
    @Autowired AuditLogService auditLogService;
    @Autowired AuditChainVerifier verifier;
    @Autowired DataSource ds;
    @Autowired RedisConnectionFactory redisFactory;

    JdbcTemplate jdbc;

    // Fixed tenant/actor UUIDs for reproducible FK inserts
    private static final UUID TAMPERED_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000C1");
    private static final UUID INTACT_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000C2");
    private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final String ACTOR_EMAIL = "alice@crosscert.com";

    /** APP_OWNER (schema owner) pool — owner-only table cleanup + audit_log tamper (APP_ADMIN 은 UPDATE 불가). */
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
            ds.setPoolName("security-incident-it-owner");
            ownerPool = ds;
        }
        return new JdbcTemplate(ownerPool);
    }

    // ----------------------------------------------------------------
    // State reset — clean tables + seed one tampered tenant + one intact tenant
    // ----------------------------------------------------------------

    @BeforeEach
    void resetState() {
        jdbc = new JdbcTemplate(ds);

        // FK-safe delete order (mirrors AuditChainPerTenantIT) + security_incident.
        ownerJdbc().update("DELETE FROM APP_OWNER.security_incident");
        ownerJdbc().update("DELETE FROM APP_OWNER.audit_log");
        jdbc.update("DELETE FROM APP_OWNER.api_key_scope");
        jdbc.update("DELETE FROM APP_OWNER.api_key");
        jdbc.update("DELETE FROM APP_OWNER.credential");
        jdbc.update("DELETE FROM APP_OWNER.tenant_allowed_origin");
        jdbc.update("DELETE FROM APP_OWNER.tenant_accepted_format");
        jdbc.update("UPDATE APP_OWNER.admin_user SET tenant_id = NULL, role = 'PLATFORM_OPERATOR' WHERE tenant_id IS NOT NULL");
        jdbc.update("DELETE FROM APP_OWNER.tenant");

        seedTenant(TAMPERED_TENANT_ID, "tenant-tampered", "Tampered Tenant");
        seedTenant(INTACT_TENANT_ID, "tenant-intact", "Intact Tenant");

        // Flush Redis session data
        var conn = redisFactory.getConnection();
        try {
            conn.serverCommands().flushAll();
        } finally {
            conn.close();
        }
    }

    private void seedTenant(UUID id, String slug, String displayName) {
        jdbc.update("""
                INSERT INTO APP_OWNER.tenant (id, slug, display_name, rp_id, rp_name, status,
                    require_user_verification, mds_required, created_at, updated_at)
                VALUES (HEXTORAW(?), ?, ?, 'localhost', ?, 'active', 'Y', 'N', SYSTIMESTAMP, SYSTIMESTAMP)
                """, uuidToHex(id), slug, displayName, displayName);
        jdbc.update("""
                INSERT INTO APP_OWNER.tenant_allowed_origin (id, tenant_id, origin, sort_order)
                VALUES (SYS_GUID(), HEXTORAW(?), 'http://localhost:9090', 0)
                """, uuidToHex(id));
        jdbc.update("""
                INSERT INTO APP_OWNER.tenant_accepted_format (id, tenant_id, format)
                VALUES (SYS_GUID(), HEXTORAW(?), 'none')
                """, uuidToHex(id));
    }

    /**
     * 한 테넌트의 audit_log 를 위변조 상태로 만든다(AuditChainPerTenantIT 와 동일 방식):
     * 3행을 append 한 뒤 2번째 행의 payload 를 APP_OWNER 풀로 UPDATE 해 체인 hash 를 깬다.
     *
     * @return 깨진 row id (= verifyTenant().brokenAt() = 서버가 도출할 tamperedEntryId)
     */
    private UUID tamperTenant(UUID tenantId) {
        auditLogService.append(new AuditAppendRequest(
                ACTOR_ID, ACTOR_EMAIL, "TENANT_CREATE",
                "TENANT", tenantId.toString(), tenantId, Map.of("seq", 1)));
        var row2 = auditLogService.append(new AuditAppendRequest(
                ACTOR_ID, ACTOR_EMAIL, "TENANT_UPDATE",
                "TENANT", tenantId.toString(), tenantId, Map.of("seq", 2)));
        auditLogService.append(new AuditAppendRequest(
                ACTOR_ID, ACTOR_EMAIL, "TENANT_UPDATE",
                "TENANT", tenantId.toString(), tenantId, Map.of("seq", 3)));

        UUID tamperTargetId = row2.getId();
        int updated = ownerJdbc().update(
                "UPDATE APP_OWNER.audit_log SET payload = '{\"tampered\":true}' WHERE id = HEXTORAW(?)",
                uuidToHex(tamperTargetId));
        assertThat(updated).as("tamper UPDATE should affect exactly 1 row").isEqualTo(1);

        // Sanity: chain is now genuinely broken at the tampered row.
        AuditChainVerifier.TenantResult r = verifier.verifyTenant(tenantId);
        assertThat(r.ok()).as("tenant chain must be broken after payload tamper").isFalse();
        assertThat(r.brokenAt()).as("brokenAt should point to the tampered row").isEqualTo(tamperTargetId);
        return tamperTargetId;
    }

    /** intact 테넌트: 깨지 않은 정상 행만 append. */
    private void seedIntactChain(UUID tenantId) {
        auditLogService.append(new AuditAppendRequest(
                ACTOR_ID, ACTOR_EMAIL, "TENANT_CREATE",
                "TENANT", tenantId.toString(), tenantId, Map.of("seq", 1)));
        auditLogService.append(new AuditAppendRequest(
                ACTOR_ID, ACTOR_EMAIL, "TENANT_UPDATE",
                "TENANT", tenantId.toString(), tenantId, Map.of("seq", 2)));
        AuditChainVerifier.TenantResult r = verifier.verifyTenant(tenantId);
        assertThat(r.ok()).as("intact tenant chain must verify ok").isTrue();
    }

    // ----------------------------------------------------------------
    // 1. create happy path — 위변조→생성→audit 기록 e2e
    // ----------------------------------------------------------------

    @Test
    void create_happyPath_persistsIncidentAndAuditRow() {
        UUID brokenRowId = tamperTenant(TAMPERED_TENANT_ID);

        SecurityIncident incident = incidentService.create(TAMPERED_TENANT_ID, ACTOR_ID, ACTOR_EMAIL);

        // 반환 incident 상태
        assertThat(incident.getStatus()).isEqualTo(SecurityIncident.STATUS_OPEN);
        assertThat(incident.getSeverity()).isEqualTo(SecurityIncident.SEVERITY_CRITICAL);
        assertThat(incident.getTenantId()).isEqualTo(TAMPERED_TENANT_ID);
        // tamperedEntryId 는 caller 입력이 아니라 서버가 verifyTenant 에서 도출한 깨진 행 id 와 일치해야 한다.
        assertThat(incident.getTamperedEntryId())
                .as("tamperedEntryId must be derived from verifyTenant().brokenAt()")
                .isEqualTo(brokenRowId);

        // DB 에 security_incident 행이 실제로 INSERT 됐는지(APP_ADMIN SELECT 경로).
        Integer incidentRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM APP_OWNER.security_incident WHERE id = HEXTORAW(?) AND status = 'OPEN'",
                Integer.class, uuidToHex(incident.getId()));
        assertThat(incidentRows).as("OPEN security_incident row must be persisted").isEqualTo(1);

        // audit_log 에 AUDIT_CHAIN_INCIDENT_CREATED 행이 추가됐는지.
        Integer createdAuditRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM APP_OWNER.audit_log WHERE action = 'AUDIT_CHAIN_INCIDENT_CREATED'"
                        + " AND target_id = ?",
                Integer.class, incident.getId().toString());
        assertThat(createdAuditRows).as("AUDIT_CHAIN_INCIDENT_CREATED audit row must be appended").isEqualTo(1);
    }

    // ----------------------------------------------------------------
    // 2. create on intact tenant → 422 (IncidentNotTamperedException)
    // ----------------------------------------------------------------

    @Test
    void create_whenNotTampered_throwsNotTampered() {
        seedIntactChain(INTACT_TENANT_ID);

        assertThatThrownBy(() -> incidentService.create(INTACT_TENANT_ID, ACTOR_ID, ACTOR_EMAIL))
                .isInstanceOf(IncidentNotTamperedException.class);

        // 위변조가 아니므로 incident 가 만들어지면 안 된다.
        assertThat(incidentRepo.existsByTenantIdAndStatus(INTACT_TENANT_ID, SecurityIncident.STATUS_OPEN))
                .isFalse();
    }

    // ----------------------------------------------------------------
    // 3. duplicate create → 409, and V50 부분 유니크 인덱스 DB 레벨 강제 검증
    // ----------------------------------------------------------------

    @Test
    void create_duplicate_blockedByPrecheckAndUniqueIndex() {
        tamperTenant(TAMPERED_TENANT_ID);

        SecurityIncident first = incidentService.create(TAMPERED_TENANT_ID, ACTOR_ID, ACTOR_EMAIL);
        assertThat(first.getStatus()).isEqualTo(SecurityIncident.STATUS_OPEN);

        // (a) 서비스 선체크 경로: 같은 테넌트 두 번째 create → 친절한 409.
        assertThatThrownBy(() -> incidentService.create(TAMPERED_TENANT_ID, ACTOR_ID, ACTOR_EMAIL))
                .isInstanceOf(IncidentConflictException.class);

        // (b) V50 부분 유니크 인덱스 DB 레벨 강제: 선체크를 우회한 raw OPEN INSERT 도 거부돼야 한다.
        //     ux_incident_open_per_tenant ON (CASE WHEN status='OPEN' THEN tenant_id END) 가
        //     같은 테넌트의 두 번째 OPEN 행을 막는지 직접 검증한다(ORA-00001 unique constraint).
        assertThatThrownBy(() -> ownerJdbc().update("""
                INSERT INTO APP_OWNER.security_incident
                    (id, tenant_id, type, severity, status, created_at, created_by)
                VALUES (SYS_GUID(), HEXTORAW(?), 'AUDIT_CHAIN_TAMPER', 'CRITICAL', 'OPEN', SYSTIMESTAMP, HEXTORAW(?))
                """, uuidToHex(TAMPERED_TENANT_ID), uuidToHex(ACTOR_ID)))
                .as("partial unique index must reject a 2nd OPEN row for the same tenant")
                .isInstanceOf(DataAccessException.class);

        // 정확히 1건의 OPEN 행만 존재.
        Integer openRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM APP_OWNER.security_incident WHERE tenant_id = HEXTORAW(?) AND status = 'OPEN'",
                Integer.class, uuidToHex(TAMPERED_TENANT_ID));
        assertThat(openRows).as("exactly one OPEN incident per tenant").isEqualTo(1);
    }

    // ----------------------------------------------------------------
    // 4. resolve happy path — resolveIfOpen 원자 UPDATE + ck_security_incident_resolution
    // ----------------------------------------------------------------

    @Test
    void resolve_happyPath_atomicUpdateSatisfiesCheckConstraint() {
        tamperTenant(TAMPERED_TENANT_ID);
        SecurityIncident open = incidentService.create(TAMPERED_TENANT_ID, ACTOR_ID, ACTOR_EMAIL);

        String note = "DBA 복구 완료 — 백업 복원 후 체인 재계산";
        SecurityIncident resolved = incidentService.resolve(open.getId(), note, ACTOR_ID, ACTOR_EMAIL);

        // 반환값
        assertThat(resolved.getStatus()).isEqualTo(SecurityIncident.STATUS_RESOLVED);
        assertThat(resolved.getResolutionNote()).isEqualTo(note);
        assertThat(resolved.getResolvedAt()).isNotNull();
        assertThat(resolved.getResolvedBy()).isEqualTo(ACTOR_ID);

        // DB 상태 — resolveIfOpen 원자 UPDATE 가 실DB 에 반영됐고 CHECK 제약(RESOLVED 시 resolved_* NOT NULL)을 충족.
        Map<String, Object> dbRow = jdbc.queryForMap(
                "SELECT status, resolved_at, resolved_by, resolution_note"
                        + " FROM APP_OWNER.security_incident WHERE id = HEXTORAW(?)",
                uuidToHex(open.getId()));
        assertThat(dbRow.get("STATUS")).isEqualTo("RESOLVED");
        assertThat(dbRow.get("RESOLVED_AT")).as("resolved_at must be set (ck_security_incident_resolution)").isNotNull();
        assertThat(dbRow.get("RESOLVED_BY")).as("resolved_by must be set").isNotNull();
        assertThat(dbRow.get("RESOLUTION_NOTE")).isEqualTo(note);

        // audit_log 에 AUDIT_CHAIN_INCIDENT_RESOLVED 행.
        Integer resolvedAuditRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM APP_OWNER.audit_log WHERE action = 'AUDIT_CHAIN_INCIDENT_RESOLVED'"
                        + " AND target_id = ?",
                Integer.class, open.getId().toString());
        assertThat(resolvedAuditRows).as("AUDIT_CHAIN_INCIDENT_RESOLVED audit row must be appended").isEqualTo(1);
    }

    // ----------------------------------------------------------------
    // 5. resolve guards — already-resolved → 409, not-found → 404
    // ----------------------------------------------------------------

    @Test
    void resolve_alreadyResolved_throwsConflict() {
        tamperTenant(TAMPERED_TENANT_ID);
        SecurityIncident open = incidentService.create(TAMPERED_TENANT_ID, ACTOR_ID, ACTOR_EMAIL);
        incidentService.resolve(open.getId(), "first resolve", ACTOR_ID, ACTOR_EMAIL);

        // resolveIfOpen 가 0행(이미 RESOLVED) → 존재하므로 409. 원자 UPDATE 가 OPEN 만 전이함을 실DB 로 검증.
        assertThatThrownBy(() -> incidentService.resolve(open.getId(), "second resolve", ACTOR_ID, ACTOR_EMAIL))
                .isInstanceOf(IncidentConflictException.class);
    }

    @Test
    void resolve_notFound_throwsNotFound() {
        UUID missing = UUID.randomUUID();
        assertThatThrownBy(() -> incidentService.resolve(missing, "note", ACTOR_ID, ACTOR_EMAIL))
                .isInstanceOf(IncidentNotFoundException.class);
    }

    // ----------------------------------------------------------------
    // 6. RESOLVED 후 재생성 — 부분 유니크 인덱스는 OPEN 만 막으므로 새 OPEN 생성 가능
    //    ("테넌트당 OPEN 1건"의 정확한 의미 검증)
    // ----------------------------------------------------------------

    @Test
    void resolveThenCreateAgain_succeeds_whenStillTampered() {
        UUID brokenRowId = tamperTenant(TAMPERED_TENANT_ID);

        SecurityIncident first = incidentService.create(TAMPERED_TENANT_ID, ACTOR_ID, ACTOR_EMAIL);
        incidentService.resolve(first.getId(), "복구 시도 1", ACTOR_ID, ACTOR_EMAIL);

        // 테넌트는 여전히 위변조 상태(audit_log tamper 가 그대로 남아 있음) → 새 OPEN incident 생성 가능.
        SecurityIncident second = incidentService.create(TAMPERED_TENANT_ID, ACTOR_ID, ACTOR_EMAIL);
        assertThat(second.getStatus()).isEqualTo(SecurityIncident.STATUS_OPEN);
        assertThat(second.getId()).as("a fresh incident is created").isNotEqualTo(first.getId());
        assertThat(second.getTamperedEntryId()).isEqualTo(brokenRowId);

        // 이제 행은 2개: RESOLVED 1 + OPEN 1. OPEN 은 정확히 1건.
        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM APP_OWNER.security_incident WHERE tenant_id = HEXTORAW(?)",
                Integer.class, uuidToHex(TAMPERED_TENANT_ID));
        assertThat(total).as("one RESOLVED + one OPEN").isEqualTo(2);
        Integer openRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM APP_OWNER.security_incident WHERE tenant_id = HEXTORAW(?) AND status = 'OPEN'",
                Integer.class, uuidToHex(TAMPERED_TENANT_ID));
        assertThat(openRows).as("still exactly one OPEN after re-create").isEqualTo(1);
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    /** Convert UUID to 32-char hex (no hyphens) for Oracle HEXTORAW binding. */
    private static String uuidToHex(UUID uuid) {
        return uuid.toString().replace("-", "").toUpperCase();
    }
}
