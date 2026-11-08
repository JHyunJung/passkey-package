package com.crosscert.passkey.admin.keymgmt

import com.crosscert.passkey.admin.audit.AuditAppendRequest
import com.crosscert.passkey.admin.audit.AuditLogService
import com.crosscert.passkey.admin.scheduler.SchedulerLeaseService
import com.crosscert.passkey.core.config.KstTime
import com.crosscert.passkey.core.entity.SigningKey
import com.crosscert.passkey.core.repository.SigningKeyRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

class KeyExpirationJobTest {

    private lateinit var repo: SigningKeyRepository
    private lateinit var leases: SchedulerLeaseService
    private lateinit var audit: AuditLogService
    private lateinit var job: KeyExpirationJob
    private val clock: Clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), KstTime.ZONE)

    @BeforeEach
    fun setUp() {
        repo = mock()
        leases = mock()
        audit = mock()
        job = KeyExpirationJob(repo, leases, audit, clock, Duration.ofMinutes(30))
    }

    @Test
    fun skipsWhenLeaseUnavailable() {
        whenever(leases.tryAcquire(anyString(), anyString(), any<Duration>()))
            .thenReturn(false)
        job.runOnce()
        verify(repo, never()).findAllByStatusAndRotatedAtBefore(anyString(), anyOrNull())
    }

    @Test
    fun transitionsExpiredRotatedToRevoked() {
        whenever(leases.tryAcquire(anyString(), anyString(), any<Duration>()))
            .thenReturn(true)
        // rotated at clock - 31min — past the 30min grace
        val old = withId(rotatedKey("expired"), UUID.fromString("00000000-0000-0000-0000-000000000001"))
        setRotatedAt(old, OffsetDateTime.now(clock).minus(Duration.ofMinutes(31)))
        whenever(repo.findAllByStatusAndRotatedAtBefore(eq("ROTATED"), anyOrNull()))
            .thenReturn(listOf(old))

        job.runOnce()

        assertThat(old.status).isEqualTo("REVOKED")
        assertThat(old.revokedAt).isEqualTo(OffsetDateTime.now(clock))
        verify(repo).save(old)

        val auditCap = argumentCaptor<AuditAppendRequest>()
        verify(audit).append(auditCap.capture())
        assertThat(auditCap.lastValue.action).isEqualTo("SIGNING_KEY_REVOKE")
        assertThat(checkNotNull(auditCap.lastValue.payload)["kid"]).isEqualTo("expired")
    }

    @Test
    fun leavesNonExpiredRotatedAlone() {
        whenever(leases.tryAcquire(anyString(), anyString(), any<Duration>()))
            .thenReturn(true)
        whenever(repo.findAllByStatusAndRotatedAtBefore(eq("ROTATED"), anyOrNull()))
            .thenReturn(listOf())
        job.runOnce()
        verify(repo, never()).save(anyOrNull<SigningKey>())
        verify(audit, never()).append(anyOrNull())
    }

    companion object {
        private fun rotatedKey(kid: String): SigningKey {
            val k = SigningKey(kid, "RS256", "{}", byteArrayOf(0))
            k.rotate(OffsetDateTime.parse("2026-06-01T00:00:00Z"))
            return k
        }

        private fun withId(k: SigningKey, id: UUID): SigningKey {
            val f = SigningKey::class.java.superclass.getDeclaredField("id")
            f.isAccessible = true
            f.set(k, id)
            return k
        }

        private fun setRotatedAt(k: SigningKey, t: OffsetDateTime) {
            val f = SigningKey::class.java.getDeclaredField("rotatedAt")
            f.isAccessible = true
            f.set(k, t)
        }
    }
}
