package com.crosscert.passkey.admin.web.support

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.servlet.ViewResolver

/**
 * `admin/error` 템플릿. 컨트롤러 밖(보안 필터의 AccessDeniedHandler)에서도
 * 같은 템플릿을 렌더링할 수 있도록 ViewResolver 로 직접 그리는 도우미를 둔다.
 */
object ErrorPage {

    const val VIEW = "admin/error"

    /**
     * @return 렌더링했으면 true, 템플릿 뷰를 찾지 못했으면 false(호출자가 다른 응답을 쓴다).
     *
     * title·message 가 null 이면 원 Java 처럼 상태를 정한 뒤 `Map.of` 에서 NPE 가 난다(그래서 nullable 로 받는다).
     */
    @JvmStatic
    @Throws(Exception::class)
    fun render(
        resolver: ViewResolver,
        req: HttpServletRequest,
        res: HttpServletResponse,
        status: Int,
        title: String?,
        message: String?,
    ): Boolean {
        val view = resolver.resolveViewName(VIEW, req.locale) ?: return false
        res.status = status
        view.render(java.util.Map.of<String, Any?>("status", status, "title", title, "message", message), req, res)
        return true
    }
}
