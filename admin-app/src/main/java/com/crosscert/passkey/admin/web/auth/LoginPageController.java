package com.crosscert.passkey.admin.web.auth;

import com.crosscert.passkey.admin.auth.MfaController;
import com.crosscert.passkey.admin.auth.MfaPendingFilter;
import com.crosscert.passkey.admin.config.ProfileController;
import com.crosscert.passkey.admin.web.support.Htmx;
import com.crosscert.passkey.admin.web.support.Toasts;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

import java.io.IOException;
import java.util.Map;

/**
 * 로그인 화면과 MFA 단계(← App.tsx 의 로그인 전후 분기, pages/LoginPage.tsx, pages/MfaChallenge.tsx).
 *
 * <ul>
 *   <li>{@code GET /admin/login}: 미인증 → 로그인 화면, MFA 대기 세션 → MFA 단계, 인증 완료 → {@code /admin}.</li>
 *   <li>로그인 제출({@code POST /admin/login})은 보안 체인의 폼 로그인이 받는다(이 컨트롤러가 아니다).</li>
 *   <li>{@code POST /admin/login/mfa}: {@link MfaController#verify} 를 Java 메서드로 부르고 결과를 화면 응답으로 옮긴다.</li>
 * </ul>
 * 인증 코드는 로그에도 응답에도 남기지 않는다.
 */
@Slf4j
@Controller
public class LoginPageController {

    /**
     * LoginPage.tsx DEMO_ACCOUNTS / selectDemo 의 데모 계정. TSX 와 같이 dev·local 프로필(또는 ProfileView.local)에서만
     * DEV 카드와 이 값이 페이지에 들어가고, 입력란은 카드를 눌렀을 때 채운다(로드 시 미리 채우지 않음).
     */
    static final String DEMO_EMAIL = "alice@crosscert.com";
    static final String DEMO_PASSWORD = "alice-temp-pw";

    static final String MODE_RECOVERY = "recovery";
    static final String MODE_TOTP = "totp";

    private final ProfileController profiles;
    private final MfaController mfa;

    public LoginPageController(ProfileController profiles, MfaController mfa) {
        this.profiles = profiles;
        this.mfa = mfa;
    }

    @GetMapping("/admin/login")
    public ModelAndView login(Authentication auth, HttpServletRequest req) {
        if (isAuthenticated(auth)) {
            return isMfaPending(req) ? new ModelAndView("admin/auth/mfa") : new ModelAndView("redirect:/admin");
        }
        ModelAndView mav = new ModelAndView("admin/auth/login");
        // 실패 핸들러가 /admin/login?error 로 보낸다(값 없는 파라미터).
        mav.addObject("loginError", hasFlag(req, "error"));
        boolean demo = showDemoAccount(profiles.profile().data());
        mav.addObject("demoAccount", demo);
        if (demo) {
            mav.addObject("demoEmail", DEMO_EMAIL);
            mav.addObject("demoPassword", DEMO_PASSWORD);
        }
        return mav;
    }

    /**
     * MFA 단계 제출. {@code MfaController.verify} 결과 매핑:
     * <ul>
     *   <li>세션이 무효화됨(실패로 잠금이 걸림) → {@code /admin/login}</li>
     *   <li>200 → {@code /admin}. 복구 코드를 썼으면 남은 개수 경고 토스트</li>
     *   <li>그 밖의 4xx(401 invalid_code — 이미 잠긴 계정 포함) → {@code auth/mfa :: error} + 422</li>
     *   <li>5xx 또는 예외 → 본문·토스트 없는 상태 코드(화면이 "확인하지 못했습니다"로 보인다)</li>
     * </ul>
     * MFA 대기 세션이 아니면(이미 확인됨) verify 를 부르지 않고 {@code /admin} 으로 보낸다.
     */
    @PostMapping("/admin/login/mfa")
    public ModelAndView verifyMfa(@RequestParam(name = "code", required = false) String code,
                                  @RequestParam(name = "mode", required = false) String mode,
                                  Authentication auth, HttpServletRequest req, HttpServletResponse res) throws IOException {
        if (!isMfaPending(req)) {
            Htmx.redirect(req, res, "/admin");
            return null;
        }
        ResponseEntity<?> result;
        try {
            // MfaChallenge.tsx: mfaApi.verify(value.trim())
            result = mfa.verify(new MfaController.VerifyRequest(code == null ? null : code.trim()), auth, req);
        } catch (RuntimeException e) {
            log.error("admin mfa verify (screen) failed unexpectedly: {}", e.getClass().getSimpleName(), e);
            res.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
            return null;
        }
        HttpSession session = req.getSession(false);
        if (session == null) {
            // verify 가 잠금을 걸며 대기 세션을 끝냈다 — 로그인부터 다시.
            Htmx.redirect(req, res, "/admin/login");
            return null;
        }
        int status = result.getStatusCode().value();
        if (status >= 200 && status < 300) {
            if (result.getBody() instanceof Map<?, ?> body
                    && Boolean.TRUE.equals(body.get("usedRecoveryCode"))
                    && body.get("remaining") instanceof Number remaining) {
                Toasts.warnTitled(res, "복구 코드를 사용했습니다.", "남은 복구 코드: " + remaining.longValue() + "개");
            }
            Htmx.redirect(req, res, "/admin");
            return null;
        }
        if (status >= 500) {
            res.setStatus(status);
            return null;
        }
        ModelAndView mav = new ModelAndView("admin/auth/mfa :: error");
        mav.addObject("mode", MODE_RECOVERY.equals(mode) ? MODE_RECOVERY : MODE_TOTP);
        mav.setStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        return mav;
    }

    /** LoginPage.tsx: {@code p.active?.includes('dev') || p.active?.includes('local') || p.local}. */
    static boolean showDemoAccount(ProfileController.ProfileView p) {
        if (p == null) return false;
        java.util.List<String> active = p.getActive();
        return p.getLocal() || (active != null && (active.contains("dev") || active.contains("local")));
    }

    /** {@code ?error} 처럼 값 없는 쿼리 파라미터도 있는 것으로 본다(컨테이너에 따라 getParameter 가 null 일 수 있다). */
    static boolean hasFlag(HttpServletRequest req, String name) {
        if (req.getParameter(name) != null) return true;
        String q = req.getQueryString();
        if (q == null) return false;
        for (String part : q.split("&")) {
            int eq = part.indexOf('=');
            if ((eq < 0 ? part : part.substring(0, eq)).equals(name)) return true;
        }
        return false;
    }

    private static boolean isAuthenticated(Authentication auth) {
        return auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
    }

    private static boolean isMfaPending(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        return session != null && Boolean.TRUE.equals(session.getAttribute(MfaPendingFilter.MFA_PENDING_ATTR));
    }
}
