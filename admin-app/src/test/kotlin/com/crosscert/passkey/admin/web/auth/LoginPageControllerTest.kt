package com.crosscert.passkey.admin.web.auth

import com.crosscert.passkey.admin.auth.AdminUserDetails
import com.crosscert.passkey.admin.auth.MfaController
import com.crosscert.passkey.admin.auth.MfaPendingFilter
import com.crosscert.passkey.admin.config.ProfileController
import com.crosscert.passkey.admin.web.AdminWebSliceTest
import com.crosscert.passkey.core.api.ApiResponse
import jakarta.servlet.http.HttpServletRequest
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.emptyString
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.select.Elements
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.ResponseEntity
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.util.UUID

/**
 * 로그인 화면·MFA 단계(← LoginPage.test.tsx, MfaChallenge.test.tsx, App.tsx 의 로그인 전후 분기).
 *
 * (Kotlin 전환) 즉시 역참조하는 nullable jsoup 결과(`selectFirst`·`first`·`parent`·`closest`)의 `!!` 는 원 Java 의 NPE 실패 지점과 같다.
 */
@AdminWebSliceTest(controllers = [LoginPageController::class])
@ExtendWith(OutputCaptureExtension::class)
class LoginPageControllerTest {

    @Autowired lateinit var mvc: MockMvc
    @MockitoBean lateinit var mfa: MfaController
    @MockitoBean lateinit var profiles: ProfileController

    private val json = ObjectMapper()

    companion object {
        fun operator(): Authentication {
            val principal = AdminUserDetails(
                UUID.randomUUID(), "alice@example.com", "x",
                "PLATFORM_OPERATOR", setOf(), true, null, Clock.systemUTC(),
            )
            return UsernamePasswordAuthenticationToken(principal, "x", principal.authorities)
        }

        private fun pendingSession(): MockHttpSession {
            val s = MockHttpSession()
            s.setAttribute(MfaPendingFilter.MFA_PENDING_ATTR, java.lang.Boolean.TRUE)
            return s
        }

        /** 화면에 그려진 경고(Alpine x-if 템플릿 안의 것은 제외 — Jsoup 은 template 내용도 자식으로 읽는다). */
        private fun renderedAlerts(d: Document): Elements {
            val out = Elements()
            for (e in d.select("[role=alert]")) {
                if (e.parents().stream().noneMatch { p -> p.normalName() == "template" }) out.add(e)
            }
            return out
        }
    }

    @BeforeEach
    fun prodProfile() {
        profile(listOf("prod"), false)
    }

    private fun profile(active: List<String>, local: Boolean) {
        whenever(profiles.profile()).thenReturn(ApiResponse.ok(ProfileController.ProfileView(active, local, false)))
    }

    private fun page(url: String): Document {
        val html = mvc.perform(get(url)).andExpect(status().isOk()).andReturn().response.contentAsString
        return Jsoup.parse(html)
    }

    // ── 로그인 화면 ──

