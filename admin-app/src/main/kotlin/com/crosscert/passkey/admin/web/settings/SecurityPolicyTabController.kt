package com.crosscert.passkey.admin.web.settings

import com.crosscert.passkey.admin.policy.SecurityPolicyController
import com.crosscert.passkey.admin.policy.SecurityPolicyDto
import com.crosscert.passkey.admin.web.format.ErrorMessages
import com.crosscert.passkey.admin.web.format.PolicyValidation
import com.crosscert.passkey.admin.web.format.javaIsBlank
import com.crosscert.passkey.admin.web.format.javaTrim
import com.crosscert.passkey.admin.web.support.Toasts
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Validator
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.nio.charset.StandardCharsets

private val log = LoggerFactory.getLogger(SecurityPolicyTabController::class.java)

private const val TAB_VIEW = "admin/settings/security :: tab"
private const val RESULT_VIEW = "admin/settings/security :: result"

private const val SAVE_FAILED_TITLE = "보안 정책을 저장하지 못했습니다"

/**
 * 설정 › 보안 정책 탭(← pages/settings/SecurityPolicyTab.tsx, api/securityPolicy.ts, lib/policyValidation.ts). PLATFORM 전용 — 모든 엔드포인트가 PLATFORM 을 먼저 확인한다.
 *
 * 흐름(원본 handleSave): 저장 바의 저장 = 폼 제출 = `PUT /admin/_/settings/security`.
 * 1. 검증([PolicyValidation], 서버에 이미 저장된 idle timeout 을 손대지 않았으면 오류로 막지 않는다): 실패는 422 + 필드 옆 오류(OOB, 입력·변경 상태 유지, 토스트 없음).
 * 2. 지금 쓰는 콘솔 주소(요청의 origin)가 저장된 CORS 허용 목록에 있는데 새 목록에서 빠지면 L2 확인 다이얼로그를 돌려준다. 저장이 아니므로
 *    **422** 로 응답한다 — 저장 바가 2xx 를 "저장됨"으로 세어 변경 표시를 지우지 않게 하기 위함이다(본문은 인라인 조각이라 스왑된다).
 *    다이얼로그의 확인 버튼이 같은 PUT 을 `confirmLockout=1` 로 다시 보낸다.
 * 3. 저장: `SecurityPolicyController.update`. 성공하면 탭을 새 값으로 다시 그리고 `saved` + 성공 토스트("보안 정책 저장됨").
 *    REST 거절·실패는 저장 바 경로면 오류 토스트("보안 정책을 저장하지 못했습니다" + "…입력한 내용은 그대로 있습니다."), 확인 다이얼로그 경로면 다이얼로그 안 인라인.
 *
 * 저장 바의 취소는 폼 reset 이 아니라 `GET …/tab/security?revert=1` 로 서버 값을 다시 읽어 탭을 바꾼다(원본 handleCancel).
 * 허용 목록의 origin 검증은 칩 추가 때 `GET …/security/origin-check` 가 [PolicyValidation.validateOrigin] 으로 한다.
 */
