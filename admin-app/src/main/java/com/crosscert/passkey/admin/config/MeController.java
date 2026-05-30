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

        return ApiResponse.ok(new MeView(
                principal.getUsername(),
                principal.getRole(),
                principal.getTenantId(),
                mfaEnabled,
                mfaRequired
        ));
    }
}