    @Test
    fun anonymousGetsLoginPage() {
        val d = page("/admin/login")
        // 공통 틀(사이드바·상단 바) 없이 단독 페이지
        assertThat(d.select(".app, .sidebar, .topbar")).isEmpty()
        // 브랜드 패널: 워드마크·헤드라인·장식 일러스트, 콘솔이 하는 일(가짜 지표 없음)
        assertThat(d.selectFirst("aside img[alt=CROSSCERT]")!!.attr("src")).isEqualTo("/admin/img/crosscert-logo-transparent.png")
        assertThat(d.selectFirst("h1")!!.text()).isEqualTo("패스키 인증을 운영하는 콘솔.")
        assertThat(d.select("aside img[alt=\"\"]")).hasSize(1)
        assertThat(d.select("aside li").eachText()).containsExactly(
            "tenant 생성 · 정책 설정 · API 키 발급", "사용자 패스키 조회와 회수", "감사 로그 무결성 확인과 내보내기",
        )
        assertThat(d.text()).contains("이 콘솔에서 하는 일").doesNotContain("2.4M").doesNotContain("ceremony / 24h")
            .doesNotContainPattern("audit log|hash chain|ceremony")
        // 폼: hx-post /admin/login, email·password·_csrf
        val form = d.selectFirst("form#login-form")!!
        assertThat(form.attr("hx-post")).isEqualTo("/admin/login")
        assertThat(form.select("input[name=email][type=email]#login-email")).hasSize(1)
        assertThat(form.select("input[name=password][type=password]#login-password")).hasSize(1)
        assertThat(form.select("input[type=hidden][name=_csrf]").attr("value")).isNotBlank()
        assertThat(d.selectFirst("label[for=login-email]")!!.text()).isEqualTo("이메일")
        assertThat(d.selectFirst("label[for=login-password]")!!.text()).isEqualTo("비밀번호")
        // 이메일 칸에 자동 포커스
        assertThat(d.selectFirst("#login-email")!!.hasAttr("autofocus")).isTrue()
        assertThat(d.selectFirst("#login-password")!!.hasAttr("autofocus")).isFalse()
        // 링크·안내·버튼
        assertThat(d.selectFirst("a:containsOwn(비밀번호를 잊으셨나요?)")!!.attr("href")).isEqualTo("/admin/forgot-password")
        assertThat(d.selectFirst("a:containsOwn(가입 요청)")!!.attr("href")).isEqualTo("/admin/signup")
        assertThat(d.text()).contains("30분 동안 활동이 없으면 자동으로 로그아웃됩니다.")
        val submit = form.selectFirst("button[type=submit]")!!
        assertThat(submit.text()).isEqualTo("로그인")
        assertThat(submit.classNames()).contains("btn", "btn--primary", "btn--lg")
        // 비밀번호 표시 토글(초기 상태)
        val toggle = d.selectFirst("button[aria-label=비밀번호 표시]")!!
        assertThat(toggle.attr("aria-pressed")).isEqualTo("false")
        assertThat(toggle.attr("x-on:click")).isEqualTo("showPw = !showPw")
        // 오류 없음, 문의 링크는 주소가 설정됐을 때만(기본 빈 값)
        assertThat(renderedAlerts(d)).isEmpty()
        assertThat(d.select("a:containsOwn(도움말 · 운영자에게 문의)")).isEmpty()
        // 데모 계정은 dev·local 에서만(기본 prod)
        assertThat(d.text()).doesNotContain("데모 계정으로 로그인")
        assertThat(d.html()).doesNotContain("alice-temp-pw")
    }

    @Test
    fun errorParamShowsLoginError() {
        val d = page("/admin/login?error")
        assertThat(renderedAlerts(d)).hasSize(1)
        val alert = renderedAlerts(d).first()!!
        assertThat(alert.text()).contains("로그인 실패").contains("이메일 또는 비밀번호가 올바르지 않습니다.").contains("A001")
        // 폼 맨 위(이메일 입력보다 앞)
        val email = d.selectFirst("#login-email")!!
        assertThat(alert.elementSiblingIndex()).isLessThan(email.parent()!!.elementSiblingIndex())
        assertThat(alert.parent()).isEqualTo(email.parent()!!.parent())
        // 비밀번호 칸에 포커스, 비밀번호 값 없음
        assertThat(d.selectFirst("#login-password")!!.hasAttr("autofocus")).isTrue()
        assertThat(d.selectFirst("#login-password")!!.attr("value")).isEmpty()
        assertThat(email.hasAttr("autofocus")).isFalse()
        // 입력을 바꾸면 경고가 사라진다(Alpine)
        assertThat(alert.attr("x-show")).isEqualTo("error === 'credentials'")
        assertThat(d.selectFirst("form#login-form")!!.attr("data-error")).isEqualTo("credentials")
        assertThat(email.attr("x-on:input")).isEqualTo("clearError()")
        // 토스트 없음
        assertThat(d.select(".toast, .toast-rack")).isEmpty()
    }

