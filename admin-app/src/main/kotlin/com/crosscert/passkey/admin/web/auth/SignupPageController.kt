package com.crosscert.passkey.admin.web.auth

import com.crosscert.passkey.admin.config.ProfileController
import com.crosscert.passkey.admin.operator.SignupRequestController
import com.crosscert.passkey.admin.operator.PASSWORD_RULE
import com.crosscert.passkey.admin.operator.PasswordPolicy
import com.crosscert.passkey.admin.operator.SignupRequestDto
import com.crosscert.passkey.admin.web.format.javaTrim
import com.crosscert.passkey.admin.web.support.Htmx
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Validator
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.ModelAndView
import java.util.Locale
import java.util.regex.Pattern

private val log = LoggerFactory.getLogger(SignupPageController::class.java)

private val EMAIL_RE: Pattern = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
private const val PLATFORM_OPERATOR = "PLATFORM_OPERATOR"

/**
 * 관리자 계정 가입 요청 화면(← pages/SignupRequestPage.tsx).
 * [SignupRequestController.request] 를 메서드로 부르므로 `@Valid` 가 동작하지 않는다 —
 * [Validator] 로 [SignupRequestDto.Create] 를 직접 검증하고 오류를 필드 옆에 보인다(422).
 *
 * 완료: 응답의 `activated`(Platform Operator 즉시 가입 설정으로 계정이 바로 만들어짐)이면 "계정이 만들어졌습니다",
 * 아니면 "요청이 접수되었습니다". 이미 가입·대기 중이어도 서버는 같은 202 를 주므로 같은 안내다(계정 열거 방지).
 * 4xx 성격의 예외도 접수 안내, 그 밖의 예외는 본문 없는 500(화면이 "보내지 못했습니다"). 이메일·비밀번호는 로그에 남기지 않는다.
 */
@Controller
class SignupPageController(
    private val signup: SignupRequestController,
    private val profiles: ProfileController,
    private val validator: Validator,
) {

    @GetMapping("/admin/signup")
    fun form(): ModelAndView = form(ModelAndView("admin/auth/signup"), "", "", "", null)

    @PostMapping("/admin/signup")
    fun submit(
        @RequestParam(name = "email", required = false) email: String?,
        @RequestParam(name = "password", required = false) password: String?,
        @RequestParam(name = "password2", required = false) password2: String?,
        @RequestParam(name = "reason", required = false) reason: String?,
        @RequestParam(name = "role", required = false) role: String?,
        req: HttpServletRequest,
        res: HttpServletResponse,
    ): ModelAndView? {
        val view = if (Htmx.isHtmx(req)) "admin/auth/signup :: card" else "admin/auth/signup"
        val selfSignup = selfSignupAvailable()
        // TSX: 역할은 즉시 가입이 켜졌고 Platform Operator 를 골랐을 때만 보낸다.
        val effectiveRole: String? = if (selfSignup && PLATFORM_OPERATOR == role) PLATFORM_OPERATOR else null
        val em = if (email == null) "" else email.javaTrim().lowercase(Locale.ROOT)
        val pw = password ?: ""
        val rs = if (reason == null) "" else reason.javaTrim()

        val body = SignupRequestDto.Create(em, pw, if (rs.isEmpty()) null else rs, effectiveRole)
        val errors = validate(body, em, pw, password2)
        if (errors.isNotEmpty()) {
            val mav = form(ModelAndView(view), em, reason ?: "", effectiveRole, errors)
            mav.status = HttpStatus.UNPROCESSABLE_ENTITY
            return mav
        }

        val activated: Boolean = try {
            val r = signup.request(body)
            val b: Map<String, Boolean>? = r.body
            b != null && true == b["activated"]
        } catch (e: IllegalArgumentException) {
            false // 4xx 성격 — 접수 안내(enumeration 방지)
        } catch (e: ResponseStatusException) {
            if (!e.statusCode.is4xxClientError) {
                res.status = e.statusCode.value()
                return null
            }
            false
        } catch (e: RuntimeException) {
            log.error("admin signup request (screen) failed unexpectedly: {}", e.javaClass.simpleName)
            res.status = HttpStatus.INTERNAL_SERVER_ERROR.value()
            return null
        }
        val mav = ModelAndView(view)
        mav.addObject("state", if (activated) "activated" else "pending")
        return mav
    }

    private fun form(mav: ModelAndView, email: String, reason: String, role: String?, errors: Map<String, String>?): ModelAndView {
        mav.addObject("state", "form")
        mav.addObject("selfSignup", selfSignupAvailable())
        mav.addObject("email", email)
        mav.addObject("reason", reason)
        mav.addObject("role", role ?: "RP_ADMIN")
        mav.addObject("errors", errors)
        return mav
    }

    private fun validate(body: SignupRequestDto.Create, email: String, pw: String, pw2: String?): Map<String, String> {
        val errors = LinkedHashMap<String, String>()
        val violations = validator.validate(body)
        for (v in violations) {
            when (v.propertyPath.toString()) {
                "email" -> errors["email"] = "이메일 형식이 올바르지 않습니다. 예: you@company.com"
                "password" -> errors["password"] = passwordError(pw)
                "reason" -> errors["reason"] = "요청 사유는 500자 이하로 입력하세요."
                else -> { /* role 은 위에서 걸러 보낸다 */ }
            }
        }
        // @Size 는 글자 수만 본다 — 다바이트(한글 25자 = 75바이트)는 BCrypt 상한을 넘으므로 같은 문구로 거른다.
        if (!errors.containsKey("password") && !PasswordPolicy.accepts(pw)) {
            errors["password"] = passwordError(pw)
        }
        // SignupRequestPage.tsx 의 EMAIL_RE: 점이 없는 주소(a@b)는 @Email 은 통과시키지만 화면은 거부한다.
        if (!errors.containsKey("email") && !EMAIL_RE.matcher(email).matches()) {
            errors["email"] = "이메일 형식이 올바르지 않습니다. 예: you@company.com"
        }
        if (!errors.containsKey("password") && pw != pw2) {
            errors["password2"] = "비밀번호가 일치하지 않습니다."
        }
        return errors
    }

    /** 화면 문구: 입력 글자 수 + 규칙. */
    private fun passwordError(pw: String): String = "${pw.length}자 입력됨 · ${PASSWORD_RULE}로 입력하세요"

    private fun selfSignupAvailable(): Boolean {
        return try {
            val p = profiles.profile()
            val d = p.data()
            d != null && d.platformOperatorSelfSignup
        } catch (e: RuntimeException) {
            false // 조회 실패 — 기본(승인 필요) 화면
        }
    }
}
