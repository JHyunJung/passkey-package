package com.crosscert.passkey.admin.audit

import com.crosscert.passkey.core.config.KstTime
import com.crosscert.passkey.core.entity.AuditLog
import com.crosscert.passkey.core.repository.AuditLogRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

class AuditChainVerifierTest {

    private lateinit var repo: AuditLogRepository
    private lateinit var verifier: AuditChainVerifier

    // KST clock so the seeded createdAt + hashed timestamp carry the +09:00
    // offset, matching production (KstTime.ZONE). Absolute instant unchanged.
    private val clock: Clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), KstTime.ZONE)

    @BeforeEach
    fun setUp() {
        repo = mock()
        verifier = AuditChainVerifier(repo, ObjectMapper())
    }

    @Test
    fun emptyLogIsValid() {
        whenever(repo.findAllOrdered()).thenReturn(listOf())
        val r = verifier.verify()
        assertThat(r.ok).isTrue()
        assertThat(r.brokenAt).isNull()
    }

    @Test
    fun freshChainOfThreeRowsVerifies() {
        val rows = buildValidChain(3)
        whenever(repo.findAllOrdered()).thenReturn(rows)
        val r = verifier.verify()
        assertThat(r.ok).isTrue()
    }

    @Test
    fun tamperedPayloadIsDetectedAtThatRow() {
        val rows = buildValidChain(3)
        // Tamper the middle row's payload — its stored hash no longer matches.
        val middle = rows[1]
        val tampered = AuditLog(
            middle.prevHash, middle.hash, middle.actorId,
            middle.actorEmail, middle.action, middle.targetType,
            middle.targetId, null, null, null,
            "{\"x\":\"tampered\"}", middle.createdAt,
        )
        copyId(tampered, ROW_2_ID)
        rows[1] = tampered
        whenever(repo.findAllOrdered()).thenReturn(rows)

        val r = verifier.verify()
        assertThat(r.ok).isFalse()
        assertThat(r.brokenAt).isEqualTo(ROW_2_ID)
    }

    @Test
    fun prevHashMismatchIsDetected() {
        val rows = buildValidChain(2)
        val second = rows[1]
        val rebound = AuditLog(
            byteArrayOf(0, 0, 0), // wrong prev_hash
            second.hash, second.actorId, second.actorEmail,
            second.action, second.targetType, second.targetId,
            null, null, null,
            second.payload, second.createdAt,
        )
        copyId(rebound, ROW_2_ID)
        rows[1] = rebound
        whenever(repo.findAllOrdered()).thenReturn(rows)

        val r = verifier.verify()
        assertThat(r.ok).isFalse()
        assertThat(r.brokenAt).isEqualTo(ROW_2_ID)
    }

    /** Build a valid chain by reusing AuditLogService.computeHash. */
    private fun buildValidChain(n: Int): MutableList<AuditLog> {
        val chain = ArrayList<AuditLog>()
        var prev: ByteArray? = null
        for (i in 1..n) {
            val actorId = UUID.fromString(String.format("00000000-0000-0000-0000-%012d", i))
            val req = AuditAppendRequest(
                actorId, "alice@example.com", "ACTION_$i",
                "TENANT", "T_$i", null, mapOf("seq" to i),
            )
            val payload = "{\"seq\":$i}"
            // Single now (KST, +09:00) shared by the hash input and the seeded
            // createdAt so the verifier recomputes an identical hash.
            val now = OffsetDateTime.now(clock)
            val hash = AuditLogService.computeHash(prev, req, payload, now)
            val row = AuditLog(
                prev, hash, req.actorId, req.actorEmail,
                req.action, req.targetType, req.targetId,
                null, null, null,
                payload, now,
            )
            val rowId = UUID.fromString(String.format("00000000-0000-0000-0000-%012d", i))
            copyId(row, rowId)
            chain.add(row)
            prev = hash
        }
        return chain
    }

    private companion object {
        /** Stable UUID for row 2 — used in tamper/prevHash-mismatch assertions. */
        val ROW_2_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")

        fun copyId(row: AuditLog, id: UUID) {
            try {
                // id now lives on BaseEntity (Phase 8 T7).
                val f = AuditLog::class.java.superclass.getDeclaredField("id")
                f.isAccessible = true
                f.set(row, id)
            } catch (e: Exception) {
                throw RuntimeException(e)
            }
        }
    }
}
