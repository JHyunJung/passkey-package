package com.crosscert.passkey.admin.web.auth

import com.crosscert.passkey.admin.config.ProfileController
import com.crosscert.passkey.admin.operator.SignupRequestController
import com.crosscert.passkey.admin.operator.SignupRequestDto
import com.crosscert.passkey.admin.web.AdminWebSliceTest
import com.crosscert.passkey.core.api.ApiResponse
import org.assertj.core.api.Assertions.assertThat
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.server.ResponseStatusException
import java.util.regex.Pattern

/**
 * 가입 요청 화면(← SignupRequestPage.test.tsx).
 *
 * (Kotlin 전환) 즉시 역참조하는 nullable jsoup 결과(`selectFirst`·`first`·`parent`)의 `!!` 는 원 Java 의 NPE 실패 지점과 같다.
 */
@AdminWebSliceTest(controllers = [SignupPageController::class])
@ExtendWith(OutputCaptureExtension::class)
class SignupPageControllerTest {

    companion object {
        private const val PW = "correct horse battery"

        private fun accepted(activated: Boolean): ResponseEntity<Map<String, Boolean>> =
            ResponseEntity.status(HttpStatus.ACCEPTED).body(java.util.Map.of("accepted", true, "activated", activated))

        private fun ourLogs(out: CapturedOutput): String =
            java.util.Arrays.stream(Pattern.compile("\n").split(out.all))
                .filter { l -> l.contains("SignupPageController") }.collect(java.util.stream.Collectors.joining("\n"))

        private fun doc(r: MvcResult): Document = Jsoup.parse(r.response.contentAsString)
    }

    @Autowired lateinit var mvc: MockMvc
    @MockitoBean lateinit var signup: SignupRequestController
    @MockitoBean lateinit var profiles: ProfileController

    @BeforeEach
    fun defaults() {
        selfSignup(false)
        whenever(signup.request(anyOrNull())).thenReturn(accepted(false))
    }

    private fun selfSignup(on: Boolean) {
        whenever(profiles.profile()).thenReturn(ApiResponse.ok(ProfileController.ProfileView(listOf("prod"), false, on)))
    }

    private fun form(email: String, pw: String, pw2: String, reason: String?, role: String?): MockHttpServletRequestBuilder {
        val rb = post("/admin/signup").with(csrf()).header("HX-Request", "true")
            .param("email", email).param("password", pw).param("password2", pw2)
        if (reason != null) rb.param("reason", reason)
        if (role != null) rb.param("role", role)
        return rb
    }

    private fun send(email: String, pw: String, pw2: String, reason: String?, role: String?): MvcResult =
        mvc.perform(form(email, pw, pw2, reason, role)).andReturn()

    @Test
    fun signupPageUsesWideAuthShell() {
        val d = Jsoup.parse(mvc.perform(get("/admin/signup")).andExpect(status().isOk()).andReturn().response.contentAsString)
        assertThat(d.select(".app, .sidebar, .topbar")).isEmpty()
        assertThat(d.select(".auth .auth__col.auth__col--wide > img.auth__logo[alt=CROSSCERT]")).hasSize(1)
        assertThat(d.selectFirst("h1")!!.text()).isEqualTo("관리자 계정 가입 요청")
        assertThat(d.selectFirst(".card__sub")!!.text())
            .isEqualTo("플랫폼 운영자가 승인하면 계정이 활성화됩니다. 역할과 담당 tenant 는 승인 시 지정됩니다.")
        val f = d.selectFirst("form[hx-post=/admin/signup]")!!
        assertThat(f.select("input#signup-email[name=email][type=email]")).hasSize(1)
        assertThat(f.select("input#signup-pw[name=password][type=password][maxlength=128]")).hasSize(1)
        assertThat(f.select("input#signup-pw2[name=password2][type=password][maxlength=128]")).hasSize(1)
        assertThat(f.select("textarea#signup-reason[name=reason][maxlength=500]")).hasSize(1)
        assertThat(f.select("input[type=hidden][name=_csrf]").attr("value")).isNotBlank()
        assertThat(f.selectFirst("label[for=signup-reason]")!!.text()).isEqualTo("요청 사유 (선택)")
        assertThat(f.text()).contains("12–128자")
        assertThat(f.selectFirst("button[type=submit]")!!.text()).isEqualTo("가입 요청 보내기")
        assertThat(f.attr("x-data")).isEqualTo("signupForm")
        assertThat(f.selectFirst("button[type=submit]")!!.attr("x-bind:disabled")).isEqualTo("!canSubmit()")
        // 카드 밖 로그인 링크
        val back = d.selectFirst("a:containsOwn(← 로그인으로)")!!
        assertThat(back.attr("href")).isEqualTo("/admin")
        assertThat(back.parents().hasClass("auth-card")).isFalse()
    }

