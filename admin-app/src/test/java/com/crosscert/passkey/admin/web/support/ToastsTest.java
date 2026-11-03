package com.crosscert.passkey.admin.web.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ToastsTest {

    @Test
    void headerIsAsciiJsonAndLastCallWins() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        Toasts.success(res, "first");
        Toasts.error(res, "저장 실패 \"x\"");

        String header = res.getHeader("HX-Trigger");
        assertThat(res.getHeaders("HX-Trigger")).hasSize(1);
        // HTTP 헤더는 Latin-1 — 한글은 \\uXXXX 로 이스케이프돼야 한다.
        assertThat(header).matches("\\A\\p{ASCII}*\\z");
        JsonNode toast = new ObjectMapper().readTree(header).get("toast");
        assertThat(toast.get("kind").asText()).isEqualTo("error");
        assertThat(toast.get("message").asText()).isEqualTo("저장 실패 \"x\"");
    }

    @Test
    void errorToastCarriesDetailAndTraceId() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        Toasts.error(res, "요청을 처리하지 못했습니다", "HTTP 409 · [T001] 중복", "abc123");
        JsonNode toast = new ObjectMapper().readTree(res.getHeader("HX-Trigger")).get("toast");
        assertThat(toast.get("message").asText()).isEqualTo("요청을 처리하지 못했습니다");
        assertThat(toast.get("detail").asText()).isEqualTo("HTTP 409 · [T001] 중복");
        assertThat(toast.get("traceId").asText()).isEqualTo("abc123");

        MockHttpServletResponse plain = new MockHttpServletResponse();
        Toasts.error(plain, "x");
        JsonNode t2 = new ObjectMapper().readTree(plain.getHeader("HX-Trigger")).get("toast");
        assertThat(t2.has("detail")).isFalse();
        assertThat(t2.has("traceId")).isFalse();
    }

    @Test
    void threeArgErrorHasDetailAndNoTraceId() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        Toasts.error(res, "실패", "HTTP 500");
        JsonNode toast = new ObjectMapper().readTree(res.getHeader("HX-Trigger")).get("toast");
        assertThat(toast.get("detail").asText()).isEqualTo("HTTP 500");
        assertThat(toast.has("traceId")).isFalse();
    }

    @Test
    void titledErrorCarriesTitleMessageDetailAndTraceId() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        Toasts.errorTitled(res, "RP를 전환하지 못했습니다", "서버가 잠시 응답하지 않습니다.", "HTTP 503", "tr-1");
        JsonNode toast = new ObjectMapper().readTree(res.getHeader("HX-Trigger")).get("toast");
        assertThat(toast.get("kind").asText()).isEqualTo("error");
        assertThat(toast.get("title").asText()).isEqualTo("RP를 전환하지 못했습니다");
        assertThat(toast.get("message").asText()).isEqualTo("서버가 잠시 응답하지 않습니다.");
        assertThat(toast.get("detail").asText()).isEqualTo("HTTP 503");
        assertThat(toast.get("traceId").asText()).isEqualTo("tr-1");
        MockHttpServletResponse plain = new MockHttpServletResponse();
        Toasts.error(plain, "실패");
        assertThat(new ObjectMapper().readTree(plain.getHeader("HX-Trigger")).get("toast").has("title")).isFalse();
    }

    @Test
    void htmxRedirectVsPlainRedirect() throws Exception {
        MockHttpServletRequest htmx = new MockHttpServletRequest();
        htmx.addHeader("HX-Request", "true");
        MockHttpServletResponse r1 = new MockHttpServletResponse();
        Htmx.redirect(htmx, r1, "/admin");
        assertThat(r1.getStatus()).isEqualTo(200);
        assertThat(r1.getHeader("HX-Redirect")).isEqualTo("/admin");

        MockHttpServletResponse r2 = new MockHttpServletResponse();
        Htmx.redirect(new MockHttpServletRequest(), r2, "/admin");
        assertThat(r2.getStatus()).isEqualTo(302);
        assertThat(r2.getRedirectedUrl()).isEqualTo("/admin");
    }
}
