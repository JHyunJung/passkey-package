package com.crosscert.passkey.admin.auth;

import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * RP_ADMIN 의 "현재 활성 RP" 를 HttpSession 속성으로 관리한다.
 * 활성 RP 는 항상 allowedTenantIds 범위 안에서만 선택 가능(세션 변조 방어).
 * 세션에 없거나 허용범위를 벗어난 값이면 정렬상 첫 RP 로 복구한다(결정적 기본값).
 */
@Component
public class ActiveTenantResolver {

    public static final String ACTIVE_TENANT_ATTR = "ACTIVE_TENANT_ID";

    /** RP_ADMIN 의 현재 활성 RP. allowedTenantIds 가 비면 null. */
    public UUID resolve(AdminUserDetails me) {
        Set<UUID> allowed = me.getAllowedTenantIds();
        if (allowed.isEmpty()) return null;
        UUID fromSession = (UUID) getAttr(ACTIVE_TENANT_ATTR);
        if (fromSession != null && allowed.contains(fromSession)) {
            return fromSession;
        }
        UUID fallback = new TreeSet<>(allowed).first();
        setAttr(ACTIVE_TENANT_ATTR, fallback);
        return fallback;
    }

    /** 활성 RP 변경. allowedTenantIds 밖이면 ACCESS_DENIED. */
    public void setActive(AdminUserDetails me, UUID tenantId) {
        if (!me.getAllowedTenantIds().contains(tenantId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED,
                    "tenant not in allowed set: " + tenantId);
        }
        setAttr(ACTIVE_TENANT_ATTR, tenantId);
    }

    private Object getAttr(String name) {
        ServletRequestAttributes attrs = current();
        return attrs == null ? null
                : attrs.getAttribute(name, ServletRequestAttributes.SCOPE_SESSION);
    }

    private void setAttr(String name, Object value) {
        ServletRequestAttributes attrs = current();
        if (attrs != null) {
            attrs.setAttribute(name, value, ServletRequestAttributes.SCOPE_SESSION);
        }
    }

    private ServletRequestAttributes current() {
        var ra = RequestContextHolder.getRequestAttributes();
        return (ra instanceof ServletRequestAttributes sra) ? sra : null;
    }
}
