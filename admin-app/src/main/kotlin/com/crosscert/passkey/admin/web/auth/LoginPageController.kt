package com.crosscert.passkey.admin.web.auth

import com.crosscert.passkey.admin.auth.MfaController
import com.crosscert.passkey.admin.auth.MfaPendingFilter
import com.crosscert.passkey.admin.config.ProfileController
import com.crosscert.passkey.admin.web.format.javaTrim
import com.crosscert.passkey.admin.web.support.Htmx
import com.crosscert.passkey.admin.web.support.Toasts
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpSession
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.ModelAndView
import java.io.IOException
import java.util.regex.Pattern

private val log = LoggerFactory.getLogger(LoginPageController::class.java)

/**
 * LoginPage.tsx DEMO_ACCOUNTS / selectDemo 의 데모 계정. TSX 와 같이 dev·local 프로필(또는 ProfileView.local)에서만
 * DEV 카드와 이 값이 페이지에 들어가고, 입력란은 카드를 눌렀을 때 채운다(로드 시 미리 채우지 않음).
 */
private const val DEMO_EMAIL = "alice@crosscert.com"
private const val DEMO_PASSWORD = "alice-temp-pw"

private const val MODE_RECOVERY = "recovery"
private const val MODE_TOTP = "totp"

/** Java `String.split("&")` 와 같은 분할(끝의 빈 토큰 제거). */
private val AMP = Pattern.compile("&")

/**
 * 로그인 화면과 MFA 단계(← App.tsx 의 로그인 전후 분기, pages/LoginPage.tsx, pages/MfaChallenge.tsx).
 *
 * - `GET /admin/login`: 미인증 → 로그인 화면, MFA 대기 세션 → MFA 단계, 인증 완료 → `/admin`.
 * - 로그인 제출(`POST /admin/login`)은 보안 체인의 폼 로그인이 받는다(이 컨트롤러가 아니다).
 * - `POST /admin/login/mfa`: [MfaController.verify] 를 메서드로 부르고 결과를 화면 응답으로 옮긴다.
 *
 * 인증 코드는 로그에도 응답에도 남기지 않는다.
 */
@Controller
class LoginPageController(
    private val profiles: ProfileController,
    private val mfa: MfaController,
) {

    /** 미인증이면 Spring 이 auth 에 null 을 넘긴다. */
    @GetMapping("/admin/login")
    fun login(auth: Authentication?, req: HttpServletRequest): ModelAndView {
        if (isAuthenticated(auth)) {
            return if (isMfaPending(req)) ModelAndView("admin/auth/mfa") else ModelAndView("redirect:/admin")
        }
        val mav = ModelAndView("admin/auth/login")
        // 실패 핸들러가 /admin/login?error 로 보낸다(값 없는 파라미터).
        mav.addObject("loginError", hasFlag(req, "error"))
        val demo = showDemoAccount(profiles.profile().data())
        mav.addObject("demoAccount", demo)
        if (demo) {
            mav.addObject("demoEmail", DEMO_EMAIL)
            mav.addObject("demoPassword", DEMO_PASSWORD)
        }
        return mav
    }

    /**
     * MFA 단계 제출. `MfaController.verify` 결과 매핑:
     * - 세션이 무효화됨(실패로 잠금이 걸림) → `/admin/login`
     * - 200 → `/admin`. 복구 코드를 썼으면 남은 개수 경고 토스트
     * - 그 밖의 4xx(401 invalid_code — 이미 잠긴 계정 포함) → `auth/mfa :: error` + 422
     * - 5xx 또는 예외 → 본문·토스트 없는 상태 코드(화면이 "확인하지 못했습니다"로 보인다)
     *
     * MFA 대기 세션이 아니면(이미 확인됨) verify 를 부르지 않고 `/admin` 으로 보낸다.
     */
    @PostMapping("/admin/login/mfa")
    @Throws(IOException::class)
    fun verifyMfa(
        @RequestParam(name = "code", required = false) code: String?,
        @RequestParam(name = "mode", required = false) mode: String?,
        auth: Authentication?,
        req: HttpServletRequest,
        res: HttpServletResponse,
    ): ModelAndView? {
        if (!isMfaPending(req)) {
            Htmx.redirect(req, res, "/admin")
            return null
        }
        val result: ResponseEntity<*>
        try {
            // MfaChallenge.tsx: mfaApi.verify(value.trim())
            // 원 Java 는 auth 를 그대로 넘기고 verify 안의 auth.getName() 이 NPE — `!!` 로 같은 try 안에서 같은 예외(500).
            result = mfa.verify(MfaController.VerifyRequest(if (code == null) null else code.javaTrim()), auth!!, req)
        } catch (e: RuntimeException) {
            log.error("admin mfa verify (screen) failed unexpectedly: {}", e.javaClass.simpleName, e)
            res.status = HttpStatus.INTERNAL_SERVER_ERROR.value()
            return null
        }
        val session: HttpSession? = req.getSession(false)
        if (session == null) {
            // verify 가 잠금을 걸며 대기 세션을 끝냈다 — 로그인부터 다시.
            Htmx.redirect(req, res, "/admin/login")
            return null
        }
        val status = result.statusCode.value()
        if (status >= 200 && status < 300) {
            val body = result.body
            if (body is Map<*, *> && true == body["usedRecoveryCode"]) {
                val remaining = body["remaining"]
                if (remaining is Number) {
                    Toasts.warnTitled(res, "복구 코드를 사용했습니다.", "남은 복구 코드: " + remaining.toLong() + "개")
                }
            }
            Htmx.redirect(req, res, "/admin")
            return null
        }
        if (status >= 500) {
            res.status = status
            return null
        }
        val mav = ModelAndView("admin/auth/mfa :: error")
        mav.addObject("mode", if (MODE_RECOVERY == mode) MODE_RECOVERY else MODE_TOTP)
        mav.status = HttpStatus.UNPROCESSABLE_ENTITY
        return mav
    }
}

/** LoginPage.tsx: `p.active?.includes('dev') || p.active?.includes('local') || p.local`. */
private fun showDemoAccount(p: ProfileController.ProfileView?): Boolean {
    if (p == null) return false
    val active = p.active
    return p.local || (active != null && (active.contains("dev") || active.contains("local")))
}

/** `?error` 처럼 값 없는 쿼리 파라미터도 있는 것으로 본다(컨테이너에 따라 getParameter 가 null 일 수 있다). */
private fun hasFlag(req: HttpServletRequest, name: String): Boolean {
    if (req.getParameter(name) != null) return true
    val q: String = req.queryString ?: return false
    for (part in AMP.split(q)) {
        val eq = part.indexOf('=')
        if ((if (eq < 0) part else part.substring(0, eq)) == name) return true
    }
    return false
}

private fun isAuthenticated(auth: Authentication?): Boolean =
    auth != null && auth.isAuthenticated && auth !is AnonymousAuthenticationToken

private fun isMfaPending(req: HttpServletRequest): Boolean {
    val session: HttpSession? = req.getSession(false)
    return session != null && true == session.getAttribute(MfaPendingFilter.MFA_PENDING_ATTR)
}
