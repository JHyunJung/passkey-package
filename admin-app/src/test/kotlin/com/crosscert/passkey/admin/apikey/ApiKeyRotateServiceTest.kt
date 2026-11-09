package com.crosscert.passkey.admin.apikey

import com.crosscert.passkey.admin.audit.AuditAppendRequest
import com.crosscert.passkey.admin.audit.AuditLogService
import com.crosscert.passkey.admin.auth.TenantBoundary
import com.crosscert.passkey.core.api.BusinessException
import com.crosscert.passkey.core.api.ErrorCode
import com.crosscert.passkey.core.config.KstTime
import com.crosscert.passkey.core.entity.ApiKey
import com.crosscert.passkey.core.entity.Tenant
import com.crosscert.passkey.core.repository.ApiKeyRepository
import com.crosscert.passkey.core.repository.TenantRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.stubbing.Answer
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Optional
import java.util.UUID

class ApiKeyRotateServiceTest {

    private lateinit var repo: ApiKeyRepository
    private lateinit var audit: AuditLogService
    private lateinit var encoder: PasswordEncoder
    private lateinit var boundary: TenantBoundary
    private lateinit var tenants: TenantRepository
    private lateinit var service: ApiKeyAdminService
    private val clock: Clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), KstTime.ZONE)
    private val grace: Duration = Duration.ofHours(24)

    @BeforeEach
    fun setUp() {
        repo = mock()
        audit = mock()
        encoder = BCryptPasswordEncoder(4) // fast for tests
        boundary = mock()
        tenants = mock()
        val active: Tenant = mock()
        whenever(active.isSuspended).thenReturn(false)
        whenever(tenants.findById(anyOrNull())).thenReturn(Optional.of(active))
        whenever(repo.findByKeyPrefix(anyString())).thenReturn(Optional.empty())
        service = ApiKeyAdminService(repo, audit, encoder, SecureRandom(), clock, boundary, tenants)
        setGrace(service, grace)

        val assignId = Answer<ApiKey> { inv ->
            val k = inv.getArgument<ApiKey>(0)
            try {
                val f = ApiKey::class.java.superclass.getDeclaredField("id")
                f.isAccessible = true
                if (f.get(k) == null) f.set(k, UUID.randomUUID())
            } catch (e: Exception) {
                throw RuntimeException(e)
            }
            k
        }
        whenever(repo.save(anyOrNull<ApiKey>())).thenAnswer(assignId)
        whenever(repo.saveAndFlush(anyOrNull<ApiKey>())).thenAnswer(assignId)
    }

    private fun activeKey(): ApiKey {
        val old = ApiKey(TENANT_UUID, "pk_old12345", "\$2a\$04\$x", "primary")
        old.addScope("registration")
        old.addScope("authentication")
        setId(old, UUID.randomUUID())
        return old
    }

    @Test
    fun rotateIssuesNewKeyCopiesScopesAndGraceExpiresOldKey() {
        val old = activeKey()
        whenever(repo.findById(old.id)).thenReturn(Optional.of(old))

        val resp = service.rotate(old.id, ACTOR_UUID, "alice@example.com")

        // (a) new key plaintext returned once with copied scopes
        assertThat(resp.plaintextKey).startsWith("pk_")
        assertThat(resp.prefix).hasSize(11)
        assertThat(resp.scopes).containsExactlyInAnyOrder("registration", "authentication")

        // old key grace-expired at now + grace
        assertThat(old.expiresAt).isEqualTo(OffsetDateTime.now(clock).plus(grace))
        assertThat(resp.oldKeyExpiresAt).isEqualTo(OffsetDateTime.now(clock).plus(grace))

        // the new key persisted via saveAndFlush carries the copied scopes + a hash matching the secret
        val savedCaptor = argumentCaptor<ApiKey>()
        verify(repo).saveAndFlush(savedCaptor.capture())
        val fresh = savedCaptor.lastValue
        assertThat(fresh.scopeValues).containsExactlyInAnyOrder("registration", "authentication")
        val secret = checkNotNull(resp.plaintextKey).substring(checkNotNull(resp.prefix).length)
        assertThat(encoder.matches(secret, fresh.keyHash)).isTrue()
        assertThat(fresh.name).isEqualTo("primary")
    }

    @Test
    fun rotateAuditsPrefixesNotSecret() {
        val old = activeKey()
        whenever(repo.findById(old.id)).thenReturn(Optional.of(old))

        val resp = service.rotate(old.id, ACTOR_UUID, "alice@example.com")

        val auditCaptor = argumentCaptor<AuditAppendRequest>()
        verify(audit).append(auditCaptor.capture())
        val aud = auditCaptor.lastValue
        assertThat(aud.action).isEqualTo("API_KEY_ROTATED")
        assertThat(aud.payload).containsKeys("oldPrefix", "newPrefix", "tenantId", "oldKeyExpiresAt")
        val payload = checkNotNull(aud.payload)
        assertThat(payload["oldPrefix"]).isEqualTo("pk_old12345")
        assertThat(payload.values).doesNotContain(resp.plaintextKey)
    }

    @Test
    fun rotateRejectsRevokedKey() {
        val old = activeKey()
        old.revoke(OffsetDateTime.now(clock).minusSeconds(60))
        whenever(repo.findById(old.id)).thenReturn(Optional.of(old))

        assertThatThrownBy { service.rotate(old.id, ACTOR_UUID, "alice@example.com") }
            .isInstanceOf(BusinessException::class.java)
            .extracting { (it as BusinessException).errorCode }
            .isEqualTo(ErrorCode.API_KEY_NOT_FOUND)

        verify(repo, never()).saveAndFlush(anyOrNull<ApiKey>())
        verify(audit, never()).append(anyOrNull())
    }

    @Test
    fun rotateRejectsExpiredKey() {
        val old = activeKey()
        old.expireAt(OffsetDateTime.now(clock).minusSeconds(60))
        whenever(repo.findById(old.id)).thenReturn(Optional.of(old))

        assertThatThrownBy { service.rotate(old.id, ACTOR_UUID, "alice@example.com") }
            .isInstanceOf(BusinessException::class.java)
            .extracting { (it as BusinessException).errorCode }
            .isEqualTo(ErrorCode.API_KEY_NOT_FOUND)
    }

    @Test
    fun rotateRejectsMissingKey() {
        val missing = UUID.randomUUID()
        whenever(repo.findById(missing)).thenReturn(Optional.empty())

        assertThatThrownBy { service.rotate(missing, ACTOR_UUID, "alice@example.com") }
            .isInstanceOf(BusinessException::class.java)
            .extracting { (it as BusinessException).errorCode }
            .isEqualTo(ErrorCode.API_KEY_NOT_FOUND)
    }

    @Test
    fun rotateRejectsSuspendedTenant() {
        val old = activeKey()
        whenever(repo.findById(old.id)).thenReturn(Optional.of(old))
        val suspended: Tenant = mock()
        whenever(suspended.isSuspended).thenReturn(true)
        whenever(tenants.findById(TENANT_UUID)).thenReturn(Optional.of(suspended))

        assertThatThrownBy { service.rotate(old.id, ACTOR_UUID, "alice@example.com") }
            .isInstanceOf(BusinessException::class.java)
            .extracting { (it as BusinessException).errorCode }
            .isEqualTo(ErrorCode.TENANT_SUSPENDED)

        verify(repo, never()).saveAndFlush(anyOrNull<ApiKey>())
        verify(audit, never()).append(anyOrNull())
    }

    private companion object {
        val TENANT_UUID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val ACTOR_UUID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000010")

        fun setGrace(svc: ApiKeyAdminService, g: Duration) {
            try {
                val f = ApiKeyAdminService::class.java.getDeclaredField("rotationGrace")
                f.isAccessible = true
                f.set(svc, g)
            } catch (e: Exception) {
                throw RuntimeException(e)
            }
        }

        fun setId(k: ApiKey, id: UUID) {
            try {
                val f = ApiKey::class.java.superclass.getDeclaredField("id")
                f.isAccessible = true
                f.set(k, id)
            } catch (e: Exception) {
                throw RuntimeException(e)
            }
        }
    }
}
