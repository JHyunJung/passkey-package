package com.crosscert.passkey.admin.tenant

import com.crosscert.passkey.admin.audit.AuditAppendRequest
import com.crosscert.passkey.admin.audit.AuditLogService
import com.crosscert.passkey.admin.auth.TenantBoundary
import com.crosscert.passkey.core.api.BusinessException
import com.crosscert.passkey.core.api.ErrorCode
import com.crosscert.passkey.core.entity.AuditLog
import com.crosscert.passkey.core.entity.Tenant
import com.crosscert.passkey.core.entity.TenantAaguidPolicy
import com.crosscert.passkey.core.entity.TenantWebauthnSnapshot
import com.crosscert.passkey.core.repository.ApiKeyRepository
import com.crosscert.passkey.core.repository.AuditLogRepository
import com.crosscert.passkey.core.repository.CredentialRepository
import com.crosscert.passkey.core.repository.TenantAaguidPolicyRepository
import com.crosscert.passkey.core.repository.TenantRepository
import com.crosscert.passkey.core.repository.TenantWebauthnSnapshotRepository
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

private val log = LoggerFactory.getLogger(TenantAdminService::class.java)

// 원 Java 의 (UUID) r[0] 캐스트처럼 null 원소는 그대로 통과(as UUID?), (Number) r[1] 의 null 은 NPE.
private fun toCountMap(rows: List<Array<Any?>>): Map<UUID?, Long> {
    val m = HashMap<UUID?, Long>()
    for (r in rows) {
        m[r[0] as UUID?] = (r[1] as Number).toLong()
    }
    return m
}

private fun toOffsetDateTimeMap(rows: List<Array<Any?>>): Map<UUID?, OffsetDateTime?> {
    val m = HashMap<UUID?, OffsetDateTime?>()
    for (r in rows) {
        m[r[0] as UUID?] = r[1] as OffsetDateTime?
    }
    return m
}

/**
 * 등록/수정 시 allowedOrigin 형식을 DB 도달 전에 검증 — 위반 시 400 fail-fast.
 * 웹 origin 또는 android:apk-key-hash origin 만 허용 (AllowedOriginFormat).
 * origins 가 null 이면 원 Java for-each 처럼 NullPointerException(@NotEmpty 검증을 거친 경로에선 불가).
 */
private fun validateOriginFormats(origins: List<String>?) {
    for (origin in origins!!) {
        if (!AllowedOriginFormat.isValid(origin)) {
            throw BusinessException(
                ErrorCode.INVALID_INPUT,
                "invalid allowedOrigin format: $origin",
            )
        }
    }
}

