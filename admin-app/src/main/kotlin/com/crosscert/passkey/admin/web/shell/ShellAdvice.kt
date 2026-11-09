package com.crosscert.passkey.admin.web.shell

import com.crosscert.passkey.admin.auth.AdminUserDetails
import com.crosscert.passkey.admin.license.LicenseController
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.util.UrlPathHelper

private val PATHS = UrlPathHelper()

/**
 * 공통 틀(사이드바·상단 바)이 읽는 모델 값: `navActive`(현재 메뉴), `deploymentMode`, `helpLinks`.
 *
 * `me` 는 `ViewModelAdvice` 가 넣는다. 라이선스 컨트롤러는 [ObjectProvider] 로 받아,
 * 그 빈이 없는 슬라이스 테스트에서도 컨텍스트가 뜬다.
 */
@ControllerAdvice(basePackages = ["com.crosscert.passkey.admin.web"])
class ShellAdvice(
    private val license: ObjectProvider<LicenseController>,
    @Value("\${passkey.admin.help.api-docs:}") apiDocs: String?,
    @Value("\${passkey.admin.help.rp-guide:}") rpGuide: String?,
    @Value("\${passkey.admin.help.support:}") support: String?,
) {

    /** config/links.ts 의 HELP_LINKS. 값이 비면 그 항목은 숨긴다. */
    data class HelpLinks(val apiDocs: String?, val rpGuide: String?, val support: String?)

    private val helpLinks = HelpLinks(apiDocs, rpGuide, support)

    @ModelAttribute
    fun populate(model: Model, req: HttpServletRequest) {
        model.addAttribute("helpLinks", helpLinks)
        val path: String? = PATHS.getPathWithinApplication(req)
        // 조각(/admin/_/**)은 레이아웃을 안 쓴다 — 메뉴 강조·라이선스 조회를 하지 않는다.
        if (path != null && path.startsWith("/admin/_/")) return
        model.addAttribute("navActive", navActive(path))
        model.addAttribute("deploymentMode", deploymentMode())
    }

    /** 라이선스 메뉴는 PLATFORM 에게만 보이므로 그때만 조회한다. 조회 실패는 saas(TSX 의 catch). */
    private fun deploymentMode(): String? {
        val auth = SecurityContextHolder.getContext().authentication
        val user = auth?.principal as? AdminUserDetails
        if (user == null || "PLATFORM_OPERATOR" != user.role) {
            return null
        }
        val c = license.getIfAvailable() ?: return "saas"
        return try {
            c.get().data().deploymentMode
        } catch (e: RuntimeException) {
            "saas"
        }
    }

    companion object {
        /**
         * 경로 → 사이드바 항목. 테넌트 상세는 Tenants. 그 밖(조각·오류)은 null.
         * internal: 원 Java 는 package-private static — 같은 모듈 테스트(ShellControllerTest)가 부른다.
         */
        internal fun navActive(path: String?): String? {
            if (path == null) return null
            for (id in arrayOf("tenants", "insights", "activity", "audit-chain", "settings", "license")) {
                val base = "/admin/$id"
                if (path == base || path.startsWith("$base/")) return id
            }
            return null
        }
    }
}
