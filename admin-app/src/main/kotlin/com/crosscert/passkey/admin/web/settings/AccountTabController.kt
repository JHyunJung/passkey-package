package com.crosscert.passkey.admin.web.settings

import com.crosscert.passkey.admin.auth.MfaController
import com.crosscert.passkey.admin.config.MeController
import com.crosscert.passkey.admin.web.format.ErrorMessages
import com.crosscert.passkey.admin.web.format.KstFormat
import com.crosscert.passkey.admin.web.format.RecoveryCodeFilename
import com.crosscert.passkey.admin.web.support.CurrentAdmin
import com.crosscert.passkey.admin.web.support.QrSvg
import com.crosscert.passkey.admin.web.support.Toasts
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.Clock

private val log = LoggerFactory.getLogger(AccountTabController::class.java)

private const val ACCOUNT_VIEW = "admin/settings/account :: account"
private const val ENROLL_VIEW = "admin/settings/account :: enroll"
private const val CODES_VIEW = "admin/settings/account :: recoveryCodes"
private const val CODE_ERROR_VIEW = "admin/settings/account :: codeError"
private const val EMPTY_VIEW = "admin/fragments/shell/empty :: none"

/** AccountTab.tsx CODE_MISMATCH */
private const val CODE_MISMATCH = "코드가 맞지 않습니다. 앱에 표시된 새 코드(30초마다 바뀜)를 입력하세요."

/** 원본 QrCode size(AccountTab.tsx: `<QrCode size={152} />`). */
private const val QR_SIZE = 152

private const val ENROLL_ERROR_ID = "mfa-enroll-error"
private const val DISABLE_ERROR_ID = "mfa-disable-err"

/**
 * 설정 › 내 계정 탭(← pages/settings/AccountTab.tsx, RecoveryCodesModal.tsx, shell/QrCode.tsx, api/mfa.ts).
 *
 * 2단계 인증(MFA) 켜기·끄기는 [MfaController] 의 enroll/confirm/disable 을 메서드로 부른다(ResponseEntity 의 상태·본문을 그대로 해석).
 * - 켜기 1–2단계: `POST /admin/_/settings/mfa/enroll` → 응답 본문이 켜기 다이얼로그(QR = [QrSvg] data URI, Base32 secret).
 *   실패는 원본 errorToast 처럼 "2단계 인증을 시작하지 못했습니다" 오류 토스트 + 실제 상태 + 빈 본문.
 * - 확인: `POST …/mfa/confirm` → 성공 응답이 복구 코드 다이얼로그(3/3)다. 코드는 이 응답 본문에만 있다 — 세션·플래시·헤더·토스트·리다이렉트
 *   없음, `Cache-Control: no-store`, 다이얼로그 루트 `hx-history="false"`. .txt 다운로드·복사는 브라우저(Alpine)가 화면의 코드로 한다.
 *   실패는 토스트 없이 입력칸 아래 인라인(422 + HX-Retarget): 401 invalid_code 만 "코드가 맞지 않습니다…", 그 밖은 쉬운 오류 문구.
 * - 끄기: `POST …/mfa/disable` → 성공하면 계정 카드를 다시 그린다(경고 토스트 "2단계 인증이 꺼졌습니다."). 실패는 확인 다이얼로그 안 인라인.
 *
 * 켜기·끄기가 성공하면 원본의 `onMeChange(await getMe())` 처럼 [MeController.me] 로 계정 정보를 다시 읽어 카드를 그린다(실패하면
 * "계정 정보를 새로고침하지 못했습니다." 경고). 코드·secret 은 어떤 로그에도 남기지 않는다(예외는 클래스 이름만).
 *
 * `auth` 는 nullable: 원 Java 는 null 이어도 메서드에 들어와 `me(model)` 판정(403)이 먼저다. 넘기는 자리의 `!!` 는 원 Java 에서
 * Kotlin 피호출자의 파라미터 검사 NPE 가 나던 자리(모두 같은 try 안)다.
 */
