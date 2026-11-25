package com.crosscert.passkey.admin.usage

import com.crosscert.passkey.core.ceremony.CeremonyAction
import com.crosscert.passkey.core.config.KstTime
import com.crosscert.passkey.core.entity.UsagePeriodClose
import com.crosscert.passkey.core.repository.AuditLogRepository
import com.crosscert.passkey.core.repository.UsageMonthlyRepository
import com.crosscert.passkey.core.repository.UsagePeriodCloseRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.OracleContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.MountableFile
import tools.jackson.databind.ObjectMapper
import java.nio.ByteBuffer
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

/**
 * 월 마감 IT — 실제 Oracle 에서 마감 트랜잭션·감사 payload·불변성·권한을 확인한다.
 *
 * 시계는 2026-11-25 10:00 KST 로 고정한다(2026-10 은 마감 대상, 부분 월 아님). 데이터소스는 PSK_APP_ADMIN_USER 라
 * 마감 테이블 UPDATE·DELETE 가 권한 오류(ORA-01031)로 막히는지도 같은 접속으로 본다.
 * 컨테이너·컨텍스트를 클래스 안에서 공유하므로, 각 테스트는 [ensureClosed] 로 "2026-10 마감 완료" 상태를 한 번만 만든 뒤
 * 그 위에서 단언한다(테스트 순서에 기대지 않는다).
 */
@SpringBootTest(properties = ["passkey.usage.ledger-start-date=2026-10-01"])
@ActiveProfiles("test")
@Testcontainers
class UsageCloseIT {

