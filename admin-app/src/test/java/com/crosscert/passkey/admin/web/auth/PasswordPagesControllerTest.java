package com.crosscert.passkey.admin.web.auth;

import com.crosscert.passkey.admin.operator.PasswordResetController;
import com.crosscert.passkey.admin.web.AdminWebSliceTest;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
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
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 비밀번호 찾기·재설정 화면(← ForgotPasswordPage.test.tsx, ResetPasswordPage.test.tsx). */
@AdminWebSliceTest(controllers = PasswordPagesController.class)
@ExtendWith(OutputCaptureExtension.class)
class PasswordPagesControllerTest {

    private static final String TOKEN = "rst_SECRET-TOKEN-VALUE-123";
    private static final String GOOD_PW = "correct horse battery";

    @Autowired MockMvc mvc;
    @MockBean PasswordResetController reset;

    private void okRequest() {
        doReturn(ResponseEntity.ok(Map.of("ok", true))).when(reset).request(any());
    }

    private void okConfirm() {
        doReturn(ResponseEntity.ok(Map.of("reset", true))).when(reset).confirm(any());
    }

    private Document page(String url) throws Exception {
        return Jsoup.parse(mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private MvcResult postForgot(String email, boolean htmx) throws Exception {
        var rb = post("/admin/forgot-password").with(csrf()).param("email", email);
        if (htmx) rb.header("HX-Request", "true");
        return mvc.perform(rb).andReturn();
    }

    private MvcResult postReset(String token, String pw, String pw2) throws Exception {
        var rb = post("/admin/reset-password").with(csrf()).header("HX-Request", "true");
        if (token != null) rb.param("token", token);
        if (pw != null) rb.param("password", pw);
        if (pw2 != null) rb.param("password2", pw2);
        return mvc.perform(rb).andReturn();
    }

    /** Alpine x-if 템플릿 안(Jsoup 은 template 내용도 자식으로 읽는다)을 뺀 화면 문구. */
    private static String shown(Document d) {
        Document c = d.clone();
        c.select("template").remove();
        return c.text();
    }

    /** 이 컨트롤러가 남긴 로그 줄만(MockMvc 가 요청 파라미터를 DEBUG 로 찍는 줄은 제외). */
    private static String ourLogs(CapturedOutput out) {
        return java.util.Arrays.stream(out.getAll().split("\n"))
                .filter(l -> l.contains("PasswordPagesController")).collect(java.util.stream.Collectors.joining("\n"));
    }

    private static Document doc(MvcResult r) throws Exception {
        return Jsoup.parse(r.getResponse().getContentAsString());
    }

    // ── 비밀번호 찾기 ──

    @Test
    void forgotPageUsesAuthShell() throws Exception {
        Document d = page("/admin/forgot-password");
        assertThat(d.select(".app, .sidebar, .topbar")).isEmpty();
        assertThat(d.selectFirst("img.auth__logo[alt=CROSSCERT]")).isNotNull();
        assertThat(d.select(".auth .auth__col:not(.auth__col--wide) > .auth-card")).hasSize(1);
        assertThat(d.selectFirst("h1").text()).isEqualTo("비밀번호 재설정");
        assertThat(d.selectFirst(".card__sub").text())
                .isEqualTo("가입한 이메일을 입력하면 재설정 링크를 보내드립니다. 링크는 일정 시간 후 만료됩니다.");
        var form = d.selectFirst("form[hx-post=/admin/forgot-password]");
        assertThat(form.select("input#forgot-email[name=email][type=email]")).hasSize(1);
        assertThat(form.selectFirst("input#forgot-email").hasAttr("autofocus")).isTrue();
        assertThat(form.select("input[type=hidden][name=_csrf]").attr("value")).isNotBlank();
        assertThat(form.selectFirst("button[type=submit]").text()).isEqualTo("재설정 링크 보내기");
        // 카드 밖 로그인 링크
        var back = d.selectFirst("a:containsOwn(← 로그인으로)");
        assertThat(back.attr("href")).isEqualTo("/admin");
        assertThat(back.parents().hasClass("auth-card")).isFalse();
    }

    @Test
    void forgotAlwaysSameMessage() throws Exception {
        okRequest();
        MvcResult known = postForgot("alice@example.com", true);
        // 존재하지 않는 이메일 · 서버가 4xx 성격 예외를 던지는 경우도 같은 응답
        doThrow(new IllegalArgumentException("whatever")).when(reset).request(any());
        MvcResult weird = postForgot("nobody@example.com", true);
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "x")).when(reset).request(any());
        MvcResult weird2 = postForgot("other@example.com", true);

        for (MvcResult r : new MvcResult[] {known, weird, weird2}) {
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            Document d = doc(r);
            assertThat(d.text()).contains("메일을 확인하세요")
                    .contains("해당 이메일로 가입된 계정이 있으면 재설정 링크가 발송됩니다.")
                    .contains("메일이 오지 않으면 스팸함을 확인하거나 플랫폼 운영자에게 문의하세요.");
            assertThat(d.select("form")).isEmpty();
        }
        // 이메일 값이 응답에 섞이지 않으므로 본문이 완전히 같다(CSRF 토큰 등 변하는 값이 없는 완료 카드)
        assertThat(weird.getResponse().getContentAsString()).isEqualTo(known.getResponse().getContentAsString());
        assertThat(weird2.getResponse().getContentAsString()).isEqualTo(known.getResponse().getContentAsString());
    }

