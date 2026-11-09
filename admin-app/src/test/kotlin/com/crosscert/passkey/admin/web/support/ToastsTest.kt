package com.crosscert.passkey.admin.web.support

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.databind.ObjectMapper

class ToastsTest {

    @Test
    fun headerIsAsciiJsonAndLastCallWins() {
        val res = MockHttpServletResponse()
        Toasts.success(res, "first")
        Toasts.error(res, "저장 실패 \"x\"")

        val header = res.getHeader("HX-Trigger")
        assertThat(res.getHeaders("HX-Trigger")).hasSize(1)
        // HTTP 헤더는 Latin-1 — 한글은 \\uXXXX 로 이스케이프돼야 한다.
        assertThat(header).matches("\\A\\p{ASCII}*\\z")
        val toast = ObjectMapper().readTree(header).get("toast")
        assertThat(toast.get("kind").asString()).isEqualTo("error")
        assertThat(toast.get("message").asString()).isEqualTo("저장 실패 \"x\"")
    }

    @Test
    fun errorToastCarriesDetailAndTraceId() {
        val res = MockHttpServletResponse()
        Toasts.error(res, "요청을 처리하지 못했습니다", "HTTP 409 · [T001] 중복", "abc123")
        val toast = ObjectMapper().readTree(res.getHeader("HX-Trigger")).get("toast")
        assertThat(toast.get("message").asString()).isEqualTo("요청을 처리하지 못했습니다")
        assertThat(toast.get("detail").asString()).isEqualTo("HTTP 409 · [T001] 중복")
        assertThat(toast.get("traceId").asString()).isEqualTo("abc123")

        val plain = MockHttpServletResponse()
        Toasts.error(plain, "x")
        val t2 = ObjectMapper().readTree(plain.getHeader("HX-Trigger")).get("toast")
        assertThat(t2.has("detail")).isFalse()
        assertThat(t2.has("traceId")).isFalse()
    }

    @Test
    fun threeArgErrorHasDetailAndNoTraceId() {
        val res = MockHttpServletResponse()
        Toasts.error(res, "실패", "HTTP 500")
        val toast = ObjectMapper().readTree(res.getHeader("HX-Trigger")).get("toast")
        assertThat(toast.get("detail").asString()).isEqualTo("HTTP 500")
        assertThat(toast.has("traceId")).isFalse()
    }

    @Test
    fun titledErrorCarriesTitleMessageDetailAndTraceId() {
        val res = MockHttpServletResponse()
        Toasts.errorTitled(res, "RP를 전환하지 못했습니다", "서버가 잠시 응답하지 않습니다.", "HTTP 503", "tr-1")
        val toast = ObjectMapper().readTree(res.getHeader("HX-Trigger")).get("toast")
        assertThat(toast.get("kind").asString()).isEqualTo("error")
        assertThat(toast.get("title").asString()).isEqualTo("RP를 전환하지 못했습니다")
        assertThat(toast.get("message").asString()).isEqualTo("서버가 잠시 응답하지 않습니다.")
        assertThat(toast.get("detail").asString()).isEqualTo("HTTP 503")
        assertThat(toast.get("traceId").asString()).isEqualTo("tr-1")
        val plain = MockHttpServletResponse()
        Toasts.error(plain, "실패")
        assertThat(ObjectMapper().readTree(plain.getHeader("HX-Trigger")).get("toast").has("title")).isFalse()
    }

    @Test
    fun htmxRedirectVsPlainRedirect() {
        val htmx = MockHttpServletRequest()
        htmx.addHeader("HX-Request", "true")
        val r1 = MockHttpServletResponse()
        Htmx.redirect(htmx, r1, "/admin")
        assertThat(r1.status).isEqualTo(200)
        assertThat(r1.getHeader("HX-Redirect")).isEqualTo("/admin")

        val r2 = MockHttpServletResponse()
        Htmx.redirect(MockHttpServletRequest(), r2, "/admin")
        assertThat(r2.status).isEqualTo(302)
        assertThat(r2.redirectedUrl).isEqualTo("/admin")
    }
}