    companion object {
        private const val ORACLE_IMAGE = "gvenzl/oracle-xe:21-slim-faststart"
        private const val SYS_PASSWORD = "app_owner_pw"

        @Container
        @JvmField
        val ORACLE: OracleContainer = OracleContainer(ORACLE_IMAGE)
            .withUsername("PSK_APP_OWNER")
            .withPassword(SYS_PASSWORD)
            .withCopyFileToContainer(
                MountableFile.forClasspathResource("test-db-init.sql"),
                "/tmp/test-db-init.sql",
            )

        @Container
        @JvmField
        val REDIS: GenericContainer<*> = GenericContainer("redis:7-alpine")
            .withExposedPorts(6379)

        @JvmStatic
        @DynamicPropertySource
        fun registerProps(reg: DynamicPropertyRegistry) {
            val exec = ORACLE.execInContainer(
                "bash", "-c",
                "sqlplus -S sys/" + SYS_PASSWORD + "@localhost:1521/XEPDB1 as sysdba " +
                    "@/tmp/test-db-init.sql",
            )
            if (exec.exitCode != 0) {
                throw IllegalStateException(
                    "test-db-init.sql failed (exit=" + exec.exitCode + ")\n" +
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

        private val TENANT_A: UUID = UUID.fromString("0a000000-0000-0000-0000-00000000000a")
        private val TENANT_B: UUID = UUID.fromString("0b000000-0000-0000-0000-00000000000b")

        /** 클래스 안에서 한 번만 시드·마감한다. */
        private var closedOnce = false
        private var firstCloseResult: List<String> = emptyList()

        private fun uuidToBytes(id: UUID): ByteArray {
            val bb = ByteBuffer.allocate(16)
            bb.putLong(id.mostSignificantBits)
            bb.putLong(id.leastSignificantBits)
            return bb.array()
        }
    }

    @TestConfiguration
    class FixedClockConfig {
        @Bean
        @Primary
        fun fixedClock(): Clock = Clock.fixed(OffsetDateTime.parse("2026-11-25T10:00+09:00").toInstant(), KstTime.ZONE)
    }

    @Autowired lateinit var close: UsageCloseService
    @Autowired lateinit var rollup: UsageRollupService
    @Autowired lateinit var monthly: UsageMonthlyRepository
    @Autowired lateinit var closes: UsagePeriodCloseRepository
    @Autowired lateinit var auditRepo: AuditLogRepository
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var ds: DataSource

    private val jdbc by lazy { JdbcTemplate(ds) }

    @BeforeEach
    fun ensureClosed() {
        if (closedOnce) return
        seed(TENANT_A, CeremonyAction.REGISTRATION_SUCCESS, 2, "2026-10-15 12:00:00")
        seed(TENANT_A, CeremonyAction.AUTHENTICATION_SUCCESS, 5, "2026-10-31 23:30:00")
        seed(TENANT_A, CeremonyAction.AUTHENTICATION_BEGIN, 7, "2026-10-20 09:00:00")
        seed(TENANT_B, CeremonyAction.AUTHENTICATION_SUCCESS, 1, "2026-10-01 00:00:00")
        // 이번 달(진행 중) 행 — 마감 대상이 아니다.
        seed(TENANT_A, CeremonyAction.AUTHENTICATION_SUCCESS, 3, "2026-11-02 10:00:00")
        assertThat(close.backfillIfNeeded()).isTrue()
        firstCloseResult = close.closeDueMonths()
        closedOnce = true
    }

    private fun seed(tenant: UUID, action: String, count: Int, kstLocal: String) {
        repeat(count) {
            jdbc.update(
                "INSERT INTO PSK_APP_OWNER.ceremony_event (tenant_id, action, created_at, updated_at) VALUES " +
                    "(?, ?, TO_TIMESTAMP_TZ(? || ' +09:00', 'YYYY-MM-DD HH24:MI:SS TZH:TZM'), SYSTIMESTAMP)",
                uuidToBytes(tenant), action, kstLocal,
            )
        }
    }

    private fun countAudit(action: String): Int = auditRepo.findByActionIn(listOf(action)).size

    private fun dailyRowsFor(fromDay: String, toDayExclusive: String): List<Map<String, Any?>> = jdbc.queryForList(
        "SELECT RAWTOHEX(tenant_id) t, usage_date, reg_success, auth_success, reg_attempt, auth_attempt, basis " +
            "FROM PSK_APP_OWNER.usage_daily WHERE usage_date >= DATE '$fromDay' AND usage_date < DATE '$toDayExclusive' " +
            "ORDER BY 1, 2",
    )

    private fun monthlySnapshot(period: String) = monthly.findByPeriod(period)
        .map { listOf(it.tenantId, it.regSuccess, it.authSuccess, it.regAttempt, it.authAttempt) }
        .sortedBy { it[0].toString() }

    @Test
    fun closesMonth_andAuditPayloadMatchesMonthlyRows() {
        assertThat(firstCloseResult).containsExactly("2026-10")

        assertThat(monthlySnapshot("2026-10")).containsExactly(
            listOf(TENANT_A, 2L, 5L, 0L, 7L),
            listOf(TENANT_B, 0L, 1L, 0L, 0L),
        )
        val pc = closes.findById("2026-10").orElseThrow()
        assertThat(pc.status).isEqualTo(UsagePeriodClose.STATUS_CLOSED)
        assertThat(pc.basis).isEqualTo("LEDGER")
        assertThat(listOf(pc.regSuccess, pc.authSuccess, pc.tenantCount.toLong())).containsExactly(2L, 6L, 2L)
        assertThat(closes.existsById("2026-11")).isFalse()

        val log = auditRepo.findByActionIn(listOf("USAGE_MONTH_CLOSED")).single()
        assertThat(log.tenantId).isNull()
        assertThat(log.targetType).isEqualTo("usage_period")
        assertThat(log.targetId).isEqualTo("2026-10")
        val payload = mapper.readTree(log.payload)
        assertThat(payload.get("period").asString()).isEqualTo("2026-10")
        assertThat(payload.get("status").asString()).isEqualTo("CLOSED")
        assertThat(payload.get("basis").asString()).isEqualTo("LEDGER")
        assertThat(payload.get("registrationSuccess").asLong()).isEqualTo(pc.regSuccess)
        assertThat(payload.get("authenticationSuccess").asLong()).isEqualTo(pc.authSuccess)
        // JsonNode.map 은 노드 자신에 대한 변환이라(Jackson 3) 원소는 인덱스로 꺼낸다.
        val tenantsNode = payload.get("tenants")
        val fromAudit = (0 until tenantsNode.size()).map { tenantsNode.get(it) }.map {
            listOf(UUID.fromString(it.get("tenantId").asString()), it.get("registrationSuccess").asLong(), it.get("authenticationSuccess").asLong())
        }
        assertThat(fromAudit).isEqualTo(monthlySnapshot("2026-10").map { listOf(it[0], it[1], it[2]) })

        // 백필 표식도 남았다(부분 월 없음 — 2026-10-01 은 2026-08-27 보다 뒤).
        val marker = mapper.readTree(auditRepo.findByActionIn(listOf("USAGE_BACKFILL_DONE")).single().payload)
        assertThat(marker.get("partialPeriods").size()).isZero()
        assertThat(countAudit("USAGE_MONTH_PARTIAL")).isZero()
    }

    @Test
    fun closedMonth_isNotChangedByLaterRollup() {
        val dailyBefore = dailyRowsFor("2026-10-01", "2026-11-01")
        val monthlyBefore = monthlySnapshot("2026-10")
        assertThat(dailyBefore).isNotEmpty()

        seed(TENANT_A, CeremonyAction.REGISTRATION_SUCCESS, 4, "2026-10-31 23:59:00")
        seed(TENANT_B, CeremonyAction.REGISTRATION_SUCCESS, 1, "2026-10-10 10:00:00")
        rollup.rollupRecent()
        assertThat(rollup.rollupDays(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))).isZero()

        assertThat(dailyRowsFor("2026-10-01", "2026-11-01")).isEqualTo(dailyBefore)
        assertThat(monthlySnapshot("2026-10")).isEqualTo(monthlyBefore)
        assertThat(close.closeDueMonths()).isEmpty()
        assertThat(monthlySnapshot("2026-10")).isEqualTo(monthlyBefore)
    }

    @Test
    fun closeTwice_secondIsNoop() {
        assertThat(close.closeDueMonths()).isEmpty()
        assertThat(close.closeDueMonths()).isEmpty()
        assertThat(close.backfillIfNeeded()).isFalse()

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM PSK_APP_OWNER.usage_period_close", Int::class.java)).isEqualTo(1)
        assertThat(countAudit("USAGE_MONTH_CLOSED")).isEqualTo(1)
        assertThat(countAudit("USAGE_BACKFILL_DONE")).isEqualTo(1)
        assertThat(close.releasedBefore()).isEqualTo(OffsetDateTime.parse("2026-11-01T00:00+09:00"))
    }

    @Test
    fun adminRole_cannotUpdateClosedTables() {
        val statements = listOf(
            "UPDATE PSK_APP_OWNER.usage_monthly SET reg_success = reg_success + 1",
            "DELETE FROM PSK_APP_OWNER.usage_monthly",
            "UPDATE PSK_APP_OWNER.usage_period_close SET reg_success = reg_success + 1",
            "DELETE FROM PSK_APP_OWNER.usage_period_close",
        )
        for (sql in statements) {
            assertThatThrownBy { jdbc.update(sql) }
                .`as`(sql)
                .hasStackTraceContaining("ORA-01031")
        }
        assertThat(monthly.findByPeriod("2026-10")).hasSize(2)
    }
}
