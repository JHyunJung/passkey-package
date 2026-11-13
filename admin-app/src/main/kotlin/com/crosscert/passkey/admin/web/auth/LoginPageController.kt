package com.crosscert.passkey.admin.web.auth

import com.crosscert.passkey.admin.auth.MfaController
import com.crosscert.passkey.admin.auth.MfaPendingFilter
import com.crosscert.passkey.admin.auth.MfaSessionState
import com.crosscert.passkey.admin.config.ProfileController
import com.crosscert.passkey.admin.web.format.KstFormat
import com.crosscert.passkey.admin.web.format.RecoveryCodeFilename
import com.crosscert.passkey.admin.web.format.javaTrim
import com.crosscert.passkey.admin.web.settings.AccountTabController
import com.crosscert.passkey.admin.web.support.Htmx
import com.crosscert.passkey.admin.web.support.QrSvg
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
import java.time.Clock
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

/** MFA 필수 정책 등록 화면은 설정 › 내 계정 탭의 켜기·복구 코드·코드 오류 조각을 그대로 쓴다. */
private const val ENROLL_PAGE_VIEW = "admin/auth/mfa-enroll"
private const val ENROLL_VIEW = "admin/settings/account :: enroll"
private const val CODES_VIEW = "admin/settings/account :: recoveryCodes"
private const val CODE_ERROR_VIEW = "admin/settings/account :: codeError"
private const val ENROLL_PATH = "/admin/login/mfa/enroll"
private const val CONFIRM_PATH = "/admin/login/mfa/confirm"
private const val ENROLL_START_ERROR_ID = "mfa-enroll-start-error"
private const val ENROLL_ERROR_ID = "mfa-enroll-error"
private const val QR_SIZE = 152
private const val HOME = "/admin"

/** Java `String.split("&")` 와 같은 분할(끝의 빈 토큰 제거). */
private val AMP = Pattern.compile("&")

/**
 * 로그인 화면과 MFA 단계(← App.tsx 의 로그인 전후 분기, pages/LoginPage.tsx, pages/MfaChallenge.tsx).
 *
 * - `GET /admin/login`: 미인증 → 로그인 화면, MFA 대기 세션 → MFA 단계, 인증 완료 → `/admin`.
 * - 로그인 제출(`POST /admin/login`)은 보안 체인의 폼 로그인이 받는다(이 컨트롤러가 아니다).
 * - `POST /admin/login/mfa`: [MfaController.verify] 를 메서드로 부르고 결과를 화면 응답으로 옮긴다.
 * - MFA 필수 정책 등록(`MFA_ENROLL_REQUIRED` 세션): `GET /admin/login` 이 등록 화면, `POST /admin/login/mfa/enroll`·`…/confirm` 이
 *   [MfaController.enroll]·[MfaController.confirm] 을 메서드로 부른다(등록 필요 해제·감사는 confirm 이 한다). secret·복구 코드는
 *   응답 본문에만(no-store) — 세션·플래시·헤더·토스트·로그에 없다.
 *
 * 인증 코드는 로그에도 응답에도 남기지 않는다.
 */
