package com.crosscert.passkey.admin.web.support

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import tools.jackson.core.JacksonException
import tools.jackson.core.json.JsonWriteFeature
import tools.jackson.databind.ObjectMapper

/**
 * 서버 → 브라우저 토스트. `HX-Trigger: {"toast":{"kind":"…","message":"…"}}` 헤더를 쓰고,
 * app.js(Task 4)가 이 이벤트를 받아 토스트로 띄운다. 같은 응답에 여러 번 부르면 마지막 것이 이긴다.
 *
 * HTTP 헤더 값은 Latin-1 로 전송되므로 한글을 그대로 넣으면 깨진다. 비 ASCII 문자는
 * JSON `\uXXXX` 이스케이프로 내보내고, 브라우저의 JSON 파싱이 원래 문자로 되돌린다.
 */
object Toasts {

    const val HX_TRIGGER = "HX-Trigger"

    private val JSON: ObjectMapper = StandaloneJson.jackson2Like()
        .enable(JsonWriteFeature.ESCAPE_NON_ASCII)
        .build()

    fun success(res: HttpServletResponse, message: String?) {
        write(res, "success", message)
    }

    fun error(res: HttpServletResponse, message: String?) {
        write(res, "error", message, null, null)
    }

    /** [error] 에서 traceId 없이. */
    fun error(res: HttpServletResponse, message: String?, detail: String?) {
        error(res, message, detail, null)
    }

    /**
     * 오류 토스트. `detail`(예: `HTTP 409 · [CODE] 서버 메시지`)과 `traceId` 는
     * 토스트의 접힌 "자세히" 안에만 보인다(errorToast.ts). 본문 `message` 에는 쉬운 문구만 둔다.
     */
    fun error(res: HttpServletResponse, message: String?, detail: String?, traceId: String?) {
        write(res, "error", message, detail, traceId)
    }

    /** 확인 요청(confirmDialog 의 확인 버튼)임을 알리는 요청 헤더. 값은 "1". */
    const val CONFIRM_HEADER = "X-Confirm"

    /** 확인 요청의 요청 헤더가 있는지. */
    fun isConfirm(req: HttpServletRequest): Boolean = "1" == req.getHeader(CONFIRM_HEADER)

    /**
     * 확인 요청의 실패 안내. `HX-Trigger: {"confirmError":{"message","detail","traceId"}}` — 토스트가 아니라 열려 있는
     * confirmDialog 안의 인라인 오류로만 보인다(토스트 중복 없음). message 는 쉬운 문구, detail·traceId 는 선택.
     */
    fun confirmError(res: HttpServletResponse, message: String?, detail: String?, traceId: String?) {
        try {
            val m = LinkedHashMap<String, Any?>()
            m["message"] = message ?: ""
            if (detail != null && detail.isNotEmpty()) m["detail"] = detail
            if (traceId != null && traceId.isNotEmpty()) m["traceId"] = traceId
            res.setHeader(HX_TRIGGER, JSON.writeValueAsString(java.util.Map.of("confirmError", m)))
        } catch (e: JacksonException) {
            throw IllegalStateException("confirmError header serialization failed", e)
        }
    }

    /**
     * 제목이 있는 오류 토스트(errorToast.ts: title=무엇이 실패했는지, message=쉬운 문구). 토스트는 제목을 크게, message 를 작게 보인다.
     */
    fun errorTitled(res: HttpServletResponse, title: String?, message: String?, detail: String?, traceId: String?) {
        write(res, "error", title, message, detail, traceId)
    }

    /** 제목이 있는 성공 토스트(예: NewTenant 의 "Tenant 생성 완료" / "<이름> · WebAuthn 설정으로 이동합니다"). */
    fun successTitled(res: HttpServletResponse, title: String?, message: String?) {
        write(res, "success", title, message, null, null)
    }

    /**
     * 저장 성공: 제목 있는 성공 토스트와 함께 `saved` 이벤트를 보낸다(`HX-Trigger: {"saved":true,"toast":{…}}`).
     * app.js 가 `saved` 로 저장 안 한 변경 표시(`$store.dirty`)를 지운다.
     */
    fun successSaved(res: HttpServletResponse, title: String?, message: String?) {
        try {
            val toast = LinkedHashMap<String, Any?>()
            toast["kind"] = "success"
            if (title != null && title.isNotEmpty()) toast["title"] = title
            toast["message"] = message ?: ""
            val events = LinkedHashMap<String, Any?>()
            events["saved"] = true
            events["toast"] = toast
            res.setHeader(HX_TRIGGER, JSON.writeValueAsString(events))
        } catch (e: JacksonException) {
            throw IllegalStateException("saved toast header serialization failed", e)
        }
    }

    /**
     * 동작 버튼이 있는 성공 토스트(원본 toast({action:{label,onClick}})). 버튼을 누르면 toastHost 가 window 이벤트 `event`(detail = `detail`)를 올린다.
     * 이벤트를 듣는 쪽이 요청을 보낸다 — 서버가 준 주소만 쓰도록 듣는 쪽이 접두사를 확인해야 한다.
     */
    fun successTitledAction(
        res: HttpServletResponse,
        title: String?,
        message: String?,
        label: String?,
        event: String?,
        detail: Map<String, Any?>?,
    ) {
        try {
            val action = LinkedHashMap<String, Any?>()
            action["label"] = label
            action["event"] = event
            action["detail"] = detail
            val toast = LinkedHashMap<String, Any?>()
            toast["kind"] = "success"
            if (title != null && title.isNotEmpty()) toast["title"] = title
            toast["message"] = message ?: ""
            toast["action"] = action
            res.setHeader(HX_TRIGGER, JSON.writeValueAsString(java.util.Map.of("toast", toast)))
        } catch (e: JacksonException) {
            throw IllegalStateException("action toast header serialization failed", e)
        }
    }

    /** 제목이 있는 경고 토스트(예: MfaChallenge 의 "복구 코드를 사용했습니다." / "남은 복구 코드: N개"). */
    fun warnTitled(res: HttpServletResponse, title: String?, message: String?) {
        write(res, "warn", title, message, null, null)
    }

    private fun write(res: HttpServletResponse, kind: String, message: String?) {
        write(res, kind, message, null, null)
    }

    private fun write(res: HttpServletResponse, kind: String, message: String?, detail: String?, traceId: String?) {
        write(res, kind, null, message, detail, traceId)
    }

    private fun write(
        res: HttpServletResponse,
        kind: String,
        title: String?,
        message: String?,
        detail: String?,
        traceId: String?,
    ) {
        try {
            val toast = LinkedHashMap<String, Any?>()
            toast["kind"] = kind
            if (title != null && title.isNotEmpty()) toast["title"] = title
            toast["message"] = message ?: ""
            if (detail != null && detail.isNotEmpty()) toast["detail"] = detail
            if (traceId != null && traceId.isNotEmpty()) toast["traceId"] = traceId
            res.setHeader(HX_TRIGGER, JSON.writeValueAsString(java.util.Map.of("toast", toast)))
        } catch (e: JacksonException) {
            throw IllegalStateException("toast header serialization failed", e)
        }
    }
}
