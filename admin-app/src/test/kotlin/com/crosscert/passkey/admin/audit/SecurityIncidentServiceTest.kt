package com.crosscert.passkey.admin.audit

import com.crosscert.passkey.core.alert.SecurityAlertEvent
import com.crosscert.passkey.core.entity.SecurityIncident
import com.crosscert.passkey.core.entity.Tenant
import com.crosscert.passkey.core.repository.SecurityIncidentRepository
import com.crosscert.passkey.core.repository.TenantRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

class SecurityIncidentServiceTest {

    lateinit var repo: SecurityIncidentRepository
    lateinit var verifier: AuditChainVerifier
    lateinit var audit: AuditLogService
    lateinit var events: ApplicationEventPublisher
    lateinit var tenants: TenantRepository
    lateinit var clock: Clock
    lateinit var objectMapper: ObjectMapper
    lateinit var svc: SecurityIncidentService

    val TENANT: UUID = UUID.randomUUID()
    val ENTRY: UUID = UUID.randomUUID()
    val ACTOR: UUID = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        repo = mock()
        verifier = mock()
        audit = mock()
        events = mock()
        tenants = mock()
        clock = Clock.fixed(Instant.parse("2026-06-21T00:00:00Z"), ZoneOffset.ofHours(9))
        objectMapper = ObjectMapper()
        svc = SecurityIncidentService(repo, verifier, audit, events, tenants, clock, objectMapper)

