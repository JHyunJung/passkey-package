package com.crosscert.passkey.admin.audit

import com.crosscert.passkey.admin.web.AdminWebSliceTest
import com.crosscert.passkey.core.api.GlobalExceptionHandler
import com.crosscert.passkey.core.config.KstTime
import com.crosscert.passkey.core.entity.AuditLog
import com.crosscert.passkey.core.entity.Tenant
import com.crosscert.passkey.core.repository.AuditLogRepository
import com.crosscert.passkey.core.repository.TenantRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * P3 특성화 — GET /admin/api/audit/chain/overview 의 응답 본문을 고정한다.
 *
 * 실제 [AuditChainVerifier] 와 실제 MVC Jackson 직렬화로, tenant 별 검증 결과·행 수·
 * 시간 버킷(경계 포함)·break 이후 행 집계를 문자열 단위로 고정한다. audit_log 1회 로드 변경
 * 전후 모두 통과해야 한다. 슬라이스 구성은 [AdminWebSliceTest] (verifier 만 실제 빈).
 */
@AdminWebSliceTest(controllers = [AuditChainMonitorController::class])
@Import(GlobalExceptionHandler::class, AuditChainVerifier::class)
class AuditChainOverviewCharacterizationTest {

    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var mapper: ObjectMapper

    // Controller collaborators (verifier 는 @Import 한 실제 빈)
    @MockitoBean
    lateinit var backfillService: AuditChainBackfillService

    @MockitoBean
    lateinit var incidents: SecurityIncidentService

    // AdminWebSliceTest 묶음의 mock — 스텁·검증용으로 받는다.
    @Autowired
    lateinit var clock: Clock

    @Autowired
    lateinit var tenantRepo: TenantRepository

    @Autowired
    lateinit var auditRepo: AuditLogRepository

    @BeforeEach
    fun seed() {
        // 고정 시계 → verificationMs = 0, verifiedAt = NOW (출력이 결정적).
        whenever(clock.instant()).thenReturn(NOW)
        whenever(clock.millis()).thenReturn(NOW.toEpochMilli())

        val acme = mock<Tenant>()
        whenever(acme.id).thenReturn(TENANT_A)
        whenever(acme.displayName).thenReturn("Acme")
        val idle = mock<Tenant>() // audit 행이 없는 tenant 는 overview 에 나오지 않는다
        whenever(idle.id).thenReturn(TENANT_IDLE)
        whenever(idle.displayName).thenReturn("Idle")
        whenever(tenantRepo.findAll()).thenReturn(listOf(acme, idle))

        // 순서 = 응답 tenants 순서. null = platform chain.
        whenever(auditRepo.findDistinctTenantIds()).thenReturn(listOf(TENANT_A, null, TENANT_B))

        // A: 창 밖(30h), 정확히 24h 전(idx 24 → 버림), 정확히 60분 전(idx 1), 30분 전(idx 0), 미래 +5분(idx 0).
        whenever(auditRepo.findAllByTenantOrdered(TENANT_A)).thenReturn(
            chain(TENANT_A, 100, minutesAgo(30 * 60), minutesAgo(24 * 60), minutesAgo(60), minutesAgo(30), minutesAgo(-5)),
        )
        // platform(null): 5시간 전 1행.
        whenever(auditRepo.findAllByTenantOrdered(null)).thenReturn(chain(null, 200, minutesAgo(5 * 60)))
        // B: 3h, 2h(변조 → break), 90분(break 이후 행 — 집계에는 포함돼야 한다).
        val b = chain(TENANT_B, 300, minutesAgo(3 * 60), minutesAgo(2 * 60), minutesAgo(90))
        b[1] = withPayload(b[1], "{\"seq\":99}")
        whenever(auditRepo.findAllByTenantOrdered(TENANT_B)).thenReturn(b)
    }

