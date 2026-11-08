package com.crosscert.passkey.admin.auth

import com.crosscert.passkey.core.api.BusinessException
import com.crosscert.passkey.core.api.ErrorCode
import com.crosscert.passkey.core.tenant.TenantContextHolder
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter
import java.util.Optional
import java.util.UUID

/**
 * 인증된 admin 요청에서 RP_ADMIN 이면 자기 tenantId 를 [TenantContextHolder] 에
 * set 하여 Hibernate @Filter(TenantFilterAspect)가 자기 tenant 만 보게 한다(defense in depth).
 * PLATFORM_OPERATOR 는 scope 가 empty 이므로 set 하지 않음 → 전체 조회(의도).
 *
 * Security filter chain 내부의 [MfaPendingFilter] 이후에
 * `http.addFilterAfter(..., MfaPendingFilter.class)` 로 등록되어야 한다.
 * MfaPendingFilter 가 [org.springframework.security.web.access.intercept.AuthorizationFilter]
 * 뒤에 있으므로 그 뒤로 두면 SecurityContextHolder 가 채워지고 MFA 게이트도 통과한 뒤
 * 실행된다. AdminSecurityConfig 에서 [MfaPendingFilter] · AdminMdcFilter 와
 * 동일한 패턴(new + addFilterAfter)을 따른다.
 *
 * `finally` 에서 **무조건** [TenantContextHolder.clear] 를 호출한다.
 * set 여부와 무관하게 clear 해야 이전 요청이 스레드에 남긴 stale context 가 누출되지
 * 않는다(HIGH BUG fix).
 */
class TenantContextAdminFilter(private val tenantBoundary: TenantBoundary) : OncePerRequestFilter() {

    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        try {
            // safeScope() 는 미인증/미지원role 일 때만 empty 를 반환(fail-closed).
            // 그 외 예기치 못한 예외는 전파하여 unscoped 전체 조회를 차단한다.
            scope().ifPresent { TenantContextHolder.set(it) }
            chain.doFilter(req, res)
        } finally {
            // 무조건 clear: set 하지 않은 요청이라도, 이전 요청이 같은 스레드에 남긴
            // stale tenant context 가 있으면 여기서 반드시 제거해야 누출되지 않는다.
            TenantContextHolder.clear()
        }
    }

    /**
     * [TenantBoundary.currentTenantScope] 를 fail-closed 로 호출한다.
     *
     * - PLATFORM_OPERATOR → `Optional.empty()` (정상 반환, set 하지 않음 → 전체 조회)
     * - RP_ADMIN         → `Optional.of(tenantId)` (정상 반환, set 함)
     * - 미인증/anonymous → `BusinessException(UNAUTHORIZED)` → empty 반환.
     *   인증/인가는 보안 체인·엔드포인트가 책임진다.
     * - 미지원 role      → `BusinessException(ACCESS_DENIED)` → empty 반환.
     *
     * **fail-closed:** 위 두 [ErrorCode] 외의 예외(NPE·DB오류·기타
     * [BusinessException])는 잡지 않고 전파한다. 인증된 RP_ADMIN 의 scope 해석이
     * 우연히 실패했는데 이를 empty 로 삼키면 unscoped 전체 테넌트 누출이 되므로,
     * 차라리 요청을 실패시키는 것이 안전하다.
     */
    private fun scope(): Optional<UUID> {
        try {
            return tenantBoundary.currentTenantScope()
        } catch (e: BusinessException) {
            val code = e.errorCode
            if (code == ErrorCode.UNAUTHORIZED || code == ErrorCode.ACCESS_DENIED) {
                // unauthenticated / anonymous / unknown-role → no context.
                return Optional.empty()
            }
            throw e // any other business error → fail-closed
        }
    }
}
