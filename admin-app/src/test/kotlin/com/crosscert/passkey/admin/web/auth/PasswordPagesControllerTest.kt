package com.crosscert.passkey.admin.web.auth

import com.crosscert.passkey.admin.operator.PasswordResetController
import com.crosscert.passkey.admin.web.AdminWebSliceTest
import org.assertj.core.api.Assertions.assertThat
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.server.ResponseStatusException
import java.util.regex.Pattern

/**
 * 비밀번호 찾기·재설정 화면(← ForgotPasswordPage.test.tsx, ResetPasswordPage.test.tsx).
 *
 * (Kotlin 전환) 즉시 역참조하는 nullable jsoup 결과(`selectFirst`·`first`·`parent`)의 `!!` 는 원 Java 의 NPE 실패 지점과 같다.
 */
@AdminWebSliceTest(controllers = [PasswordPagesController::class])
@ExtendWith(OutputCaptureExtension::class)
class PasswordPagesControllerTest {

    companion object {
        private const val TOKEN = "rst_SECRET-TOKEN-VALUE-123"
        private const val GOOD_PW = "correct horse battery"

        /** Alpine x-if 템플릿 안(Jsoup 은 template 내용도 자식으로 읽는다)을 뺀 화면 문구. */
        private fun shown(d: Document): String {
            val c = d.clone()
            c.select("template").remove()
            return c.text()
        }

        /** 이 컨트롤러가 남긴 로그 줄만(MockMvc 가 요청 파라미터를 DEBUG 로 찍는 줄은 제외). */
        private fun ourLogs(out: CapturedOutput): String =
            java.util.Arrays.stream(Pattern.compile("\n").split(out.all))
                .filter { l -> l.contains("PasswordPagesController") }.collect(java.util.stream.Collectors.joining("\n"))

        private fun doc(r: MvcResult): Document = Jsoup.parse(r.response.contentAsString)
    }

    @Autowired lateinit var mvc: MockMvc
    @MockitoBean lateinit var reset: PasswordResetController

    private fun okRequest() {
        doReturn(ResponseEntity.ok(mapOf("ok" to true))).whenever(reset).request(anyOrNull())
    }

    private fun okConfirm() {
        doReturn(ResponseEntity.ok(mapOf("reset" to true))).whenever(reset).confirm(anyOrNull())
    }

    private fun page(url: String): Document =
        Jsoup.parse(mvc.perform(get(url)).andExpect(status().isOk()).andReturn().response.contentAsString)

    private fun postForgot(email: String, htmx: Boolean): MvcResult {
        val rb = post("/admin/forgot-password").with(csrf()).param("email", email)
        if (htmx) rb.header("HX-Request", "true")
        return mvc.perform(rb).andReturn()
    }

    private fun postReset(token: String?, pw: String?, pw2: String?): MvcResult {
        val rb = post("/admin/reset-password").with(csrf()).header("HX-Request", "true")
        if (token != null) rb.param("token", token)
        if (pw != null) rb.param("password", pw)
        if (pw2 != null) rb.param("password2", pw2)
        return mvc.perform(rb).andReturn()
    }

    // ── 비밀번호 찾기 ──

    @Test
    fun forgotPageUsesAuthShell() {
        val d = page("/admin/forgot-password")
        assertThat(d.select(".app, .sidebar, .topbar")).isEmpty()
        assertThat(d.selectFirst("img.auth__logo[alt=CROSSCERT]")).isNotNull()
        assertThat(d.select(".auth .auth__col:not(.auth__col--wide) > .auth-card")).hasSize(1)
        assertThat(d.selectFirst("h1")!!.text()).isEqualTo("비밀번호 재설정")
        assertThat(d.selectFirst(".card__sub")!!.text())
            .isEqualTo("가입한 이메일을 입력하면 재설정 링크를 보내드립니다. 링크는 일정 시간 후 만료됩니다.")
        val form = d.selectFirst("form[hx-post=/admin/forgot-password]")!!
        assertThat(form.select("input#forgot-email[name=email][type=email]")).hasSize(1)
        assertThat(form.selectFirst("input#forgot-email")!!.hasAttr("autofocus")).isTrue()
        assertThat(form.select("input[type=hidden][name=_csrf]").attr("value")).isNotBlank()
        assertThat(form.selectFirst("button[type=submit]")!!.text()).isEqualTo("재설정 링크 보내기")
        // 카드 밖 로그인 링크
        val back = d.selectFirst("a:containsOwn(← 로그인으로)")!!
        assertThat(back.attr("href")).isEqualTo("/admin")
        assertThat(back.parents().hasClass("auth-card")).isFalse()
    }

