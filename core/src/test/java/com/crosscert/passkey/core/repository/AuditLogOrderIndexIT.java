package com.crosscert.passkey.core.repository;

import com.crosscert.passkey.core.entity.AuditLog;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 감사 P5 — audit_log 체인 헤드/순회 정렬 {@code (createdAt, id)} 의 결과를 고정하고,
 * V6 {@code audit_log_created_id_ix} 가 체인 헤드 조회(정렬식
 * {@code SYS_EXTRACT_UTC(createdAt) desc, id desc})를 정렬 단계 없이 받치는지 확인한다.
 *
 * <p>특성화 테스트(이 클래스의 앞 4개)는 인덱스 유무와 무관하게 같아야 한다 —
 * {@code id} 가 PK 라 정렬 키가 전순서이므로 실행 계획이 바뀌어도 결과 순서는 SQL
 * 의미로 유일하다. 픽스처는 서로 다른 오프셋(+09:00 / +00:00)을 섞어 TSTZ 정렬이
 * 벽시계가 아니라 UTC 순간 기준임을, 같은 순간의 두 행으로 id tie-breaker 를 고정한다.
 * 이 4개는 통계 없는 소규모 테이블이라 전체 스캔 경로를 탄다. 인덱스 경로의 결과는
 * {@code chainHeadLookup_usesCompositeIndexWithoutSort_andKeepsOrder} 가 고정한다.
 *
 * <p>Testcontainers Oracle XE 21 / bootstrap-schema.sql / owner pool 패턴은
 * {@code ActivityRepositoryFeedPageIT} 와 같다. PSK_APP_ADMIN_USER 는 audit_log 에
 * DELETE 권한이 없어 픽스처 삽입·정리·통계는 스키마 OWNER 커넥션으로 한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class AuditLogOrderIndexIT {

    @SpringBootApplication
    @EntityScan("com.crosscert.passkey.core.entity")
    @EnableJpaRepositories("com.crosscert.passkey.core.repository")
    static class TestApp {
    }

    private static final String ORACLE_IMAGE = "gvenzl/oracle-xe:21-slim-faststart";
    private static final String SYS_PASSWORD = "app_owner_pw";

    @org.testcontainers.junit.jupiter.Container
    static final OracleContainer ORACLE =
            new OracleContainer(ORACLE_IMAGE)
                    .withUsername("PSK_APP_OWNER")
                    .withPassword(SYS_PASSWORD)
                    .withCopyFileToContainer(
                            MountableFile.forClasspathResource("bootstrap-schema.sql"),
                            "/tmp/bootstrap-schema.sql");

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
        reg.add("spring.datasource.username", () -> "PSK_APP_ADMIN_USER");
        reg.add("spring.datasource.password", () -> "admin_pw");
        reg.add("spring.flyway.url", ORACLE::getJdbcUrl);
        reg.add("spring.flyway.user", () -> "PSK_APP_OWNER");
        reg.add("spring.flyway.password", () -> SYS_PASSWORD);
        reg.add("spring.jpa.properties.hibernate.session_factory.statement_inspector",
                SqlCapture.class::getName);
    }

    /** Hibernate 가 실제로 보내는 SQL 을 그대로 기록한다(SQL 은 바꾸지 않는다). */
    public static class SqlCapture implements StatementInspector {
        static final List<String> SQL = new CopyOnWriteArrayList<>();

        @Override
        public String inspect(String sql) {
            SQL.add(sql);
            return sql;
        }
    }

    // RAW(16) 정렬은 부호 없는 바이트 순서다. Java UUID.compareTo(부호 있는 long)와
    // 섞이지 않도록 하위 바이트만 쓰는 작은 양수 id 를 직접 지정한다.
    private static final UUID ID_E = new UUID(0L, 0x01L);
    private static final UUID ID_A = new UUID(0L, 0x0AL);
    private static final UUID ID_B = new UUID(0L, 0x0BL);
    private static final UUID ID_C = new UUID(0L, 0x0CL);
    private static final UUID ID_D = new UUID(0L, 0x0DL);

    @Autowired
    AuditLogRepository auditLogRepository;

    @Autowired
    PlatformTransactionManager txManager;

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
            ds.setUsername("PSK_APP_OWNER");
            ds.setPassword(SYS_PASSWORD);
            ds.setMaximumPoolSize(2);
            ds.setPoolName("audit-log-order-index-it-owner");
            ownerPool = ds;
        }
        return new JdbcTemplate(ownerPool);
    }

    /** 행과 옵티마이저 통계를 함께 지워 테스트 메서드 순서가 실행 계획에 새지 않게 한다. */
    @BeforeEach
    @AfterEach
    void cleanup() {
        ownerJdbc().update("DELETE FROM PSK_APP_OWNER.audit_log");
        ownerJdbc().execute(
                "BEGIN DBMS_STATS.DELETE_TABLE_STATS("
                        + "ownname => 'PSK_APP_OWNER', tabname => 'AUDIT_LOG', cascade_indexes => TRUE); END;");
    }

    private static String hex(UUID id) {
        return String.format("%016X%016X", id.getMostSignificantBits(), id.getLeastSignificantBits());
    }

    private void insertAt(UUID id, String tstz) {
        ownerJdbc().update(
                "INSERT INTO PSK_APP_OWNER.audit_log "
                        + "(id, hash, actor_email, action, payload, created_at) "
                        + "VALUES (HEXTORAW(?), HEXTORAW('AB'), 'p5@example.com', 'P5_ORDER', '{}', "
                        + "TO_TIMESTAMP_TZ(?, 'YYYY-MM-DD HH24:MI:SS.FF6 TZH:TZM'))",
                hex(id), tstz);
    }

    /**
     * UTC 순간 기준 오름차순: A(00:00:00.000000Z) < E(00:00:00.000001Z) < B(00:30Z)
     * < C(01:00:00.000005Z) = D(같은 순간, id 더 큼).
     * B 는 벽시계(00:30)가 A(09:00)보다 작지만 UTC 로는 늦다. E 는 id 가 가장 작지만
     * 시간상 두 번째다 — 정렬이 id 우선이 아님을 고정.
     */
    private void seedOrderingFixture() {
        insertAt(ID_A, "2026-10-13 09:00:00.000000 +09:00");
        insertAt(ID_E, "2026-10-13 00:00:00.000001 +00:00");
        insertAt(ID_B, "2026-10-13 00:30:00.000000 +00:00");
        insertAt(ID_C, "2026-10-13 10:00:00.000005 +09:00");
        insertAt(ID_D, "2026-10-13 01:00:00.000005 +00:00");
    }

    private Optional<AuditLog> lockedHead() {
        return new TransactionTemplate(txManager)
                .execute(status -> auditLogRepository.findLatestForUpdate());
    }

    @Test
    void findLatestForUpdate_returnsUtcLatestThenHighestId() {
        seedOrderingFixture();

        assertThat(lockedHead().map(AuditLog::getId))
                .as("chain head = UTC 최신 순간, 동순간이면 id 가 큰 행")
                .contains(ID_D);
    }

    /**
     * 헤드 판정에서 (1) 벽시계는 가장 늦지만(18:00 +09:00 = 09:00Z) UTC 로는 이른 행이 이기지
     * 않고, (2) 완전히 같은 TSTZ 값(같은 오프셋) 두 행은 id 가 큰 쪽이 헤드다. 벽시계 행의 id 를
     * 가장 크게 둬 id 우선·벽시계 우선 정렬이면 모두 틀리게 했다.
     */
    @Test
    void findLatestForUpdate_mixedOffsetsAndExactTie_picksUtcLatestThenHighestId() {
        UUID wallClockLatest = new UUID(0L, 0x30L);
        UUID tieLow = new UUID(0L, 0x21L);
        UUID tieHigh = new UUID(0L, 0x22L);
        insertAt(wallClockLatest, "2026-10-13 18:00:00.000000 +09:00");
        insertAt(tieHigh, "2026-10-13 10:00:00.000000 +00:00");
        insertAt(tieLow, "2026-10-13 10:00:00.000000 +00:00");

        assertThat(lockedHead().map(AuditLog::getId))
                .as("UTC 최신 순간, 완전 동률이면 id 가 큰 행")
                .contains(tieHigh);
        assertThat(auditLogRepository.findAllOrdered())
                .extracting(AuditLog::getId)
                .containsExactly(wallClockLatest, tieLow, tieHigh);
    }

    @Test
    void findLatestForUpdate_emptyTable_returnsEmpty() {
        assertThat(lockedHead()).isEmpty();
    }

    @Test
    void findAllOrdered_ordersByUtcInstantThenId() {
        seedOrderingFixture();

        List<AuditLog> rows = auditLogRepository.findAllOrdered();

        assertThat(rows)
                .extracting(AuditLog::getId)
                .containsExactly(ID_A, ID_E, ID_B, ID_C, ID_D);
    }

    @Test
    void search_unfiltered_ordersDescByUtcInstantThenId() {
        seedOrderingFixture();

        List<AuditLog> rows = auditLogRepository
                .search(null, null, null, null, null, PageRequest.of(0, 10))
                .getContent();

        assertThat(rows)
                .extracting(AuditLog::getId)
                .containsExactly(ID_D, ID_C, ID_B, ID_E, ID_A);
    }

    private static final String INDEX_NAME = "AUDIT_LOG_CREATED_ID_IX";

    /**
     * findLatestForUpdate 의 정렬식(V6 인덱스 식과 동일)으로 쓴 내부 서브쿼리 형태 — 리터럴 1.
     * 평범한 {@code ORDER BY a.created_at} 은 TSTZ 함수 기반 인덱스로 정렬이 사라지지 않는다
     * (실측: INDEX_DESC 힌트를 줘도 WINDOW SORT) — 그래서 JPQL 정렬식을 인덱스 식에 맞췄다.
     */
    private static final String HEAD_ID_SQL =
            "SELECT a.id FROM PSK_APP_OWNER.audit_log a "
                    + "ORDER BY SYS_EXTRACT_UTC(a.created_at) DESC, a.id DESC FETCH FIRST 1 ROWS ONLY";

    /** 같은 서브쿼리를 Hibernate 처럼 바인드 변수로 — EXPLAIN PLAN 은 bind peeking 을 하지 않는다. */
    private static final String HEAD_ID_BIND_SQL =
            "SELECT a.id FROM PSK_APP_OWNER.audit_log a "
                    + "ORDER BY SYS_EXTRACT_UTC(a.created_at) DESC, a.id DESC FETCH FIRST :1 ROWS ONLY";

    /** wrapper 없이 렌더링되는 경우의 형태(전체 컬럼, CLOB 포함). */
    private static final String HEAD_ROW_SQL =
            "SELECT a.* FROM PSK_APP_OWNER.audit_log a "
                    + "ORDER BY SYS_EXTRACT_UTC(a.created_at) DESC, a.id DESC FETCH FIRST 1 ROWS ONLY";

    @Test
    void compositeIndexExists_onUtcCreatedAtThenId() {
        List<String> tables = ownerJdbc().queryForList(
                "SELECT table_name FROM user_indexes WHERE index_name = ?", String.class, INDEX_NAME);
        assertThat(tables).as("V6 인덱스가 audit_log 에 있어야 한다").containsExactly("AUDIT_LOG");

        List<String> cols = ownerJdbc().queryForList(
                "SELECT column_name FROM user_ind_columns WHERE index_name = ? ORDER BY column_position",
                String.class, INDEX_NAME);
        assertThat(cols).hasSize(2);
        assertThat(cols.get(1)).isEqualTo("ID");

        String expr = ownerJdbc().queryForObject(
                "SELECT column_expression FROM user_ind_expressions "
                        + "WHERE index_name = ? AND column_position = 1",
                String.class, INDEX_NAME);
        assertThat(expr).contains("SYS_EXTRACT_UTC").contains("CREATED_AT");
    }

    @Test
    void chainHeadLookup_usesCompositeIndexWithoutSort_andKeepsOrder() {
        // 옵티마이저가 "작은 테이블이라 FULL SCAN" 으로 빠지지 않게 실데이터 규모 + 통계.
        // 벌크 행은 고정된 과거(2000-01-01 UTC 기준)라 2026 픽스처가 항상 가장 최신이다.
        ownerJdbc().update(
                "INSERT INTO PSK_APP_OWNER.audit_log "
                        + "(id, hash, actor_email, action, payload, created_at) "
                        + "SELECT SYS_GUID(), HEXTORAW('AB'), 'p5-plan@example.com', 'P5_PLAN', '{}', "
                        + "TIMESTAMP '2000-01-01 00:00:00 +00:00' + NUMTODSINTERVAL(LEVEL, 'SECOND') "
                        + "FROM dual CONNECT BY LEVEL <= 20000");
        ownerJdbc().execute(
                "BEGIN DBMS_STATS.GATHER_TABLE_STATS("
                        + "ownname => 'PSK_APP_OWNER', tabname => 'AUDIT_LOG', cascade => TRUE); END;");
        seedOrderingFixture();

        // 1) 단독 서브쿼리 형태(리터럴/바인드, wrapper 없는 전체 컬럼) — 정렬 없이 V6 인덱스를
        //    내림차순으로 읽다가 STOPKEY 로 멈춘다.
        for (String sql : List.of(HEAD_ID_SQL, HEAD_ID_BIND_SQL, HEAD_ROW_SQL)) {
            List<String> plan = explain(sql);
            assertThat(plan)
                    .as("plan for [%s] = %s", sql, plan)
                    .anyMatch(line -> line.endsWith(" " + INDEX_NAME));
            assertThat(plan)
                    .as("정렬 단계가 없어야 한다(WINDOW NOSORT 만 허용): %s", plan)
                    .noneMatch(line -> line.contains("SORT") && !line.contains("NOSORT"));
            assertThat(plan)
                    .as("audit_log 전체 스캔이 없어야 한다: %s", plan)
                    .noneMatch(line -> line.startsWith("TABLE ACCESS FULL"));
            assertThat(plan)
                    .as("단독 서브쿼리는 STOPKEY 로 첫 키에서 멈춰야 한다: %s", plan)
                    .anyMatch(line -> line.contains("STOPKEY"));
        }

        // 2) Hibernate 가 실제로 보낸 findLatestForUpdate SQL(잠금 wrapper:
        //    id IN (… fetch first 1 rows only) FOR UPDATE) — 결과·EXPLAIN·실제 커서 계획.
        //    wrapper 의 세미조인 unnesting 이 FETCH FIRST 를 WINDOW BUFFER 로 바꿔 STOPKEY 는
        //    없다(실측 20,005행: 인덱스 전체 스캔, buffer gets 376→92). 그래서 여기서는 V6 인덱스
        //    사용과 테이블 전체 스캔·전체 정렬 부재만 단언한다.
        SqlCapture.SQL.clear();
        assertThat(lockedHead().map(AuditLog::getId))
                .as("인덱스 경로 chain head")
                .contains(ID_D);
        List<String> lockSql = SqlCapture.SQL.stream()
                .filter(q -> q.toLowerCase(Locale.ROOT).contains("for update"))
                .toList();
        assertThat(lockSql).as("captured SQL = %s", SqlCapture.SQL).hasSize(1);
        String hibernateSql = lockSql.get(0);
        assertThat(hibernateSql.toLowerCase(Locale.ROOT))
                .as("Hibernate SQL = %s", hibernateSql)
                .contains("sys_extract_utc(");

        List<String> hibernateExplain = explain(bindToNamed(hibernateSql));
        assertIndexWithoutSort("EXPLAIN of Hibernate SQL [" + hibernateSql + "]", hibernateExplain);

        List<String> cursorPlan = cursorPlan(hibernateSql);
        assertIndexWithoutSort("V$SQL_PLAN of Hibernate SQL [" + hibernateSql + "]", cursorPlan);

        // 3) 인덱스 경로에서도 결과 행·순서는 특성화 테스트와 동일.
        assertThat(auditLogRepository
                .search(null, null, null, null, null, PageRequest.of(0, 5))
                .getContent())
                .extracting(AuditLog::getId)
                .containsExactly(ID_D, ID_C, ID_B, ID_E, ID_A);
    }

    /**
     * 실제 Hibernate SQL 의 계획 판정: V6 인덱스 사용, audit_log 전체 스캔 없음,
     * 전체 정렬(WINDOW SORT / SORT ORDER BY) 없음. locking wrapper 의 세미조인이 1건짜리
     * 서브쿼리 결과에 SORT UNIQUE·BUFFER SORT 를 내므로 그 밖의 SORT 는 막지 않는다.
     * STOPKEY 는 요구하지 않는다(위 2) 주석 — 후속 ROWNUM native 형태의 과제).
     */
    private static void assertIndexWithoutSort(String what, List<String> plan) {
        assertThat(plan).as("%s = %s", what, plan)
                .anyMatch(line -> line.endsWith(" " + INDEX_NAME))
                .noneMatch(line -> line.startsWith("TABLE ACCESS FULL"))
                .noneMatch(line -> line.startsWith("WINDOW SORT"))
                .noneMatch(line -> line.startsWith("SORT ORDER BY"));
    }

    /** JDBC {@code ?} 자리표시자를 EXPLAIN PLAN 이 받는 {@code :n} 바인드로 바꾼다(값 없이 계획만). */
    private static String bindToNamed(String sql) {
        StringBuilder out = new StringBuilder();
        int n = 0;
        for (char ch : sql.toCharArray()) {
            if (ch == '?') {
                out.append(':').append(++n);
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }

    /**
     * 방금 실행된 커서의 실제 계획(V$SQL_PLAN). bind peeking 이 반영된 실제 실행 계획이다.
     * V$ 뷰는 SYSDBA 로 읽는다(컨테이너 SYS 비밀번호 = SYS_PASSWORD).
     */
    private static List<String> cursorPlan(String sqlText) {
        Properties props = new Properties();
        props.setProperty("user", "sys");
        props.setProperty("password", SYS_PASSWORD);
        props.setProperty("internal_logon", "sysdba");
        try (Connection con = DriverManager.getConnection(ORACLE.getJdbcUrl(), props);
             PreparedStatement find = con.prepareStatement(
                     "SELECT sql_id, child_number FROM v$sql "
                             + "WHERE DBMS_LOB.COMPARE(sql_fulltext, TO_CLOB(?)) = 0 "
                             + "ORDER BY last_active_time DESC, child_number DESC")) {
            find.setString(1, sqlText);
            String sqlId;
            int child;
            try (ResultSet rs = find.executeQuery()) {
                assertThat(rs.next()).as("V$SQL 에 Hibernate SQL 커서가 있어야 한다: %s", sqlText).isTrue();
                sqlId = rs.getString(1);
                child = rs.getInt(2);
            }
            List<String> lines = new ArrayList<>();
            try (PreparedStatement plan = con.prepareStatement(
                    "SELECT operation, options, object_name FROM v$sql_plan "
                            + "WHERE sql_id = ? AND child_number = ? ORDER BY id")) {
                plan.setString(1, sqlId);
                plan.setInt(2, child);
                try (ResultSet rs = plan.executeQuery()) {
                    while (rs.next()) {
                        String options = rs.getString(2);
                        String object = rs.getString(3);
                        lines.add(rs.getString(1)
                                + (options == null ? "" : " " + options)
                                + (object == null ? "" : " " + object));
                    }
                }
            }
            return lines;
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("V$SQL_PLAN lookup failed", e);
        }
    }

    /**
     * PLAN_TABLE 은 세션별 GTT 이므로 EXPLAIN 과 조회를 한 커넥션에서 수행한다.
     * 각 줄은 "OPERATION OPTIONS OBJECT_NAME" 형태(예: "INDEX FULL SCAN DESCENDING AUDIT_LOG_CREATED_ID_IX").
     */
    private List<String> explain(String sql) {
        return ownerJdbc().execute((ConnectionCallback<List<String>>) con -> {
            try (Statement st = con.createStatement()) {
                st.execute("DELETE FROM plan_table WHERE statement_id = 'P5'");
                st.execute("EXPLAIN PLAN SET STATEMENT_ID = 'P5' FOR " + sql);
                List<String> lines = new ArrayList<>();
                try (ResultSet rs = st.executeQuery(
                        "SELECT operation, options, object_name FROM plan_table "
                                + "WHERE statement_id = 'P5' ORDER BY id")) {
                    while (rs.next()) {
                        String options = rs.getString(2);
                        String object = rs.getString(3);
                        lines.add(rs.getString(1)
                                + (options == null ? "" : " " + options)
                                + (object == null ? "" : " " + object));
                    }
                }
                return lines;
            }
        });
    }
}
