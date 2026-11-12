package com.crosscert.passkey.admin.config

import com.crosscert.passkey.admin.auth.MfaController
import com.crosscert.passkey.admin.web.AdminWebSliceTest
import com.crosscert.passkey.admin.web.auth.LoginPageController
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get

/**
 * admin 콘솔 응답 보안 헤더. CSP 는 실제 화면 구성에 맞춘 값이다 — 인라인 `<script>`·외부 리소스가 0건이고
 * Alpine·htmx 가 속성 식을 평가하느라 'unsafe-eval', 인라인 style 속성·htmx 표시기 스타일 때문에 style 'unsafe-inline',
 * MFA QR 이 data: SVG 라 img data: 가 필요하다. HSTS 는 Spring Security 기본값으로 HTTPS 요청(프록시 뒤면
 * native forward-headers 가 복원한 isSecure)에만 붙는다.
 */
@AdminWebSliceTest(controllers = [LoginPageController::class])
class AdminSecurityHeadersTest {

    @Autowired lateinit var mvc: MockMvc
    @MockitoBean lateinit var mfa: MfaController
    @MockitoBean lateinit var profiles: ProfileController

    @Test
    fun loginPage_carriesCspAndBaselineHeaders_withoutHstsOnPlainHttp() {
        val r = mvc.perform(get("/admin/login")).andReturn().response
        assertThat(r.getHeader("Content-Security-Policy")).isEqualTo(
            "default-src 'self'; script-src 'self' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; " +
                "img-src 'self' data:; font-src 'self'; connect-src 'self'; object-src 'none'; " +
                "base-uri 'self'; form-action 'self'; frame-ancestors 'none'",
        )
        assertThat(r.getHeader("X-Content-Type-Options")).isEqualTo("nosniff")
        assertThat(r.getHeader("X-Frame-Options")).isEqualTo("DENY")
        assertThat(r.getHeader("Referrer-Policy")).isEqualTo("same-origin")
        assertThat(r.getHeader("Strict-Transport-Security")).isNull()
    }

    @Test
    fun secureRequest_getsHsts() {
        val r = mvc.perform(get("/admin/login").secure(true)).andReturn().response
        assertThat(r.getHeader("Strict-Transport-Security")).isEqualTo("max-age=31536000 ; includeSubDomains")
    }
}
