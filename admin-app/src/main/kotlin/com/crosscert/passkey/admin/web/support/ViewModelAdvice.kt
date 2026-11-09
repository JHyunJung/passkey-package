package com.crosscert.passkey.admin.web.support

import com.crosscert.passkey.admin.auth.AdminUserDetails
import com.crosscert.passkey.admin.config.MeController
import com.crosscert.passkey.admin.config.ProfileController
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.ObjectProvider
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute

/**
 * 모든 화면 모델에 `me`(미인증이면 null)와 `profile` 을 넣는다.
 * 기존 REST 컨트롤러 빈을 메서드로 호출한다(HTTP 아님).
 *
 * 협력 빈은 [ObjectProvider] 로 받는다. `@ControllerAdvice` 는 모든 `@WebMvcTest`
 * 슬라이스에 올라가는데, 기존 REST 슬라이스(예: `SystemInfoControllerSecurityTest`)에는
 * MeController·ProfileController 가 없다. 그 경우에도 컨텍스트가 뜨도록 없으면 넣지 않는다.
 * 실제 애플리케이션에서는 두 빈이 항상 있다.
 */
@ControllerAdvice(basePackages = ["com.crosscert.passkey.admin.web"])
class ViewModelAdvice(
    private val meController: ObjectProvider<MeController>,
    private val profileController: ObjectProvider<ProfileController>,
) {

    @ModelAttribute
    fun populate(model: Model, req: HttpServletRequest) {
        model.addAttribute("me", currentAdmin(req))
        val profiles = profileController.getIfAvailable()
        model.addAttribute("profile", if (profiles == null) null else profiles.profile().data())
    }

    private fun currentAdmin(req: HttpServletRequest): CurrentAdmin? {
        val auth = SecurityContextHolder.getContext().authentication
        // MeController 는 principal 을 AdminUserDetails 로 캐스팅한다. 익명·미인증이면 호출하지 않는다.
        if (auth == null || !auth.isAuthenticated || auth.principal !is AdminUserDetails) {
            return null
        }
        val me = meController.getIfAvailable()
        return if (me == null) null else CurrentAdmin.from(me.me(auth, req).data())
    }
}
