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
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
@ExtendWith(OutputCaptureExtension::class)
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
    @Autowired lateinit var txTemplate: TransactionTemplate

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

    private fun countClosedAudit(period: String): Int =
        auditRepo.findByActionIn(listOf("USAGE_MONTH_CLOSED")).count { it.targetId == period }

    private fun countCloseRows(period: String): Int = jdbc.queryForObject(
        "SELECT COUNT(*) FROM PSK_APP_OWNER.usage_period_close WHERE period = ?", Int::class.java, period,
    )!!

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

        val log = auditRepo.findByActionIn(listOf("USAGE_MONTH_CLOSED")).single { it.targetId == "2026-10" }
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

        assertThat(countCloseRows("2026-10")).isEqualTo(1)
        assertThat(countClosedAudit("2026-10")).isEqualTo(1)
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

    /**
     * 진짜 경합: 두 트랜잭션이 같은 달(2026-09)을 동시에 확정한다. 앞 트랜잭션은 마감 행을 INSERT 한 채 열려 있고, 그동안 뒤
     * 트랜잭션이 존재 확인(아직 커밋 전이라 false)을 지나 같은 PK INSERT 에서 기다리다가, 앞 트랜잭션 커밋 뒤 ORA-00001 로 건너뛴다.
     *
     * 공개 메서드(closeDueMonths) 두 번으로는 이 겹침을 확정적으로 만들 수 없다 — 트랜잭션을 붙잡아 둘 지점이 없고, 그달
     * usage_daily 행이 있으면 재집계 MERGE 의 행 락에서 먼저 줄 서므로 뒤 트랜잭션은 빠른 경로(존재 확인)로 빠진다.
     * 그래서 한 달 확정 루틴(closeMonth)을 두 스레드에서 직접 돌리고, 앞 스레드는 바깥 트랜잭션으로 커밋을 latch 뒤로 미룬다.
     * 2026-09 는 원본(ceremony_event)이 없고 usage_daily 만 직접 넣었다 — 재집계 MERGE 가 잠그는 행이 없어 뒤 트랜잭션이
     * 마감 행 INSERT 까지 간다. 2026-10 바로 앞 달이라 다른 테스트의 closeDueMonths 결과(빈 목록)도 바뀌지 않는다.
     */
    @Test
    fun concurrentClose_samePeriod_exactlyOneWins(output: CapturedOutput) {
        val sep = java.time.YearMonth.of(2026, 9)
        for ((tenant, reg, auth) in listOf(Triple(TENANT_A, 3L, 4L), Triple(TENANT_B, 0L, 2L))) {
            jdbc.update(
                "INSERT INTO PSK_APP_OWNER.usage_daily " +
                    "(tenant_id, usage_date, reg_success, auth_success, reg_attempt, auth_attempt, basis, aggregated_at) " +
                    "VALUES (?, DATE '2026-09-10', ?, ?, 0, 0, 'LEGACY', SYSTIMESTAMP)",
                uuidToBytes(tenant), reg, auth,
            )
        }
        val holdMs = 3000L
        val firstInserted = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<Boolean> {
                txTemplate.execute {
                    val r = close.closeMonth(sep, UsagePeriodClose.STATUS_CLOSED)
                    firstInserted.countDown()
                    Thread.sleep(holdMs) // 뒤 트랜잭션이 PK 에서 기다리는 동안 커밋을 미룬다
                    r
                }
            }
            val second = pool.submit<Pair<Boolean, Long>> {
                check(firstInserted.await(30, TimeUnit.SECONDS)) { "first close did not reach insert" }
                val t0 = System.nanoTime()
                val r = close.closeMonth(sep, UsagePeriodClose.STATUS_CLOSED)
                r to TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
            }

            assertThat(first.get(60, TimeUnit.SECONDS)).isTrue()
            val (secondResult, secondElapsedMs) = second.get(60, TimeUnit.SECONDS) // 예외가 나면 여기서 터진다
            assertThat(secondResult).isFalse()
            // 뒤 트랜잭션이 앞 트랜잭션 커밋까지 막혀 있었다 = 존재 확인을 지나 PK INSERT 에서 기다렸다.
            assertThat(secondElapsedMs).isGreaterThan(holdMs / 2)
            assertThat(output.out).contains("usage month closed by another instance — skip: period=2026-09")
        } finally {
            pool.shutdownNow()
        }

        assertThat(countCloseRows("2026-09")).isEqualTo(1)
        assertThat(monthlySnapshot("2026-09")).containsExactly(
            listOf(TENANT_A, 3L, 4L, 0L, 0L),
            listOf(TENANT_B, 0L, 2L, 0L, 0L),
        )
        assertThat(countClosedAudit("2026-09")).isEqualTo(1)
        val pc = closes.findById("2026-09").orElseThrow()
        assertThat(listOf(pc.regSuccess, pc.authSuccess, pc.tenantCount.toLong())).containsExactly(3L, 6L, 2L)
    }

    /**
     * 경합 테스트가 못 가르는 순서 — 뒤 트랜잭션의 존재 확인 "뒤" 에 앞 트랜잭션이 커밋한 경우 — 를 직접 본다.
     * 이미 커밋된 기간으로 새 인스턴스를 saveAndFlush 하면 PK 위반이 나야 한다. ID 지정 엔티티의 기본 판정(merge)이었다면
     * 기존 행을 찾아 예외 없이 지나갔다(UsagePeriodClose 가 Persistable 인 이유).
     */
    @Test
    fun saveOfNewCloseRow_forCommittedPeriod_isPkViolation() {
        assertThatThrownBy {
            txTemplate.execute {
                closes.saveAndFlush(
                    UsagePeriodClose("2026-10", UsagePeriodClose.STATUS_CLOSED, "LEDGER", 9, 9, 9, 9, 1, OffsetDateTime.now()),
                )
            }
        }.isInstanceOf(org.springframework.dao.DataIntegrityViolationException::class.java)
        assertThat(closes.findById("2026-10").orElseThrow().regSuccess).isEqualTo(2L)
    }
}