    @Test
    @WithMockUser(roles = ["PLATFORM_OPERATOR"])
    fun overview24hMatchesPinnedResultIncludingRowsAfterBreak() {
        val a = LongArray(24); a[22] = 1; a[23] = 2
        val p = LongArray(24); p[18] = 1
        val b = LongArray(24); b[20] = 1; b[21] = 1; b[22] = 1
        val expected = AuditChainOverview(
            NOW, 24, 60,
            AuditChainOverview.Totals(2, 3, 1, 9, 0),
            listOf(
                AuditChainTenantOverview(TENANT_A, "Acme", true, 5, a.toList(), null),
                AuditChainTenantOverview(null, "[platform]", true, 1, p.toList(), null),
                AuditChainTenantOverview(TENANT_B, TENANT_B.toString(), false, 3, b.toList(), B_BROKEN),
            ),
        )

        val body = mvc.perform(get("/admin/api/audit/chain/overview"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totals.tenantsTampered").value(1))
            .andExpect(jsonPath("$.totals.verifiedRows").value(9))
            .andExpect(jsonPath("$.tenants[1].tenantName").value("[platform]"))
            .andExpect(jsonPath("$.tenants[2].intact").value(false))
            .andExpect(jsonPath("$.tenants[2].tamperedEntryId").value(B_BROKEN.toString()))
            .andExpect(jsonPath("$.tenants[2].verifiedRows").value(3))
            .andExpect(jsonPath("$.tenants[2].buckets[22]").value(1))
            .andReturn().response.getContentAsString(StandardCharsets.UTF_8)

        assertThat(body).isEqualTo(mapper.writeValueAsString(expected))
    }

    @Test
    @WithMockUser(roles = ["PLATFORM_OPERATOR"])
    fun overview2hWindowKeepsBucketBoundariesAndCountsAllRows() {
        // since = NOW-2h. A: 60분 → [0], 30분·미래 5분 → [1]. platform 5h → 없음.
        // B: 3h 는 since 이전(skip), 정확히 2h 는 idx 2(버림), 90분 → [0]. verifiedRows 는 창과 무관하게 전체 행 수.
        val expected = AuditChainOverview(
            NOW, 2, 60,
            AuditChainOverview.Totals(2, 3, 1, 9, 0),
            listOf(
                AuditChainTenantOverview(TENANT_A, "Acme", true, 5, listOf(1L, 2L), null),
                AuditChainTenantOverview(null, "[platform]", true, 1, listOf(0L, 0L), null),
                AuditChainTenantOverview(TENANT_B, TENANT_B.toString(), false, 3, listOf(1L, 0L), B_BROKEN),
            ),
        )

        val body = mvc.perform(get("/admin/api/audit/chain/overview").param("windowHours", "2"))
            .andExpect(status().isOk())
            .andReturn().response.getContentAsString(StandardCharsets.UTF_8)

        assertThat(body).isEqualTo(mapper.writeValueAsString(expected))
    }

    /** P3 — overview 는 tenant 마다 audit_log 를 한 번만 읽어 검증과 버킷 집계에 함께 쓴다. */
    @Test
    @WithMockUser(roles = ["PLATFORM_OPERATOR"])
    fun overviewReadsEachTenantChainOnce() {
        mvc.perform(get("/admin/api/audit/chain/overview")).andExpect(status().isOk())

        verify(auditRepo, times(1)).findDistinctTenantIds()
        verify(auditRepo, times(1)).findAllByTenantOrdered(TENANT_A)
        verify(auditRepo, times(1)).findAllByTenantOrdered(null)
        verify(auditRepo, times(1)).findAllByTenantOrdered(TENANT_B)
        verify(auditRepo, never()).findAllOrdered()
    }

    // ---- fixture helpers ------------------------------------------------------

    private companion object {
        val NOW: Instant = Instant.parse("2026-06-01T00:00:00Z")
        val TENANT_A: UUID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001")
        val TENANT_B: UUID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002")
        val TENANT_IDLE: UUID = UUID.fromString("cccccccc-0000-0000-0000-000000000003")

        /** TENANT_B 체인의 2번째 행(id base 300 + 2) — payload 변조로 여기서 break. */
        val B_BROKEN: UUID = rowId(302)

        fun minutesAgo(minutes: Long): OffsetDateTime =
            OffsetDateTime.ofInstant(NOW.minus(minutes, ChronoUnit.MINUTES), KstTime.ZONE)

        /** tenant chain(tenant_prev_hash/tenant_hash) 을 AuditLogService.computeHash 로 유효하게 만든다. id = base+1.. */
        fun chain(tenantId: UUID?, idBase: Int, vararg times: OffsetDateTime): MutableList<AuditLog> {
            val rows = ArrayList<AuditLog>()
            var prev: ByteArray? = null
            for (i in times.indices) {
                val payload = "{\"seq\":$i}"
                val req = AuditAppendRequest(
                    null, "op@example.com", "ACTION_$i", "TENANT", "T_$i", tenantId, mapOf("seq" to i),
                )
                val hash = AuditLogService.computeHash(prev, req, payload, times[i])
                val row = AuditLog(
                    null, hash, null, req.actorEmail, req.action, req.targetType, req.targetId,
                    tenantId, prev, hash, payload, times[i],
                )
                setId(row, rowId(idBase + i + 1))
                rows.add(row)
                prev = hash
            }
            return rows
        }

        /** 저장된 hash 는 그대로 두고 payload 만 바꾼 행(위변조). */
        fun withPayload(r: AuditLog, payload: String): AuditLog {
            val t = AuditLog(
                r.prevHash, r.hash, r.actorId, r.actorEmail, r.action,
                r.targetType, r.targetId, r.tenantId,
                r.tenantPrevHash, r.tenantHash, payload, r.createdAt,
            )
            setId(t, r.id)
            return t
        }

        fun rowId(n: Int): UUID = UUID.fromString(String.format("00000000-0000-0000-0000-%012d", n))

        fun setId(row: AuditLog, id: UUID) {
            try {
                val f = AuditLog::class.java.superclass.getDeclaredField("id")
                f.isAccessible = true
                f.set(row, id)
            } catch (e: Exception) {
                throw RuntimeException(e)
            }
        }
    }
}