@Controller
class LoginPageController(
    private val profiles: ProfileController,
    private val mfa: MfaController,
    private val clock: Clock,
) {

    /** 미인증이면 Spring 이 auth 에 null 을 넘긴다. */
    @GetMapping("/admin/login")
    fun login(auth: Authentication?, req: HttpServletRequest): ModelAndView {
        if (isAuthenticated(auth)) {
            if (isMfaPending(req)) return ModelAndView("admin/auth/mfa")
            if (MfaSessionState.isEnrollRequired(req)) return ModelAndView(ENROLL_PAGE_VIEW, "authFocus", true)
            return ModelAndView("redirect:/admin")
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

    // ── MFA 필수 정책: 등록 ─────────────────────────────────

    /**
     * 등록 시작(다시 누르면 새 secret 으로 다시 시작). 성공 → 설정 탭과 같은 켜기 다이얼로그(QR·수동 입력 키·코드 확인 폼, 확인은
     * [CONFIRM_PATH]). 실패·예외 → 422 + `HX-Retarget: #mfa-enroll-start-error` 쉬운 문구(원문 비노출). 등록 필요 세션이 아니면 `/admin`.
     */
    @PostMapping(ENROLL_PATH)
    @Throws(IOException::class)
    fun startEnroll(auth: Authentication?, req: HttpServletRequest, res: HttpServletResponse): ModelAndView? {
        if (!MfaSessionState.isEnrollRequired(req)) {
            Htmx.redirect(req, res, HOME)
            return null
        }
        AccountTabController.noStore(res)
        val r: ResponseEntity<*>
        try {
            r = mfa.enroll(auth!!, req)
        } catch (e: RuntimeException) {
            log.warn("admin mfa enroll (required) failed: {}", e.javaClass.simpleName)
            return codeError(res, ENROLL_START_ERROR_ID, AccountTabController.codeErrorText(e))
        }
        val status = r.statusCode.value()
        if (status < 200 || status >= 300) {
            return codeError(res, ENROLL_START_ERROR_ID, AccountTabController.codeErrorText(status, AccountTabController.errorOf(r)))
        }
        val body: Map<*, *> = r.body as? Map<*, *> ?: java.util.Map.of<Any, Any>()
        val mav = ModelAndView(ENROLL_VIEW)
        mav.addObject(
            "enroll",
            AccountTabController.EnrollView(QrSvg.render(body["otpauthUri"].toString(), QR_SIZE), body["secret"].toString()),
        )
        mav.addObject("confirmAction", CONFIRM_PATH)
        return mav
    }

    /**
     * 코드 확인. 성공 → 복구 코드 다이얼로그(1회 표시, 닫으면 `/admin` — `data-done-href`). 이때 `MFA_PENDING` 은 붙이지 않는다
     * (방금 TOTP 를 확인했다; 등록 필요 해제는 [MfaController.confirm] 이 한다). 실패 → 422 + `HX-Retarget: #mfa-enroll-error`
     * (설정 탭과 같은 문구). 등록 필요 세션이 아니면 `/admin`.
     */
    @PostMapping(CONFIRM_PATH)
    @Throws(IOException::class)
    fun confirmEnroll(
        @RequestParam(name = "code", required = false) code: String?,
        auth: Authentication?,
        req: HttpServletRequest,
        res: HttpServletResponse,
    ): ModelAndView? {
        if (!MfaSessionState.isEnrollRequired(req)) {
            Htmx.redirect(req, res, HOME)
            return null
        }
        AccountTabController.noStore(res)
        val r: ResponseEntity<*>
        try {
            r = mfa.confirm(MfaController.VerifyRequest(code?.javaTrim()), auth!!, req)
        } catch (e: RuntimeException) {
            log.warn("admin mfa confirm (required) failed: {}", e.javaClass.simpleName)
            return codeError(res, ENROLL_ERROR_ID, AccountTabController.codeErrorText(e))
        }
        val status = r.statusCode.value()
        if (status < 200 || status >= 300) {
            return codeError(res, ENROLL_ERROR_ID, AccountTabController.codeErrorText(status, AccountTabController.errorOf(r)))
        }
        val mav = ModelAndView(CODES_VIEW)
        mav.addObject("codes", AccountTabController.codesOf(r))
        mav.addObject("rcFilename", RecoveryCodeFilename.of(auth.name, KstFormat.kstTodayDate(clock.instant())))
        mav.addObject("rcDoneHref", HOME)
        mav.addObject("accountOob", false)
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

/** 실패 문구를 입력칸 아래 자리(slotId)에 422 로 넣는다(설정 탭 codeError 와 같은 규칙, 토스트 없음). */
private fun codeError(res: HttpServletResponse, slotId: String, text: String): ModelAndView {
    res.setHeader("HX-Retarget", "#$slotId")
    res.setHeader("HX-Reswap", "innerHTML")
    val mav = ModelAndView(CODE_ERROR_VIEW)
    mav.addObject("codeError", text)
    mav.status = HttpStatus.UNPROCESSABLE_ENTITY
    return mav
}