@Service
class TenantAdminService(
    private val tenants: TenantRepository,
    private val audit: AuditLogService,
    private val em: EntityManager,
    private val tenantBoundary: TenantBoundary,
    private val aaguidPolicyRepo: TenantAaguidPolicyRepository,
    private val snapshotRepo: TenantWebauthnSnapshotRepository,
    private val credentialRepository: CredentialRepository,
    private val apiKeyRepository: ApiKeyRepository,
    private val auditLogRepository: AuditLogRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun list(): List<TenantAdminDto.TenantView> {
        val tid = tenantBoundary.currentTenantScope() ?: return listAllWithBatchAggregates()
        return tenants.findById(tid)
            .map { toView(it) }
            .map { java.util.List.of(it) }
            .orElseGet { java.util.List.of() }
    }

    /**
     * PLATFORM_OPERATOR cross-tenant 목록. KPI 3종(credentials/apiKeys/lastEventAt)을
     * tenant 당 3쿼리(N+1) 대신 GROUP BY 배치 집계 3쿼리로 모은 뒤 toView 에서 lookup.
     * 반환 숫자/필드는 per-tenant 경로와 동일 — UI 표시값 불변.
     */
    private fun listAllWithBatchAggregates(): List<TenantAdminDto.TenantView> {
        val credByTenant = toCountMap(credentialRepository.countGroupedByTenantId())
        val activeKeysByTenant =
            toCountMap(apiKeyRepository.countActiveGroupedByTenantId(OffsetDateTime.now(clock)))
        val lastEventByTenant =
            toOffsetDateTimeMap(auditLogRepository.findLatestCreatedAtGroupedByTenantId())

        return tenants.findAll()
            .map { t ->
                toView(
                    t,
                    credByTenant.getOrDefault(t.id, 0L),
                    activeKeysByTenant.getOrDefault(t.id, 0L),
                    lastEventByTenant[t.id],
                )
            }
    }

    @Transactional(readOnly = true)
    fun get(idOrSlug: String): TenantAdminDto.TenantView {
        val t = lookup(idOrSlug)
        tenantBoundary.assertCanAccessTenant(t.id)
        return toView(t)
    }

    /**
     * 단건 경로(get/create/update)용 — per-tenant 쿼리 3개로 KPI 집계.
     * list() 는 listAllWithBatchAggregates() 가 배치 집계 후 아래 오버로드를 직접 호출.
     */
    private fun toView(t: Tenant): TenantAdminDto.TenantView {
        val credentials = credentialRepository.countByTenantId(t.id)
        val apiKeys = apiKeyRepository.countActiveByTenantId(t.id, OffsetDateTime.now(clock))
        val lastEventAt: OffsetDateTime? = auditLogRepository
            .findFirstByTenantIdOrderByCreatedAtDesc(t.id)
            .map(AuditLog::getCreatedAt)
            .orElse(null)
        return toView(t, credentials, apiKeys, lastEventAt)
    }

    private fun toView(t: Tenant, credentials: Long, apiKeys: Long, lastEventAt: OffsetDateTime?): TenantAdminDto.TenantView =
        TenantAdminDto.TenantView.from(t, credentials, apiKeys, lastEventAt)

    @Transactional
    fun create(
        req: TenantAdminDto.TenantCreateRequest,
        actorId: UUID?,
        actorEmail: String?,
    ): TenantAdminDto.TenantView {
        if (tenants.existsBySlug(req.slug)) {
            throw BusinessException(ErrorCode.TENANT_DUPLICATE)
        }

        val t = Tenant(req.slug, req.displayName, req.rpId, req.rpName)
        // 원 Java 의 Boolean → boolean 언박싱과 같은 지점의 NPE(@NotNull 검증을 거친 컨트롤러 경로에선 불가).
        t.setRequireUserVerification(req.requireUserVerification!!)
        t.setMdsRequired(req.mdsRequired!!)
        t.attestationConveyance = req.attestationConveyance
        t.webauthnTimeoutMs = req.webauthnTimeoutMs

        validateOriginFormats(req.allowedOrigins)
        var order = 0
        for (origin in req.allowedOrigins!!) {
            t.addAllowedOrigin(origin, order++)
        }
        // 원 Java for-each 와 동일: null 이면 NPE(@NotEmpty 검증을 거친 경로에선 불가).
        for (format in req.acceptedFormats!!) {
            t.addAcceptedFormat(format)
        }

        tenants.saveAndFlush(t)

        // AAGUID Policy 기본값 (ANY, mdsStrict=false) 자동 INSERT
        aaguidPolicyRepo.save(
            TenantAaguidPolicy(
                t.id,
                TenantAaguidPolicy.Mode.ANY,
                false,
                "system:create",
            ),
        )

        // 초기 WebAuthn snapshot
        try {
            val originsJson = objectMapper.writeValueAsString(t.allowedOriginValues)
            val formatsJson = objectMapper.writeValueAsString(ArrayList(t.acceptedFormatValues))
            snapshotRepo.save(
                TenantWebauthnSnapshot(
                    t.id,
                    t.rpId, t.rpName,
                    originsJson, formatsJson,
                    t.isRequireUserVerification, t.isMdsRequired,
                    "system:create",
                ),
            )
        } catch (e: Exception) {
            throw IllegalStateException("Failed to snapshot on tenant create", e)
        }

        val payload = LinkedHashMap<String, Any?>()
        payload["slug"] = req.slug
        payload["displayName"] = req.displayName
        payload["rpId"] = req.rpId
        audit.append(
            AuditAppendRequest(
                actorId, actorEmail, "TENANT_CREATE",
                "TENANT", req.slug,
                t.id,
                payload,
            ),
        )

        log.info(
            "tenant created: slug={} id={} rpId={}",
            req.slug, t.id, req.rpId,
        )

        return toView(t)
    }

    @Transactional
    fun update(
        idOrSlug: String,
        req: TenantAdminDto.TenantUpdateRequest,
        actorId: UUID?,
        actorEmail: String?,
    ): TenantAdminDto.TenantView {
        val t = lookup(idOrSlug)
        tenantBoundary.assertCanAccessTenant(t.id)
        // fail-fast: snapshot 저장·mutation 이전에 origin 형식 검증 (DB 도달 전 400)
        validateOriginFormats(req.allowedOrigins)
        val before = TenantSnapshot.of(t)

        // 변경 직전 값을 snapshot 으로 보존
        try {
            snapshotRepo.save(
                TenantWebauthnSnapshot(
                    t.id,
                    t.rpId, t.rpName,
                    objectMapper.writeValueAsString(t.allowedOriginValues),
                    objectMapper.writeValueAsString(ArrayList(t.acceptedFormatValues)),
                    t.isRequireUserVerification, t.isMdsRequired,
                    actorEmail,
                ),
            )
        } catch (e: Exception) {
            throw IllegalStateException("Failed to snapshot before update", e)
        }

        // rpId / slug 는 silent ignore (별도 워크플로우 필요 — spec § 6.1)
        if (req.rpId != null && req.rpId != t.rpId) {
            log.debug(
                "rpId update ignored — not yet implemented (tenant={} from={} to={})",
                t.id, t.rpId, req.rpId,
            )
        }

        t.displayName = req.displayName
        t.rpName = req.rpName
        replaceAllowedOrigins(t, req.allowedOrigins)
        replaceAcceptedFormats(t, req.acceptedFormats)
        // 원 Java 의 Boolean → boolean 언박싱과 같은 지점의 NPE(@NotNull 검증을 거친 컨트롤러 경로에선 불가).
        t.setRequireUserVerification(req.requireUserVerification!!)
        t.setMdsRequired(req.mdsRequired!!)
        t.attestationConveyance = req.attestationConveyance
        t.webauthnTimeoutMs = req.webauthnTimeoutMs

        tenants.saveAndFlush(t)

        val after = TenantSnapshot.of(t)
        val changed = before.diff(after)

        if (!changed.isEmpty()) {
            val payload = HashMap<String, Any?>()
            payload["before"] = before
            payload["after"] = after
            payload["changedFields"] = changed
            audit.append(
                AuditAppendRequest(
                    actorId, actorEmail, "TENANT_UPDATE",
                    "TENANT", t.id.toString(),
                    t.id,
                    payload,
                ),
            )
            log.info(
                "tenant updated id={} slug={} changed={} actor={}",
                t.id, t.slug, changed, actorEmail,
            )
        } else {
            log.debug(
                "tenant update no-op id={} slug={} actor={}",
                t.id, t.slug, actorEmail,
            )
        }

        return toView(t)
    }

    private fun replaceAllowedOrigins(t: Tenant, origins: List<String>?) {
        t.clearAllowedOrigins()
        // Flush the orphan DELETEs before re-inserting — Oracle's unique constraint
        // on (tenant_id, origin, sort_order) would otherwise see both DELETE and INSERT
        // in the same batch with INSERTs ordered before DELETEs.
        em.flush()
        var order = 0
        // 원 Java for-each 와 동일: null 이면 NPE(앞선 validateOriginFormats 가 이미 같은 NPE 를 낸다).
        for (origin in origins!!) {
            t.addAllowedOrigin(origin, order++)
        }
    }

    private fun replaceAcceptedFormats(t: Tenant, formats: Set<String>?) {
        t.clearAcceptedFormats()
        // Flush the orphan DELETEs before re-inserting — Oracle's unique constraint
        // UQ_TAF_TENANT_FORMAT(tenant_id, format) would otherwise be violated when
        // Hibernate batches INSERTs before DELETEs in the same flush cycle.
        em.flush()
        // 원 Java for-each 와 동일: null 이면 clear·flush 뒤 이 지점에서 NPE(@NotEmpty 검증을 거친 경로에선 불가).
        for (format in formats!!) {
            t.addAcceptedFormat(format)
        }
    }

    /**
     * idOrSlug 의 UUID 파싱 시도 후 실패하면 slug 로 조회.
     * audit log payload 의 changedFields 키로 직렬화.
     */
    private fun lookup(idOrSlug: String): Tenant {
        // Try slug first (operator-friendly URL pattern: /admin/api/tenants/acme)
        val bySlug = tenants.findBySlug(idOrSlug)
        if (bySlug.isPresent) {
            return bySlug.get()
        }
        // UUID fallback (direct API call with UUID string)
        try {
            val id = UUID.fromString(idOrSlug)
            return tenants.findById(id)
                .orElseThrow { BusinessException(ErrorCode.TENANT_NOT_FOUND) }
        } catch (e: IllegalArgumentException) {
            throw BusinessException(ErrorCode.TENANT_NOT_FOUND)
        }
    }
}
