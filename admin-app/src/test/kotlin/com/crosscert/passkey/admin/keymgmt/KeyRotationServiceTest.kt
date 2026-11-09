package com.crosscert.passkey.admin.keymgmt

import com.crosscert.passkey.admin.audit.AuditAppendRequest
import com.crosscert.passkey.admin.audit.AuditLogService
import com.crosscert.passkey.admin.scheduler.SchedulerLeaseService
import com.crosscert.passkey.core.api.BusinessException
import com.crosscert.passkey.core.api.ErrorCode
import com.crosscert.passkey.core.config.KstTime
import com.crosscert.passkey.core.entity.SigningKey
import com.crosscert.passkey.core.jwt.KeyEnvelope
import com.crosscert.passkey.core.jwt.SigningKeyProvider
import com.crosscert.passkey.core.repository.SigningKeyRepository
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64
import java.util.Optional
import java.util.UUID

class KeyRotationServiceTest {

    private lateinit var repo: SigningKeyRepository
    private lateinit var provider: SigningKeyProvider
    private lateinit var leases: SchedulerLeaseService
    private lateinit var audit: AuditLogService
    private lateinit var envelope: KeyEnvelope
    private val clock: Clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), KstTime.ZONE)
    private lateinit var svc: KeyRotationService

    @BeforeEach
    fun setUp() {
        repo = mock()
        provider = mock()
        leases = mock()
        audit = mock()
        envelope = KeyEnvelope(
            Base64.getEncoder().encodeToString(ByteArray(32)),
            SecureRandom(),
        )
        svc = KeyRotationService(repo, provider, leases, audit, envelope, clock)
    }

    @Test
    fun rotateTransitionsActiveAndInsertsNew() {
        val current = freshActiveKey("old-kid")
        whenever(leases.tryAcquire(anyString(), anyString(), any<Duration>()))
            .thenReturn(true)
        whenever(repo.findFirstByStatusOrderByCreatedAtDesc("ACTIVE"))
            .thenReturn(Optional.of(current))
        whenever(repo.saveAndFlush(anyOrNull<SigningKey>())).thenAnswer { inv -> inv.getArgument<SigningKey>(0) }
        whenever(repo.save(anyOrNull<SigningKey>())).thenAnswer { inv -> inv.getArgument<SigningKey>(0) }

        val result = svc.rotate(UUID.randomUUID(), "alice@example.com")

        assertThat(current.status).isEqualTo("ROTATED")
        assertThat(current.rotatedAt).isEqualTo(OffsetDateTime.now(clock))

        // Codex T17 P1-1: UPDATE-old (saveAndFlush) must happen BEFORE
        // INSERT-new (save) so Hibernate emits SQL in that order and
        // the function-based unique index sees one ACTIVE at any moment.
        val ordered = inOrder(repo)
        ordered.verify(repo).saveAndFlush(current)
        ordered.verify(repo).save(any<SigningKey>())

        val savedCaptor = argumentCaptor<SigningKey>()
        verify(repo).save(savedCaptor.capture())
        val newRow = savedCaptor.lastValue
        assertThat(newRow.status).isEqualTo("ACTIVE")
        assertThat(newRow.kid).isNotEqualTo("old-kid")
        assertThat(newRow.alg).isEqualTo("RS256")

        // No active TX in this unit test → provider.reload() runs inline
        // (the production path schedules it via TransactionSynchronization).
        verify(provider).reload()
        val auditCap = argumentCaptor<AuditAppendRequest>()
        verify(audit).append(auditCap.capture())
        assertThat(auditCap.lastValue.action).isEqualTo("SIGNING_KEY_ROTATE")
        assertThat(auditCap.lastValue.payload).containsKey("oldKid")
        assertThat(auditCap.lastValue.payload).containsKey("newKid")

        assertThat(result.oldKid).isEqualTo("old-kid")
        assertThat(result.newKid).isEqualTo(newRow.kid)
    }

    @Test
    fun rotateThrowsBusinessExceptionWhenLeaseUnavailable() {
        whenever(leases.tryAcquire(anyString(), anyString(), any<Duration>()))
            .thenReturn(false)
        assertThatThrownBy { svc.rotate(UUID.randomUUID(), "alice@example.com") }
            .isInstanceOf(BusinessException::class.java)
            .extracting { (it as BusinessException).errorCode }
            .isEqualTo(ErrorCode.KEY_ROTATION_CONFLICT)
    }

    @Test
    fun rotateThrowsWhenNoActiveKeyExists() {
        whenever(leases.tryAcquire(anyString(), anyString(), any<Duration>()))
            .thenReturn(true)
        whenever(repo.findFirstByStatusOrderByCreatedAtDesc("ACTIVE"))
            .thenReturn(Optional.empty())
        assertThatThrownBy { svc.rotate(UUID.randomUUID(), "alice@example.com") }
            .isInstanceOf(BusinessException::class.java)
            .extracting { (it as BusinessException).errorCode }
            .isEqualTo(ErrorCode.KEY_NO_ACTIVE)
    }

    private fun freshActiveKey(kid: String): SigningKey {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        val pair = gen.generateKeyPair()
        val rsa: RSAKey = RSAKey.Builder(pair.public as RSAPublicKey)
            .privateKey(pair.private as RSAPrivateKey)
            .keyID(kid)
            .keyUse(KeyUse.SIGNATURE)
            .algorithm(JWSAlgorithm.RS256)
            .build()
        return SigningKey(
            kid, "RS256", rsa.toPublicJWK().toJSONString(),
            envelope.seal(pair.private.encoded),
        )
    }
}
