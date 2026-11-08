package com.crosscert.passkey.admin.tenant

import com.crosscert.passkey.admin.audit.AuditAppendRequest
import com.crosscert.passkey.admin.audit.AuditLogService
import com.crosscert.passkey.admin.auth.TenantBoundary
import com.crosscert.passkey.core.api.BusinessException
import com.crosscert.passkey.core.api.ErrorCode
import com.crosscert.passkey.core.entity.Tenant
import com.crosscert.passkey.core.repository.ApiKeyRepository
import com.crosscert.passkey.core.repository.AuditLogRepository
import com.crosscert.passkey.core.repository.CredentialRepository
import com.crosscert.passkey.core.repository.TenantAaguidPolicyRepository
import com.crosscert.passkey.core.repository.TenantRepository
import com.crosscert.passkey.core.repository.TenantWebauthnSnapshotRepository
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.ThrowingConsumer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.OffsetDateTime
import java.util.Optional
import java.util.UUID

class TenantAdminServiceTest {

    private lateinit var repo: TenantRepository
    private lateinit var audit: AuditLogService
    private lateinit var em: EntityManager
    private lateinit var boundary: TenantBoundary
    private lateinit var aaguidPolicyRepo: TenantAaguidPolicyRepository
    private lateinit var snapshotRepo: TenantWebauthnSnapshotRepository
    private lateinit var credentialRepository: CredentialRepository
    private lateinit var apiKeyRepository: ApiKeyRepository
    private lateinit var auditLogRepository: AuditLogRepository
    private lateinit var service: TenantAdminService

    @BeforeEach
    fun setUp() {
        repo = mock()
        audit = mock()
        em = mock()
        boundary = mock()
        aaguidPolicyRepo = mock()
        snapshotRepo = mock()
        credentialRepository = mock()
        apiKeyRepository = mock()
        auditLogRepository = mock()
        service = TenantAdminService(
            repo, audit, em, boundary,
            aaguidPolicyRepo, snapshotRepo,
            credentialRepository, apiKeyRepository, auditLogRepository,
            ObjectMapper(), Clock.systemUTC(),
        )
    }

    @Test
    fun createPersistsTenantAndAppendsAudit() {
        whenever(repo.existsBySlug("T_A")).thenReturn(false)
        whenever(repo.saveAndFlush(anyOrNull<Tenant>())).thenAnswer { inv -> inv.getArgument<Tenant>(0) }
        val req = TenantAdminDto.TenantCreateRequest(
            "T_A", "Tenant A", "localhost", "Tenant A",
            listOf("http://localhost"),
            setOf("none", "packed"),
            true, false,
            "NONE", 60000,
        )

        val view = service.create(req, UUID.randomUUID(), "alice@example.com")

        assertThat(view.slug).isEqualTo("T_A")
        val tenantCaptor = argumentCaptor<Tenant>()
        verify(repo).saveAndFlush(tenantCaptor.capture())
        assertThat(tenantCaptor.lastValue.rpId).isEqualTo("localhost")
        assertThat(tenantCaptor.lastValue.isRequireUserVerification).isTrue()
        assertThat(tenantCaptor.lastValue.isMdsRequired).isFalse()
        assertThat(tenantCaptor.lastValue.allowedOriginValues)
            .containsExactly("http://localhost")
        assertThat(tenantCaptor.lastValue.acceptedFormatValues)
            .containsExactlyInAnyOrder("none", "packed")

        val auditCaptor = argumentCaptor<AuditAppendRequest>()
        verify(audit).append(auditCaptor.capture())
        assertThat(auditCaptor.lastValue.action).isEqualTo("TENANT_CREATE")
        assertThat(auditCaptor.lastValue.targetId).isEqualTo("T_A")
    }