@Controller
class AccountTabController(
    private val mfa: MfaController,
    private val meController: MeController,
    private val clock: Clock,
) {

    /** 계정 카드 모델. enrolledAt 은 서버(MeView)가 아직 내려주지 않아 늘 null — 원본처럼 그 줄을 그리지 않는다. */
    data class AccountView(val email: String?, val mfaEnabled: Boolean, val enrolledAt: String?) {
        internal companion object {
            fun of(me: CurrentAdmin): AccountView = AccountView(me.email, me.mfaEnabled, null)
        }
    }

    /** 켜기 다이얼로그 모델: QR 이미지 data URI 와 수동 입력 키. */
    data class EnrollView(val qrSrc: String?, val secret: String?)

    /** 복구 코드 한 줄: 번호(01–10)와 코드. */
    data class Code(val no: String?, val value: String?)

    // ── 탭 ──────────────────────────────────────────────────

    /** 설정 화면(전체 페이지·일반 탭 조각)이 같은 모델로 내 계정 탭을 그린다. */
    fun populate(model: Model, me: CurrentAdmin) {
        model.addAttribute("account", AccountView.of(me))
        model.addAttribute(
            "enrollAttrs",
            java.util.Map.of(
                "hx-post", ENROLL_PATH, "hx-target", "#mfa-enroll-slot", "hx-swap", "innerHTML",
                "hx-sync", "this:drop", "hx-disabled-elt", "this",
            ),
        )
    }

    @GetMapping(TAB_PATH)
    fun tab(model: Model): String {
        populate(model, me(model))
        return CONTENT_VIEW
    }

    // ── 켜기 ────────────────────────────────────────────────

    @PostMapping(ENROLL_PATH)
    fun enroll(auth: Authentication?, req: HttpServletRequest, res: HttpServletResponse, model: Model): String {
        me(model)
        noStore(res)
        val r: ResponseEntity<*>
        try {
            r = mfa.enroll(auth!!, req)
        } catch (e: RuntimeException) {
            log.warn("[settings] mfa enroll failed: {}", e.javaClass.simpleName)
            val info = ErrorMessages.describe(e)
            val status = info.httpStatus ?: 500
            return enrollFailed(res, status, info)
        }
        val status = r.statusCode.value()
        if (status < 200 || status >= 300) {
            val err = errorOf(r)
            return enrollFailed(res, status, ErrorMessages.describe(status, err, err))
        }
        val body: Map<*, *> = r.body as? Map<*, *> ?: java.util.Map.of<Any, Any>()
        val secret = body["secret"].toString()
        val uri = body["otpauthUri"].toString()
        model.addAttribute("enroll", EnrollView(QrSvg.render(uri, QR_SIZE), secret))
        model.addAttribute("confirmAction", CONFIRM_PATH)
        return ENROLL_VIEW
    }

    private fun enrollFailed(res: HttpServletResponse, status: Int, info: ErrorMessages.Info): String {
        Toasts.errorTitled(res, "2단계 인증을 시작하지 못했습니다", info.toastMessage(), "HTTP $status", MDC.get("traceId"))
        res.status = status
        return EMPTY_VIEW
    }

    // ── 확인(복구 코드) ─────────────────────────────────────

    @PostMapping(CONFIRM_PATH)
    fun confirm(
        @RequestParam(name = "code", required = false) code: String?,
        auth: Authentication?,
        req: HttpServletRequest,
        res: HttpServletResponse,
        model: Model,
    ): String {
        val me = me(model)
        noStore(res)
        val r: ResponseEntity<*>
        try {
            r = mfa.confirm(MfaController.VerifyRequest(code), auth!!, req)
        } catch (e: RuntimeException) {
            log.warn("[settings] mfa confirm failed: {}", e.javaClass.simpleName)
            return codeError(res, model, ENROLL_ERROR_ID, codeErrorText(e))
        }
        val status = r.statusCode.value()
        if (status < 200 || status >= 300) return codeError(res, model, ENROLL_ERROR_ID, codeErrorText(status, errorOf(r)))

        val codes = ArrayList<Code>()
        val body = r.body
        if (body is Map<*, *>) {
            val list = body["recoveryCodes"]
            if (list is List<*>) {
                for (c in list) codes.add(Code(String.format("%02d", codes.size + 1), c.toString()))
            }
        }
        model.addAttribute("codes", codes)
        model.addAttribute("rcFilename", RecoveryCodeFilename.of(me.email, KstFormat.kstTodayDate(clock.instant())))
        // 복구 코드는 계정 정보 새로고침의 성공 여부와 무관하게 먼저 보여 준다.
        val fresh = reloadMe(auth, req)
        if (fresh != null) {
            populate(model, fresh)
            model.addAttribute("accountOob", true)
            Toasts.successTitled(res, "2단계 인증이 켜졌습니다.", "")
        } else {
            model.addAttribute("accountOob", false)
            meRefreshFailed(res)
        }
        return CODES_VIEW
    }

    // ── 끄기 ────────────────────────────────────────────────

    @PostMapping(DISABLE_PATH)
    fun disable(
        @RequestParam(name = "code", required = false) code: String?,
        auth: Authentication?,
        req: HttpServletRequest,
        res: HttpServletResponse,
        model: Model,
    ): String {
        me(model)
        noStore(res)
        val r: ResponseEntity<*>
        try {
            r = mfa.disable(MfaController.VerifyRequest(code), auth!!, req)
        } catch (e: RuntimeException) {
            log.warn("[settings] mfa disable failed: {}", e.javaClass.simpleName)
            return codeError(res, model, DISABLE_ERROR_ID, codeErrorText(e))
        }
        val status = r.statusCode.value()
        if (status < 200 || status >= 300) return codeError(res, model, DISABLE_ERROR_ID, codeErrorText(status, errorOf(r)))

        val fresh = reloadMe(auth, req)
        if (fresh == null) {
            // 카드는 그대로 두고(원본도 me 가 안 바뀐다) 다이얼로그만 닫힌다(요청 성공).
            res.setHeader("HX-Reswap", "none")
            meRefreshFailed(res)
            return EMPTY_VIEW
        }
        populate(model, fresh)
        Toasts.warnTitled(res, "2단계 인증이 꺼졌습니다.", "")
        return ACCOUNT_VIEW
    }

    // ── 도우미 ──────────────────────────────────────────────

    private fun reloadMe(auth: Authentication?, req: HttpServletRequest): CurrentAdmin? {
        try {
            return CurrentAdmin.from(meController.me(auth!!, req).data())
        } catch (e: RuntimeException) {
            log.warn("[settings] me reload failed: {}", e.javaClass.simpleName)
            return null
        }
    }

    companion object {
        /** internal: 같은 모듈 테스트(AccountTabControllerTest)가 읽는다. */
        internal const val TAB_PATH = "/admin/_/settings/tab/account"
        internal const val ENROLL_PATH = "/admin/_/settings/mfa/enroll"
        internal const val CONFIRM_PATH = "/admin/_/settings/mfa/confirm"
        internal const val DISABLE_PATH = "/admin/_/settings/mfa/disable"

        internal const val CONTENT_VIEW = "admin/settings/account :: content"

        /**
         * 원본 codeErrorText: 서버의 코드 불일치 응답(401 {"error":"invalid_code"})만 코드 불일치, 그 밖(403 권한·429·5xx 등)은 describeError 의
         * `${title}. ${detail}`.
         * internal: 원 Java 는 package-private static — 같은 모듈 테스트(AccountTabControllerTest)가 부른다.
         */
        internal fun codeErrorText(status: Int, error: String?): String {
            if (status == 401 && "invalid_code" == error) return CODE_MISMATCH
            return join(ErrorMessages.describe(status, error, error))
        }

        fun codeErrorText(e: Throwable?): String = join(ErrorMessages.describe(e))

        private fun join(info: ErrorMessages.Info): String {
            val d = info.detail
            return if (d == null || d.isEmpty()) info.title + "." else info.title + ". " + d
        }

        /** 실패 문구를 입력칸 아래 자리(slotId)에 422 로 넣는다. 토스트 없음(원본: 입력칸 아래에만). */
        private fun codeError(res: HttpServletResponse, model: Model, slotId: String, text: String): String {
            res.status = 422
            res.setHeader("HX-Retarget", "#$slotId")
            res.setHeader("HX-Reswap", "innerHTML")
            model.addAttribute("codeError", text)
            model.addAttribute("codeErrorId", slotId)
            return CODE_ERROR_VIEW
        }

        /** mfaApi 의 ApiError(code): 본문의 `error` 값, 없으면 'error'. */
        private fun errorOf(r: ResponseEntity<*>): String {
            val m = r.body
            if (m is Map<*, *> && m["error"] != null) return m["error"].toString()
            return "error"
        }

        /**
         * 원본은 성공 토스트 뒤에 이 경고를 하나 더 띄운다. HX-Trigger 토스트는 응답마다 하나라 더 중요한 이 경고만 보낸다(성공은 화면 변화로 알 수 있다).
         */
        private fun meRefreshFailed(res: HttpServletResponse) {
            Toasts.warnTitled(res, "계정 정보를 새로고침하지 못했습니다.", "페이지를 새로고침해 주세요.")
        }

        private fun noStore(res: HttpServletResponse) {
            res.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
            res.setHeader(HttpHeaders.PRAGMA, "no-cache")
        }

        internal fun me(model: Model): CurrentAdmin =
            model.getAttribute("me") as? CurrentAdmin ?: throw AccessDeniedException("no authenticated admin")
    }
}
