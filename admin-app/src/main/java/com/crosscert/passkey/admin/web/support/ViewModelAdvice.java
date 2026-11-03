package com.crosscert.passkey.admin.web.support;

import com.crosscert.passkey.admin.auth.AdminUserDetails;
import com.crosscert.passkey.admin.config.MeController;
import com.crosscert.passkey.admin.config.ProfileController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * 모든 화면 모델에 {@code me}(미인증이면 null)와 {@code profile} 을 넣는다.
 * 기존 REST 컨트롤러 빈을 Java 메서드로 호출한다(HTTP 아님).
 *
 * <p>협력 빈은 {@link ObjectProvider} 로 받는다. {@code @ControllerAdvice} 는 모든 {@code @WebMvcTest}
 * 슬라이스에 올라가는데, 기존 REST 슬라이스(예: {@code SystemInfoControllerSecurityTest})에는
 * MeController·ProfileController 가 없다. 그 경우에도 컨텍스트가 뜨도록 없으면 넣지 않는다.
 * 실제 애플리케이션에서는 두 빈이 항상 있다.
 */
@ControllerAdvice(basePackages = "com.crosscert.passkey.admin.web")
public class ViewModelAdvice {

    private final ObjectProvider<MeController> meController;
    private final ObjectProvider<ProfileController> profileController;

    public ViewModelAdvice(ObjectProvider<MeController> meController,
                           ObjectProvider<ProfileController> profileController) {
        this.meController = meController;
        this.profileController = profileController;
    }

    @ModelAttribute
    public void populate(Model model, HttpServletRequest req) {
        model.addAttribute("me", currentAdmin(req));
        ProfileController profiles = profileController.getIfAvailable();
        model.addAttribute("profile", profiles == null ? null : profiles.profile().data());
    }

    private CurrentAdmin currentAdmin(HttpServletRequest req) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        // MeController 는 principal 을 AdminUserDetails 로 캐스팅한다. 익명·미인증이면 호출하지 않는다.
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof AdminUserDetails)) {
            return null;
        }
        MeController me = meController.getIfAvailable();
        return me == null ? null : CurrentAdmin.from(me.me(auth, req).data());
    }
}