        val t: Tenant = mock()
        whenever(t.displayName).thenReturn("Acme Corp")
        whenever(tenants.findById(TENANT)).thenReturn(Optional.of(t))
        whenever(repo.saveAndFlush(any<SecurityIncident>())).thenAnswer { inv -> inv.getArgument<SecurityIncident>(0) }
    }

    private fun stubTampered(tampered: Boolean) {
        // verifyTenant returns a TenantResult record; ok()==true means chain intact.
        val r = if (tampered) {
            AuditChainVerifier.TenantResult.broken(TENANT, ENTRY)
        } else {
            AuditChainVerifier.TenantResult.valid(TENANT)
        }
        whenever(verifier.verifyTenant(TENANT)).thenReturn(r)
    }

    @Test
    fun create_whenTampered_savesAuditsAndPublishesAlert() {
        stubTampered(true)
        whenever(repo.existsByTenantIdAndStatus(TENANT, "OPEN")).thenReturn(false)

        val i = svc.create(TENANT, ACTOR, "alice@crosscert.com")

        assertThat(i.status).isEqualTo("OPEN")
        assertThat(i.tenantId).isEqualTo(TENANT)
        // tamperedEntryId 는 caller 입력이 아니라 verifyTenant 의 broken row(ENTRY)에서 도출돼야 한다.
        assertThat(i.tamperedEntryId).isEqualTo(ENTRY)
        verify(repo).saveAndFlush(any<SecurityIncident>())
        verify(audit).append(anyOrNull())
        // 단위 테스트는 트랜잭션 동기화 비활성 → afterCommit 우회 즉시 발행(else 분기).
        verify(events).publishEvent(any<SecurityAlertEvent>())
    }

    // never() publishEvent 검증: 원 Java 의 bare any() 는 javac 가 publishEvent(ApplicationEvent) 오버로드로
    // 바인딩했다(javap 확인). 의미 보존을 위해 같은 오버로드를 명시한다 — 서비스는 SecurityAlertEvent(record)를
    // publishEvent(Object) 로 발행하므로 이 검증은 원래부터 공허하다(보고서에 기록).

    @Test
    fun create_whenAlreadyOpen_throwsConflict() {
        stubTampered(true)
        whenever(repo.existsByTenantIdAndStatus(TENANT, "OPEN")).thenReturn(true)

        assertThatThrownBy { svc.create(TENANT, ACTOR, "alice@crosscert.com") }
            .isInstanceOf(IncidentConflictException::class.java)
        verify(repo, never()).saveAndFlush(anyOrNull())
        verify(events, never()).publishEvent(anyOrNull<ApplicationEvent>())
    }

    @Test
    fun create_whenNotActuallyTampered_throwsUnprocessable() {
        stubTampered(false)

        assertThatThrownBy { svc.create(TENANT, ACTOR, "alice@crosscert.com") }
            .isInstanceOf(IncidentNotTamperedException::class.java)
        verify(repo, never()).saveAndFlush(anyOrNull())
    }

    @Test
    fun resolve_whenOpen_setsResolvedFields() {
        val open = SecurityIncident.open(TENANT, ENTRY, "{}", ACTOR, OffsetDateTime.now(clock))
        // Atomic conditional UPDATE succeeds (1 row), then we re-read the RESOLVED row.
        whenever(repo.resolveIfOpen(eq(open.id), eq(ACTOR), eq("DBA 복구 완료"), any<OffsetDateTime>()))
            .thenReturn(1)
        open.resolve(ACTOR, "DBA 복구 완료", OffsetDateTime.now(clock))
        whenever(repo.findById(open.id)).thenReturn(Optional.of(open))

        val resolved = svc.resolve(open.id, "DBA 복구 완료", ACTOR, "alice@crosscert.com")

        assertThat(resolved.status).isEqualTo("RESOLVED")
        assertThat(resolved.resolutionNote).isEqualTo("DBA 복구 완료")
        assertThat(resolved.resolvedAt).isNotNull()
        verify(audit).append(anyOrNull())
    }

    @Test
    fun resolve_whenNotFound_throwsNotFound() {
        val id = UUID.randomUUID()
        // Atomic UPDATE affects 0 rows AND row does not exist → 404.
        whenever(repo.resolveIfOpen(eq(id), eq(ACTOR), eq("note"), any<OffsetDateTime>()))
            .thenReturn(0)
        whenever(repo.existsById(id)).thenReturn(false)

        assertThatThrownBy { svc.resolve(id, "note", ACTOR, "alice@crosscert.com") }
            .isInstanceOf(IncidentNotFoundException::class.java)
        verify(audit, never()).append(anyOrNull())
    }

    @Test
    fun resolve_whenAlreadyResolved_throwsConflict() {
        val id = UUID.randomUUID()
        // Atomic UPDATE affects 0 rows but row exists → already RESOLVED (race loser lands here too) → 409.
        whenever(repo.resolveIfOpen(eq(id), eq(ACTOR), eq("note"), any<OffsetDateTime>()))
            .thenReturn(0)
        whenever(repo.existsById(id)).thenReturn(true)

        assertThatThrownBy { svc.resolve(id, "note", ACTOR, "alice@crosscert.com") }
            .isInstanceOf(IncidentConflictException::class.java)
        verify(audit, never()).append(anyOrNull())
    }

    @Test
    fun resolve_whenNoteBlank_throwsValidation() {
        val id = UUID.randomUUID()

        assertThatThrownBy { svc.resolve(id, "  ", id, "alice@crosscert.com") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { svc.resolve(id, null, id, "alice@crosscert.com") }
            .isInstanceOf(IllegalArgumentException::class.java)

        verify(repo, never()).resolveIfOpen(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
        verify(audit, never()).append(anyOrNull())
    }

    @Test
    fun create_whenUniqueIndexViolated_translatesToConflictWithoutSideEffects() {
        // 선체크는 통과(false)했지만 동시 생성 race 에서 DB 부분 유니크 인덱스가 막는 경우:
        // saveAndFlush 가 즉시 위반을 던지고 409 로 변환되며 audit/alert side-effect 가 없어야 한다.
        stubTampered(true)
        whenever(repo.existsByTenantIdAndStatus(TENANT, "OPEN")).thenReturn(false)
        whenever(repo.saveAndFlush(any<SecurityIncident>()))
            .thenThrow(DataIntegrityViolationException("ux_incident_open_per_tenant"))

        assertThatThrownBy { svc.create(TENANT, ACTOR, "alice@crosscert.com") }
            .isInstanceOf(IncidentConflictException::class.java)

        verify(audit, never()).append(anyOrNull())
        verify(events, never()).publishEvent(anyOrNull<ApplicationEvent>())
    }

    @Test
    fun create_whenActorIdNull_throws() {
        // actorId 가드(Objects.requireNonNull → NPE)가 영속화 전에 차단해야 한다.
        assertThatThrownBy { svc.create(TENANT, null, "alice@crosscert.com") }
            .isInstanceOf(NullPointerException::class.java)
        verify(repo, never()).saveAndFlush(anyOrNull())
        verify(audit, never()).append(anyOrNull())
        verify(events, never()).publishEvent(anyOrNull<ApplicationEvent>())
    }

    @Test
    fun resolve_whenActorIdNull_throws() {
        // actorId 가드(Objects.requireNonNull → NPE)가 UPDATE 전에 차단해야 한다.
        val id = UUID.randomUUID()
        assertThatThrownBy { svc.resolve(id, "note", null, "alice@crosscert.com") }
            .isInstanceOf(NullPointerException::class.java)
        verify(repo, never()).resolveIfOpen(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
        verify(audit, never()).append(anyOrNull())
    }
}
