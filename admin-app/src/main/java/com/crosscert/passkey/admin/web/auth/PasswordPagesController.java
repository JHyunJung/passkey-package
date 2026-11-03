package com.crosscert.passkey.admin.web.auth;

import com.crosscert.passkey.admin.operator.PasswordResetController;
import com.crosscert.passkey.admin.web.support.Htmx;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.ModelAndView;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 비밀번호 찾기·재설정 화면(← pages/ForgotPasswordPage.tsx, pages/ResetPasswordPage.tsx).
 * 로그인 전 화면이라 공통 틀 없이 auth 단독 페이지. REST 빈({@link PasswordResetController})을 Java 메서드로 부른다.
 *
 * <p>비밀번호 찾기: 가입 여부와 상관없이 같은 완료 카드(계정 열거 방지). 4xx 성격의 예외도 같은 카드,
 * 5xx 성격(예외)만 본문 없는 500 → 화면(auth.js)이 "보내지 못했습니다"를 보인다. 이메일은 로그에 남기지 않는다.
 *
 * <p>재설정: 토큰은 폼 hidden 으로만 오가고 로그·예외 메시지에 남기지 않는다(예외는 클래스 이름만 기록).
 * 토큰이 든 페이지는 {@code no-store}·{@code no-referrer}.
 */
@Slf4j
@Controller
public class PasswordPagesController {

    static final int MIN_LENGTH = 12;
    private static final Pattern TOKEN_WORD = Pattern.compile("token", Pattern.CASE_INSENSITIVE);

    private final PasswordResetController reset;

    public PasswordPagesController(PasswordResetController reset) {
        this.reset = reset;
    }

    @GetMapping("/admin/forgot-password")
    public ModelAndView forgotForm() {
        ModelAndView mav = new ModelAndView("admin/auth/forgot-password");
        mav.addObject("sent", false);
        return mav;
    }

    @PostMapping("/admin/forgot-password")
    public ModelAndView forgot(@RequestParam(name = "email", required = false) String email,
                               HttpServletRequest req, HttpServletResponse res) {
        String value = email == null ? "" : email.trim();
        if (value.isEmpty()) {
            ModelAndView mav = new ModelAndView(view(req, "admin/auth/forgot-password"));
            mav.addObject("sent", false);
            mav.addObject("emailError", "이메일을 입력하세요.");
            mav.setStatus(HttpStatus.UNPROCESSABLE_ENTITY);
            return mav;
        }
        try {
            reset.request(new PasswordResetController.RequestBodyDto(value));
        } catch (IllegalArgumentException | ResponseStatusException e) {
            // ForgotPasswordPage.tsx: 4xx 는 성공과 같은 안내(enumeration 방지).
        } catch (RuntimeException e) {
            log.error("admin password-reset request (screen) failed unexpectedly: {}", e.getClass().getSimpleName());
            res.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
            return null;
        }
        ModelAndView mav = new ModelAndView(view(req, "admin/auth/forgot-password"));
        mav.addObject("sent", true);
        return mav;
    }

    @GetMapping("/admin/reset-password")
    public ModelAndView resetForm(@RequestParam(name = "token", required = false) String token, HttpServletResponse res) {
        noLeak(res);
        String t = token == null ? "" : token.trim();
        ModelAndView mav = new ModelAndView("admin/auth/reset-password");
        mav.addObject("state", t.isEmpty() ? "invalid" : "form");
        mav.addObject("token", t);
        return mav;
    }

    @PostMapping("/admin/reset-password")
    public ModelAndView resetConfirm(@RequestParam(name = "token", required = false) String token,
                                     @RequestParam(name = "password", required = false) String password,
                                     @RequestParam(name = "password2", required = false) String password2,
                                     HttpServletRequest req, HttpServletResponse res) {
        noLeak(res);
        String view = view(req, "admin/auth/reset-password");
        String t = token == null ? "" : token.trim();
        if (t.isEmpty()) {
            return card(view, "invalid", t, null, null, HttpStatus.UNPROCESSABLE_ENTITY);
        }
        String pw = password == null ? "" : password;
        // 클라이언트가 막는 규칙(ResetPasswordPage.tsx canSubmit)을 서버에서도 확인한다. 비밀번호는 다시 그리지 않는다.
        if (pw.length() < MIN_LENGTH) {
            return card(view, "form", t, Map.of("password", "12자 이상으로 입력하세요."), null, HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (!pw.equals(password2)) {
            return card(view, "form", t, Map.of("password2", "비밀번호가 일치하지 않습니다."), null, HttpStatus.UNPROCESSABLE_ENTITY);
        }
        try {
            reset.confirm(new PasswordResetController.ConfirmBody(t, pw));
        } catch (IllegalArgumentException e) {
            // GlobalExceptionHandler 가 400 + 메시지로 옮기는 예외. 토큰 거부(invalid token · already used · expired)는 만료 카드.
            String msg = e.getMessage();
            if (msg != null && TOKEN_WORD.matcher(msg).find()) {
                return card(view, "expired", t, null, null, HttpStatus.UNPROCESSABLE_ENTITY);
            }
            return card(view, "form", t, null, msg == null || msg.isBlank() ? "비밀번호 변경에 실패했습니다." : msg,
                    HttpStatus.UNPROCESSABLE_ENTITY);
        } catch (ResponseStatusException e) {
            if (e.getStatusCode().is4xxClientError()) {
                String reason = e.getReason();
                return card(view, "form", t, null, reason == null || reason.isBlank() ? "비밀번호 변경에 실패했습니다." : reason,
                        HttpStatus.UNPROCESSABLE_ENTITY);
            }
            res.setStatus(e.getStatusCode().value());
            return null;
        } catch (RuntimeException e) {
            log.error("admin password-reset confirm (screen) failed unexpectedly: {}", e.getClass().getSimpleName());
            res.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
            return null;
        }
        return card(view, "done", t, null, null, HttpStatus.OK);
    }

    private static ModelAndView card(String view, String state, String token, Map<String, String> errors,
                                     String message, HttpStatus status) {
        ModelAndView mav = new ModelAndView(view);
        mav.addObject("state", state);
        // 폼 상태에서만 토큰을 되돌려 그린다(다시 제출하려면 필요).
        mav.addObject("token", "form".equals(state) ? token : "");
        mav.addObject("errors", errors);
        mav.addObject("message", message);
        mav.setStatus(status);
        return mav;
    }

    /** htmx 요청이면 card 조각만, 아니면 전체 페이지. */
    private static String view(HttpServletRequest req, String page) {
        return Htmx.isHtmx(req) ? page + " :: card" : page;
    }

    private static void noLeak(HttpServletResponse res) {
        res.setHeader("Cache-Control", "no-store");
        res.setHeader("Pragma", "no-cache");
        res.setHeader("Referrer-Policy", "no-referrer");
    }
}
