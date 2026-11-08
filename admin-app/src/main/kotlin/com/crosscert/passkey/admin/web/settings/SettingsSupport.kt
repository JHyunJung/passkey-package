package com.crosscert.passkey.admin.web.settings

import com.crosscert.passkey.admin.web.format.ErrorMessages
import com.crosscert.passkey.admin.web.support.CurrentAdmin
import com.crosscert.passkey.admin.web.support.Toasts
import com.crosscert.passkey.core.api.BusinessException
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.security.access.AccessDeniedException
import org.springframework.ui.Model

/** 설정 탭 컨트롤러 공통: PLATFORM 확인(리터럴 탭 경로는 일반 매핑의 판정을 받지 않는다), 오류 응답 매핑. */
internal object SettingsSupport {

    const val EMPTY_VIEW = "admin/fragments/shell/empty :: none"

    /** PLATFORM 이 아니면 AccessDeniedException(403). REST 는 부르지 않는다. */
    fun platform(model: Model): CurrentAdmin {
        val me = AccountTabController.me(model)
        if (!me.platform) throw AccessDeniedException("platform only")
        return me
    }

    /** 예외의 HTTP 상태. 모르는 예외는 500. */
    fun statusOf(e: RuntimeException): Int =
        ErrorMessages.describe(e).httpStatus ?: HttpServletResponse.SC_INTERNAL_SERVER_ERROR

    /**
     * 제목 있는 오류 응답(원본 errorToast(e, title)): 실제 상태 + 오류 토스트 + 빈 본문. 확인 요청이면 토스트가 아니라 다이얼로그 안 인라인(confirmError).
     * 알 수 없는 예외의 원문은 싣지 않는다(ErrorMessages).
     */
    fun fail(res: HttpServletResponse, title: String, e: RuntimeException, confirm: Boolean): String {
        if (e is AccessDeniedException) throw e
        val info = ErrorMessages.describe(e)
        val status = statusOf(e)
        val detail = "HTTP " + status + (if (e is BusinessException) " · [" + e.errorCode.code + "]" else "")
        if (confirm) Toasts.confirmError(res, title + ". " + info.toastMessage(), detail, MDC.get("traceId"))
        else Toasts.errorTitled(res, title, info.toastMessage(), detail, MDC.get("traceId"))
        res.status = status
        return EMPTY_VIEW
    }

    /**
     * 화면 상태(낡은 목록·막힌 대상) 때문에 REST 를 부르지 않은 동작: 원본 `blocked()` 가 만든 ApiError 처럼 "요청을 처리하지 못했습니다. <사유>" + 409.
     * 확인 요청이면 다이얼로그 안 인라인(confirmError), 아니면 오류 토스트. 본문 없음.
     */
    fun blocked(res: HttpServletResponse, reason: String, confirm: Boolean): String {
        val msg = "요청을 처리하지 못했습니다. $reason"
        if (confirm) Toasts.confirmError(res, msg, "HTTP 409", MDC.get("traceId"))
        else Toasts.error(res, msg, "HTTP 409", MDC.get("traceId"))
        res.status = HttpServletResponse.SC_CONFLICT
        return EMPTY_VIEW
    }
}