    private fun assertDemoCard(d: Document) {
        val card = d.selectFirst("button:has(span.tag)")
        assertThat(card).isNotNull()
        card!!
        assertThat(card.text()).contains("DEV").contains("데모 계정으로 로그인").contains("Platform Operator / alice@crosscert.com")
        assertThat(card.attr("type")).isEqualTo("button")
        assertThat(card.attr("aria-pressed")).isEqualTo("false")
        // 카드를 누르면 alice 계정을 채운다(auth.js loginForm.selectDemo) — 로드 시에는 채우지 않는다(TSX 와 같음)
        assertThat(card.attr("data-demo-email")).isEqualTo("alice@crosscert.com")
        assertThat(card.attr("data-demo-password")).isEqualTo("alice-temp-pw")
        assertThat(card.attr("x-on:click")).isEqualTo("selectDemo(\$el)")
        assertThat(d.selectFirst("#login-email")!!.attr("value")).isEmpty()
        assertThat(d.selectFirst("#login-password")!!.attr("value")).isEmpty()
    }

    @Test
    fun localProfileShowsDemoCardFilledOnClick() {
        profile(listOf("local"), true)
        assertDemoCard(page("/admin/login"))
    }

    @Test
    fun devProfileShowsDemoCardFilledOnClick() {
        // LoginPage.tsx: active 에 dev 또는 local, 또는 local=true
        profile(listOf("dev"), false)
        assertDemoCard(page("/admin/login"))
    }

    @Test
    fun prodAndQaProfilesHaveNoDemoAccount() {
        for (p in listOf("prod", "qa")) {
            profile(listOf(p), false)
            val html = mvc.perform(get("/admin/login")).andReturn().response.contentAsString
            assertThat(html).doesNotContain("데모 계정으로 로그인").doesNotContain("alice-temp-pw").doesNotContain("data-demo-")
        }
    }

    @Test
    fun authenticatedWithoutMfaRedirectsHome() {
        mvc.perform(get("/admin/login").with(authentication(operator())))
            .andExpect(status().isFound())
            .andExpect(redirectedUrl("/admin"))
    }

    // ── MFA 단계 화면 ──

    @Test
    fun mfaPendingGetsMfaPage() {
        val html = mvc.perform(get("/admin/login").with(authentication(operator())).session(pendingSession()))
            .andExpect(status().isOk()).andReturn().response.contentAsString
        val d = Jsoup.parse(html)
        assertThat(d.select(".app, .sidebar")).isEmpty()
        val card = d.selectFirst("main.auth .auth-card")
        assertThat(card).isNotNull()
        card!!
        assertThat(d.selectFirst("img.auth__logo")!!.attr("alt")).isEqualTo("CROSSCERT")
        assertThat(card.attr("hx-post")).isEqualTo("/admin/login/mfa")
        assertThat(card.attr("hx-target")).isEqualTo("#mfa-error")
        assertThat(card.select("input[type=hidden][name=_csrf]").attr("value")).isNotBlank()
        // x-if 템플릿 안: 일러스트·제목·OTP 6칸(name=code)·안내
        val tpl = card.select("template").outerHtml()
        assertThat(tpl).contains("2단계 인증").contains("authenticator 앱에 표시된 6자리 코드를 입력하세요.")
            .contains("mfa.webp").contains("붙여넣기 지원 · 6자리를 모두 입력하면 자동 확인")
            .contains("기기를 잃어버렸나요? 운영자에게 MFA 초기화를 요청하세요.")
            .contains("시계가 맞지 않으면 코드가 계속 틀릴 수 있습니다. 휴대폰 시간 자동 설정을 확인하세요.")
            .contains("복구 코드로 로그인").contains("MFA 등록 때 저장한 복구 코드 하나를 입력하세요. 사용한 코드는 다시 쓸 수 없습니다.")
            .contains("placeholder=\"AB3F-2K7M\"").contains("← authenticator 코드로 돌아가기")
        val inner = Jsoup.parse(tpl)
        assertThat(inner.select(".otp__cell")).hasSize(6)
        assertThat(inner.select(".otp__cell")[0].attr("aria-label")).isEqualTo("인증 코드 1번째 자리")
        assertThat(inner.select("input[type=hidden][name=code]")).hasSize(1)
        assertThat(inner.select("input#mfa-recovery[name=code]")).hasSize(1)
        assertThat(inner.selectFirst("label[for=mfa-recovery]")!!.text()).isEqualTo("복구 코드")
        // "← 로그인으로": 카드 밖, POST /admin/logout(_csrf)
        val back = d.selectFirst("button:containsOwn(← 로그인으로)")!!
        assertThat(back.closest(".auth-card")).isNull()
        val logout = back.closest("form")!!
        assertThat(logout.attr("action")).isEqualTo("/admin/logout")
        assertThat(logout.attr("method")).isEqualToIgnoringCase("post")
        assertThat(logout.select("input[name=_csrf]")).hasSize(1)
        // 처음에는 오류가 없다(오류 조각은 전체 페이지에 그리지 않는다)
        assertThat(renderedAlerts(d)).isEmpty()
        assertThat(d.text()).doesNotContain("코드가 맞지 않습니다.")
    }