    @Test
    fun listUsesBatchAggregatesNotPerTenantQueries() {
        // two ACTIVE tenants. BaseEntity.id is assigned by @UuidGenerator at persist
        // time, so a freshly-constructed entity has a null id in a pure unit test —
        // assign deterministic ids reflectively (same helper pattern as
        // ApiKeyRotateServiceTest#setId) so the aggregate Map lookups have stable keys.
        val a = Tenant("T_A", "Tenant A", "a.example", "Tenant A")
        val b = Tenant("T_B", "Tenant B", "b.example", "Tenant B")
        val idA = UUID.randomUUID()
        val idB = UUID.randomUUID()
        setId(a, idA)
        setId(b, idB)
        // audit_log.created_at is OffsetDateTime (KST, +09:00) after the timezone migration;
        // the batch aggregate row carries an OffsetDateTime that the view exposes as-is.
        val eventA = OffsetDateTime.parse("2026-05-01T09:00:00+09:00")

        whenever(boundary.currentTenantScope()).thenReturn(null)
        whenever(repo.findAll()).thenReturn(listOf(a, b))

        // batch aggregate stubs — Object[]{tenantId(UUID), count/maxCreatedAt}
        whenever(credentialRepository.countGroupedByTenantId())
            .thenReturn(listOf<Array<Any?>>(arrayOf(idA, 5L), arrayOf(idB, 0L)))
        whenever(apiKeyRepository.countActiveGroupedByTenantId(anyOrNull()))
            .thenReturn(listOf<Array<Any?>>(arrayOf(idA, 2L))) // idB absent => 0
        whenever(auditLogRepository.findLatestCreatedAtGroupedByTenantId())
            .thenReturn(listOf<Array<Any?>>(arrayOf(idA, eventA))) // idB absent => null

        val views = service.list()

        assertThat(views).hasSize(2)
        val va = views.stream().filter { v -> v.id == idA }.findFirst().orElseThrow()
        val vb = views.stream().filter { v -> v.id == idB }.findFirst().orElseThrow()
        assertThat(va.credentials).isEqualTo(5L)
        assertThat(va.apiKeys).isEqualTo(2L)
        assertThat(va.lastEventAt).isEqualTo(eventA)
        assertThat(vb.credentials).isEqualTo(0L)
        assertThat(vb.apiKeys).isEqualTo(0L) // tenant absent from active-key aggregate => 0
        assertThat(vb.lastEventAt).isNull() // tenant absent from event aggregate => null

        // invariant: list() must NOT use the per-tenant N+1 methods anymore
        verify(credentialRepository, never()).countByTenantId(anyOrNull())
        verify(apiKeyRepository, never()).countActiveByTenantId(anyOrNull(), anyOrNull())
        verify(auditLogRepository, never()).findFirstByTenantIdOrderByCreatedAtDesc(anyOrNull())
    }

    @Test
    fun createRejectsMalformedOrigin() {
        whenever(repo.existsBySlug("T_A")).thenReturn(false)
        val req = TenantAdminDto.TenantCreateRequest(
            "T_A", "Tenant A", "localhost", "Tenant A",
            listOf("ftp://bad.example.com"), // 형식 위반
            setOf("none"),
            true, false,
            "NONE", 60000,
        )
        assertThatThrownBy { service.create(req, UUID.randomUUID(), "alice@example.com") }
            .isInstanceOf(BusinessException::class.java)
            .satisfies(
                ThrowingConsumer { ex ->
                    assertThat((ex as BusinessException).errorCode)
                        .isEqualTo(ErrorCode.INVALID_INPUT)
                },
            )
    }

    @Test
    fun updateRejectsMalformedOrigin() {
        // lookup(idOrSlug) → findBySlug 가 기존 tenant 반환 (realistic existing tenant)
        val existing = Tenant("T_A", "Tenant A", "localhost", "Tenant A")
        existing.addAllowedOrigin("http://localhost", 0)
        existing.addAcceptedFormat("none")
        setId(existing, UUID.randomUUID())
        whenever(repo.findBySlug("T_A")).thenReturn(Optional.of(existing))
        // tenantBoundary.assertCanAccessTenant 는 void mock → 통과

        val req = TenantAdminDto.TenantUpdateRequest(
            "Tenant A", "localhost", "Tenant A",
            listOf("ftp://bad.example.com"), // 형식 위반
            setOf("none"),
            true, false,
            "NONE", 60000,
        )

        assertThatThrownBy { service.update("T_A", req, UUID.randomUUID(), "alice@example.com") }
            .isInstanceOf(BusinessException::class.java)
            .satisfies(
                ThrowingConsumer { ex ->
                    assertThat((ex as BusinessException).errorCode)
                        .isEqualTo(ErrorCode.INVALID_INPUT)
                },
            )

        // validate-before-mutate: 형식 위반이면 saveAndFlush 까지 도달하지 않음
        verify(repo, never()).saveAndFlush(anyOrNull<Tenant>())
        // validate-before-snapshot (hoist lock-in): access check 직후 검증 → snapshot insert 시도 안 함
        verify(snapshotRepo, never()).save(anyOrNull())
    }

    @Test
    fun createRejectsDuplicateSlug() {
        whenever(repo.existsBySlug("T_A")).thenReturn(true)
        val req = TenantAdminDto.TenantCreateRequest(
            "T_A", "Tenant A", "localhost", "Tenant A",
            listOf("http://localhost"),
            setOf("none"),
            true, false,
            "NONE", 60000,
        )
        assertThatThrownBy { service.create(req, UUID.randomUUID(), "alice@example.com") }
            .isInstanceOf(BusinessException::class.java)
            .satisfies(
                ThrowingConsumer { ex ->
                    assertThat((ex as BusinessException).errorCode)
                        .isEqualTo(ErrorCode.TENANT_DUPLICATE)
                },
            )
    }

    private companion object {
        /** Assigns BaseEntity.id on an un-persisted entity (mirrors ApiKeyRotateServiceTest#setId). */
        fun setId(t: Tenant, id: UUID) {
            try {
                val f = Tenant::class.java.superclass.getDeclaredField("id")
                f.isAccessible = true
                f.set(t, id)
            } catch (e: Exception) {
                throw RuntimeException(e)
            }
        }
    }
}
