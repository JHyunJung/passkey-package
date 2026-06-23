package com.crosscert.passkey.admin.auth;

import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import com.crosscert.passkey.core.tenant.TenantContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * 인증된 admin 요청에서 RP_ADMIN 이면 자기 tenantId 를 {@link TenantContextHolder} 에
 * set 하여 Hibernate @Filter(TenantFilterAspect)가 자기 tenant 만 보게 한다(defense in depth).
 * PLATFORM_OPERATOR 는 scope 가 empty 이므로 set 하지 않음 → 전체 조회(의도).
 *
 * <p>Security filter chain 내부의 {@link MfaPendingFilter} 이후에
 * {@code http.addFilterAfter(..., MfaPendingFilter.class)} 로 등록되어야 한다.
 * MfaPendingFilter 가 {@link org.springframework.security.web.access.intercept.AuthorizationFilter}
 * 뒤에 있으므로 그 뒤로 두면 SecurityContextHolder 가 채워지고 MFA 게이트도 통과한 뒤
 * 실행된다. AdminSecurityConfig 에서 {@link MfaPendingFilter} · AdminMdcFilter 와
 * 동일한 패턴(new + addFilterAfter)을 따른다.
 *
 * <p>{@code finally} 에서 <b>무조건</b> {@link TenantContextHolder#clear()} 를 호출한다.
 * set 여부와 무관하게 clear 해야 이전 요청이 스레드에 남긴 stale context 가 누출되지
 * 않는다(HIGH BUG fix).
 */
public class TenantContextAdminFilter extends OncePerRequestFilter {

    private final TenantBoundary tenantBoundary;

    public TenantContextAdminFilter(TenantBoundary tenantBoundary) {
        this.tenantBoundary = tenantBoundary;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        try {
            // safeScope() 는 미인증/미지원role 일 때만 empty 를 반환(fail-closed).
            // 그 외 예기치 못한 예외는 전파하여 unscoped 전체 조회를 차단한다.
            scope().ifPresent(TenantContextHolder::set);
            chain.doFilter(req, res);
        } finally {
            // 무조건 clear: set 하지 않은 요청이라도, 이전 요청이 같은 스레드에 남긴
            // stale tenant context 가 있으면 여기서 반드시 제거해야 누출되지 않는다.
            TenantContextHolder.clear();
        }
    }

    /**
     * {@link TenantBoundary#currentTenantScope()} 를 fail-closed 로 호출한다.
     *
     * <ul>
     *   <li>PLATFORM_OPERATOR → {@code Optional.empty()} (정상 반환, set 하지 않음 → 전체 조회)</li>
     *   <li>RP_ADMIN         → {@code Optional.of(tenantId)} (정상 반환, set 함)</li>
     *   <li>미인증/anonymous → {@code BusinessException(UNAUTHORIZED)} → empty 반환.
     *       인증/인가는 보안 체인·엔드포인트가 책임진다.</li>
     *   <li>미지원 role      → {@code BusinessException(ACCESS_DENIED)} → empty 반환.</li>
     * </ul>
     *
     * <p><b>fail-closed:</b> 위 두 {@link ErrorCode} 외의 예외(NPE·DB오류·기타
     * {@link BusinessException})는 잡지 않고 전파한다. 인증된 RP_ADMIN 의 scope 해석이
     * 우연히 실패했는데 이를 empty 로 삼키면 unscoped 전체 테넌트 누출이 되므로,
     * 차라리 요청을 실패시키는 것이 안전하다.
     */
    private Optional<UUID> scope() {
        try {
            return tenantBoundary.currentTenantScope();
        } catch (BusinessException e) {
            ErrorCode code = e.getErrorCode();
            if (code == ErrorCode.UNAUTHORIZED || code == ErrorCode.ACCESS_DENIED) {
                // unauthenticated / anonymous / unknown-role → no context.
                return Optional.empty();
            }
            throw e; // any other business error → fail-closed
        }
    }
}
