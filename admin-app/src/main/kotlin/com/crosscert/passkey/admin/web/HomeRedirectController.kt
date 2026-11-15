package com.crosscert.passkey.admin.web

import com.crosscert.passkey.admin.auth.AdminUserDetails
import com.crosscert.passkey.admin.config.MeController
import com.crosscert.passkey.admin.config.MeView
import com.crosscert.passkey.admin.web.support.CurrentAdmin
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import java.util.UUID

/**
 * 홈과 옛 주소(`/admin/api-keys`·`/admin/audit` 등) 리다이렉트.
 * PLATFORM_OPERATOR 는 `/admin/tenants`, RP_ADMIN 은 `/admin/tenants/{tenantIds[0]}`.
 * RP_ADMIN 인데 tenantIds 가 비었으면(데이터 이상) `/admin/tenants` 로 보낸다 —
 * 그 화면(TenantsPageController)이 "계정 구성 오류"를 보인다.
 * tenantIds 는 MeController 가 UUID 순으로 정렬해 주므로 [0] 이 백엔드 기본 활성 RP 와 같다.
 */
@Controller
class HomeRedirectController(private val me: MeController) {

    /** 미인증이면 Spring 이 auth 에 null 을 넘긴다. */
    @GetMapping("/admin", "/admin/", "/admin/api-keys", "/admin/audit", "/admin/mds", "/admin/keys")
    fun home(auth: Authentication?, req: HttpServletRequest): String {
        if (auth == null || auth.principal !is AdminUserDetails) {
            return "redirect:/admin/tenants"
        }
        return "redirect:" + homePath(me.me(auth, req).data())
    }

    companion object {
        /** [MeView] 용(위 `home`). */
        fun homePath(v: MeView): String = homePath(v.role, v.tenantIds)

        /** 화면 모델의 `me`(CurrentAdmin)용. 같은 규칙이다 — PLATFORM 전용 화면이 RP_ADMIN 을 돌려보낼 때 쓴다. */
        fun homePath(v: CurrentAdmin): String = homePath(v.role, v.tenantIds)

        private fun homePath(role: String?, ids: List<UUID>?): String {
            if ("RP_ADMIN" == role && ids != null && ids.isNotEmpty()) {
                return "/admin/tenants/" + ids[0]
            }
            return "/admin/tenants"
        }
    }
}
