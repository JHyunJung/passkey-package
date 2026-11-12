package com.crosscert.passkey.app.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * passkey-app 응답 보안 헤더. passkey-app 은 RP 서버가 호출하는 JSON API 이고 인증은 [ApiKeyAuthFilter] 가 맡으므로
 * Spring Security 체인을 들이지 않고(필터 순서·actuator 노출 등 동작이 바뀐다) 헤더만 붙인다.
 * HTML 을 내지 않으므로 CSP 는 모든 로드를 막는다. HSTS 는 HTTPS 요청(프록시 뒤면 native forward-headers 가 복원한
 * isSecure)에만 — 평문 http 응답의 HSTS 는 무시되고 dev 에서 혼란만 준다.
 *
 * 헤더는 체인 진입 **전에** 쓴다 — [RateLimitFilter]·[ApiKeyAuthFilter] 가 체인을 잇지 않고 429/401 을 써도 실린다.
 * 그래서 순서가 가장 앞이다(RateLimitFilter 는 HIGHEST_PRECEDENCE + 2).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class SecurityHeadersFilter : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        response.setHeader("X-Content-Type-Options", "nosniff")
        response.setHeader("X-Frame-Options", "DENY")
        response.setHeader("Referrer-Policy", "no-referrer")
        response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
        if (request.isSecure) {
            response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
        }
        chain.doFilter(request, response)
    }
}
