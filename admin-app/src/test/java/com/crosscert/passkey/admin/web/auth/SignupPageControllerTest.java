package com.crosscert.passkey.admin.web.auth;

import com.crosscert.passkey.admin.config.ProfileController;
import com.crosscert.passkey.admin.operator.SignupRequestController;
import com.crosscert.passkey.admin.operator.SignupRequestDto;
import com.crosscert.passkey.admin.web.AdminWebSliceTest;
import com.crosscert.passkey.core.api.ApiResponse;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 가입 요청 화면(← SignupRequestPage.test.tsx). */
@AdminWebSliceTest(controllers = SignupPageController.class)
@ExtendWith(OutputCaptureExtension.class)
class SignupPageControllerTest {

    private static final String PW = "correct horse battery";

    @Autowired MockMvc mvc;
    @MockBean SignupRequestController signup;
    @MockBean ProfileController profiles;

    @BeforeEach
    void defaults() {
        selfSignup(false);
        when(signup.request(any())).thenReturn(accepted(false));
    }

    private void selfSignup(boolean on) {
        when(profiles.profile()).thenReturn(ApiResponse.ok(new ProfileController.ProfileView(List.of("prod"), false, on)));
    }

    private static ResponseEntity<Map<String, Boolean>> accepted(boolean activated) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("accepted", true, "activated", activated));
    }

    private MockHttpServletRequestBuilder form(String email, String pw, String pw2, String reason, String role) {
        var rb = post("/admin/signup").with(csrf()).header("HX-Request", "true")
                .param("email", email).param("password", pw).param("password2", pw2);
        if (reason != null) rb.param("reason", reason);
        if (role != null) rb.param("role", role);
        return rb;
    }

    private MvcResult send(String email, String pw, String pw2, String reason, String role) throws Exception {
        return mvc.perform(form(email, pw, pw2, reason, role)).andReturn();
    }

    private static String ourLogs(CapturedOutput out) {
        return java.util.Arrays.stream(out.getAll().split("\n"))
                .filter(l -> l.contains("SignupPageController")).collect(java.util.stream.Collectors.joining("\n"));
    }

    private static Document doc(MvcResult r) throws Exception {
        return Jsoup.parse(r.getResponse().getContentAsString());
    }

    @Test
    void signupPageUsesWideAuthShell() throws Exception {
        Document d = Jsoup.parse(mvc.perform(get("/admin/signup")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(d.select(".app, .sidebar, .topbar")).isEmpty();
        assertThat(d.select(".auth .auth__col.auth__col--wide > img.auth__logo[alt=CROSSCERT]")).hasSize(1);
        assertThat(d.selectFirst("h1").text()).isEqualTo("관리자 계정 가입 요청");
        assertThat(d.selectFirst(".card__sub").text())
                .isEqualTo("플랫폼 운영자가 승인하면 계정이 활성화됩니다. 역할과 담당 tenant 는 승인 시 지정됩니다.");
        var f = d.selectFirst("form[hx-post=/admin/signup]");
        assertThat(f.select("input#signup-email[name=email][type=email]")).hasSize(1);
        assertThat(f.select("input#signup-pw[name=password][type=password][maxlength=128]")).hasSize(1);
        assertThat(f.select("input#signup-pw2[name=password2][type=password][maxlength=128]")).hasSize(1);
        assertThat(f.select("textarea#signup-reason[name=reason][maxlength=500]")).hasSize(1);
        assertThat(f.select("input[type=hidden][name=_csrf]").attr("value")).isNotBlank();
        assertThat(f.selectFirst("label[for=signup-reason]").text()).isEqualTo("요청 사유 (선택)");
        assertThat(f.text()).contains("12–128자");
        assertThat(f.selectFirst("button[type=submit]").text()).isEqualTo("가입 요청 보내기");
        assertThat(f.attr("x-data")).isEqualTo("signupForm");
        assertThat(f.selectFirst("button[type=submit]").attr("x-bind:disabled")).isEqualTo("!canSubmit()");
        // 카드 밖 로그인 링크
        var back = d.selectFirst("a:containsOwn(← 로그인으로)");
        assertThat(back.attr("href")).isEqualTo("/admin");
        assertThat(back.parents().hasClass("auth-card")).isFalse();
    }

    @Test
    void roleChoicesOnlyWhenSelfSignupEnabled() throws Exception {
        Document off = Jsoup.parse(mvc.perform(get("/admin/signup")).andReturn().getResponse().getContentAsString());
        assertThat(off.select("[role=radiogroup]")).isEmpty();
        assertThat(off.text()).doesNotContain("Platform Operator");
        selfSignup(true);
        Document on = Jsoup.parse(mvc.perform(get("/admin/signup")).andReturn().getResponse().getContentAsString());
        assertThat(on.select("[role=radiogroup][aria-label=역할] [role=radio]")).hasSize(2);
        assertThat(on.text()).contains("승인 후 사용").contains("운영자가 승인하며 역할·tenant 를 지정")
                .contains("Platform Operator").contains("승인 없이 바로 사용 (QA 전용)");
        assertThat(on.selectFirst("input[type=hidden][name=role]").attr("value")).isEqualTo("RP_ADMIN");
    }

    @Test
    void signupValidationErrorsInline(CapturedOutput out) throws Exception {
        MvcResult bad = send("not-an-email", "short", "different", "x".repeat(501), null);
        assertThat(bad.getResponse().getStatus()).isEqualTo(422);
        Document d = doc(bad);
        assertThat(d.select("form")).hasSize(1);
        assertThat(d.select("input#signup-email").first().parent().select(".field__error:not([x-show])").text())
                .isEqualTo("이메일 형식이 올바르지 않습니다. 예: you@company.com");
        assertThat(d.select("input#signup-pw").first().parent().select(".field__error:not([x-show])").text())
                .isEqualTo("5자 입력됨 · 12–128자로 입력하세요");
        assertThat(d.select("textarea#signup-reason").first().parent().select(".field__error:not([x-show])").text())
                .isEqualTo("요청 사유는 500자 이하로 입력하세요.");
        // 비밀번호 길이가 맞으면 확인란 불일치가 따로 나온다
        MvcResult mismatch = send("a@b.co", PW, PW + "x", null, null);
        assertThat(mismatch.getResponse().getStatus()).isEqualTo(422);
        assertThat(doc(mismatch).select("input#signup-pw2").first().parent().select(".field__error:not([x-show])").text())
                .isEqualTo("비밀번호가 일치하지 않습니다.");
        verify(signup, never()).request(any());
        // 입력한 이메일·사유는 되살리고 비밀번호는 되돌려 쓰지 않는다
        assertThat(bad.getResponse().getContentAsString()).doesNotContain("short").doesNotContain("different");
        assertThat(doc(bad).selectFirst("form").attr("data-email")).isEqualTo("not-an-email");
        assertThat(ourLogs(out)).doesNotContain(PW);
    }

    @Test
    void emailWithoutDotIsRejectedLikeTheClientRegex() throws Exception {
        MvcResult r = send("a@b", PW, PW, null, null);
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
        verify(signup, never()).request(any());
    }

    @Test
    void passwordOver128IsRejected() throws Exception {
        String pw = "p".repeat(129);
        MvcResult r = send("a@b.co", pw, pw, null, null);
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
        assertThat(doc(r).select("input#signup-pw").first().parent().select(".field__error:not([x-show])").text())
                .isEqualTo("129자 입력됨 · 12–128자로 입력하세요");
    }

    @Test
    void pendingCardOnAccepted() throws Exception {
        MvcResult r = send("Alice@Example.com ", PW, PW, "  QA 팀  ", null);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        Document d = doc(r);
        assertThat(d.selectFirst("h1").text()).isEqualTo("요청이 접수되었습니다");
        assertThat(d.selectFirst(".card__sub").text()).isEqualTo("운영자 승인 후 이메일로 안내됩니다. 승인 전에는 로그인할 수 없습니다.");
        assertThat(d.selectFirst("img[alt=\"\"]").attr("src")).isEqualTo("/admin/img/signup-sent.webp");
        assertThat(d.selectFirst("a.btn").text()).isEqualTo("로그인으로 돌아가기");
        assertThat(d.selectFirst("a.btn").attr("href")).isEqualTo("/admin");
        assertThat(d.select("form")).isEmpty();
        // 완료 상태에는 카드 밖 로그인 링크가 없다
        assertThat(d.select("a:containsOwn(← 로그인으로)")).isEmpty();
        assertThat(d.selectFirst(".auth-card").attr("style")).contains("align-items: center").contains("text-align: center");
        // 이메일 소문자·trim, 사유 trim
        verify(signup).request(argThat(b -> "alice@example.com".equals(b.email()) && PW.equals(b.password())
                && "QA 팀".equals(b.reason()) && b.role() == null));
    }

    @Test
    void blankReasonIsSentAsNull() throws Exception {
        send("a@b.co", PW, PW, "   ", null);
        verify(signup).request(argThat(b -> b.reason() == null));
    }

    @Test
    void activatedCardWhenServerActivatedAccount() throws Exception {
        selfSignup(true);
        when(signup.request(any())).thenReturn(accepted(true));
        MvcResult r = send("a@b.co", PW, PW, null, "PLATFORM_OPERATOR");
        Document d = doc(r);
        assertThat(d.selectFirst("h1").text()).isEqualTo("계정이 만들어졌습니다");
        assertThat(d.selectFirst(".card__sub").text()).isEqualTo("Platform Operator 계정이 활성화되었습니다. 바로 로그인할 수 있습니다.");
        assertThat(d.selectFirst("a.btn").text()).isEqualTo("로그인하기");
        assertThat(d.selectFirst("img[alt=\"\"]").attr("src")).isEqualTo("/admin/img/signup-sent.webp");
        verify(signup).request(argThat(b -> "PLATFORM_OPERATOR".equals(b.role())));
    }

    @Test
    void defaultRoleChoiceSendsNoRoleAndShowsPending() throws Exception {
        selfSignup(true);
        MvcResult r = send("a@b.co", PW, PW, null, "RP_ADMIN");
        assertThat(doc(r).selectFirst("h1").text()).isEqualTo("요청이 접수되었습니다");
        verify(signup).request(argThat(b -> b.role() == null));
    }

    @Test
    void platformOperatorRoleIgnoredWhenSelfSignupIsOff() throws Exception {
        selfSignup(false);
        send("a@b.co", PW, PW, null, "PLATFORM_OPERATOR");
        verify(signup).request(argThat(b -> b.role() == null));
    }

    @Test
    void clientErrorFromServiceStillShowsAcceptedNotice() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "x")).when(signup).request(any());
        assertThat(doc(send("a@b.co", PW, PW, null, null)).selectFirst("h1").text()).isEqualTo("요청이 접수되었습니다");
        doThrow(new IllegalArgumentException("x")).when(signup).request(any());
        assertThat(doc(send("a@b.co", PW, PW, null, null)).selectFirst("h1").text()).isEqualTo("요청이 접수되었습니다");
    }

    @Test
    void serverFailureIsNotShownAsAccepted(CapturedOutput out) throws Exception {
        doThrow(new IllegalStateException("db down for a@b.co " + PW)).when(signup).request(any());
        MvcResult r = send("a@b.co", PW, PW, null, null);
        assertThat(r.getResponse().getStatus()).isEqualTo(500);
        assertThat(r.getResponse().getContentAsString()).isEmpty();
        assertThat(ourLogs(out)).contains("IllegalStateException").doesNotContain("a@b.co").doesNotContain(PW);
    }

    @Test
    void htmxReturnsFragmentAndPlainPostReturnsFullPage() throws Exception {
        String frag = send("a@b.co", PW, PW, null, null).getResponse().getContentAsString();
        assertThat(frag).contains("id=\"auth-col\"").doesNotContain("<html");
        var plain = post("/admin/signup").with(csrf()).param("email", "a@b.co").param("password", PW).param("password2", PW);
        assertThat(mvc.perform(plain).andReturn().getResponse().getContentAsString()).contains("<html");
    }

    @Test
    void formPostsNativelyWithoutHtmx() throws Exception {
        var f = Jsoup.parse(mvc.perform(get("/admin/signup")).andReturn().getResponse().getContentAsString()).selectFirst("form");
        assertThat(f.attr("method")).isEqualToIgnoringCase("post");
        assertThat(f.attr("action")).isEqualTo("/admin/signup");
    }

    @Test
    void serverFieldErrorsAreLinkedToInputs() throws Exception {
        Document d = doc(send("bad", "short", "x", "x".repeat(501), null));
        for (String[] p : new String[][] {{"signup-email", "signup-email-server-error"}, {"signup-pw", "signup-pw-server-error"},
                {"signup-reason", "signup-reason-server-error"}}) {
            assertThat(d.selectFirst("#" + p[0]).attr("aria-describedby")).contains(p[1]);
            assertThat(d.selectFirst("#" + p[1]).text()).isNotBlank();
        }
        Document m = doc(send("a@b.co", PW, PW + "x", null, null));
        assertThat(m.selectFirst("#signup-pw2").attr("aria-describedby")).contains("signup-pw2-server-error");
        assertThat(m.selectFirst("#signup-pw2-server-error").text()).isNotBlank();
    }

    @Test
    void postsRequireCsrf() throws Exception {
        mvc.perform(post("/admin/signup").param("email", "a@b.co").param("password", PW).param("password2", PW))
                .andExpect(status().isForbidden());
        verify(signup, never()).request(any());
    }

    @Test
    void requestBodyTypeIsTheRestDto() {
        // 화면 컨트롤러는 REST DTO 를 그대로 만들어 넘긴다(필드 구성 고정).
        assertThat(SignupRequestDto.Create.class.getRecordComponents()).hasSize(4);
    }
}