    // ── MFA 제출 ──

    private fun submit(code: String, mode: String?, session: MockHttpSession): MvcResult {
        var req = post("/admin/login/mfa").param("code", code).header("HX-Request", "true")
            .with(csrf()).with(authentication(operator())).session(session)
        if (mode != null) req = req.param("mode", mode)
        return mvc.perform(req).andReturn()
    }

    @Test
    fun mfaSuccessRedirectsHome() {
        whenever(mfa.verify(anyOrNull(), anyOrNull(), anyOrNull())).thenAnswer { ResponseEntity.ok(mapOf("verified" to true)) }
        val r = submit(" 123456 ", "totp", pendingSession())
        assertThat(r.response.status).isEqualTo(200)
        assertThat(r.response.getHeader("HX-Redirect")).isEqualTo("/admin")
        assertThat(r.response.getHeader("HX-Trigger")).isNull()
        verify(mfa).verify(argThat { "123456" == code }, any<Authentication>(), any<HttpServletRequest>())
    }

    @Test
    fun mfaRecoveryCodeShowsRemainingToast() {
        whenever(mfa.verify(anyOrNull(), anyOrNull(), anyOrNull())).thenAnswer {
            ResponseEntity.ok(mapOf("verified" to true, "usedRecoveryCode" to true, "remaining" to 3L))
        }
        val r = submit("AB3F-2K7M", "recovery", pendingSession())
        assertThat(r.response.getHeader("HX-Redirect")).isEqualTo("/admin")
        val toast = json.readTree(r.response.getHeader("HX-Trigger")).get("toast")
        assertThat(toast.get("kind").asString()).isEqualTo("warn")
        assertThat(toast.get("title").asString()).isEqualTo("복구 코드를 사용했습니다.")
        assertThat(toast.get("message").asString()).isEqualTo("남은 복구 코드: 3개")
        verify(mfa).verify(argThat { "AB3F-2K7M" == code }, anyOrNull(), anyOrNull())
    }