    @Test
    fun roleChoicesOnlyWhenSelfSignupEnabled() {
        val off = Jsoup.parse(mvc.perform(get("/admin/signup")).andReturn().response.contentAsString)
        assertThat(off.select("[role=radiogroup]")).isEmpty()
        assertThat(off.text()).doesNotContain("Platform Operator")
        selfSignup(true)
        val on = Jsoup.parse(mvc.perform(get("/admin/signup")).andReturn().response.contentAsString)
        assertThat(on.select("[role=radiogroup][aria-label=역할] [role=radio]")).hasSize(2)
        assertThat(on.text()).contains("승인 후 사용").contains("운영자가 승인하며 역할·tenant 를 지정")
            .contains("Platform Operator").contains("승인 없이 바로 사용 (QA 전용)")
        assertThat(on.selectFirst("input[type=hidden][name=role]")!!.attr("value")).isEqualTo("RP_ADMIN")
    }

    @Test
    fun signupValidationErrorsInline(out: CapturedOutput) {
        val bad = send("not-an-email", "short", "different", "x".repeat(501), null)
        assertThat(bad.response.status).isEqualTo(422)
        val d = doc(bad)
        assertThat(d.select("form")).hasSize(1)
        assertThat(d.select("input#signup-email").first()!!.parent()!!.select(".field__error:not([x-show])").text())
            .isEqualTo("이메일 형식이 올바르지 않습니다. 예: you@company.com")
        assertThat(d.select("input#signup-pw").first()!!.parent()!!.select(".field__error:not([x-show])").text())
            .isEqualTo("5자 입력됨 · 12–128자로 입력하세요")
        assertThat(d.select("textarea#signup-reason").first()!!.parent()!!.select(".field__error:not([x-show])").text())
            .isEqualTo("요청 사유는 500자 이하로 입력하세요.")
        // 비밀번호 길이가 맞으면 확인란 불일치가 따로 나온다
        val mismatch = send("a@b.co", PW, PW + "x", null, null)
        assertThat(mismatch.response.status).isEqualTo(422)
        assertThat(doc(mismatch).select("input#signup-pw2").first()!!.parent()!!.select(".field__error:not([x-show])").text())
            .isEqualTo("비밀번호가 일치하지 않습니다.")
        verify(signup, never()).request(anyOrNull())
        // 입력한 이메일·사유는 되살리고 비밀번호는 되돌려 쓰지 않는다
        assertThat(bad.response.contentAsString).doesNotContain("short").doesNotContain("different")
        assertThat(doc(bad).selectFirst("form")!!.attr("data-email")).isEqualTo("not-an-email")
        assertThat(ourLogs(out)).doesNotContain(PW)
    }

    @Test
    fun emailWithoutDotIsRejectedLikeTheClientRegex() {
        val r = send("a@b", PW, PW, null, null)
        assertThat(r.response.status).isEqualTo(422)
        verify(signup, never()).request(anyOrNull())
    }

    @Test
    fun passwordOver128IsRejected() {
        val pw = "p".repeat(129)
        val r = send("a@b.co", pw, pw, null, null)
        assertThat(r.response.status).isEqualTo(422)
        assertThat(doc(r).select("input#signup-pw").first()!!.parent()!!.select(".field__error:not([x-show])").text())
            .isEqualTo("129자 입력됨 · 12–128자로 입력하세요")
    }

    @Test
    fun pendingCardOnAccepted() {
        val r = send("Alice@Example.com ", PW, PW, "  QA 팀  ", null)
        assertThat(r.response.status).isEqualTo(200)
        val d = doc(r)
        assertThat(d.selectFirst("h1")!!.text()).isEqualTo("요청이 접수되었습니다")
        assertThat(d.selectFirst(".card__sub")!!.text()).isEqualTo("운영자 승인 후 이메일로 안내됩니다. 승인 전에는 로그인할 수 없습니다.")
        assertThat(d.selectFirst("img[alt=\"\"]")!!.attr("src")).isEqualTo("/admin/img/signup-sent.webp")
        assertThat(d.selectFirst("a.btn")!!.text()).isEqualTo("로그인으로 돌아가기")
        assertThat(d.selectFirst("a.btn")!!.attr("href")).isEqualTo("/admin")
        assertThat(d.select("form")).isEmpty()
        // 완료 상태에는 카드 밖 로그인 링크가 없다
        assertThat(d.select("a:containsOwn(← 로그인으로)")).isEmpty()
        assertThat(d.selectFirst(".auth-card")!!.attr("style")).contains("align-items: center").contains("text-align: center")
        // 이메일 소문자·trim, 사유 trim
        verify(signup).request(
            argThat {
                "alice@example.com" == email && PW == password &&
                    "QA 팀" == reason && role == null
            },
        )
    }

    @Test
    fun blankReasonIsSentAsNull() {
        send("a@b.co", PW, PW, "   ", null)
        verify(signup).request(argThat { reason == null })
    }

