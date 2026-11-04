package com.crosscert.passkey.admin.web.shell;

import com.crosscert.passkey.admin.auth.AdminUserDetails;
import com.crosscert.passkey.admin.license.LicenseController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.util.UrlPathHelper;

/**
 * 공통 틀(사이드바·상단 바)이 읽는 모델 값: {@code navActive}(현재 메뉴), {@code deploymentMode}, {@code helpLinks}.
 *
 * <p>{@code me} 는 {@code ViewModelAdvice} 가 넣는다. 라이선스 컨트롤러는 {@link ObjectProvider} 로 받아,
 * 그 빈이 없는 슬라이스 테스트에서도 컨텍스트가 뜬다.
 */
@ControllerAdvice(basePackages = "com.crosscert.passkey.admin.web")
public class ShellAdvice {

    /** config/links.ts 의 HELP_LINKS. 값이 비면 그 항목은 숨긴다. */
    public record HelpLinks(String apiDocs, String rpGuide, String support) {}

    private static final UrlPathHelper PATHS = new UrlPathHelper();

    private final ObjectProvider<LicenseController> license;
    private final HelpLinks helpLinks;

    public ShellAdvice(ObjectProvider<LicenseController> license,
                       @Value("${passkey.admin.help.api-docs:}") String apiDocs,
                       @Value("${passkey.admin.help.rp-guide:}") String rpGuide,
                       @Value("${passkey.admin.help.support:}") String support) {
        this.license = license;
        this.helpLinks = new HelpLinks(apiDocs, rpGuide, support);
    }

    @ModelAttribute
    public void populate(Model model, HttpServletRequest req) {
        model.addAttribute("helpLinks", helpLinks);
        String path = PATHS.getPathWithinApplication(req);
        // 조각(/admin/_/**)은 레이아웃을 안 쓴다 — 메뉴 강조·라이선스 조회를 하지 않는다.
        if (path != null && path.startsWith("/admin/_/")) return;
        model.addAttribute("navActive", navActive(path));
        model.addAttribute("deploymentMode", deploymentMode());
    }

    /** 경로 → 사이드바 항목. 테넌트 상세는 Tenants. 그 밖(조각·오류)은 null. */
    static String navActive(String path) {
        if (path == null) return null;
        for (String id : new String[] {"tenants", "activity", "audit-chain", "settings", "license"}) {
            String base = "/admin/" + id;
            if (path.equals(base) || path.startsWith(base + "/")) return id;
        }
        return null;
    }

    /** 라이선스 메뉴는 PLATFORM 에게만 보이므로 그때만 조회한다. 조회 실패는 saas(TSX 의 catch). */
    private String deploymentMode() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AdminUserDetails user)
                || !"PLATFORM_OPERATOR".equals(user.getRole())) {
            return null;
        }
        LicenseController c = license.getIfAvailable();
        if (c == null) return "saas";
        try {
            return c.get().data().deploymentMode();
        } catch (RuntimeException e) {
            return "saas";
        }
    }
}
