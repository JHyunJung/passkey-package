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
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Optional
import java.util.UUID

class ApiKeyAdminServiceTest {

    private lateinit var repo: ApiKeyRepository
    private lateinit var audit: AuditLogService
    private lateinit var encoder: PasswordEncoder
    private lateinit var boundary: TenantBoundary
    private lateinit var tenants: TenantRepository
    private lateinit var service: ApiKeyAdminService
    private val clock: Clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), KstTime.ZONE)

    @BeforeEach
    fun setUp() {
        repo = mock()
        audit = mock()
        encoder = BCryptPasswordEncoder(4) // fast for tests
        boundary = mock()
        tenants = mock()
        // Default: target tenant is active so existing issue() tests pass the
        // P0-2 suspended-tenant guard. suspended cases override this stub.
        val active: Tenant = mock()
        whenever(active.isSuspended).thenReturn(false)
        whenever(tenants.findById(anyOrNull())).thenReturn(Optional.of(active))
        service = ApiKeyAdminService(repo, audit, encoder, SecureRandom(), clock, boundary, tenants)
        // @UuidGenerator sets id during JPA persist. In unit tests we must
        // set it reflectively so getId() is non-null after repo.save() mock.
        // The service uses saveAndFlush for issue() and save() for revoke().
        // Phase 8 T4: id is now on BaseEntity superclass — use getSuperclass().
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

    @Test
    fun issueProducesPlainTextAndPersistsHash() {
        whenever(repo.findByKeyPrefix(anyString())).thenReturn(Optional.empty())

        val req = ApiKeyAdminDto.ApiKeyCreateRequest(
            TENANT_UUID, "primary", setOf("registration", "authentication"), null,
        )
        val resp = service.issue(req, ACTOR_UUID, "alice@example.com")

        assertThat(resp.plainText).startsWith("pk_")
        assertThat(resp.plainText).hasSizeGreaterThan(20)
        assertThat(resp.prefix).hasSize(11) // pk_ + 8 chars

        val keyCaptor = argumentCaptor<ApiKey>()
        verify(repo).saveAndFlush(keyCaptor.capture())
        val saved = keyCaptor.lastValue
        // Verify the persisted hash actually matches the plaintext secret
        // (full plainText = prefix + secret; we know prefix == resp.prefix).
        val secret = checkNotNull(resp.plainText).substring(checkNotNull(resp.prefix).length)
        assertThat(encoder.matches(secret, saved.keyHash)).isTrue()
    }

    @Test
    fun issueAppendsAuditWithoutSecret() {
        whenever(repo.findByKeyPrefix(anyString())).thenReturn(Optional.empty())

        service.issue(
            ApiKeyAdminDto.ApiKeyCreateRequest(TENANT_UUID, "primary", setOf("registration"), null),
            ACTOR_UUID, "alice@example.com",
        )

        val auditCaptor = argumentCaptor<AuditAppendRequest>()
        verify(audit).append(auditCaptor.capture())
        val aud = auditCaptor.lastValue
        assertThat(aud.action).isEqualTo("API_KEY_ISSUE")
        // payload must contain prefix + tenantId + name but NOT plaintext.
        assertThat(aud.payload).containsKey("prefix")
        assertThat(aud.payload).doesNotContainKey("plainText")
        assertThat(aud.payload).doesNotContainKey("secret")
    }

    @Test
    fun issueRetriesOnPrefixCollision() {
        // First two random prefixes already exist; third is fresh.
        whenever(repo.findByKeyPrefix(anyString()))
            .thenReturn(Optional.of(ApiKey(TENANT_UUID, "pk_xxxxxxxx", "h", "n")))
            .thenReturn(Optional.of(ApiKey(TENANT_UUID, "pk_yyyyyyyy", "h", "n")))
            .thenReturn(Optional.empty())

        val resp = service.issue(
            ApiKeyAdminDto.ApiKeyCreateRequest(TENANT_UUID, "primary", setOf("registration"), null),
            ACTOR_UUID, "alice@example.com",
        )
        assertThat(resp.prefix).startsWith("pk_")
    }

    @Test
    fun issueNormalizesScopeCaseBeforePersisting() {
        whenever(repo.findByKeyPrefix(anyString())).thenReturn(Optional.empty())

        val req = ApiKeyAdminDto.ApiKeyCreateRequest(
            TENANT_UUID, "primary", setOf("Registration", "AUTHENTICATION"), null,
        )
        val resp = service.issue(req, ACTOR_UUID, "alice@example.com")

        // response reflects the normalized (lowercased) scopes
        assertThat(resp.scopes).containsExactlyInAnyOrder("registration", "authentication")

        // persisted key carries the normalized scopes, not the verbatim request
        val keyCaptor = argumentCaptor<ApiKey>()
        verify(repo).saveAndFlush(keyCaptor.capture())
        assertThat(keyCaptor.lastValue.scopeValues)
            .containsExactlyInAnyOrder("registration", "authentication")
    }

    @Test
    fun issueRejectsUnknownScope_withInvalidInputError() {
        whenever(repo.findByKeyPrefix(anyString())).thenReturn(Optional.empty())

        val req = ApiKeyAdminDto.ApiKeyCreateRequest(
            TENANT_UUID, "primary", setOf("registration", "bogus"), null,
        )

        assertThatThrownBy { service.issue(req, ACTOR_UUID, "alice@example.com") }
            .isInstanceOf(BusinessException::class.java)
            .extracting { (it as BusinessException).errorCode }
            .isEqualTo(ErrorCode.INVALID_INPUT)

        // unknown scope rejected before any persistence / audit side effect
        verify(repo, never()).saveAndFlush(anyOrNull<ApiKey>())
        verify(audit, never()).append(anyOrNull())
    }

    @Test
    fun issueRejectsSuspendedTenant_withTenantSuspendedError() {
        val suspended: Tenant = mock()
        whenever(suspended.isSuspended).thenReturn(true)
        whenever(tenants.findById(TENANT_UUID)).thenReturn(Optional.of(suspended))

        val req = ApiKeyAdminDto.ApiKeyCreateRequest(
            TENANT_UUID, "primary", setOf("registration"), null,
        )

        assertThatThrownBy { service.issue(req, ACTOR_UUID, "alice@example.com") }
            .isInstanceOf(BusinessException::class.java)
            .extracting { (it as BusinessException).errorCode }
            .isEqualTo(ErrorCode.TENANT_SUSPENDED)

        // No key persisted, no audit appended on the rejection path.
        verify(repo, never()).saveAndFlush(anyOrNull<ApiKey>())
        verify(audit, never()).append(anyOrNull())
    }

    @Test
    fun issueWithExpiresInMonthsSetsExpiresAt() {
        whenever(repo.findByKeyPrefix(anyString())).thenReturn(Optional.empty())

        val resp = service.issue(
            ApiKeyAdminDto.ApiKeyCreateRequest(TENANT_UUID, "primary", setOf("registration"), 24),
            ACTOR_UUID, "alice@example.com",
        )

        assertThat(resp.expiresAt).isEqualTo(OffsetDateTime.parse("2028-06-01T09:00:00+09:00"))

        val keyCaptor = argumentCaptor<ApiKey>()
        verify(repo).saveAndFlush(keyCaptor.capture())
        assertThat(keyCaptor.lastValue.expiresAt).isEqualTo(OffsetDateTime.parse("2028-06-01T09:00:00+09:00"))
    }

    @Test
    fun issueWithNullExpiresInMonthsLeavesNoExpiry() {
        whenever(repo.findByKeyPrefix(anyString())).thenReturn(Optional.empty())

        val resp = service.issue(
            ApiKeyAdminDto.ApiKeyCreateRequest(TENANT_UUID, "primary", setOf("registration"), null),
            ACTOR_UUID, "alice@example.com",
        )

        assertThat(resp.expiresAt).isNull()
        val keyCaptor = argumentCaptor<ApiKey>()
        verify(repo).saveAndFlush(keyCaptor.capture())
        assertThat(keyCaptor.lastValue.expiresAt).isNull()
    }

    @Test
    fun issueAuditPayloadContainsExpiresAt() {
        whenever(repo.findByKeyPrefix(anyString())).thenReturn(Optional.empty())

        service.issue(
            ApiKeyAdminDto.ApiKeyCreateRequest(TENANT_UUID, "primary", setOf("registration"), 6),
            ACTOR_UUID, "alice@example.com",
        )

        val auditCaptor = argumentCaptor<AuditAppendRequest>()
        verify(audit).append(auditCaptor.capture())
        assertThat(checkNotNull(auditCaptor.lastValue.payload)["expiresAt"])
            .isEqualTo("2026-12-01T09:00+09:00")
    }

    @Test
    fun revokeThrowsBusinessExceptionWhenIdNotFound() {
        val missingId = UUID.randomUUID()
        whenever(repo.findById(missingId)).thenReturn(Optional.empty())

        assertThatThrownBy { service.revoke(missingId, ACTOR_UUID, "alice@example.com") }
            .isInstanceOf(BusinessException::class.java)
            .extracting { (it as BusinessException).errorCode }
            .isEqualTo(ErrorCode.API_KEY_NOT_FOUND)

        verify(audit, never()).append(anyOrNull())
    }

    @Test
    fun revokeSetsRevokedAtAndAuditsPrefixOnly() {
        val existingId = UUID.randomUUID()
        val existing = ApiKey(TENANT_UUID, "pk_abcd1234", "\$2a\$04\$x", "primary")
        try {
            // Phase 8 T4: id is now on BaseEntity superclass — use getSuperclass().
            val f = ApiKey::class.java.superclass.getDeclaredField("id")
            f.isAccessible = true
            f.set(existing, existingId)
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
        whenever(repo.findById(existingId)).thenReturn(Optional.of(existing))

        service.revoke(existingId, ACTOR_UUID, "alice@example.com")

        assertThat(existing.isActive(OffsetDateTime.now(clock))).isFalse()
        val auditCaptor = argumentCaptor<AuditAppendRequest>()
        verify(audit).append(auditCaptor.capture())
        val aud = auditCaptor.lastValue
        assertThat(aud.action).isEqualTo("API_KEY_REVOKE")
        assertThat(checkNotNull(aud.payload)["prefix"]).isEqualTo("pk_abcd1234")
    }

    // ── list(): 필터를 쿼리로 내린 뒤에도 기존 의미(문자열 정확일치, RP_ADMIN 스코프)를 그대로 지킨다 ──

    private fun key(tid: UUID): ApiKey = ApiKey(tid, "pk_" + UUID.randomUUID().toString().take(8), "h", "k")

    @Test
    fun listPlatformWithoutFilterUsesOrderedAllQuery() {
        whenever(boundary.currentTenantScope()).thenReturn(null)
        val a = key(TENANT_UUID); val b = key(OTHER_UUID)
        whenever(repo.findAllForAdminList()).thenReturn(listOf(a, b))
        assertThat(service.list(null).map { it.tenantId }).containsExactly(TENANT_UUID, OTHER_UUID)
        verify(repo, org.mockito.kotlin.never()).findAll()
    }

    @Test
    fun listPlatformWithTenantFilterQueriesThatTenant() {
        whenever(boundary.currentTenantScope()).thenReturn(null)
        whenever(repo.findAllForAdminListByTenantId(TENANT_UUID)).thenReturn(listOf(key(TENANT_UUID)))
        assertThat(service.list(TENANT_UUID.toString())).hasSize(1)
    }

    @Test
    fun listTenantFilterMustBeCanonicalUuidStringElseEmpty() {
        // 이전 구현은 kid.toString() 과 문자열 비교 — 대문자·비정규 UUID·잘못된 값은 아무것도 맞지 않았다.
        whenever(boundary.currentTenantScope()).thenReturn(null)
        for (bad in listOf("0000000A-0000-0000-0000-00000000000B", "not-a-uuid", "1-1-1-1-1", "")) {
            assertThat(service.list(bad)).`as`(bad).isEmpty()
        }
        verify(repo, org.mockito.kotlin.never()).findAllForAdminListByTenantId(anyOrNull())
        verify(repo, org.mockito.kotlin.never()).findAllForAdminList()
    }

    @Test
    fun listRpAdminIsScopedToOwnTenant() {
        whenever(boundary.currentTenantScope()).thenReturn(TENANT_UUID)
        whenever(repo.findAllForAdminListByTenantId(TENANT_UUID)).thenReturn(listOf(key(TENANT_UUID)))
        assertThat(service.list(null)).hasSize(1)
        assertThat(service.list(TENANT_UUID.toString())).hasSize(1)
        // 다른 테넌트를 지정하면 빈 목록(이전과 동일) — 그 테넌트를 조회하지도 않는다.
        assertThat(service.list(OTHER_UUID.toString())).isEmpty()
        verify(repo, org.mockito.kotlin.never()).findAllForAdminListByTenantId(OTHER_UUID)
        verify(repo, org.mockito.kotlin.never()).findAllForAdminList()
    }

    private companion object {
        val TENANT_UUID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val OTHER_UUID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val ACTOR_UUID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000010")
    }
}
