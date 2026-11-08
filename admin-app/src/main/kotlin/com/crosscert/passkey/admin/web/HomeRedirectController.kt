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
 * 홈과 옛 주소(← App.tsx 의 `<Route path="*">`, me/roles.ts `rpTenantId`).
 * PLATFORM_OPERATOR 는 `/admin/tenants`, RP_ADMIN 은 `/admin/tenants/{tenantIds[0]}`.
 * RP_ADMIN 인데 tenantIds 가 비었으면(데이터 이상) App.tsx 처럼 `/admin/tenants` 로 보낸다 —
 * 그 화면이 RequirePlatform 의 "계정 구성 오류"를 보인다(Tenant 목록 화면 몫).
 * tenantIds 는 MeController 가 UUID 순으로 정렬해 주므로 [0] 이 백엔드 기본 활성 RP 와 같다.
 */
@Controller
class HomeRedirectController(private val me: MeController) {

    /** 미인증이면 Spring 이 auth 에 null 을 넘긴다(원 Java 와 같은 분기). */
    @GetMapping("/admin", "/admin/", "/admin/api-keys", "/admin/audit", "/admin/mds", "/admin/keys")
    fun home(auth: Authentication?, req: HttpServletRequest): String {
        if (auth == null || auth.principal !is AdminUserDetails) {
            return "redirect:/admin/tenants"
        }
        return "redirect:" + homePath(me.me(auth, req).data())
    }

    companion object {
        /** rpTenantId(me) ? /admin/tenants/{id} : /admin/tenants */
        fun homePath(v: MeView): String = homePath(v.role, v.tenantIds)

        /** 화면 모델의 `me`(CurrentAdmin)용. 같은 규칙이다 — RequirePlatform 이 쓴다. */
        fun homePath(v: CurrentAdmin): String = homePath(v.role, v.tenantIds)

        private fun homePath(role: String?, ids: List<UUID>?): String {
            if ("RP_ADMIN" == role && ids != null && ids.isNotEmpty()) {
                return "/admin/tenants/" + ids[0]
            }
            return "/admin/tenants"
        }
    }
}