@Controller
class SecurityPolicyTabController(
    private val policy: SecurityPolicyController,
    private val validator: Validator,
) {

    /** 화면 값. idle 은 입력 원문, consoleSaved 는 저장된 목록에서 콘솔 주소와 같은(대소문자 무시) 항목(없으면 null). */
    data class Form(val idle: String?, val mfa: Boolean, val cors: List<String>?, val consoleOrigin: String?, val consoleSaved: String?)

    // ── 화면 ────────────────────────────────────────────────

    @GetMapping(TAB_PATH)
    fun tab(
        @RequestParam(name = "revert", defaultValue = "") revert: String,
        req: HttpServletRequest,
        res: HttpServletResponse,
        model: Model,
    ): String {
        SettingsSupport.platform(model)
        model.addAttribute("retryAttrs", SettingsPageController.tabRequesterAttrs(TAB_PATH))
        val v: SecurityPolicyDto.View
        try {
            v = policy.get()
        } catch (e: AccessDeniedException) {
            throw e
        } catch (e: RuntimeException) {
            // 저장 바의 취소(원본 handleCancel): 다시 읽기에 실패하면 화면·입력을 그대로 두고 오류만 알린다(스왑 없는 오류 상태 + 토스트).
            if ("1" == revert) return SettingsSupport.fail(res, "되돌리지 못했습니다", e, false)
            // 로드 실패를 기본값(30분·MFA 켜짐)처럼 보이지 않게: 입력칸·저장 없이 LoadError.
            log.warn("[settings] security policy load failed: {}", e.javaClass.simpleName)
            model.addAttribute("loadError", e)
            model.addAttribute("sp", null)
            return TAB_VIEW
        }
        fill(model, v, consoleOrigin(req))
        return TAB_VIEW
    }

    /** ChipInput 원격 검증: 본문 = 오류 문구(비면 통과), 통과하면 X-Normalized-Value = 정규화한 origin. */
    @GetMapping(ORIGIN_CHECK_PATH)
    fun originCheck(
        @RequestParam(name = "value", defaultValue = "") value: String,
        @RequestParam(name = "existing", required = false) existing: List<String>?,
        model: Model,
    ): ResponseEntity<String> {
        SettingsSupport.platform(model)
        val err = PolicyValidation.validateOrigin(value, existing ?: java.util.List.of())
        val b = ResponseEntity.ok().contentType(MediaType("text", "plain", StandardCharsets.UTF_8))
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
        if (err != null) return b.body(err)
        return b.header("X-Normalized-Value", PolicyValidation.normalizeOrigin(value)).body("")
    }

    // ── 저장 ────────────────────────────────────────────────

    @PutMapping(SAVE_PATH)
    fun save(
        @RequestParam(name = "sessionIdleTimeoutMinutes", defaultValue = "") idle: String,
        @RequestParam(name = "mfaRequired", defaultValue = "") mfaRaw: String,
        @RequestParam(name = "corsAllowlist", required = false) corsRaw: List<String?>?,
        @RequestParam(name = "confirmLockout", defaultValue = "") confirmLockout: String,
        auth: Authentication?,
        req: HttpServletRequest,
        res: HttpServletResponse,
        model: Model,
    ): String {
        SettingsSupport.platform(model)
        val confirm = Toasts.isConfirm(req)
        val cors = clean(corsRaw)
        val idleIn = idle.javaTrim()

        val saved: SecurityPolicyDto.View
        try {
            saved = policy.get() // 저장된 값 기준(손대지 않은 idle·콘솔 주소 판정)
        } catch (e: RuntimeException) {
            return SettingsSupport.fail(res, SAVE_FAILED_TITLE, e, confirm)
        }

        val errors = LinkedHashMap<String, String>()
        val untouched = idleIn == saved.sessionIdleTimeoutMinutes.toString()
        var idleMin: Int? = if (untouched) saved.sessionIdleTimeoutMinutes else null
        if (!untouched) {
            val e = PolicyValidation.timeoutError(idleIn)
            if (e != null) errors["sessionIdleTimeoutMinutes"] = e
            else idleMin = Integer.parseInt(idleIn) // 1–1440 검증 통과
        }
        val mfa: Boolean? = if ("true" == mfaRaw) true else if ("false" == mfaRaw) false else null
        if (mfa == null) errors["form"] = "저장할 수 없는 값이 있습니다. 입력을 확인한 뒤 다시 시도하세요."
        // 허용 목록은 PolicyValidation 과 같은 규칙(개수·길이·형식·중복)으로. idle 은 위에서 따로 걸렀으므로 자리값(1)으로 채운다.
        val listErr = PolicyValidation.validate(SecurityPolicyDto.UpdateRequest(1, true, cors))["corsAllowlist"]
        if (listErr != null) errors["corsAllowlist"] = listErr

        // !!: errors 가 비었으면 idleMin 은 채워져 있다 — 원 Java 는 이 자리의 Integer 언박싱(UpdateRequest(int, …))에서 NPE.
        val body = if (errors.isEmpty()) SecurityPolicyDto.UpdateRequest(idleMin!!, mfa, cors) else null
        if (body != null) {
            for (cv in validator.validate(body)) {
                // 위에서 걸러지지 않은 제약 위반(예: 저장된 idle 이 1 미만) → 일반 오류. REST 는 부르지 않는다.
                log.debug("[settings] security policy violation: {}", cv.propertyPath)
                errors.putIfAbsent("form", "저장할 수 없는 값이 있습니다. 입력을 확인한 뒤 다시 시도하세요.")
            }
        }
        // body 가 null 이면 errors 는 비어 있지 않다(오류는 늘기만 한다) — 원 Java 의 !errors.isEmpty() 와 같은 판정.
        if (body == null || !errors.isEmpty()) {
            if (confirm) {
                // 확인 단계에서의 검증 실패는 위조·경합: 다이얼로그 안 인라인 오류.
                Toasts.confirmError(res, errors.values.iterator().next(), null, null)
                res.status = HttpServletResponse.SC_BAD_REQUEST
                return SettingsSupport.EMPTY_VIEW
            }
            res.status = 422
            model.addAttribute("errors", errors)
            model.addAttribute("dialog", false)
            return RESULT_VIEW
        }

        val console = consoleOrigin(req)
        if (removesConsole(saved.corsAllowlist, cors, console) && "1" != confirmLockout) {
            res.status = 422 // 저장이 아님 — 저장 바가 변경 표시를 지우지 않도록 2xx 를 쓰지 않는다.
            model.addAttribute("errors", java.util.Map.of<String, String>())
            model.addAttribute("dialog", true)
            model.addAttribute("draftIdle", idleMin.toString())
            model.addAttribute("draftMfa", mfa)
            model.addAttribute("draftCors", cors)
            model.addAttribute("consoleOrigin", console)
            model.addAttribute("saveAttrs", saveAttrs())
            return RESULT_VIEW
        }

        val updated: SecurityPolicyDto.View
        try {
            // !!: 원 Java 는 null auth 를 그대로 넘겨 Kotlin 피호출자의 파라미터 검사 NPE(같은 try 안). platform(model) 이 먼저라 도달하지 않는다.
            updated = policy.update(body, auth!!)
        } catch (e: RuntimeException) {
            if (e is AccessDeniedException) throw e
            log.warn("[settings] security policy save failed: {}", e.javaClass.simpleName)
            val info = ErrorMessages.describe(e)
            val status = SettingsSupport.statusOf(e)
            if (confirm) {
                Toasts.confirmError(res, SAVE_FAILED_TITLE + ". " + info.toastMessage(), "HTTP $status", MDC.get("traceId"))
            } else {
                Toasts.errorTitled(res, SAVE_FAILED_TITLE, info.title + ". 입력한 내용은 그대로 있습니다.", "HTTP $status", MDC.get("traceId"))
            }
            res.status = status
            return SettingsSupport.EMPTY_VIEW
        }
        Toasts.successSaved(res, "보안 정책 저장됨", "")
        res.setHeader("HX-Retarget", "#" + SettingsPageController.TARGET_ID)
        res.setHeader("HX-Reswap", "innerHTML")
        model.addAttribute("retryAttrs", SettingsPageController.tabRequesterAttrs(TAB_PATH))
        fill(model, updated, console)
        return TAB_VIEW
    }

    // ── 도우미 ──────────────────────────────────────────────

    private fun fill(model: Model, v: SecurityPolicyDto.View, console: String) {
        var saved: String? = null
        for (o in v.corsAllowlist) {
            if (o.equals(console, ignoreCase = true)) {
                saved = o
                break
            }
        }
        model.addAttribute("loadError", null)
        model.addAttribute("sp", Form(v.sessionIdleTimeoutMinutes.toString(), v.mfaRequired, v.corsAllowlist, console, saved))
        model.addAttribute("savePath", SAVE_PATH)
        model.addAttribute("revertUrl", "$TAB_PATH?revert=1")
        model.addAttribute("originCheckUrl", ORIGIN_CHECK_PATH)
    }

    companion object {
        /** internal: 같은 모듈 테스트(SecurityPolicyTabControllerTest)가 읽는다. */
        internal const val TAB_PATH = "/admin/_/settings/tab/security"
        internal const val SAVE_PATH = "/admin/_/settings/security"
        internal const val ORIGIN_CHECK_PATH = "/admin/_/settings/security/origin-check"

        /** 이 콘솔의 origin(scheme://host[:port]) — 원본 window.location.origin. */
        private fun consoleOrigin(req: HttpServletRequest): String =
            ServletUriComponentsBuilder.fromRequest(req).replacePath(null).replaceQuery(null).build().toUriString()

        private fun hasConsole(list: List<String>, console: String): Boolean =
            list.stream().anyMatch { o -> o.equals(console, ignoreCase = true) }

        /**
         * 원본 removesConsole: 저장된 목록엔 콘솔 주소가 있고 새 목록엔 없다.
         * internal: 원 Java 는 package-private static — 같은 모듈 테스트(SecurityPolicyTabControllerTest)가 부른다.
         */
        internal fun removesConsole(saved: List<String>, next: List<String>, console: String): Boolean =
            hasConsole(saved, console) && !hasConsole(next, console)

        private fun saveAttrs(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            m["hx-put"] = SAVE_PATH
            m["hx-target"] = "#" + SettingsPageController.TARGET_ID
            m["hx-swap"] = "innerHTML"
            m["hx-sync"] = SettingsPageController.SYNC
            return m
        }

        private fun clean(raw: List<String?>?): List<String> {
            val out = ArrayList<String>()
            if (raw != null) {
                for (o in raw) if (o != null && !o.javaIsBlank() && !out.contains(o.javaTrim())) out.add(o.javaTrim())
            }
            return out
        }
    }
}
