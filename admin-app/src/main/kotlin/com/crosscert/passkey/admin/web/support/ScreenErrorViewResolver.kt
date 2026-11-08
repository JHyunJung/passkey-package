package com.crosscert.passkey.admin.web.support

import com.crosscert.passkey.admin.web.format.ErrorMessages
import jakarta.servlet.RequestDispatcher
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.webmvc.autoconfigure.error.ErrorViewResolver
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Component
import org.springframework.web.servlet.ModelAndView

/**
 * 컨테이너 오류 디스패치(`/error`, Boot 의 BasicErrorController)가 화면 경로에서 났을 때 `admin/error` 를
 * 원래 상태 코드로 그린다(예: 템플릿 렌더링 실패 500). Boot 의 기본 ErrorViewResolver(templates/error/\*.html)를 대신한다.
 *
 * 보안 체인은 화면 경로(`/admin` 아래, `/admin/api` 제외)의 ERROR 디스패치만 허용한다(`AdminSecurityConfig`).
 * 그 밖의 오류 디스패치는 여기 오지 않지만, 혹시 오더라도 null 을 돌려 Boot 기본 처리에 맡긴다.
 */
@Component
class ScreenErrorViewResolver : ErrorViewResolver {

    override fun resolveErrorView(
        request: HttpServletRequest,
        status: HttpStatus,
        model: Map<String, Any>,
    ): ModelAndView? {
        val uri: Any? = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI)
        // 원래 주소가 없으면(드묾) 화면으로 본다 — 보안 매처와 같은 규칙. /admin 밖·/admin/api 는 Boot 기본 처리.
        if (uri != null && !ScreenNotFoundAdvice.isScreen(uri.toString())) return null
        return errorView(status.value())
    }

    companion object {
        /** ErrorMessages(describeError.ts) 의 상태별 문구로 `admin/error`. 서버 내부 메시지는 싣지 않는다. */
        internal fun errorView(status: Int): ModelAndView {
            val info = ErrorMessages.describe(status, null)
            val mav = ModelAndView(ErrorPage.VIEW)
            mav.status = HttpStatusCode.valueOf(status)
            mav.addObject("status", status)
            mav.addObject("title", info.title)
            // 상태별 쉬운 문구가 없는 4xx 는 detail 이 서버 원문 자리(null) — 빈 문자열로.
            mav.addObject("message", if (info.detail == null || info.detailIsRaw) "" else info.detail)
            return mav
        }
    }
}
