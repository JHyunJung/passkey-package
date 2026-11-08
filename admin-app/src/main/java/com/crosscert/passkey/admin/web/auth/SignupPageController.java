package com.crosscert.passkey.admin.web.auth;

import com.crosscert.passkey.admin.config.ProfileController;
import com.crosscert.passkey.admin.operator.SignupRequestController;
import com.crosscert.passkey.admin.operator.SignupRequestDto;
import com.crosscert.passkey.admin.web.support.Htmx;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.ModelAndView;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 관리자 계정 가입 요청 화면(← pages/SignupRequestPage.tsx).
 * {@link SignupRequestController#request} 를 Java 메서드로 부르므로 {@code @Valid} 가 동작하지 않는다 —
 * {@link Validator} 로 {@link SignupRequestDto.Create} 를 직접 검증하고 오류를 필드 옆에 보인다(422).
 *
 * <p>완료: 응답의 {@code activated}(Platform Operator 즉시 가입 설정으로 계정이 바로 만들어짐)이면 "계정이 만들어졌습니다",
 * 아니면 "요청이 접수되었습니다". 이미 가입·대기 중이어도 서버는 같은 202 를 주므로 같은 안내다(계정 열거 방지).
 * 4xx 성격의 예외도 접수 안내, 그 밖의 예외는 본문 없는 500(화면이 "보내지 못했습니다"). 이메일·비밀번호는 로그에 남기지 않는다.
 */
@Slf4j
@Controller
public class SignupPageController {

    private static final Pattern EMAIL_RE = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    static final String PLATFORM_OPERATOR = "PLATFORM_OPERATOR";

    private final SignupRequestController signup;
    private final ProfileController profiles;
    private final Validator validator;

    public SignupPageController(SignupRequestController signup, ProfileController profiles, Validator validator) {
        this.signup = signup;
        this.profiles = profiles;
        this.validator = validator;
    }

    @GetMapping("/admin/signup")
    public ModelAndView form() {
        return form(new ModelAndView("admin/auth/signup"), "", "", "", null);
    }

    @PostMapping("/admin/signup")
    public ModelAndView submit(@RequestParam(name = "email", required = false) String email,
                               @RequestParam(name = "password", required = false) String password,
                               @RequestParam(name = "password2", required = false) String password2,
                               @RequestParam(name = "reason", required = false) String reason,
                               @RequestParam(name = "role", required = false) String role,
                               HttpServletRequest req, HttpServletResponse res) {
        String view = Htmx.isHtmx(req) ? "admin/auth/signup :: card" : "admin/auth/signup";
        boolean selfSignup = selfSignupAvailable();
        // TSX: 역할은 즉시 가입이 켜졌고 Platform Operator 를 골랐을 때만 보낸다.
        String effectiveRole = selfSignup && PLATFORM_OPERATOR.equals(role) ? PLATFORM_OPERATOR : null;
        String em = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        String pw = password == null ? "" : password;
        String rs = reason == null ? "" : reason.trim();

        SignupRequestDto.Create body = new SignupRequestDto.Create(em, pw, rs.isEmpty() ? null : rs, effectiveRole);
        Map<String, String> errors = validate(body, em, pw, password2, reason);
        if (!errors.isEmpty()) {
            ModelAndView mav = form(new ModelAndView(view), em, reason == null ? "" : reason, effectiveRole, errors);
            mav.setStatus(HttpStatus.UNPROCESSABLE_ENTITY);
            return mav;
        }

        boolean activated;
        try {
            ResponseEntity<Map<String, Boolean>> r = signup.request(body);
            activated = r.getBody() != null && Boolean.TRUE.equals(r.getBody().get("activated"));
        } catch (IllegalArgumentException e) {
            activated = false; // 4xx 성격 — 접수 안내(enumeration 방지)
        } catch (ResponseStatusException e) {
            if (!e.getStatusCode().is4xxClientError()) {
                res.setStatus(e.getStatusCode().value());
                return null;
            }
            activated = false;
        } catch (RuntimeException e) {
            log.error("admin signup request (screen) failed unexpectedly: {}", e.getClass().getSimpleName());
            res.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
            return null;
        }
        ModelAndView mav = new ModelAndView(view);
        mav.addObject("state", activated ? "activated" : "pending");
        return mav;
    }

    private ModelAndView form(ModelAndView mav, String email, String reason, String role, Map<String, String> errors) {
        mav.addObject("state", "form");
        mav.addObject("selfSignup", selfSignupAvailable());
        mav.addObject("email", email);
        mav.addObject("reason", reason);
        mav.addObject("role", role == null ? "RP_ADMIN" : role);
        mav.addObject("errors", errors);
        return mav;
    }

    private Map<String, String> validate(SignupRequestDto.Create body, String email, String pw, String pw2, String rawReason) {
        Map<String, String> errors = new LinkedHashMap<>();
        Set<ConstraintViolation<SignupRequestDto.Create>> violations = validator.validate(body);
        for (ConstraintViolation<SignupRequestDto.Create> v : violations) {
            String field = v.getPropertyPath().toString();
            switch (field) {
                case "email" -> errors.put("email", "이메일 형식이 올바르지 않습니다. 예: you@company.com");
                case "password" -> errors.put("password", pw.length() + "자 입력됨 · 12–128자로 입력하세요");
                case "reason" -> errors.put("reason", "요청 사유는 500자 이하로 입력하세요.");
                default -> { /* role 은 위에서 걸러 보낸다 */ }
            }
        }
        // SignupRequestPage.tsx 의 EMAIL_RE: 점이 없는 주소(a@b)는 @Email 은 통과시키지만 화면은 거부한다.
        if (!errors.containsKey("email") && !EMAIL_RE.matcher(email).matches()) {
            errors.put("email", "이메일 형식이 올바르지 않습니다. 예: you@company.com");
        }
        if (!errors.containsKey("password") && !pw.equals(pw2)) {
            errors.put("password2", "비밀번호가 일치하지 않습니다.");
        }
        return errors;
    }

    private boolean selfSignupAvailable() {
        try {
            var p = profiles.profile();
            return p != null && p.data() != null && p.data().getPlatformOperatorSelfSignup();
        } catch (RuntimeException e) {
            return false; // 조회 실패 — 기본(승인 필요) 화면
        }
    }
}