    @Test
    void forgotSentCardKeepsShortSubtitleAndDropsExpiryNote() throws Exception {
        okRequest();
        Document d = doc(postForgot("a@b.co", true));
        assertThat(d.selectFirst(".card__sub").text()).isEqualTo("가입한 이메일을 입력하면 재설정 링크를 보내드립니다.");
    }

    @Test
    void forgotHtmxReturnsFragmentOnlyAndPlainPostReturnsFullPage() throws Exception {
        okRequest();
        String frag = postForgot("a@b.co", true).getResponse().getContentAsString();
        assertThat(frag).contains("id=\"auth-card\"").doesNotContain("<html").doesNotContain("<body");
        String full = postForgot("a@b.co", false).getResponse().getContentAsString();
        assertThat(full).contains("<html").contains("id=\"auth-card\"");
    }

    @Test
    void forgotTrimsEmailAndCallsRestBean() throws Exception {
        okRequest();
        postForgot("  alice@example.com  ", true);
        verify(reset).request(argThat(b -> "alice@example.com".equals(b.email())));
    }

    @Test
    void forgotBlankEmailIsInlineErrorAndNotSent() throws Exception {
        MvcResult r = postForgot("   ", true);
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
        Document d = doc(r);
        assertThat(d.selectFirst("#forgot-email-error").text()).isEqualTo("이메일을 입력하세요.");
        assertThat(d.select("form")).hasSize(1);
        verify(reset, never()).request(any());
    }

    @Test
    void forgotServerFailureIsNotShownAsSent(CapturedOutput out) throws Exception {
        doThrow(new IllegalStateException("db down for alice@example.com")).when(reset).request(any());
        MvcResult r = postForgot("alice@example.com", true);
        assertThat(r.getResponse().getStatus()).isEqualTo(500);
        assertThat(r.getResponse().getContentAsString()).isEmpty();
        assertThat(ourLogs(out)).contains("IllegalStateException").doesNotContain("alice@example.com");
    }

    @Test
    void forgotFormHasClientSideRetryWiring() throws Exception {
        var form = page("/admin/forgot-password").selectFirst("form");
        assertThat(form.attr("x-data")).isEqualTo("forgotForm");
        assertThat(form.attr("hx-sync")).isEqualTo("this:drop");
        assertThat(form.selectFirst("button[type=submit]").attr("x-bind:disabled")).isEqualTo("!canSubmit()");
    }

    // ── 비밀번호 재설정 ──

