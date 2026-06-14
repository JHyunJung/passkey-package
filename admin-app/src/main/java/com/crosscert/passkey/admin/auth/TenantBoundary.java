package com.crosscert.passkey.admin.auth;

import com.crosscert.passkey.core.alert.SecurityAlertEvent;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import com.crosscert.passkey.core.util.CryptoUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * RP_ADMIN 이 자기 tenant 외부에 접근하지 못하게 차단하는 단일 진입점.
 * 모든 admin service 의 첫 줄에서 호출.
 *
 *   tenantBoundary.assertCanAccessTenant(tenantId);
 *
 * PLATFORM_OPERATOR 는 무제한. RP_ADMIN 은 자기 tenantId 와 일치할 때만.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantBoundary {

    private final ApplicationEventPublisher eventPublisher;

    /** Builds + publishes a TENANT_BOUNDARY_VIOLATION/CRITICAL alert.
     *  ctx must contain only masked/identifier values (masked actor email, role, tenant UUIDs). */
    private void publishViolation(Map<String, String> ctx) {
        eventPublisher.publishEvent(new SecurityAlertEvent(
                SecurityAlertEvent.AlertType.TENANT_BOUNDARY_VIOLATION,
                SecurityAlertEvent.Severity.CRITICAL,
                "tenant boundary violation",
                ctx));
    }

    public void assertCanAccessTenant(UUID tenantId) {
        AdminUserDetails me = currentPrincipal();
        if (me.isPlatformOperator()) return;
        if (me.isRpAdmin()) {
            if (!me.getTenantId().equals(tenantId)) {
                log.warn("tenant boundary violation: actor={} role={} requested={} allowed={}",
                        CryptoUtils.maskEmail(me.getUsername()), me.getRole(), tenantId, me.getTenantId());
                publishViolation(Map.of(
                        "actor", CryptoUtils.maskEmail(me.getUsername()),
                        "role", String.valueOf(me.getRole()),
                        "requested", String.valueOf(tenantId),
                        "allowed", String.valueOf(me.getTenantId())));
                throw new BusinessException(ErrorCode.ACCESS_DENIED,
                        "RP_ADMIN cannot access tenant " + tenantId);
            }
            return;
        }
        log.warn("tenant boundary violation: actor={} role={} requested={} allowed=none",
                CryptoUtils.maskEmail(me.getUsername()), me.getRole(), tenantId);
        publishViolation(Map.of(
                "actor", CryptoUtils.maskEmail(me.getUsername()),
                "role", String.valueOf(me.getRole()),
                "requested", String.valueOf(tenantId),
                "allowed", "none"));
        throw new BusinessException(ErrorCode.ACCESS_DENIED, "unknown role: " + me.getRole());
    }

    /**
     * list 분기 — PLATFORM_OPERATOR 는 empty (모든 tenant), RP_ADMIN 은 자기 tenantId.
     */
    public Optional<UUID> currentTenantScope() {
        AdminUserDetails me = currentPrincipal();
        if (me.isPlatformOperator()) return Optional.empty();
        if (me.isRpAdmin())          return Optional.of(me.getTenantId());
        log.warn("tenant boundary violation: actor={} role={} scope=list allowed=none",
                CryptoUtils.maskEmail(me.getUsername()), me.getRole());
        publishViolation(Map.of(
                "actor", CryptoUtils.maskEmail(me.getUsername()),
                "role", String.valueOf(me.getRole()),
                "scope", "list",
                "allowed", "none"));
        throw new BusinessException(ErrorCode.ACCESS_DENIED, "unknown role: " + me.getRole());
    }

    /**
     * 현재 로그인 사용자가 PLATFORM_OPERATOR 임을 강제.
     */
    public void assertPlatformOperator() {
        AdminUserDetails me = currentPrincipal();
        if (!me.isPlatformOperator()) {
            log.warn("tenant boundary violation: actor={} role={} required=PLATFORM_OPERATOR",
                    CryptoUtils.maskEmail(me.getUsername()), me.getRole());
            publishViolation(Map.of(
                    "actor", CryptoUtils.maskEmail(me.getUsername()),
                    "role", String.valueOf(me.getRole()),
                    "required", "PLATFORM_OPERATOR"));
            throw new BusinessException(ErrorCode.ACCESS_DENIED,
                    "platform-only operation");
        }
    }

    private AdminUserDetails currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AdminUserDetails p)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "not authenticated");
        }
        return p;
    }
}
