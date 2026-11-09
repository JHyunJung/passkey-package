package com.crosscert.passkey.admin.web.auth

import com.crosscert.passkey.admin.operator.PasswordResetController
import com.crosscert.passkey.admin.web.format.javaIsBlank
import com.crosscert.passkey.admin.web.format.javaTrim
import com.crosscert.passkey.admin.web.support.Htmx
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.ModelAndView
import java.util.regex.Pattern

private val log = LoggerFactory.getLogger(PasswordPagesController::class.java)

private const val MIN_LENGTH = 12
private val TOKEN_WORD: Pattern = Pattern.compile("token", Pattern.CASE_INSENSITIVE)

/**
 * 비밀번호 찾기·재설정 화면(← pages/ForgotPasswordPage.tsx, pages/ResetPasswordPage.tsx).
 * 로그인 전 화면이라 공통 틀 없이 auth 단독 페이지. REST 빈([PasswordResetController])을 메서드로 부른다.
 *
 * 비밀번호 찾기: 가입 여부와 상관없이 같은 완료 카드(계정 열거 방지). 4xx 성격의 예외도 같은 카드,
 * 5xx 성격(예외)만 본문 없는 500 → 화면(auth.js)이 "보내지 못했습니다"를 보인다. 이메일은 로그에 남기지 않는다.
 *
 * 재설정: 토큰은 폼 hidden 으로만 오가고 로그·예외 메시지에 남기지 않는다(예외는 클래스 이름만 기록).
 * 토큰이 든 페이지는 `no-store`·`no-referrer`.
 */
@Controller
class PasswordPagesController(private val reset: PasswordResetController) {

    @GetMapping("/admin/forgot-password")
    fun forgotForm(): ModelAndView {
        val mav = ModelAndView("admin/auth/forgot-password")
        mav.addObject("sent", false)
        return mav
    }

    @PostMapping("/admin/forgot-password")
    fun forgot(
        @RequestParam(name = "email", required = false) email: String?,
        req: HttpServletRequest,
        res: HttpServletResponse,
    ): ModelAndView? {
        val value = if (email == null) "" else email.javaTrim()
        if (value.isEmpty()) {
            val mav = ModelAndView(view(req, "admin/auth/forgot-password"))
            mav.addObject("sent", false)
            mav.addObject("emailError", "이메일을 입력하세요.")
            mav.status = HttpStatus.UNPROCESSABLE_ENTITY
            return mav
        }
        try {
            reset.request(PasswordResetController.RequestBodyDto(value))
        } catch (e: IllegalArgumentException) {
            // ForgotPasswordPage.tsx: 4xx 는 성공과 같은 안내(enumeration 방지).
        } catch (e: ResponseStatusException) {
            if (!e.statusCode.is4xxClientError) {
                log.error("admin password-reset request (screen) failed: status={}", e.statusCode.value())
                res.status = e.statusCode.value()
                return null
            }
        } catch (e: RuntimeException) {
            log.error("admin password-reset request (screen) failed unexpectedly: {}", e.javaClass.simpleName)
            res.status = HttpStatus.INTERNAL_SERVER_ERROR.value()
            return null
        }
        val mav = ModelAndView(view(req, "admin/auth/forgot-password"))
        mav.addObject("sent", true)
        return mav
    }

    /**
     * 재설정 메일의 링크는 `admin.invite.base-url + /reset-password?token=…` 이다(PasswordResetService — React 시절 SPA 경로, base-url 기본값은 바꾸지 않는다).
     * base-url 이 콘솔 주소면 그 경로는 /admin 밖이라 화면이 없으므로 쿼리(토큰)를 그대로 붙여 `/admin/reset-password` 로 보낸다.
     * 이동할 곳은 고정 경로라 열린 리다이렉트가 아니다. 토큰이 든 주소라 응답은 캐시·리퍼러를 남기지 않는다.
     */
    @GetMapping("/reset-password")
    fun resetLink(req: HttpServletRequest, res: HttpServletResponse) {
        noLeak(res)
        val q: String? = req.queryString
        res.status = HttpStatus.FOUND.value()
        res.setHeader("Location", "/admin/reset-password" + (if (q == null || q.isEmpty()) "" else "?$q"))
    }

