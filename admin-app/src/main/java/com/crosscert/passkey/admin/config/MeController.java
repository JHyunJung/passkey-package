package com.crosscert.passkey.admin.config;

import com.crosscert.passkey.admin.auth.AdminUserDetails;
import com.crosscert.passkey.admin.auth.MfaPendingFilter;
import com.crosscert.passkey.core.api.ApiResponse;
import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * SPA bootstrap call. AdminUserDetails 에서 email/role/tenantId 추출.
 *
 * <p>{@code mfaEnabled} 는 운영자 계정의 MFA 등록 여부(AdminUser flag),
 * {@code mfaRequired} 는 <b>이 세션</b>이 2차 인증 대기 상태인지(세션 속성
 * {@link MfaPendingFilter#MFA_PENDING_ATTR})를 나타낸다. SPA 는 로그인 직후
 * 이 두 값으로 MFA 챌린지 노출 여부를 분기한다.
 */
@RestController
@RequestMapping("/admin/api")
public class MeController {

    private final AdminUserRepository users;

    public MeController(AdminUserRepository users) {
        this.users = users;
    }

    @GetMapping("/me")
    public ApiResponse<MeView> me(Authentication auth, HttpServletRequest req) {
        AdminUserDetails principal = (AdminUserDetails) auth.getPrincipal();

        boolean mfaEnabled = users.findByEmail(principal.getUsername())
                .map(AdminUser::isMfaEnabled)
                .orElse(false);

        HttpSession session = req.getSession(false);
        boolean mfaRequired = session != null
                && Boolean.TRUE.equals(session.getAttribute(MfaPendingFilter.MFA_PENDING_ATTR));

        int idleMinutes = AdminSecurityConfig.DEFAULT_IDLE_MINUTES;
        if (session != null && session.getMaxInactiveInterval() > 0) {
            idleMinutes = session.getMaxInactiveInterval() / 60;
        }

        // tenantIds 를 UUID 자연 순서로 정렬해 내려준다. ActiveTenantResolver 의 기본 활성 RP 가
        // new TreeSet<>(allowed).first() = UUID 최소값이므로, 프론트가 tenantIds[0] 로 라우팅해도
        // 백엔드 활성 RP 와 항상 같은 RP 를 가리킨다(로그인 직후 화면 ↔ 스위처 활성 표시 일치).
        List<java.util.UUID> tenantIds = new ArrayList<>(principal.getAllowedTenantIds());
        tenantIds.sort(null);
        return ApiResponse.ok(new MeView(
                principal.getUsername(),
                principal.getRole(),
                tenantIds,
                mfaEnabled,
                mfaRequired,
                idleMinutes
        ));
    }
}
