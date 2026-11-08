package com.crosscert.passkey.admin.web;

import com.crosscert.passkey.admin.auth.AdminUserDetails;
import com.crosscert.passkey.admin.config.MeController;
import com.crosscert.passkey.admin.config.MeView;
import com.crosscert.passkey.admin.web.support.CurrentAdmin;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;
import java.util.UUID;

/**
 * 홈과 옛 주소(← App.tsx 의 {@code <Route path="*">}, me/roles.ts {@code rpTenantId}).
 * PLATFORM_OPERATOR 는 {@code /admin/tenants}, RP_ADMIN 은 {@code /admin/tenants/{tenantIds[0]}}.
 * RP_ADMIN 인데 tenantIds 가 비었으면(데이터 이상) App.tsx 처럼 {@code /admin/tenants} 로 보낸다 —
 * 그 화면이 RequirePlatform 의 "계정 구성 오류"를 보인다(Tenant 목록 화면 몫).
 * tenantIds 는 MeController 가 UUID 순으로 정렬해 주므로 [0] 이 백엔드 기본 활성 RP 와 같다.
 */
@Controller
public class HomeRedirectController {

    private final MeController me;

    public HomeRedirectController(MeController me) {
        this.me = me;
    }

    @GetMapping({"/admin", "/admin/", "/admin/api-keys", "/admin/audit", "/admin/mds", "/admin/keys"})
    public String home(Authentication auth, HttpServletRequest req) {
        if (auth == null || !(auth.getPrincipal() instanceof AdminUserDetails)) {
            return "redirect:/admin/tenants";
        }
        return "redirect:" + homePath(me.me(auth, req).data());
    }

    /** rpTenantId(me) ? /admin/tenants/{id} : /admin/tenants */
    public static String homePath(MeView v) {
        return homePath(v.getRole(), v.getTenantIds());
    }

    /** 화면 모델의 {@code me}(CurrentAdmin)용. 같은 규칙이다 — RequirePlatform 이 쓴다. */
    public static String homePath(CurrentAdmin v) {
        return homePath(v.role(), v.tenantIds());
    }

    private static String homePath(String role, List<UUID> ids) {
        if ("RP_ADMIN".equals(role) && ids != null && !ids.isEmpty()) {
            return "/admin/tenants/" + ids.get(0);
        }
        return "/admin/tenants";
    }
}