    @Test
    fun activatedCardWhenServerActivatedAccount() {
        selfSignup(true)
        whenever(signup.request(anyOrNull())).thenReturn(accepted(true))
        val r = send("a@b.co", PW, PW, null, "PLATFORM_OPERATOR")
        val d = doc(r)
        assertThat(d.selectFirst("h1")!!.text()).isEqualTo("계정이 만들어졌습니다")
        assertThat(d.selectFirst(".card__sub")!!.text()).isEqualTo("Platform Operator 계정이 활성화되었습니다. 바로 로그인할 수 있습니다.")
        assertThat(d.selectFirst("a.btn")!!.text()).isEqualTo("로그인하기")
        assertThat(d.selectFirst("img[alt=\"\"]")!!.attr("src")).isEqualTo("/admin/img/signup-sent.webp")
        verify(signup).request(argThat { "PLATFORM_OPERATOR" == role })
    }

    @Test
    fun defaultRoleChoiceSendsNoRoleAndShowsPending() {
        selfSignup(true)
        val r = send("a@b.co", PW, PW, null, "RP_ADMIN")
        assertThat(doc(r).selectFirst("h1")!!.text()).isEqualTo("요청이 접수되었습니다")
        verify(signup).request(argThat { role == null })
    }

    @Test
    fun platformOperatorRoleIgnoredWhenSelfSignupIsOff() {
        selfSignup(false)
        send("a@b.co", PW, PW, null, "PLATFORM_OPERATOR")
        verify(signup).request(argThat { role == null })
    }

    @Test
    fun clientErrorFromServiceStillShowsAcceptedNotice() {
        doThrow(ResponseStatusException(HttpStatus.BAD_REQUEST, "x")).whenever(signup).request(anyOrNull())
        assertThat(doc(send("a@b.co", PW, PW, null, null)).selectFirst("h1")!!.text()).isEqualTo("요청이 접수되었습니다")
        doThrow(IllegalArgumentException("x")).whenever(signup).request(anyOrNull())
        assertThat(doc(send("a@b.co", PW, PW, null, null)).selectFirst("h1")!!.text()).isEqualTo("요청이 접수되었습니다")
    }

    @Test
    fun serverFailureIsNotShownAsAccepted(out: CapturedOutput) {
        doThrow(IllegalStateException("db down for a@b.co $PW")).whenever(signup).request(anyOrNull())
        val r = send("a@b.co", PW, PW, null, null)
        assertThat(r.response.status).isEqualTo(500)
        assertThat(r.response.contentAsString).isEmpty()
        assertThat(ourLogs(out)).contains("IllegalStateException").doesNotContain("a@b.co").doesNotContain(PW)
    }

    @Test
    fun htmxReturnsFragmentAndPlainPostReturnsFullPage() {
        val frag = send("a@b.co", PW, PW, null, null).response.contentAsString
        assertThat(frag).contains("id=\"auth-col\"").doesNotContain("<html")
        val plain = post("/admin/signup").with(csrf()).param("email", "a@b.co").param("password", PW).param("password2", PW)
        assertThat(mvc.perform(plain).andReturn().response.contentAsString).contains("<html")
    }

    @Test
    fun formPostsNativelyWithoutHtmx() {
        val f = Jsoup.parse(mvc.perform(get("/admin/signup")).andReturn().response.contentAsString).selectFirst("form")!!
        assertThat(f.attr("method")).isEqualToIgnoringCase("post")
        assertThat(f.attr("action")).isEqualTo("/admin/signup")
    }

    @Test
    fun serverFieldErrorsAreLinkedToInputs() {
        val d = doc(send("bad", "short", "x", "x".repeat(501), null))
        for (
            p in arrayOf(
                arrayOf("signup-email", "signup-email-server-error"), arrayOf("signup-pw", "signup-pw-server-error"),
                arrayOf("signup-reason", "signup-reason-server-error"),
            )
        ) {
            assertThat(d.selectFirst("#" + p[0])!!.attr("aria-describedby")).contains(p[1])
            assertThat(d.selectFirst("#" + p[1])!!.text()).isNotBlank()
        }
        val m = doc(send("a@b.co", PW, PW + "x", null, null))
        assertThat(m.selectFirst("#signup-pw2")!!.attr("aria-describedby")).contains("signup-pw2-server-error")
        assertThat(m.selectFirst("#signup-pw2-server-error")!!.text()).isNotBlank()
    }

    @Test
    fun postsRequireCsrf() {
        mvc.perform(post("/admin/signup").param("email", "a@b.co").param("password", PW).param("password2", PW))
            .andExpect(status().isForbidden())
        verify(signup, never()).request(anyOrNull())
    }

    @Test
    fun requestBodyTypeIsTheRestDto() {
        // 화면 컨트롤러는 REST DTO 를 그대로 만들어 넘긴다(필드 구성 고정).
        assertThat(
            java.util.Arrays.stream(SignupRequestDto.Create::class.java.declaredFields)
                .filter { f -> !java.lang.reflect.Modifier.isStatic(f.modifiers) && !f.isSynthetic },
        )
            .hasSize(4)
    }
}