    @Test
    fun forgotAlwaysSameMessage() {
        okRequest()
        val known = postForgot("alice@example.com", true)
        // 존재하지 않는 이메일 · 서버가 4xx 성격 예외를 던지는 경우도 같은 응답
        doThrow(IllegalArgumentException("whatever")).whenever(reset).request(anyOrNull())
        val weird = postForgot("nobody@example.com", true)
        doThrow(ResponseStatusException(HttpStatus.BAD_REQUEST, "x")).whenever(reset).request(anyOrNull())
        val weird2 = postForgot("other@example.com", true)

        for (r in arrayOf(known, weird, weird2)) {
            assertThat(r.response.status).isEqualTo(200)
            val d = doc(r)
            assertThat(d.text()).contains("메일을 확인하세요")
                .contains("해당 이메일로 가입된 계정이 있으면 재설정 링크가 발송됩니다.")
                .contains("메일이 오지 않으면 스팸함을 확인하거나 플랫폼 운영자에게 문의하세요.")
            assertThat(d.select("form")).isEmpty()
        }
        // 이메일 값이 응답에 섞이지 않으므로 본문이 완전히 같다(CSRF 토큰 등 변하는 값이 없는 완료 카드)
        assertThat(weird.response.contentAsString).isEqualTo(known.response.contentAsString)
        assertThat(weird2.response.contentAsString).isEqualTo(known.response.contentAsString)
    }

    @Test
    fun forgotSentCardKeepsShortSubtitleAndDropsExpiryNote() {
        okRequest()
        val d = doc(postForgot("a@b.co", true))
        assertThat(d.selectFirst(".card__sub")!!.text()).isEqualTo("가입한 이메일을 입력하면 재설정 링크를 보내드립니다.")
    }

    @Test
    fun forgotHtmxReturnsFragmentOnlyAndPlainPostReturnsFullPage() {
        okRequest()
        val frag = postForgot("a@b.co", true).response.contentAsString
        assertThat(frag).contains("id=\"auth-card\"").doesNotContain("<html").doesNotContain("<body")
        val full = postForgot("a@b.co", false).response.contentAsString
        assertThat(full).contains("<html").contains("id=\"auth-card\"")
    }

    @Test
    fun forgotTrimsEmailAndCallsRestBean() {
        okRequest()
        postForgot("  alice@example.com  ", true)
        verify(reset).request(argThat { "alice@example.com" == email })
    }

    @Test
    fun forgotBlankEmailIsInlineErrorAndNotSent() {
        val r = postForgot("   ", true)
        assertThat(r.response.status).isEqualTo(422)
        val d = doc(r)
        assertThat(d.selectFirst("#forgot-email-error")!!.text()).isEqualTo("이메일을 입력하세요.")
        assertThat(d.select("form")).hasSize(1)
        verify(reset, never()).request(anyOrNull())
    }

    @Test
    fun forgotServerFailureIsNotShownAsSent(out: CapturedOutput) {
        doThrow(IllegalStateException("db down for alice@example.com")).whenever(reset).request(anyOrNull())
        val r = postForgot("alice@example.com", true)
        assertThat(r.response.status).isEqualTo(500)
        assertThat(r.response.contentAsString).isEmpty()
        assertThat(ourLogs(out)).contains("IllegalStateException").doesNotContain("alice@example.com")
    }

    @Test
    fun forgotFormHasClientSideRetryWiring() {
        val form = page("/admin/forgot-password").selectFirst("form")!!
        assertThat(form.attr("x-data")).isEqualTo("forgotForm")
        assertThat(form.attr("hx-sync")).isEqualTo("this:drop")
        assertThat(form.selectFirst("button[type=submit]")!!.attr("x-bind:disabled")).isEqualTo("!canSubmit()")
    }

    // ── 비밀번호 재설정 ──

    @Test
    fun resetWithoutTokenShowsInvalid() {
        for (token in arrayOf<String?>(null, "", "  ")) {
            val rb = get("/admin/reset-password")
            if (token != null) rb.param("token", token)
            val d = Jsoup.parse(mvc.perform(rb).andExpect(status().isOk()).andReturn().response.contentAsString)
            assertThat(shown(d)).contains("링크를 사용할 수 없습니다")
                .contains("유효하지 않은 링크입니다. 만료됐거나 이미 사용된 링크일 수 있습니다.")
            assertThat(d.select("form")).isEmpty()
            assertThat(d.selectFirst("a.btn.btn--primary")!!.attr("href")).isEqualTo("/admin/forgot-password")
            assertThat(d.selectFirst("a:containsOwn(← 로그인으로)")!!.attr("href")).isEqualTo("/admin")
        }
        // POST 도 토큰이 없으면 REST 빈을 부르지 않는다
        val r = postReset(null, GOOD_PW, GOOD_PW)
        assertThat(r.response.status).isEqualTo(422)
        assertThat(shown(doc(r))).contains("유효하지 않은 링크입니다")
        verify(reset, never()).confirm(anyOrNull())
    }

