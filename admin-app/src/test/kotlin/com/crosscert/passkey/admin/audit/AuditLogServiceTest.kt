package com.crosscert.passkey.admin.audit

import com.crosscert.passkey.core.config.DbSchemaProperties
import com.crosscert.passkey.core.config.KstTime
import com.crosscert.passkey.core.entity.AuditLog
import com.crosscert.passkey.core.repository.AuditLogRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.Query
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.isNull
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

class AuditLogServiceTest {

    private lateinit var repo: AuditLogRepository
    private lateinit var em: EntityManager
    private lateinit var service: AuditLogService

    // KST clock: the fixed absolute instant (UTC midnight) presents as
    // 2026-06-01T09:00+09:00 wall-clock, matching production's KstTime.ZONE.
    private val clock: Clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), KstTime.ZONE)

    @BeforeEach
    fun setUp() {
        repo = mock()
        em = mock()
        // Stub the chain-lock SELECT FOR UPDATE so unit tests don't need a DB.
        val lockQuery: Query = mock()
        whenever(em.createNativeQuery(anyString())).thenReturn(lockQuery)
        whenever(lockQuery.setParameter(anyString(), anyOrNull<Any>())).thenReturn(lockQuery)
        whenever(lockQuery.singleResult).thenReturn(1)
        // Phase B — findLatestByTenant 기본 stub: genesis (테넌트 chain 없음)
        whenever(repo.findLatestByTenant(isNull(), anyOrNull())).thenReturn(listOf())
        whenever(repo.findLatestByTenant(any<UUID>(), anyOrNull())).thenReturn(listOf())
        service = AuditLogService(
            repo, em, ObjectMapper(), clock,
            DbSchemaProperties("PSK_APP_OWNER"),
        )
    }

    @Test
    fun genesisRowHasNullPrevHashAndHashOfInputs() {
        whenever(repo.findLatestForUpdate()).thenReturn(Optional.empty())

        val payload = LinkedHashMap<String, Any?>()
        payload["ip"] = "127.0.0.1"
        payload["ua"] = "JUnit"

        val actorUuid = UUID.fromString("00000000-0000-0000-0000-000000000042")
        service.append(
            AuditAppendRequest(
                actorUuid, "alice@example.com", "ADMIN_LOGIN",
                null, null, null, payload,
            ),
        )

        val captor = argumentCaptor<AuditLog>()
        verify(repo).save(captor.capture())
        val row = captor.lastValue

        assertThat(row.prevHash).isNull()
        assertThat(row.actorId).isEqualTo(actorUuid)
        assertThat(row.action).isEqualTo("ADMIN_LOGIN")
        assertThat(row.payload).isEqualTo("{\"ip\":\"127.0.0.1\",\"ua\":\"JUnit\"}")

        // Manually recompute the expected hash (actorId is UUID string in hash input).
        // KST clock: OffsetDateTime.now(clock).truncatedTo(MICROS).toString() of the
        // fixed UTC-midnight instant is "2026-06-01T09:00+09:00" (seconds collapse to
        // empty when zero; +09:00 offset is part of the hashed form).
        val expected = MessageDigest.getInstance("SHA-256")
            .digest(
                (
                    "|" + actorUuid + "|ADMIN_LOGIN||" +
                        "|2026-06-01T09:00+09:00|" +
                        row.payload
                    ).toByteArray(StandardCharsets.UTF_8),
            )
        assertThat(row.hash).containsExactly(*toIntArray(expected))
    }

    @Test
    fun secondRowChainsToFirst() {
        val actorUuid = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val prev = AuditLog(
            null, byteArrayOf(9, 9, 9), actorUuid, "alice@example.com",
            "ADMIN_LOGIN", null, null, null, null, null, "{}", OffsetDateTime.now(clock),
        )
        whenever(repo.findLatestForUpdate()).thenReturn(Optional.of(prev))

        service.append(
            AuditAppendRequest(
                actorUuid, "alice@example.com", "TENANT_CREATE",
                "TENANT", "T_A", null, mapOf("id" to "T_A"),
            ),
        )

        val captor = argumentCaptor<AuditLog>()
        verify(repo).save(captor.capture())
        val row = captor.lastValue
        assertThat(row.prevHash).containsExactly(*toIntArray(byteArrayOf(9, 9, 9)))
    }

    @Test
    fun createdAtAdvancesPastLatestRowWhenClockIsBehind() {
        // 직전 row 가 시계보다 5초 뒤(시계가 뒤로 감 / 다른 서버 시계가 빠름) → 새 row 는 직전 + 1µs,
        // 해시도 그 보정된 시각으로 계산된다(검증기가 저장된 created_at 으로 다시 계산해도 일치).
        val ahead = OffsetDateTime.now(clock).plusSeconds(5)
        val prev = AuditLog(null, byteArrayOf(1), null, null, "ADMIN_LOGIN", null, null, null, null, null, "{}", ahead)
        whenever(repo.findLatestForUpdate()).thenReturn(Optional.of(prev))

        service.append(AuditAppendRequest(null, null, "ADMIN_LOGIN", null, null, null, mapOf()))

        val captor = argumentCaptor<AuditLog>()
        verify(repo).save(captor.capture())
        val row = captor.lastValue
        assertThat(row.createdAt).isEqualTo(ahead.plusNanos(1_000))
        assertThat(row.createdAt.offset).isEqualTo(ZoneOffset.ofHours(9))
        val expected = MessageDigest.getInstance("SHA-256")
            .digest(("01||ADMIN_LOGIN|||" + row.createdAt + "|{}").toByteArray(StandardCharsets.UTF_8))
        assertThat(row.hash).containsExactly(*toIntArray(expected))
    }

    @Test
    fun nextChainTimeIsStrictlyIncreasingAndKeepsCandidateOffset() {
        val t = OffsetDateTime.parse("2026-06-01T09:00:00.000001+09:00")
        // 시계가 앞서면 그대로
        assertThat(AuditLogService.nextChainTime(t.plusSeconds(1), t)).isEqualTo(t.plusSeconds(1))
        // 같은 마이크로초 / 시계가 뒤 → 직전 + 1µs
        assertThat(AuditLogService.nextChainTime(t, t)).isEqualTo(t.plusNanos(1_000))
        assertThat(AuditLogService.nextChainTime(t.minusSeconds(3), t)).isEqualTo(t.plusNanos(1_000))
        // 직전 row 가 UTC offset 으로 읽혀도 결과는 candidate 의 offset(KST)
        val latestUtc = t.withOffsetSameInstant(ZoneOffset.UTC)
        assertThat(AuditLogService.nextChainTime(t, latestUtc).toString()).isEqualTo("2026-06-01T09:00:00.000002+09:00")
        // 첫 row
        assertThat(AuditLogService.nextChainTime(t, null)).isEqualTo(t)
    }

    @Test
    fun payloadKeysAreSortedAlphabetically() {
        whenever(repo.findLatestForUpdate()).thenReturn(Optional.empty())
        val payload = LinkedHashMap<String, Any?>()
        payload["z"] = 1
        payload["a"] = 2

        service.append(
            AuditAppendRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "alice@example.com", "X", null, null, null, payload,
            ),
        )

        val captor = argumentCaptor<AuditLog>()
        verify(repo).save(captor.capture())
        // ORDER_MAP_ENTRIES_BY_KEYS reorders to "a" then "z".
        assertThat(captor.lastValue.payload).isEqualTo("{\"a\":2,\"z\":1}")
    }

    @Test
    fun chainLockIsAcquiredBeforeReadingHead() {
        whenever(repo.findLatestForUpdate()).thenReturn(Optional.empty())
        val lockQuery: Query = mock()
        whenever(em.createNativeQuery(anyString())).thenReturn(lockQuery)
        whenever(lockQuery.setParameter(anyString(), anyOrNull<Any>())).thenReturn(lockQuery)
        whenever(lockQuery.singleResult).thenReturn(1)

        service.append(
            AuditAppendRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "alice@example.com", "ADMIN_LOGIN", null, null, null, mapOf(),
            ),
        )

        // The lock query on the sentinel row must precede the chain-head read.
        val ordered = inOrder(em, repo)
        ordered.verify(em).createNativeQuery(anyString())
        ordered.verify(repo).findLatestForUpdate()
    }

    private companion object {
        fun toIntArray(b: ByteArray): IntArray {
            val out = IntArray(b.size)
            for (i in b.indices) out[i] = b[i].toInt() and 0xff
            return out
        }
    }
}