    @Test
    void resetWithoutTokenShowsInvalid() throws Exception {
        for (String token : new String[] {null, "", "  "}) {
            var rb = get("/admin/reset-password");
            if (token != null) rb.param("token", token);
            Document d = Jsoup.parse(mvc.perform(rb).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(shown(d)).contains("링크를 사용할 수 없습니다")
                    .contains("유효하지 않은 링크입니다. 만료됐거나 이미 사용된 링크일 수 있습니다.");
            assertThat(d.select("form")).isEmpty();
            assertThat(d.selectFirst("a.btn.btn--primary").attr("href")).isEqualTo("/admin/forgot-password");
            assertThat(d.selectFirst("a:containsOwn(← 로그인으로)").attr("href")).isEqualTo("/admin");
        }
        // POST 도 토큰이 없으면 REST 빈을 부르지 않는다
        MvcResult r = postReset(null, GOOD_PW, GOOD_PW);
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
        assertThat(shown(doc(r))).contains("유효하지 않은 링크입니다");
        verify(reset, never()).confirm(any());
    }

    @Test
    void resetFormCarriesTokenOnlyAsHiddenFieldAndIsNotCacheable() throws Exception {
        MvcResult r = mvc.perform(get("/admin/reset-password").param("token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andReturn();
        Document d = doc(r);
        assertThat(d.selectFirst("h1").text()).isEqualTo("새 비밀번호 설정");
        assertThat(d.selectFirst(".card__sub").text()).isEqualTo("12자 이상으로 새 비밀번호를 입력하세요.");
        var form = d.selectFirst("form[hx-post=/admin/reset-password]");
        assertThat(form.attr("hx-post")).doesNotContain("token");
        assertThat(form.select("input[type=hidden][name=token]").attr("value")).isEqualTo(TOKEN);
        assertThat(form.select("input#reset-pw[name=password][type=password][autocomplete=new-password]")).hasSize(1);
        assertThat(form.select("input#reset-pw2[name=password2][type=password]")).hasSize(1);
        assertThat(d.selectFirst("label[for=reset-pw]").text()).isEqualTo("새 비밀번호");
        assertThat(d.selectFirst("label[for=reset-pw2]").text()).isEqualTo("새 비밀번호 확인");
        assertThat(form.selectFirst("button[type=submit]").text()).isEqualTo("비밀번호 변경");
        assertThat(d.select("#reset-pw-meter")).hasSize(1);
        assertThat(d.text()).contains("12자 이상");
        assertThat(form.attr("x-data")).isEqualTo("resetForm");
        assertThat(form.selectFirst("button[type=submit]").attr("x-bind:disabled")).isEqualTo("!canSubmit()");
        assertThat(d.select("a:containsOwn(← 로그인으로)")).hasSize(1);
    }

    @Test
    void resetShortOrMismatchedPasswordIsInlineErrorAndNotSent() throws Exception {
        MvcResult shortPw = postReset(TOKEN, "short", "short");
        assertThat(shortPw.getResponse().getStatus()).isEqualTo(422);
        assertThat(doc(shortPw).select("input#reset-pw").first().parent().text()).contains("12자 이상으로 입력하세요.");
        MvcResult mismatch = postReset(TOKEN, GOOD_PW, GOOD_PW + "x");
        assertThat(mismatch.getResponse().getStatus()).isEqualTo(422);
        assertThat(doc(mismatch).select(".field__error:not([x-show])").text()).isEqualTo("비밀번호가 일치하지 않습니다.");
        verify(reset, never()).confirm(any());
        // 다시 그린 폼은 비밀번호를 되돌려 쓰지 않는다(토큰은 다시 제출하도록 유지)
        String html = mismatch.getResponse().getContentAsString();
        assertThat(html).doesNotContain(GOOD_PW);
        assertThat(doc(mismatch).select("input[name=token]").attr("value")).isEqualTo(TOKEN);
    }

    @Test
    void resetSuccessShowsDoneCardAndCallsRestBean() throws Exception {
        okConfirm();
        MvcResult r = postReset("  " + TOKEN + " ", GOOD_PW, GOOD_PW);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        Document d = doc(r);
        assertThat(d.text()).contains("변경 완료").contains("비밀번호가 변경되었습니다. 새 비밀번호로 로그인하세요.");
        assertThat(d.selectFirst("a.btn.btn--primary").text()).isEqualTo("로그인 →");
        assertThat(d.selectFirst("a.btn.btn--primary").attr("href")).isEqualTo("/admin");
        assertThat(d.select("form, input")).isEmpty();
        assertThat(r.getResponse().getContentAsString()).doesNotContain(TOKEN).doesNotContain(GOOD_PW);
        verify(reset).confirm(argThat(b -> TOKEN.equals(b.token()) && GOOD_PW.equals(b.newPassword())));
        assertThat(r.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void resetTokenRejectionShowsExpiredCard() throws Exception {
        for (String msg : new String[] {"invalid token", "token already used", "token expired"}) {
            doThrow(new IllegalArgumentException(msg)).when(reset).confirm(any());
            MvcResult r = postReset(TOKEN, GOOD_PW, GOOD_PW);
            assertThat(r.getResponse().getStatus()).isEqualTo(422);
            Document d = doc(r);
            assertThat(shown(d)).contains("링크를 사용할 수 없습니다")
                    .contains("링크가 만료되었거나 이미 사용되었습니다. 보안을 위해 재설정 링크는 정해진 시간 안에 한 번만 쓸 수 있습니다.");
            assertThat(d.select("form")).isEmpty();
            assertThat(d.selectFirst("a.btn--primary").attr("href")).isEqualTo("/admin/forgot-password");
            assertThat(r.getResponse().getContentAsString()).doesNotContain(TOKEN);
        }
    }

    @Test
    void resetNonTokenRejectionShowsServerMessageAndKeepsForm() throws Exception {
        doThrow(new IllegalArgumentException("비밀번호 정책 위반")).when(reset).confirm(any());
        MvcResult r = postReset(TOKEN, GOOD_PW, GOOD_PW);
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
        Document d = doc(r);
        assertThat(d.select("[role=alert]:not(template [role=alert])").text()).isEqualTo("비밀번호 정책 위반");
        assertThat(d.select("form")).hasSize(1);
        assertThat(d.select("input[name=token]").attr("value")).isEqualTo(TOKEN);
    }

    @Test
    void resetServerFailureIsTransientAndDoesNotLogToken(CapturedOutput out) throws Exception {
        doThrow(new IllegalStateException("boom " + TOKEN)).when(reset).confirm(any());
        MvcResult r = postReset(TOKEN, GOOD_PW, GOOD_PW);
        assertThat(r.getResponse().getStatus()).isEqualTo(500);
        assertThat(r.getResponse().getContentAsString()).isEmpty();
        assertThat(ourLogs(out)).contains("IllegalStateException").doesNotContain(TOKEN).doesNotContain(GOOD_PW);
    }

    @Test
    void resetHtmxReturnsFragmentOnly() throws Exception {
        okConfirm();
        String frag = postReset(TOKEN, GOOD_PW, GOOD_PW).getResponse().getContentAsString();
        assertThat(frag).contains("id=\"auth-card\"").doesNotContain("<html");
    }

    @Test
    void formsPostNativelyWithoutHtmxAndNeverFallBackToGet() throws Exception {
        var f = page("/admin/forgot-password").selectFirst("form");
        assertThat(f.attr("method")).isEqualToIgnoringCase("post");
        assertThat(f.attr("action")).isEqualTo("/admin/forgot-password");
        var r = page("/admin/reset-password?token=" + TOKEN).selectFirst("form");
        assertThat(r.attr("method")).isEqualToIgnoringCase("post");
        assertThat(r.attr("action")).isEqualTo("/admin/reset-password");
        assertThat(r.attr("action")).doesNotContain("token");
    }

    @Test
    void forgotServerSideStatusErrorsAreTransientNotSent() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "x")).when(reset).request(any());
        MvcResult r = postForgot("alice@example.com", true);
        assertThat(r.getResponse().getStatus()).isEqualTo(503);
        assertThat(r.getResponse().getContentAsString()).isEmpty();
    }

    @Test
    void resetServerErrorsAreLinkedToInputs() throws Exception {
        Document d = doc(postReset(TOKEN, "short", "short"));
        var pw = d.selectFirst("input#reset-pw");
        assertThat(pw.attr("aria-describedby")).contains("reset-pw-server-error");
        assertThat(d.selectFirst("#reset-pw-server-error").text()).isNotBlank();
        Document m = doc(postReset(TOKEN, GOOD_PW, GOOD_PW + "x"));
        assertThat(m.selectFirst("input#reset-pw2").attr("aria-describedby")).contains("reset-pw2-server-error");
        assertThat(m.selectFirst("#reset-pw2-server-error").text()).isNotBlank();
    }

    // ── CSRF ──

    @Test
    void postsRequireCsrf() throws Exception {
        mvc.perform(post("/admin/forgot-password").param("email", "a@b.co")).andExpect(status().isForbidden());
        mvc.perform(post("/admin/reset-password").param("token", TOKEN).param("password", GOOD_PW).param("password2", GOOD_PW))
                .andExpect(status().isForbidden());
        verify(reset, never()).request(any());
        verify(reset, never()).confirm(any());
    }
}