    @Test
    fun mfaWrongCodeShowsError(output: CapturedOutput) {
        whenever(mfa.verify(anyOrNull(), anyOrNull(), anyOrNull())).thenAnswer {
            ResponseEntity.status(401).body(mapOf("error" to "invalid_code"))
        }
        val r = submit("999999", "totp", pendingSession())
        assertThat(r.response.status).isEqualTo(422)
        assertThat(r.response.getHeader("HX-Redirect")).isNull()
        assertThat(r.response.getHeader("HX-Trigger")).isNull() // 토스트 없음 — 카드 안 경고 하나
        val body = r.response.contentAsString
        assertThat(body).doesNotContain("<html").doesNotContain("<form")
        val d = Jsoup.parseBodyFragment(body)
        assertThat(d.select("[role=alert]")).hasSize(1)
        assertThat(d.text()).contains("코드가 맞지 않습니다.").contains("앱에 지금 표시된 코드를 다시 입력하세요.")
            .doesNotContain("복구 코드")
        assertThat(output.all).doesNotContain("999999")

        val rec = submit("ZZZZ-ZZZZ", "recovery", pendingSession())
        assertThat(rec.response.status).isEqualTo(422)
        assertThat(Jsoup.parseBodyFragment(rec.response.contentAsString).text())
            .contains("복구 코드가 맞지 않습니다.").contains("저장해 둔 복구 코드 중 아직 쓰지 않은 코드를 입력하세요.")
        assertThat(output.all).doesNotContain("ZZZZ-ZZZZ")
    }

    @Test
    fun mfaLockoutRedirectsToLogin() {
        // verify 가 실패로 잠금을 걸며 대기 세션을 무효화하고 401 을 돌려준 경우
        whenever(mfa.verify(anyOrNull(), anyOrNull(), anyOrNull())).thenAnswer { inv ->
            inv.getArgument(2, HttpServletRequest::class.java).getSession(false).invalidate()
            ResponseEntity.status(401).body(mapOf("error" to "invalid_code"))
        }
        val r = submit("999999", "totp", pendingSession())
        assertThat(r.response.status).isEqualTo(200)
        assertThat(r.response.getHeader("HX-Redirect")).isEqualTo("/admin/login")
        assertThat(r.response.contentAsString).isEmpty()
    }

    @Test
    fun mfaLockoutWithoutHtmxIs302() {
        whenever(mfa.verify(anyOrNull(), anyOrNull(), anyOrNull())).thenAnswer { inv ->
            inv.getArgument(2, HttpServletRequest::class.java).getSession(false).invalidate()
            ResponseEntity.status(401).body(mapOf("error" to "invalid_code"))
        }
        mvc.perform(
            post("/admin/login/mfa").param("code", "999999").with(csrf()).with(authentication(operator()))
                .session(pendingSession()),
        )
            .andExpect(status().isFound())
            .andExpect(redirectedUrl("/admin/login"))
    }

    @Test
    fun mfaUnexpectedErrorIs500WithoutToast(output: CapturedOutput) {
        whenever(mfa.verify(anyOrNull(), anyOrNull(), anyOrNull())).thenThrow(IllegalStateException("db down"))
        val r = submit("123456", "totp", pendingSession())
        assertThat(r.response.status).isEqualTo(500)
        assertThat(r.response.getHeader("HX-Trigger")).isNull()
        assertThat(r.response.getHeader("HX-Redirect")).isNull()
        assertThat(r.response.contentAsString).isEmpty()
        assertThat(output.all).doesNotContain("123456")
    }

    @Test
    fun mfaSubmitWhenNotPendingSkipsVerify() {
        val r = submit("123456", "totp", MockHttpSession())
        assertThat(r.response.getHeader("HX-Redirect")).isEqualTo("/admin")
        verify(mfa, never()).verify(anyOrNull(), anyOrNull(), anyOrNull())
    }

    @Test
    fun mfaSubmitRequiresCsrf() {
        mvc.perform(
            post("/admin/login/mfa").param("code", "123456").header("HX-Request", "true")
                .with(authentication(operator())).session(pendingSession()),
        )
            .andExpect(status().isForbidden())
        verify(mfa, never()).verify(anyOrNull(), anyOrNull(), anyOrNull())
    }

    @Test
    fun mfaSubmitAnonymousGoesToLogin() {
        mvc.perform(post("/admin/login/mfa").param("code", "123456").header("HX-Request", "true").with(csrf()))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("HX-Redirect", "/admin/login"))
            .andExpect(content().string(emptyString()))
        verify(mfa, never()).verify(anyOrNull(), anyOrNull(), anyOrNull())
    }
}
