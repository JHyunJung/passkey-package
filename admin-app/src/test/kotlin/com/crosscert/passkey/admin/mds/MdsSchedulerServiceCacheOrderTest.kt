package com.crosscert.passkey.admin.mds

import com.crosscert.passkey.admin.audit.AuditLogService
import com.crosscert.passkey.admin.scheduler.SchedulerLeaseService
import com.crosscert.passkey.webauthn.mds.MdsBlob
import com.crosscert.passkey.webauthn.mds.MdsBlobEntry
import com.crosscert.passkey.webauthn.mds.MdsStatusReport
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.nio.ByteBuffer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * G07-mdsAtomic — MdsSchedulerService.runOnce() must never leave a window
 * where a previously-cached AAGUID entry is absent from Redis. The old
 * implementation deleted every "mds:aaguid:*" key BEFORE repopulating them
 * (delete-then-repopulate), so any lookup landing inside that window saw a
 * cache miss and mdsRequired registrations failed closed.
 *
 * This test drives runOnce() with a mocked StringRedisTemplate and
 * asserts the ordering of Redis calls: every new-entry SET must happen
 * BEFORE the stale-key cleanup DELETE. That way, at every point in time,
 * a key that existed before the cycle either still holds its (possibly
 * stale-but-valid) previous value or has already been overwritten with the
 * fresh value — it is never simply absent.
 */
class MdsSchedulerServiceCacheOrderTest {

    private lateinit var redis: StringRedisTemplate
    private lateinit var valueOps: ValueOperations<String, String>
    private lateinit var client: MdsBlobClient
    private lateinit var store: MdsBlobStore
    private lateinit var leases: SchedulerLeaseService
    private lateinit var audit: AuditLogService
    private lateinit var historyService: MdsHistoryService
    private lateinit var scheduler: MdsSchedulerService

    @BeforeEach
    fun setUp() {
        redis = mock()
        valueOps = mock()
        whenever(redis.opsForValue()).thenReturn(valueOps)

        client = mock()
        store = mock()
        leases = mock()
        whenever(leases.tryAcquire(anyString(), anyString(), any<Duration>())).thenReturn(true)
        audit = mock()
        historyService = mock()
        val eventPublisher = mock<ApplicationEventPublisher>()
        val clock = Clock.fixed(Instant.parse("2026-07-04T00:00:00Z"), ZoneOffset.UTC)

        scheduler = MdsSchedulerService(
            leases, client, store, redis, audit, historyService,
            clock, SimpleMeterRegistry(), eventPublisher, "test-holder",
        )
    }

    @Test
    fun runOnceWritesFreshEntriesBeforeDeletingStaleKeys() {
        val keptUuid = UUID.fromString("11111111-2222-3333-4444-555555555555")
        val staleUuid = UUID.fromString("99999999-8888-7777-6666-555555555555")

        // Previous cycle populated both AAGUIDs; the new BLOB only contains "kept".
        val staleKey = "mds:aaguid:$staleUuid"
        val keptKey = "mds:aaguid:$keptUuid"
        whenever(redis.keys("mds:aaguid:*")).thenReturn(setOf(staleKey, keptKey))

        val blob = MdsBlob(
            7, LocalDate.of(2099, 1, 1),
            listOf(entry(keptUuid, listOf("FIDO_CERTIFIED_L1"))),
        )
        whenever(client.fetch()).thenReturn(MdsBlobClient.FetchResult("raw.jwt", blob))

        val result = scheduler.runOnce()

        assertThat(result.status).isEqualTo("SYNCED")

        val inOrder = inOrder(valueOps, redis)
        // Fresh SET for the surviving AAGUID must be issued first...
        inOrder.verify(valueOps).set(eq(keptKey), anyString(), any<Duration>())
        // ...and only stale keys not present in the new BLOB are deleted afterwards.
        inOrder.verify(redis).delete(
            argThat<Set<String>> { contains(staleKey) && !contains(keptKey) },
        )

        // The surviving key must never be deleted at any point in the cycle.
        verify(redis, never()).delete(argThat<Set<String>> { contains(keptKey) })
    }

    @Test
    fun runOnceDoesNotDeleteAnythingWhenNoStaleKeysRemain() {
        val uuid = UUID.fromString("11111111-2222-3333-4444-555555555555")
        val key = "mds:aaguid:$uuid"
        // Previous cycle had exactly this one key, and the new BLOB still has it.
        whenever(redis.keys("mds:aaguid:*")).thenReturn(setOf(key))

        val blob = MdsBlob(
            8, LocalDate.of(2099, 1, 1),
            listOf(entry(uuid, listOf("FIDO_CERTIFIED_L1"))),
        )
        whenever(client.fetch()).thenReturn(MdsBlobClient.FetchResult("raw.jwt", blob))

        val result = scheduler.runOnce()

        assertThat(result.status).isEqualTo("SYNCED")
        verify(valueOps).set(eq(key), anyString(), any<Duration>())
        // Nothing left to clean up — delete must not be called with a non-empty set.
        verify(redis, never()).delete(argThat<Set<String>> { !isEmpty() })
    }

    private companion object {
        fun uuidBytes(u: UUID): ByteArray {
            val bb = ByteBuffer.allocate(16)
            bb.putLong(u.mostSignificantBits)
            bb.putLong(u.leastSignificantBits)
            return bb.array()
        }

        fun entry(aaguid: UUID, statuses: List<String>): MdsBlobEntry {
            val reports = statuses.stream()
                .map { s -> MdsStatusReport(s, null) }
                .toList()
            return MdsBlobEntry(uuidBytes(aaguid), reports)
        }
    }
}