    @GetMapping("/admin/reset-password")
    fun resetForm(@RequestParam(name = "token", required = false) token: String?, res: HttpServletResponse): ModelAndView {
        noLeak(res)
        val t = if (token == null) "" else token.javaTrim()
        val mav = ModelAndView("admin/auth/reset-password")
        mav.addObject("state", if (t.isEmpty()) "invalid" else "form")
        mav.addObject("token", t)
        return mav
    }

    @PostMapping("/admin/reset-password")
    fun resetConfirm(
        @RequestParam(name = "token", required = false) token: String?,
        @RequestParam(name = "password", required = false) password: String?,
        @RequestParam(name = "password2", required = false) password2: String?,
        req: HttpServletRequest,
        res: HttpServletResponse,
    ): ModelAndView? {
        noLeak(res)
        val view = view(req, "admin/auth/reset-password")
        val t = if (token == null) "" else token.javaTrim()
        if (t.isEmpty()) {
            return card(view, "invalid", t, null, null, HttpStatus.UNPROCESSABLE_ENTITY)
        }
        val pw = password ?: ""
        // 클라이언트가 막는 규칙(ResetPasswordPage.tsx canSubmit)을 서버에서도 확인한다. 비밀번호는 다시 그리지 않는다.
        if (pw.length < MIN_LENGTH) {
            return card(view, "form", t, java.util.Map.of("password", "12자 이상으로 입력하세요."), null, HttpStatus.UNPROCESSABLE_ENTITY)
        }
        if (pw != password2) {
            return card(view, "form", t, java.util.Map.of("password2", "비밀번호가 일치하지 않습니다."), null, HttpStatus.UNPROCESSABLE_ENTITY)
        }
        try {
            reset.confirm(PasswordResetController.ConfirmBody(t, pw))
        } catch (e: IllegalArgumentException) {
            // GlobalExceptionHandler 가 400 + 메시지로 옮기는 예외. 토큰 거부(invalid token · already used · expired)는 만료 카드.
            val msg: String? = e.message
            if (msg != null && TOKEN_WORD.matcher(msg).find()) {
                return card(view, "expired", t, null, null, HttpStatus.UNPROCESSABLE_ENTITY)
            }
            return card(
                view, "form", t, null, if (msg == null || msg.javaIsBlank()) "비밀번호 변경에 실패했습니다." else msg,
                HttpStatus.UNPROCESSABLE_ENTITY,
            )
        } catch (e: ResponseStatusException) {
            if (e.statusCode.is4xxClientError) {
                val reason: String? = e.reason
                return card(
                    view, "form", t, null, if (reason == null || reason.javaIsBlank()) "비밀번호 변경에 실패했습니다." else reason,
                    HttpStatus.UNPROCESSABLE_ENTITY,
                )
            }
            res.status = e.statusCode.value()
            return null
        } catch (e: RuntimeException) {
            log.error("admin password-reset confirm (screen) failed unexpectedly: {}", e.javaClass.simpleName)
            res.status = HttpStatus.INTERNAL_SERVER_ERROR.value()
            return null
        }
        return card(view, "done", t, null, null, HttpStatus.OK)
    }
}

private fun card(
    view: String,
    state: String,
    token: String,
    errors: Map<String, String>?,
    message: String?,
    status: HttpStatus,
): ModelAndView {
    val mav = ModelAndView(view)
    mav.addObject("state", state)
    // 폼 상태에서만 토큰을 되돌려 그린다(다시 제출하려면 필요).
    mav.addObject("token", if ("form" == state) token else "")
    mav.addObject("errors", errors)
    mav.addObject("message", message)
    mav.status = status
    return mav
}

/** htmx 요청이면 card 조각만, 아니면 전체 페이지. */
private fun view(req: HttpServletRequest, page: String): String =
    if (Htmx.isHtmx(req)) "$page :: card" else page

private fun noLeak(res: HttpServletResponse) {
    res.setHeader("Cache-Control", "no-store")
    res.setHeader("Pragma", "no-cache")
    res.setHeader("Referrer-Policy", "no-referrer")
}
