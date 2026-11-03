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
