package com.crosscert.passkey.admin.auth;

import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

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
@Component
public class TenantBoundary {

    public void assertCanAccessTenant(UUID tenantId) {
        AdminUserDetails me = currentPrincipal();
        if (me.isPlatformOperator()) return;
        if (me.isRpAdmin()) {
            if (!me.getTenantId().equals(tenantId)) {
                throw new BusinessException(ErrorCode.ACCESS_DENIED,
                        "RP_ADMIN cannot access tenant " + tenantId);
            }
            return;
        }
        throw new BusinessException(ErrorCode.ACCESS_DENIED, "unknown role: " + me.getRole());
    }

    /**
     * list 분기 — PLATFORM_OPERATOR 는 empty (모든 tenant), RP_ADMIN 은 자기 tenantId.
     */
    public Optional<UUID> currentTenantScope() {
        AdminUserDetails me = currentPrincipal();
        if (me.isPlatformOperator()) return Optional.empty();
        if (me.isRpAdmin())          return Optional.of(me.getTenantId());
        throw new BusinessException(ErrorCode.ACCESS_DENIED, "unknown role: " + me.getRole());
    }

    /**
     * 현재 로그인 사용자가 PLATFORM_OPERATOR 임을 강제.
     */
    public void assertPlatformOperator() {
        AdminUserDetails me = currentPrincipal();
        if (!me.isPlatformOperator()) {
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
