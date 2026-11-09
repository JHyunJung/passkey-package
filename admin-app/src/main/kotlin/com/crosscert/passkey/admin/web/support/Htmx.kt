package com.crosscert.passkey.admin.web.support

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.io.IOException

/** htmx 요청 판별과 리다이렉트. 필터 단계(보안 핸들러)와 화면 컨트롤러가 함께 쓴다. */
object Htmx {

    const val HX_REQUEST = "HX-Request"
    const val HX_REDIRECT = "HX-Redirect"

    /** htmx 가 보낸 요청이면 `HX-Request: true` 헤더가 붙는다. */
    fun isHtmx(req: HttpServletRequest): Boolean = "true".equals(req.getHeader(HX_REQUEST), ignoreCase = true)

    /**
     * 현재 요청(RequestContextHolder)을 기준으로 [redirect]`(req, res, path)`.
     * 화면 컨트롤러용. 요청이 바인딩되지 않은 곳(보안 필터 핸들러 등)은 요청을 직접 넘기는 쪽을 쓴다.
     */
    @Throws(IOException::class)
    fun redirect(res: HttpServletResponse, path: String) {
        val attrs = RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes
            ?: throw IllegalStateException("no current request bound; use redirect(req, res, path)")
        redirect(attrs.request, res, path)
    }

    /**
     * htmx 요청이면 `HX-Redirect` 헤더 + 200 (htmx 가 전체 페이지 이동을 한다),
     * 아니면 302 리다이렉트.
     */
    @Throws(IOException::class)
    fun redirect(req: HttpServletRequest, res: HttpServletResponse, path: String) {
        if (isHtmx(req)) {
            res.status = HttpServletResponse.SC_OK
            res.setHeader(HX_REDIRECT, path)
        } else {
            res.sendRedirect(path)
        }
    }
}
