package com.crosscert.passkey.admin.web.support;

import com.crosscert.passkey.admin.auth.AdminUserDetails;
import com.crosscert.passkey.admin.web.AdminWebSliceTest;
import com.crosscert.passkey.core.api.BusinessException;
import com.crosscert.passkey.core.api.ErrorCode;
import com.crosscert.passkey.core.entity.AdminUser;
import com.crosscert.passkey.core.repository.AdminUserRepository;
import org.junit.jupiter.api.Test;
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

        @GetMapping("/admin/_/test/model")
        String model() { return "admin/error"; } // 아무 템플릿이나 — 모델만 본다
    }

    private static Authentication operator() {
        AdminUserDetails p = new AdminUserDetails(UUID.randomUUID(), "alice@example.com", "x",
                "PLATFORM_OPERATOR", Set.of(), true, null, Clock.systemUTC());
        return new UsernamePasswordAuthenticationToken(p, "x", p.getAuthorities());
    }

    @Test
    void htmxErrorBecomesToastAnd204() throws Exception {
        mvc.perform(get("/admin/_/test/business").header("HX-Request", "true").with(authentication(operator())))
            .andExpect(status().isNoContent())
            .andExpect(header().string("HX-Trigger", containsString("\"kind\":\"error\"")));
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
}
