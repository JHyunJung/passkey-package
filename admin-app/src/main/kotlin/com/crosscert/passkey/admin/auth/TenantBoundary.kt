package com.crosscert.passkey.admin.auth

import com.crosscert.passkey.core.alert.SecurityAlertEvent
import com.crosscert.passkey.core.api.BusinessException
import com.crosscert.passkey.core.api.ErrorCode
import com.crosscert.passkey.core.util.CryptoUtils
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import java.util.Optional
import java.util.UUID

private val log = LoggerFactory.getLogger(TenantBoundary::class.java)

/**
 * RP_ADMIN 이 자기 tenant 외부에 접근하지 못하게 차단하는 단일 진입점.
 * 모든 admin service 의 첫 줄에서 호출.
 *
 *   tenantBoundary.assertCanAccessTenant(tenantId);
 *
 * PLATFORM_OPERATOR 는 무제한. RP_ADMIN 은 자기 tenantId 와 일치할 때만.
 */
@Component
class TenantBoundary(
    private val eventPublisher: ApplicationEventPublisher,
    private val activeTenantResolver: ActiveTenantResolver,
) {

    /** Builds + publishes a TENANT_BOUNDARY_VIOLATION/CRITICAL alert.
     *  ctx must contain only masked/identifier values (masked actor email, role, tenant UUIDs). */
    private fun publishViolation(ctx: Map<String, String>) {
        eventPublisher.publishEvent(
            SecurityAlertEvent(
                SecurityAlertEvent.AlertType.TENANT_BOUNDARY_VIOLATION,
                SecurityAlertEvent.Severity.CRITICAL,
                "tenant boundary violation",
                ctx,
            ),
        )
    }

    /**
     * tenantId nullable: Java/Kotlin 호출자가 요청 데이터 그대로 넘긴다. RP_ADMIN 분기에서 null 이면
     * 원 Java 와 같이 불변 Set 의 contains(null) 이 NullPointerException 을 던진다
     * (Iterable.contains 확장 → java.util.Set.contains 위임). PLATFORM_OPERATOR 는 null 이어도 통과.
     */
    fun assertCanAccessTenant(tenantId: UUID?) {
        val me = currentPrincipal()
        if (me.isPlatformOperator()) return
        if (me.isRpAdmin()) {
            if (!me.allowedTenantIds.contains(tenantId)) {
                log.warn(
                    "tenant boundary violation: actor={} role={} requested={} allowed={}",
                    CryptoUtils.maskEmail(me.username), me.role, tenantId, me.allowedTenantIds,
                )
                publishViolation(
                    java.util.Map.of(
                        "actor", CryptoUtils.maskEmail(me.username),
                        "role", me.role,
                        "requested", tenantId.toString(),
                        "allowed", me.allowedTenantIds.toString(),
                    ),
                )
                throw BusinessException(ErrorCode.ACCESS_DENIED, "RP_ADMIN cannot access tenant $tenantId")
            }
            return
        }
        log.warn(
            "tenant boundary violation: actor={} role={} requested={} allowed=none",
            CryptoUtils.maskEmail(me.username), me.role, tenantId,
        )
        publishViolation(
            java.util.Map.of(
                "actor", CryptoUtils.maskEmail(me.username),
                "role", me.role,
                "requested", tenantId.toString(),
                "allowed", "none",
            ),
        )
        throw BusinessException(ErrorCode.ACCESS_DENIED, "unknown role: " + me.role)
    }

    /**
     * list 분기 — PLATFORM_OPERATOR 는 empty (모든 tenant), RP_ADMIN 은 자기 tenantId.
     */
    fun currentTenantScope(): Optional<UUID> {
        val me = currentPrincipal()
        if (me.isPlatformOperator()) return Optional.empty()
        if (me.isRpAdmin()) {
            val active = activeTenantResolver.resolve(me)
            // fail-closed: RP_ADMIN 의 active 가 null 이면(=allowedTenantIds 비어 매핑 0개)
            // empty 를 반환하면 @Filter 가 꺼져 전체 테넌트가 노출된다(fail-open). RP_ADMIN 은
            // 매핑 ≥1 이 앱 레벨 불변식이므로 여기 도달은 비정상 — 전체 노출 대신 차단한다.
            // (assertCanAccessTenant 의 RP_ADMIN 분기와 동일하게 fail-closed.)
            if (active == null) {
                log.warn(
                    "tenant boundary violation: actor={} role=RP_ADMIN scope=list active=none",
                    CryptoUtils.maskEmail(me.username),
                )
                publishViolation(
                    java.util.Map.of(
                        "actor", CryptoUtils.maskEmail(me.username),
                        "role", "RP_ADMIN",
                        "scope", "list",
                        "active", "none",
                    ),
                )
                throw BusinessException(ErrorCode.ACCESS_DENIED, "RP_ADMIN has no active tenant")
            }
            return Optional.of(active)
        }
        log.warn(
            "tenant boundary violation: actor={} role={} scope=list allowed=none",
            CryptoUtils.maskEmail(me.username), me.role,
        )
        publishViolation(
            java.util.Map.of(
                "actor", CryptoUtils.maskEmail(me.username),
                "role", me.role,
                "scope", "list",
                "allowed", "none",
            ),
        )
        throw BusinessException(ErrorCode.ACCESS_DENIED, "unknown role: " + me.role)
    }

    /**
     * 현재 로그인 사용자가 PLATFORM_OPERATOR 임을 강제.
     */
    fun assertPlatformOperator() {
        val me = currentPrincipal()
        if (!me.isPlatformOperator()) {
            log.warn(
                "tenant boundary violation: actor={} role={} required=PLATFORM_OPERATOR",
                CryptoUtils.maskEmail(me.username), me.role,
            )
            publishViolation(
                java.util.Map.of(
                    "actor", CryptoUtils.maskEmail(me.username),
                    "role", me.role,
                    "required", "PLATFORM_OPERATOR",
                ),
            )
            throw BusinessException(ErrorCode.ACCESS_DENIED, "platform-only operation")
        }
    }

    private fun currentPrincipal(): AdminUserDetails {
        val auth = SecurityContextHolder.getContext().authentication
        val p = auth?.principal
        if (auth == null || p !is AdminUserDetails) {
            throw BusinessException(ErrorCode.UNAUTHORIZED, "not authenticated")
        }
        return p
    }
}
