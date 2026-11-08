package com.crosscert.passkey.admin.auth

import com.crosscert.passkey.core.api.BusinessException
import com.crosscert.passkey.core.api.ErrorCode
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.util.TreeSet
import java.util.UUID

/**
 * RP_ADMIN 의 "현재 활성 RP" 를 HttpSession 속성으로 관리한다.
 * 활성 RP 는 항상 allowedTenantIds 범위 안에서만 선택 가능(세션 변조 방어).
 * 세션에 없거나 허용범위를 벗어난 값이면 정렬상 첫 RP 로 복구한다(결정적 기본값).
 */
@Component
class ActiveTenantResolver {

    /** RP_ADMIN 의 현재 활성 RP. allowedTenantIds 가 비면 null. */
    fun resolve(me: AdminUserDetails): UUID? {
        val allowed = me.allowedTenantIds
        if (allowed.isEmpty()) return null
        // 원 Java 의 (UUID) 캐스트: UUID 가 아닌 값이면 ClassCastException.
        val fromSession = getAttr(ACTIVE_TENANT_ATTR) as UUID?
        if (fromSession != null && allowed.contains(fromSession)) {
            return fromSession
        }
        val fallback = TreeSet(allowed).first()
        setAttr(ACTIVE_TENANT_ATTR, fallback)
        return fallback
    }

    /**
     * 활성 RP 변경. allowedTenantIds 밖이면 ACCESS_DENIED.
     *
     * tenantId nullable: 요청 JSON 에서 오는 값. null 이면 원 Java 와 같이 불변 Set 의 contains(null) 이
     * NullPointerException 을 던진다(Iterable.contains 확장 → java.util.Set.contains 위임).
     */
    fun setActive(me: AdminUserDetails, tenantId: UUID?) {
        if (!me.allowedTenantIds.contains(tenantId)) {
            throw BusinessException(ErrorCode.ACCESS_DENIED, "tenant not in allowed set: $tenantId")
        }
        // 위 contains(null) 이 이미 NPE 를 던졌으므로(불변 Set) 여기 tenantId 는 non-null — `!!` 는 발동하지 않는다.
        setAttr(ACTIVE_TENANT_ATTR, tenantId!!)
    }

    private fun getAttr(name: String): Any? {
        val attrs = current()
        return attrs?.getAttribute(name, RequestAttributes.SCOPE_SESSION)
    }

    private fun setAttr(name: String, value: Any) {
        val attrs = current()
        if (attrs != null) {
            attrs.setAttribute(name, value, RequestAttributes.SCOPE_SESSION)
        }
    }

    private fun current(): ServletRequestAttributes? =
        RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes

    companion object {
        const val ACTIVE_TENANT_ATTR = "ACTIVE_TENANT_ID"
    }
}
