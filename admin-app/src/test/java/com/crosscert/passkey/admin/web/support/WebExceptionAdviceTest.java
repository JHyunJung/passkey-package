package com.crosscert.passkey.admin.web.support;

import com.crosscert.passkey.admin.auth.AdminUserDetails;
import com.crosscert.passkey.admin.web.AdminWebSliceTest;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/** WebExceptionAdvice(htmx 토스트 / 오류 페이지)와 ViewModelAdvice(me·profile) — admin.web 패키지 더미 컨트롤러로 확인. */
@AdminWebSliceTest
@Import(WebExceptionAdviceTest.DummyPageController.class)
class WebExceptionAdviceTest {

    @Autowired MockMvc mvc;
    @Autowired AdminUserRepository users;

    @Controller
    static class DummyPageController {
        @GetMapping("/admin/_/test/business")
        String business() { throw new BusinessException(ErrorCode.TENANT_NOT_FOUND); }

        @GetMapping("/admin/_/test/boom")
        String boom() { throw new IllegalStateException("boom"); }

        @GetMapping("/admin/_/test/nonstandard")
        String nonStandard() {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatusCode.valueOf(499), "client closed");
        }

        @GetMapping("/admin/_/test/uuid/{id}")
        String uuid(@org.springframework.web.bind.annotation.PathVariable UUID id) { return "admin/error"; }

        @GetMapping("/admin/_/test/denied")
        String denied() { throw new org.springframework.security.access.AccessDeniedException("platform operator only"); }

        @GetMapping("/admin/_/test/conflict")
        String conflict() {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "이미 처리된 요청입니다");
        }

        @GetMapping("/admin/_/test/model")
        String model() { return "admin/error"; } // 아무 템플릿이나 — 모델만 본다
    }

    private static Authentication operator() {
        AdminUserDetails p = new AdminUserDetails(UUID.randomUUID(), "alice@example.com", "x",
                "PLATFORM_OPERATOR", Set.of(), true, null, Clock.systemUTC());
        return new UsernamePasswordAuthenticationToken(p, "x", p.getAuthorities());
    }

    @Test
    void htmxErrorKeepsRealStatusWithToastAndEmptyBody() throws Exception {
        // 규칙 A: 실제 오류 상태(404)를 유지하고 토스트는 HX-Trigger 로, 본문은 비운다.
        MvcResult r = mvc.perform(get("/admin/_/test/business").header("HX-Request", "true").with(authentication(operator())))
            .andExpect(status().isNotFound())
            .andExpect(header().string("HX-Trigger", containsString("\"kind\":\"error\"")))
            .andExpect(content().string(""))
            .andReturn();
        JsonNode toast = new ObjectMapper().readTree(r.getResponse().getHeader("HX-Trigger")).get("toast");
        assertThat(toast.get("message").asString()).isEqualTo("찾을 수 없습니다. 삭제되었거나 주소가 바뀌었을 수 있습니다.");
        // 원문은 본문 메시지가 아니라 detail("자세히")로만 간다(errorToast.ts).
        assertThat(toast.get("message").asString()).doesNotContain("HTTP");
        assertThat(toast.get("detail").asString()).startsWith("HTTP 404 · [").contains(ErrorCode.TENANT_NOT_FOUND.getCode());
    }

    @Test
    void htmxUnexpectedErrorIs500WithoutLeakingMessage() throws Exception {
        MvcResult r = mvc.perform(get("/admin/_/test/boom").header("HX-Request", "true").with(authentication(operator())))
            .andExpect(status().isInternalServerError())
            .andExpect(content().string(""))
            .andReturn();
        JsonNode toast = new ObjectMapper().readTree(r.getResponse().getHeader("HX-Trigger")).get("toast");
        assertThat(toast.get("detail").asString()).isEqualTo("HTTP 500");
        assertThat(r.getResponse().getHeader("HX-Trigger")).doesNotContain("boom");
    }

    @Test
    void pageErrorRendersErrorTemplateWithStatus() throws Exception {
        mvc.perform(get("/admin/_/test/business").with(authentication(operator())))
            .andExpect(status().isNotFound())
            .andExpect(view().name("admin/error"))
            .andExpect(content().string(containsString("찾을 수 없습니다")))
            .andExpect(content().string(containsString("삭제되었거나 주소가 바뀌었을 수 있습니다.")));
    }

    @Test
    void unexpectedErrorIs500Page() throws Exception {
        mvc.perform(get("/admin/_/test/boom").with(authentication(operator())))
            .andExpect(status().isInternalServerError())
            .andExpect(view().name("admin/error"))
            .andExpect(content().string(containsString("서버가 잠시 응답하지 않습니다")));
    }

    @Test
    void nonStandardStatusCodeIsKept() throws Exception {
        mvc.perform(get("/admin/_/test/nonstandard").with(authentication(operator())))
            .andExpect(status().is(499))
            .andExpect(view().name("admin/error"));
    }

    @Test
    void modelHasMeAndProfile() throws Exception {
        AdminUser u = new AdminUser("alice@example.com", "x", "PLATFORM_OPERATOR");
        when(users.findByEmail(anyString())).thenReturn(Optional.of(u));
        mvc.perform(get("/admin/_/test/model").with(authentication(operator())))
            .andExpect(status().isOk())
            .andExpect(model().attribute("me", instanceOf(CurrentAdmin.class)))
            .andExpect(model().attribute("profile", instanceOf(com.crosscert.passkey.admin.config.ProfileController.ProfileView.class)));
    }

    @Test
    void badUuidPathIs400WithoutFrameworkMessage() throws Exception {
        // 경로의 UUID 변환 실패(MethodArgumentTypeMismatchException): 400, 원문("Failed to convert…", "Invalid UUID string")은 토스트·오류 화면 어디에도 없다.
        MvcResult r = mvc.perform(get("/admin/_/test/uuid/not-a-uuid").header("HX-Request", "true").with(authentication(operator())))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(""))
            .andReturn();
        String trig = r.getResponse().getHeader("HX-Trigger");
        JsonNode toast = new ObjectMapper().readTree(trig).get("toast");
        assertThat(toast.get("detail").asString()).isEqualTo("HTTP 400");
        assertThat(trig).doesNotContain("convert").doesNotContain("UUID").doesNotContain("not-a-uuid");
        String page = mvc.perform(get("/admin/_/test/uuid/not-a-uuid").with(authentication(operator())))
            .andExpect(status().isBadRequest())
            .andExpect(view().name("admin/error"))
            .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(page).doesNotContain("convert").doesNotContain("Invalid UUID").doesNotContain("not-a-uuid");
    }

    @Test
    void rawDetailOnlyCarriesBusinessMessagesAndStatusReasons() throws Exception {
        // 권한 거부의 원문(내부 사유)은 싣지 않는다
        MvcResult d = mvc.perform(get("/admin/_/test/denied").header("HX-Request", "true").with(authentication(operator())))
            .andExpect(status().isForbidden()).andReturn();
        assertThat(new ObjectMapper().readTree(d.getResponse().getHeader("HX-Trigger")).get("toast").get("detail").asString()).isEqualTo("HTTP 403");
        // 4xx ResponseStatusException 은 reason 만(“409 CONFLICT \"…\"” 원문 아님)
        MvcResult c = mvc.perform(get("/admin/_/test/conflict").header("HX-Request", "true").with(authentication(operator())))
            .andExpect(status().isConflict()).andReturn();
        assertThat(new ObjectMapper().readTree(c.getResponse().getHeader("HX-Trigger")).get("toast").get("detail").asString())
            .isEqualTo("HTTP 409 이미 처리된 요청입니다");
    }
}