    @Test
    fun resetFormCarriesTokenOnlyAsHiddenFieldAndIsNotCacheable() {
        val r = mvc.perform(get("/admin/reset-password").param("token", TOKEN))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("Referrer-Policy", "no-referrer"))
            .andReturn()
        val d = doc(r)
        assertThat(d.selectFirst("h1")!!.text()).isEqualTo("새 비밀번호 설정")
        assertThat(d.selectFirst(".card__sub")!!.text()).isEqualTo("12자 이상으로 새 비밀번호를 입력하세요.")
        val form = d.selectFirst("form[hx-post=/admin/reset-password]")!!
        assertThat(form.attr("hx-post")).doesNotContain("token")
        assertThat(form.select("input[type=hidden][name=token]").attr("value")).isEqualTo(TOKEN)
        assertThat(form.select("input#reset-pw[name=password][type=password][autocomplete=new-password]")).hasSize(1)
        assertThat(form.select("input#reset-pw2[name=password2][type=password]")).hasSize(1)
        assertThat(d.selectFirst("label[for=reset-pw]")!!.text()).isEqualTo("새 비밀번호")
        assertThat(d.selectFirst("label[for=reset-pw2]")!!.text()).isEqualTo("새 비밀번호 확인")
        assertThat(form.selectFirst("button[type=submit]")!!.text()).isEqualTo("비밀번호 변경")
        assertThat(d.select("#reset-pw-meter")).hasSize(1)
        assertThat(d.text()).contains("12자 이상")
        assertThat(form.attr("x-data")).isEqualTo("resetForm")
        assertThat(form.selectFirst("button[type=submit]")!!.attr("x-bind:disabled")).isEqualTo("!canSubmit()")
        assertThat(d.select("a:containsOwn(← 로그인으로)")).hasSize(1)
    }

    @Test
    fun resetShortOrMismatchedPasswordIsInlineErrorAndNotSent() {
        val shortPw = postReset(TOKEN, "short", "short")
        assertThat(shortPw.response.status).isEqualTo(422)
        assertThat(doc(shortPw).select("input#reset-pw").first()!!.parent()!!.text()).contains("12자 이상으로 입력하세요.")
        val mismatch = postReset(TOKEN, GOOD_PW, GOOD_PW + "x")
        assertThat(mismatch.response.status).isEqualTo(422)
        assertThat(doc(mismatch).select(".field__error:not([x-show])").text()).isEqualTo("비밀번호가 일치하지 않습니다.")
        verify(reset, never()).confirm(anyOrNull())
        // 다시 그린 폼은 비밀번호를 되돌려 쓰지 않는다(토큰은 다시 제출하도록 유지)
        val html = mismatch.response.contentAsString
        assertThat(html).doesNotContain(GOOD_PW)
        assertThat(doc(mismatch).select("input[name=token]").attr("value")).isEqualTo(TOKEN)
    }

    @Test
    fun resetSuccessShowsDoneCardAndCallsRestBean() {
        okConfirm()
        val r = postReset("  $TOKEN ", GOOD_PW, GOOD_PW)
        assertThat(r.response.status).isEqualTo(200)
        val d = doc(r)
        assertThat(d.text()).contains("변경 완료").contains("비밀번호가 변경되었습니다. 새 비밀번호로 로그인하세요.")
        assertThat(d.selectFirst("a.btn.btn--primary")!!.text()).isEqualTo("로그인 →")
        assertThat(d.selectFirst("a.btn.btn--primary")!!.attr("href")).isEqualTo("/admin")
        assertThat(d.select("form, input")).isEmpty()
        assertThat(r.response.contentAsString).doesNotContain(TOKEN).doesNotContain(GOOD_PW)
        verify(reset).confirm(argThat { TOKEN == token && GOOD_PW == newPassword })
        assertThat(r.response.getHeader("Cache-Control")).isEqualTo("no-store")
    }

    @Test
    fun resetTokenRejectionShowsExpiredCard() {
        for (msg in arrayOf("invalid token", "token already used", "token expired")) {
            doThrow(IllegalArgumentException(msg)).whenever(reset).confirm(anyOrNull())
            val r = postReset(TOKEN, GOOD_PW, GOOD_PW)
            assertThat(r.response.status).isEqualTo(422)
            val d = doc(r)
            assertThat(shown(d)).contains("링크를 사용할 수 없습니다")
                .contains("링크가 만료되었거나 이미 사용되었습니다. 보안을 위해 재설정 링크는 정해진 시간 안에 한 번만 쓸 수 있습니다.")
            assertThat(d.select("form")).isEmpty()
            assertThat(d.selectFirst("a.btn--primary")!!.attr("href")).isEqualTo("/admin/forgot-password")
            assertThat(r.response.contentAsString).doesNotContain(TOKEN)
        }
    }

    @Test
    fun resetNonTokenRejectionShowsServerMessageAndKeepsForm() {
        doThrow(IllegalArgumentException("비밀번호 정책 위반")).whenever(reset).confirm(anyOrNull())
        val r = postReset(TOKEN, GOOD_PW, GOOD_PW)
        assertThat(r.response.status).isEqualTo(422)
        val d = doc(r)
        assertThat(d.select("[role=alert]:not(template [role=alert])").text()).isEqualTo("비밀번호 정책 위반")
        assertThat(d.select("form")).hasSize(1)
        assertThat(d.select("input[name=token]").attr("value")).isEqualTo(TOKEN)
    }

    @Test
    fun resetServerFailureIsTransientAndDoesNotLogToken(out: CapturedOutput) {
        doThrow(IllegalStateException("boom $TOKEN")).whenever(reset).confirm(anyOrNull())
        val r = postReset(TOKEN, GOOD_PW, GOOD_PW)
        assertThat(r.response.status).isEqualTo(500)
        assertThat(r.response.contentAsString).isEmpty()
        assertThat(ourLogs(out)).contains("IllegalStateException").doesNotContain(TOKEN).doesNotContain(GOOD_PW)
    }

    @Test
    fun resetHtmxReturnsFragmentOnly() {
        okConfirm()
        val frag = postReset(TOKEN, GOOD_PW, GOOD_PW).response.contentAsString
        assertThat(frag).contains("id=\"auth-card\"").doesNotContain("<html")
    }

    @Test
    fun formsPostNativelyWithoutHtmxAndNeverFallBackToGet() {
        val f = page("/admin/forgot-password").selectFirst("form")!!
        assertThat(f.attr("method")).isEqualToIgnoringCase("post")
        assertThat(f.attr("action")).isEqualTo("/admin/forgot-password")
        val r = page("/admin/reset-password?token=$TOKEN").selectFirst("form")!!
        assertThat(r.attr("method")).isEqualToIgnoringCase("post")
        assertThat(r.attr("action")).isEqualTo("/admin/reset-password")
        assertThat(r.attr("action")).doesNotContain("token")
    }

    @Test
    fun forgotServerSideStatusErrorsAreTransientNotSent() {
        doThrow(ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "x")).whenever(reset).request(anyOrNull())
        val r = postForgot("alice@example.com", true)
        assertThat(r.response.status).isEqualTo(503)
        assertThat(r.response.contentAsString).isEmpty()
    }

    @Test
    fun resetServerErrorsAreLinkedToInputs() {
        val d = doc(postReset(TOKEN, "short", "short"))
        val pw = d.selectFirst("input#reset-pw")!!
        assertThat(pw.attr("aria-describedby")).contains("reset-pw-server-error")
        assertThat(d.selectFirst("#reset-pw-server-error")!!.text()).isNotBlank()
        val m = doc(postReset(TOKEN, GOOD_PW, GOOD_PW + "x"))
        assertThat(m.selectFirst("input#reset-pw2")!!.attr("aria-describedby")).contains("reset-pw2-server-error")
        assertThat(m.selectFirst("#reset-pw2-server-error")!!.text()).isNotBlank()
    }

    // ── CSRF ──

    @Test
    fun postsRequireCsrf() {
        mvc.perform(post("/admin/forgot-password").param("email", "a@b.co")).andExpect(status().isForbidden())
        mvc.perform(post("/admin/reset-password").param("token", TOKEN).param("password", GOOD_PW).param("password2", GOOD_PW))
            .andExpect(status().isForbidden())
        verify(reset, never()).request(anyOrNull())
        verify(reset, never()).confirm(anyOrNull())
    }

    @Test
    @DisplayName("재설정 메일 링크(/reset-password?token=…, base-url 기준 루트 경로)는 미인증 GET 에서 쿼리를 보존해 /admin/reset-password 로 302")
    fun rootResetLinkRedirectsWithQuery() {
        val r = mvc.perform(get("/reset-password?token=abc")).andReturn()
        assertThat(r.response.status).isEqualTo(302)
        assertThat(r.response.getHeader("Location")).isEqualTo("/admin/reset-password?token=abc")
        assertThat(r.response.getHeader("Cache-Control")).isEqualTo("no-store")
        assertThat(r.response.getHeader("Referrer-Policy")).isEqualTo("no-referrer")
        // 인코딩된 쿼리는 그대로, 쿼리가 없으면 경로만
        assertThat(mvc.perform(get(java.net.URI.create("/reset-password?token=a%2Bb%3D&x=1"))).andReturn().response.getHeader("Location"))
            .isEqualTo("/admin/reset-password?token=a%2Bb%3D&x=1")
        assertThat(mvc.perform(get("/reset-password")).andReturn().response.getHeader("Location")).isEqualTo("/admin/reset-password")
    }
}
