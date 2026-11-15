package com.crosscert.passkey.admin.tenant

import com.crosscert.passkey.admin.audit.AuditAppendRequest
import com.crosscert.passkey.admin.audit.AuditLogService
import com.crosscert.passkey.core.api.BusinessException
import com.crosscert.passkey.core.api.ErrorCode
import com.crosscert.passkey.core.repository.ApiKeyRepository
import com.crosscert.passkey.core.repository.TenantRepository
import org.slf4j.LoggerFactory
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

private val log = LoggerFactory.getLogger(TenantLifecycleService::class.java)

/**
 * 테넌트 status 전이 (suspend/activate) + suspend 시 활성 API 키 일괄 revoke.
 * activate 는 그때 revoke 된 키를 되살리지 않는다 — 재개한 테넌트는 키를 새로 발급받아야 한다.
 *
 * tenantId nullable: 호출자(TenantAdminController)가 TenantView.id(영속 엔티티 id 에서 온 nullable DTO 필드)를
 * 그대로 넘긴다 — 정상 경로에선 null 이 오지 않는다. null 이면 리포지토리 호출 전에 SimpleJpaRepository 와 같은 문구의
 * IllegalArgumentException 을 던진다(REST 호출이면 GlobalExceptionHandler 가 400(INVALID_INPUT)).
 */
@Service
class TenantLifecycleService(
    private val tenants: TenantRepository,
    private val apiKeys: ApiKeyRepository,
    private val audit: AuditLogService,
    private val clock: Clock,
) {

    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @Transactional
    fun suspend(tenantId: UUID?, actorId: UUID?, actorEmail: String?) {
        val id = tenantId ?: throw IllegalArgumentException("The given id must not be null")
        val t = tenants.findById(id)
            .orElseThrow { BusinessException(ErrorCode.TENANT_NOT_FOUND) }
        t.suspend()
        tenants.save(t)
        val now = OffsetDateTime.now(clock)
        val active = apiKeys.findActiveByTenantId(id, now)
        for (k in active) {
            k.revoke(now)
        }
        audit.append(
            AuditAppendRequest(
                actorId, actorEmail, "TENANT_SUSPEND",
                "TENANT", id.toString(), id,
                java.util.Map.of<String, Any?>("revokedKeys", active.size),
            ),
        )
        log.warn(
            "tenant suspended: tenantId={} revokedKeys={} actor={}",
            id, active.size, actorEmail,
        )
    }

    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @Transactional
    fun activate(tenantId: UUID?, actorId: UUID?, actorEmail: String?) {
        val id = tenantId ?: throw IllegalArgumentException("The given id must not be null")
        val t = tenants.findById(id)
            .orElseThrow { BusinessException(ErrorCode.TENANT_NOT_FOUND) }
        t.activate()
        tenants.save(t)
        audit.append(
            AuditAppendRequest(
                actorId, actorEmail, "TENANT_ACTIVATE",
                "TENANT", id.toString(), id, java.util.Map.of<String, Any?>(),
            ),
        )
        log.info("tenant activated: tenantId={} actor={}", id, actorEmail)
    }
}
