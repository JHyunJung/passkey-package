package com.crosscert.passkey.app.security

import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/**
 * passkey-app 은 Spring Security 를 쓰지 않는 JSON API 라 기본 보안 헤더가 하나도 없었다.
 * 헤더는 체인 진입 전에 쓰므로 rate-limit 429·API key 401 처럼 체인이 끊긴 응답에도 실린다.
 */
class SecurityHeadersFilterTest {

    private fun run(secure: Boolean, chain: FilterChain = FilterChain { _, _ -> }): MockHttpServletResponse {
        val req = MockHttpServletRequest("POST", "/v1/authentication/start")
        req.isSecure = secure
        val res = MockHttpServletResponse()
        SecurityHeadersFilter().doFilter(req, res, chain)
        return res
    }

    @Test
    fun plainHttp_getsBaselineHeaders_butNoHsts() {
        val res = run(secure = false)
        assertThat(res.getHeader("X-Content-Type-Options")).isEqualTo("nosniff")
        assertThat(res.getHeader("X-Frame-Options")).isEqualTo("DENY")
        assertThat(res.getHeader("Referrer-Policy")).isEqualTo("no-referrer")
        assertThat(res.getHeader("Content-Security-Policy")).isEqualTo("default-src 'none'; frame-ancestors 'none'")
        assertThat(res.getHeader("Strict-Transport-Security")).isNull()
    }

    @Test
    fun secureRequest_getsHsts() {
        assertThat(run(secure = true).getHeader("Strict-Transport-Security")).isEqualTo("max-age=31536000; includeSubDomains")
    }

    @Test
    fun headersAreWrittenBeforeDownstreamShortCircuits() {
        // 하위 필터가 체인을 잇지 않고 바로 응답을 써도(429/401) 헤더가 이미 있어야 한다.
        val res = run(secure = false, chain = FilterChain { _, r -> (r as MockHttpServletResponse).status = 429 })
        assertThat(res.status).isEqualTo(429)
        assertThat(res.getHeader("X-Content-Type-Options")).isEqualTo("nosniff")
    }

    @Test
    fun runsBeforeRateLimitAndApiKeyFilters() {
        fun order(c: Class<*>) = c.getAnnotation(Order::class.java).value
        assertThat(order(SecurityHeadersFilter::class.java)).isEqualTo(Ordered.HIGHEST_PRECEDENCE)
        assertThat(order(SecurityHeadersFilter::class.java)).isLessThan(order(RateLimitFilter::class.java))
        assertThat(order(SecurityHeadersFilter::class.java)).isLessThan(order(ApiKeyAuthFilter::class.java))
    }
}
