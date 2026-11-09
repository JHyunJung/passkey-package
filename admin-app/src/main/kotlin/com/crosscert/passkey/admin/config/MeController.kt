package com.crosscert.passkey.admin.config

import com.crosscert.passkey.admin.auth.AdminUserDetails
import com.crosscert.passkey.admin.auth.MfaPendingFilter
import com.crosscert.passkey.core.api.ApiResponse
import com.crosscert.passkey.core.entity.AdminUser
import com.crosscert.passkey.core.repository.AdminUserRepository
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * SPA bootstrap call. AdminUserDetails 에서 email/role/tenantId 추출.
 *
 * `mfaEnabled` 는 운영자 계정의 MFA 등록 여부(AdminUser flag),
 * `mfaRequired` 는 **이 세션**이 2차 인증 대기 상태인지(세션 속성
 * [MfaPendingFilter.MFA_PENDING_ATTR])를 나타낸다. SPA 는 로그인 직후
 * 이 두 값으로 MFA 챌린지 노출 여부를 분기한다.
 */
@RestController
@RequestMapping("/admin/api")
class MeController(private val users: AdminUserRepository) {

    @GetMapping("/me")
    fun me(auth: Authentication, req: HttpServletRequest): ApiResponse<MeView> {
        // 원 Java 의 (AdminUserDetails) 캐스트 — 다른 principal 은 ClassCastException, null 은 NPE.
        val principal = auth.principal as AdminUserDetails

        val mfaEnabled: Boolean = users.findByEmail(principal.username)
            .map { u: AdminUser -> u.isMfaEnabled }
            .orElse(false)

        val session: HttpSession? = req.getSession(false)
        val mfaRequired = session != null &&
            java.lang.Boolean.TRUE == session.getAttribute(MfaPendingFilter.MFA_PENDING_ATTR)

        var idleMinutes = AdminSecurityConfig.DEFAULT_IDLE_MINUTES
        if (session != null && session.maxInactiveInterval > 0) {
            idleMinutes = session.maxInactiveInterval / 60
        }

        // tenantIds 를 UUID 자연 순서로 정렬해 내려준다. ActiveTenantResolver 의 기본 활성 RP 가
        // new TreeSet<>(allowed).first() = UUID 최소값이므로, 프론트가 tenantIds[0] 로 라우팅해도
        // 백엔드 활성 RP 와 항상 같은 RP 를 가리킨다(로그인 직후 화면 ↔ 스위처 활성 표시 일치).
        val tenantIds: MutableList<UUID> = ArrayList(principal.allowedTenantIds)
        tenantIds.sort() // = Java list.sort(null)(자연 순서)
        return ApiResponse.ok(
            MeView(
                principal.username,
                principal.role,
                tenantIds,
                mfaEnabled,
                mfaRequired,
                idleMinutes,
            ),
        )
    }
}
