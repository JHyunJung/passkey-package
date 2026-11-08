package com.crosscert.passkey.admin.web.settings

import com.crosscert.passkey.admin.audit.AuditChainMonitorController
import com.crosscert.passkey.admin.auth.ActiveTenantController
import com.crosscert.passkey.admin.auth.AdminUserDetails
import com.crosscert.passkey.admin.auth.MfaController
import com.crosscert.passkey.admin.license.LicenseController
import com.crosscert.passkey.admin.license.LicenseView
import com.crosscert.passkey.admin.operator.SignupRequestController
import com.crosscert.passkey.admin.system.SystemInfoController
import com.crosscert.passkey.admin.web.AdminWebSliceTest
import com.crosscert.passkey.core.api.ApiResponse
import com.crosscert.passkey.core.entity.AdminUser
import com.crosscert.passkey.core.repository.AdminUserRepository
import org.assertj.core.api.Assertions.assertThat
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.Collections
import java.util.Optional
import java.util.UUID

/**
 * 설정 › 내 계정 탭(MFA). 원본: AccountTab.test.tsx, RecoveryCodesModal.test.tsx, api/mfa.ts.
 * 다이얼로그 열기·OTP 입력·포커스·복사·다운로드는 Alpine 이라 속성·마크업까지만 본다(브라우저 미확인).
 *
 * (Kotlin 전환) 즉시 역참조하는 nullable jsoup 결과(`selectFirst`·`first`·`last`)의 `!!` 는 원 Java 의 NPE 실패 지점과 같다.
 */
@AdminWebSliceTest(controllers = [AccountTabController::class, SettingsPageController::class, SystemInfoTabController::class])
class AccountTabControllerTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var clock: Clock
    @Autowired lateinit var users: AdminUserRepository
    @MockitoBean lateinit var mfa: MfaController
    @MockitoBean lateinit var signups: SignupRequestController
    @MockitoBean lateinit var systemInfo: SystemInfoController
    @MockitoBean lateinit var license: LicenseController
    @MockitoBean lateinit var chain: AuditChainMonitorController
    @MockitoBean lateinit var activeTenants: ActiveTenantController

    companion object {
        val JSON = ObjectMapper()
        val NOW: Instant = Instant.parse("2026-10-11T00:00:00Z")
        const val EMAIL = "alice@example.com"
        val T1: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        const val SECRET = "JBSWY3DPEHPK3PXP"
        const val URI = "otpauth://totp/Passkey%20Admin:alice%40example.com?secret=" + SECRET + "&issuer=Passkey%20Admin"
        val CODES: List<String> = listOf(
            "AAAA-BBBB", "CCCC-DDDD", "EEEE-FFFF", "GGGG-HHHH", "JJJJ-KKKK",
            "LLLL-MMMM", "NNNN-PPPP", "QQQQ-RRRR", "SSSS-TTTT", "UUUU-VVVV",
        )
        const val CODE_MISMATCH = "코드가 맞지 않습니다. 앱에 표시된 새 코드(30초마다 바뀜)를 입력하세요."
        const val RC_TITLE = "복구 코드 — 지금만 표시됩니다"

        // ── fixtures ────────────────────────────────────────────

        fun user(mfaEnabled: Boolean): AdminUser {
            val u = AdminUser(EMAIL, "x", "RP_ADMIN")
            u.setMfaEnabled(mfaEnabled)
            return u
        }

        fun rp(): Authentication = auth("RP_ADMIN", setOf(T1))
        fun platform(): Authentication = auth("PLATFORM_OPERATOR", setOf())

        private fun auth(role: String, tenants: Set<UUID>): Authentication {
            val p = AdminUserDetails(UUID.randomUUID(), EMAIL, "x", role, tenants, true, null, Clock.systemUTC())
            return UsernamePasswordAuthenticationToken(p, "x", p.authorities)
        }

        private fun htmx(b: MockHttpServletRequestBuilder, a: Authentication): MockHttpServletRequestBuilder =
            b.with(authentication(a)).with(csrf()).header("HX-Request", "true")

        private fun body(r: MvcResult): String = r.response.getContentAsString(StandardCharsets.UTF_8)

        private fun doc(r: MvcResult): Document = Jsoup.parse(body(r))

        private fun trigger(r: MvcResult): JsonNode {
            val h = r.response.getHeader("HX-Trigger")
            assertThat(h).`as`("HX-Trigger").isNotNull()
            return JSON.readTree(h)
        }
    }

    @BeforeEach
    fun setUp() {
        whenever(clock.instant()).thenReturn(NOW)
        whenever(clock.zone).thenReturn(ZoneOffset.UTC)
        whenever(license.get()).thenReturn(ApiResponse.ok(LicenseView("saas", null, null, null, null, 0, listOf(), null, null, null)))
        whenever(users.findByEmail(anyString())).thenReturn(Optional.of(user(false)))
    }

    private fun tab(a: Authentication): MvcResult = mvc.perform(htmx(get(AccountTabController.TAB_PATH), a)).andReturn()

    private fun enroll(): MvcResult = mvc.perform(htmx(post(AccountTabController.ENROLL_PATH), rp())).andReturn()

    private fun confirm(code: String): MvcResult =
        mvc.perform(htmx(post(AccountTabController.CONFIRM_PATH), rp()).param("code", code)).andReturn()

    private fun disable(code: String): MvcResult =
        mvc.perform(htmx(post(AccountTabController.DISABLE_PATH), rp()).param("code", code)).andReturn()

    private fun stubEnrollOk() {
        whenever(mfa.enroll(anyOrNull(), anyOrNull())).thenAnswer { ResponseEntity.ok(mapOf("secret" to SECRET, "otpauthUri" to URI)) }
    }

    private fun stubConfirmOk() {
        whenever(mfa.confirm(anyOrNull(), anyOrNull(), anyOrNull())).thenAnswer { ResponseEntity.ok(mapOf("confirmed" to true, "recoveryCodes" to CODES)) }
    }

    // ── MFA 꺼짐 ────────────────────────────────────────────

    @Test
    @DisplayName("꺼짐: 배지·일러스트(장식)·켜기 카드, 켜기 버튼은 enroll 조각 요청, 조각은 레이아웃 없이")
    fun offShowsEnrollCard() {
        val r = tab(rp())
        assertThat(r.response.status).isEqualTo(200)
        val d = doc(r)
        assertThat(d.select("aside, nav.sidebar, header.topbar")).isEmpty()
        val card = d.selectFirst("#mfa-account")!!
        assertThat(card.select("h2.card__title").text()).isEqualTo("2단계 인증 (MFA)")
        val badge = card.selectFirst(".badge")!!
        assertThat(badge.text()).isEqualTo("꺼짐")
        assertThat(badge.hasClass("badge--success")).isFalse()
        assertThat(card.text()).contains("로그인 시 비밀번호에 더해 authenticator 앱의 6자리 코드를 요구합니다.")
            .contains("계정을 한 단계 더 보호하세요")
            .contains("3단계로 끝납니다 — 앱으로 QR 스캔 → 코드 확인 → 복구 코드 저장.")
        assertThat(card.selectFirst("img")!!.attr("alt")).isEmpty()
        val btn = card.selectFirst("button.btn--primary")!!
        assertThat(btn.text()).isEqualTo("2단계 인증 켜기")
        assertThat(btn.attr("hx-post")).isEqualTo(AccountTabController.ENROLL_PATH)
        assertThat(btn.attr("hx-target")).isEqualTo("#mfa-enroll-slot")
        assertThat(btn.attr("hx-disabled-elt")).isEqualTo("this")
        assertThat(card.text()).doesNotContain("위험 영역")
        assertThat(d.select("#mfa-enroll-slot, #mfa-rc-slot")).hasSize(2)
        // 슬롯은 카드 바깥(카드 교체가 다이얼로그를 지우지 않는다)
        assertThat(card.select("#mfa-enroll-slot, #mfa-rc-slot")).isEmpty()
    }

    // ── MFA 켜짐 ────────────────────────────────────────────

    @Test
    @DisplayName("켜짐: badge--success, 등록일·남은 복구 코드 줄 없음(서버 값 없음), 보관 안내, 위험 영역 + 끄기 확인창(alertdialog)")
    fun onShowsDangerZone() {
        whenever(users.findByEmail(anyString())).thenReturn(Optional.of(user(true)))
        val d = doc(tab(rp()))
        val card = d.selectFirst("#mfa-account")!!
        assertThat(card.selectFirst(".badge")!!.text()).isEqualTo("켜짐")
        assertThat(card.selectFirst(".badge")!!.hasClass("badge--success")).isTrue()
        assertThat(card.text()).contains("로그인 시 authenticator 코드가 필요합니다.")
            .doesNotContain("등록일").doesNotContain("남은 복구 코드")
            .contains("복구 코드 보관").contains("한 번만 표시됩니다")
            .contains("위험 영역").contains("끄면 비밀번호만으로 로그인됩니다. 현재 authenticator 코드가 필요합니다.")
        assertThat(card.select("button").stream().map { it.text() }).contains("2단계 인증 끄기")
        assertThat(card.text()).doesNotContain("계정을 한 단계 더 보호하세요")

        val tpl = card.selectFirst("template")!!
        val dlg = Jsoup.parse(tpl.html())
        val dialog = dlg.selectFirst("[role=alertdialog]")!!
        assertThat(dialog.attr("aria-labelledby")).isEqualTo("mfa-disable-title")
        assertThat(dlg.selectFirst("#mfa-disable-title")!!.text()).isEqualTo("2단계 인증 끄기")
        assertThat(dlg.text()).contains("본인 확인을 위해 현재 authenticator 코드를 입력하세요.")
        val input = dlg.selectFirst("#mfa-disable-code")!!
        assertThat(dlg.selectFirst("label[for=mfa-disable-code]")!!.text()).isEqualTo("인증 코드")
        assertThat(input.attr("name")).isEqualTo("code")
        assertThat(input.attr("inputmode")).isEqualTo("numeric")
        assertThat(input.attr("autocomplete")).isEqualTo("one-time-code")
        assertThat(input.attr("placeholder")).isEqualTo("000000")
        assertThat(input.attr("x-bind:readonly")).isEqualTo("disabling")
        val form = dlg.selectFirst("#mfa-disable-form")!!
        assertThat(form.attr("hx-post")).isEqualTo(AccountTabController.DISABLE_PATH)
        assertThat(form.attr("hx-target")).isEqualTo("#mfa-account")
        assertThat(form.attr("hx-swap")).isEqualTo("outerHTML")
        assertThat(form.hasAttr("data-quiet")).isTrue()
        val off = dlg.selectFirst("button[type=submit]")!!
        assertThat(off.text()).isEqualTo("끄기")
        assertThat(off.hasClass("btn--danger-solid")).isTrue()
        assertThat(off.hasAttr("disabled")).isTrue()
        assertThat(off.attr("x-bind:disabled")).isEqualTo("code.length !== 6 || disabling")
        assertThat(dlg.select("button").stream().map { it.text() }).contains("취소")
    }

    // ── 켜기 1–2 / 3 ────────────────────────────────────────

    @Test
    @DisplayName("enroll: QR(data URI)·수동 키·진행 표시·OTP 6칸·확인 버튼(6자리 전 비활성), no-store·hx-history=false")
    fun enrollRendersDialog() {
        stubEnrollOk()
        val r = enroll()
        assertThat(r.response.status).isEqualTo(200)
        assertThat(r.response.getHeader("Cache-Control")).contains("no-store")
        val d = doc(r)
        val wrap = d.selectFirst("[data-mfa-wrap]")!!
        assertThat(wrap.attr("hx-history")).isEqualTo("false")
        val inner = Jsoup.parse(wrap.selectFirst("template")!!.html())
        val dialog = inner.selectFirst("[role=dialog]")!!
        assertThat(dialog.attr("aria-label")).isEqualTo("2단계 인증 켜기")
        assertThat(inner.select(".stepper__label").eachText()).containsExactly("QR 스캔", "코드 확인", "복구 코드 저장")
        assertThat(inner.text()).contains("1–2 / 3").contains("앱으로 스캔하고 코드 확인").contains("또는 수동 입력 키")
            .contains("Google Authenticator · 1Password 등 TOTP 앱에 입력하세요.").contains("앱에 표시된 인증 코드")
        assertThat(inner.selectFirst(".secret__value")!!.text()).isEqualTo(SECRET)
        assertThat(inner.selectFirst("img[alt=QR 코드]")!!.attr("src")).startsWith("data:image/svg+xml;base64,")
        assertThat(inner.select(".otp__cell")).hasSize(6)
        assertThat(inner.selectFirst(".otp__cell")!!.attr("aria-label")).isEqualTo("인증 코드 1번째 자리")
        val form = inner.selectFirst("#mfa-confirm-form")!!
        assertThat(form.attr("hx-post")).isEqualTo(AccountTabController.CONFIRM_PATH)
        assertThat(form.attr("hx-target")).isEqualTo("#mfa-rc-slot")
        assertThat(form.hasAttr("data-quiet")).isTrue()
        assertThat(form.selectFirst("input[type=hidden][name=code]")).isNotNull()
        val ok = inner.selectFirst("button[type=submit][form=mfa-confirm-form]")!!
        assertThat(ok.text()).isEqualTo("확인 → 복구 코드")
        assertThat(ok.hasAttr("disabled")).isTrue()
        assertThat(ok.attr("x-bind:disabled")).isEqualTo("code.length !== 6 || confirming")
        assertThat(inner.select("button").stream().map { it.text() }).contains("취소")
        // 진행 표시는 코드 입력 여부로 바뀐다(Alpine stepState).
        assertThat(inner.select(".stepper__item")[0].attr("x-bind:aria-current")).contains("stepState(0)")
        assertThat(inner.select(".stepper__item")[2].hasClass("stepper__item--todo")).isTrue()
        // secret 은 본문에만
        for (h in r.response.headerNames) {
            for (v in r.response.getHeaders(h)) assertThat(v).`as`(h).doesNotContain(SECRET)
        }
    }

    @Test
    @DisplayName("qrIsInlineDataUriNoExternal: 켜기 응답의 QR 은 인라인 data URI, 디코드한 SVG 에 외부 참조 없음")
    fun qrIsInlineDataUriNoExternal() {
        stubEnrollOk()
        val d = doc(enroll())
        val inner = Jsoup.parse(d.selectFirst("template")!!.html())
        val src = inner.selectFirst("img[alt=QR 코드]")!!.attr("src")
        assertThat(src).startsWith("data:image/svg+xml;base64,")
        val svg = String(Base64.getDecoder().decode(src.substring("data:image/svg+xml;base64,".length)), StandardCharsets.UTF_8)
        assertThat(svg).startsWith("<svg").contains("width=\"152\"")
        assertThat(svg.replace("xmlns=\"http://www.w3.org/2000/svg\"", "")).doesNotContain("http").doesNotContain("//")
            .doesNotContain("href").doesNotContain("url(").doesNotContain("<image").doesNotContain("<script")
        assertThat(body(enroll())).doesNotContain("<svg xmlns") // 인라인 SVG(th:utext) 아님
    }

    @Test
    @DisplayName("enroll 실패: 오류 토스트 '2단계 인증을 시작하지 못했습니다' + 실제 상태 + 빈 본문")
    fun enrollFailureToast() {
        whenever(mfa.enroll(anyOrNull(), anyOrNull())).thenAnswer { ResponseEntity.status(HttpStatus.FORBIDDEN).body(mapOf("error" to "mfa_required")) }
        val r = enroll()
        assertThat(r.response.status).isEqualTo(403)
        val t = trigger(r).get("toast")
        assertThat(t.get("kind").asString()).isEqualTo("error")
        assertThat(t.get("title").asString()).isEqualTo("2단계 인증을 시작하지 못했습니다")
        assertThat(t.get("message").asString()).isEqualTo("권한이 없습니다. 이 작업을 할 권한이 없습니다. 운영자에게 문의하세요.")
        assertThat(body(r).trim { it <= ' ' }).isEmpty()

        whenever(mfa.enroll(anyOrNull(), anyOrNull())).thenThrow(IllegalStateException("db secret leaked"))
        val boom = enroll()
        assertThat(boom.response.status).isEqualTo(500)
        assertThat(trigger(boom).toString()).contains("서버가 잠시 응답하지 않습니다").doesNotContain("leaked")
    }

    // ── 확인 → 복구 코드 ────────────────────────────────────

    @Test
    @DisplayName("confirm 성공: 복구 코드 alertdialog(10개, 01–10, 목록 이름), 파일명, 체크 전 닫기 비활성, 켜기 다이얼로그 닫기, 카드 OOB(켜짐), 성공 토스트")
    fun confirmShowsRecoveryCodes() {
        stubConfirmOk()
        whenever(users.findByEmail(anyString())).thenReturn(Optional.of(user(false)), Optional.of(user(true)))
        val r = confirm("123456")
        assertThat(r.response.status).isEqualTo(200)
        val req = argumentCaptor<MfaController.VerifyRequest>()
        verify(mfa).confirm(req.capture(), anyOrNull(), anyOrNull())
        assertThat(req.lastValue.code).isEqualTo("123456")

        val d = doc(r)
        assertThat(d.selectFirst("[x-init]")!!.attr("x-init")).contains("mfa-enroll-done")
        val wrap = d.selectFirst("[data-mfa-wrap]")!!
        assertThat(wrap.attr("hx-history")).isEqualTo("false")
        assertThat(wrap.attr("x-data")).contains("\"dismissible\":false").contains("\"closeOnScrim\":false")
        val inner = Jsoup.parse(wrap.selectFirst("template")!!.html())
        val dialog = inner.selectFirst("[role=alertdialog]")!!
        assertThat(dialog.attr("aria-label")).isEqualTo(RC_TITLE)
        assertThat(dialog.attr("aria-modal")).isEqualTo("true")
        assertThat(dialog.attr("data-filename")).isEqualTo("passkey-admin-recovery-codes-alice-20261011.txt")
        assertThat(inner.selectFirst("img")!!.attr("alt")).isEmpty()
        val list = inner.selectFirst("ol[aria-label=복구 코드 목록]")!!
        assertThat(list.children()).hasSize(10)
        assertThat(list.select("[data-rc-code]").eachText()).containsExactlyElementsOf(CODES)
        assertThat(list.select(".rc-list__no").first()!!.text()).isEqualTo("01")
        assertThat(list.select(".rc-list__no").last()!!.text()).isEqualTo("10")
        assertThat(inner.text()).contains("authenticator 기기를 잃었을 때 각 코드를 한 번씩 로그인에 쓸 수 있습니다.")
            .contains("다운로드 (.txt)").contains("복사 실패 시: 직접 선택하거나 다운로드")
            .contains("복사에 실패했습니다. 위 코드를 직접 선택해 복사하거나 다운로드하세요.")
        assertThat(inner.selectFirst("label.rc-ack")!!.text()).isEqualTo("안전한 곳에 저장했습니다.")
        assertThat(inner.selectFirst("label.rc-ack input[type=checkbox]")).isNotNull()
        val close = inner.selectFirst(".dialog__foot button")!!
        assertThat(close.text()).isEqualTo("닫기 · 체크 필요")
        assertThat(close.hasAttr("disabled")).isTrue()
        assertThat(close.attr("x-bind:disabled")).isEqualTo("!checked")

        val oob = d.selectFirst("#mfa-account")!!
        assertThat(oob.attr("hx-swap-oob")).isEqualTo("true")
        assertThat(oob.selectFirst(".badge")!!.text()).isEqualTo("켜짐")
        val t = trigger(r).get("toast")
        assertThat(t.get("kind").asString()).isEqualTo("success")
        assertThat(t.get("title").asString()).isEqualTo("2단계 인증이 켜졌습니다.")
        assertThat(r.response.getHeader("Cache-Control")).contains("no-store")
    }

    @Test
    @DisplayName("recoveryCodesNotStoredInSession: 코드는 응답 본문에만 — 세션 속성·플래시·헤더·토스트·리다이렉트에 없음")
    fun recoveryCodesNotStoredInSession() {
        stubConfirmOk()
        val r = confirm("123456")
        val b = body(r)
        for (c in CODES) assertThat(b).contains(c)
        val s = r.request.getSession(false)
        if (s != null) {
            for (n in Collections.list(s.attributeNames)) {
                val v = java.lang.String.valueOf(s.getAttribute(n)) // 원 Java 와 같이 null → "null"
                for (c in CODES) assertThat(v).`as`("session attribute $n").doesNotContain(c)
            }
        }
        assertThat(r.flashMap.isEmpty()).isTrue()
        for (h in r.response.headerNames) {
            for (v in r.response.getHeaders(h)) {
                for (c in CODES) assertThat(v).`as`("header $h").doesNotContain(c)
            }
        }
        assertThat(r.response.redirectedUrl).isNull()
        assertThat(r.response.getHeader("HX-Redirect")).isNull()
        assertThat(r.response.getHeader("Cache-Control")).contains("no-store")
        assertThat(r.response.getHeader("Pragma")).isEqualTo("no-cache")
    }

    @Test
    @DisplayName("confirm 후 계정 정보 새로고침이 실패해도 복구 코드는 보여 준다(카드 OOB 없음, 경고 토스트)")
    fun confirmMeReloadFailureStillShowsCodes() {
        stubConfirmOk()
        whenever(users.findByEmail(anyString())).thenReturn(Optional.of(user(false))).thenThrow(IllegalStateException("db"))
        val r = confirm("123456")
        assertThat(r.response.status).isEqualTo(200)
        val d = doc(r)
        assertThat(Jsoup.parse(d.selectFirst("template")!!.html()).select("[data-rc-code]").eachText()).contains("AAAA-BBBB")
        assertThat(d.selectFirst("#mfa-account")).isNull()
        val t = trigger(r).get("toast")
        assertThat(t.get("kind").asString()).isEqualTo("warn")
        assertThat(t.get("title").asString()).isEqualTo("계정 정보를 새로고침하지 못했습니다.")
        assertThat(t.get("message").asString()).isEqualTo("페이지를 새로고침해 주세요.")
        assertThat(trigger(r).toString()).doesNotContain("2단계 인증 확인 실패")
    }

    @Test
    @DisplayName("잘못된 코드(401 invalid_code): 다이얼로그 안 인라인(422 + #mfa-enroll-error), 토스트 없음")
    fun confirmInvalidCodeInline() {
        whenever(mfa.confirm(anyOrNull(), anyOrNull(), anyOrNull())).thenAnswer { ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "invalid_code")) }
        val r = confirm("000000")
        assertThat(r.response.status).isEqualTo(422)
        assertThat(r.response.getHeader("HX-Retarget")).isEqualTo("#mfa-enroll-error")
        assertThat(r.response.getHeader("HX-Reswap")).isEqualTo("innerHTML")
        assertThat(r.response.getHeader("HX-Trigger")).isNull()
        val p = doc(r).selectFirst("p.field__error[role=alert]")!!
        assertThat(p.text()).isEqualTo(CODE_MISMATCH)
    }

    @Test
    @DisplayName("403(권한)·5xx·예외는 코드 불일치가 아니라 쉬운 문구로 인라인(원문 없음)")
    fun confirmOtherFailuresInline() {
        whenever(mfa.confirm(anyOrNull(), anyOrNull(), anyOrNull())).thenAnswer { ResponseEntity.status(HttpStatus.FORBIDDEN).body(mapOf("error" to "mfa_required")) }
        val r = confirm("123456")
        assertThat(r.response.status).isEqualTo(422)
        assertThat(doc(r).text()).contains("권한이 없습니다").doesNotContain(CODE_MISMATCH)

        whenever(mfa.confirm(anyOrNull(), anyOrNull(), anyOrNull())).thenThrow(IllegalStateException("ORA-00001 secret"))
        val boom = confirm("123456")
        assertThat(boom.response.status).isEqualTo(422)
        assertThat(doc(boom).text()).isEqualTo("서버가 잠시 응답하지 않습니다. 저장된 데이터는 그대로 있습니다. 잠시 후 다시 시도하세요.")
        assertThat(body(boom)).doesNotContain("ORA").doesNotContain("secret")
        assertThat(boom.response.getHeader("HX-Trigger")).isNull()
    }

    // ── 끄기 ────────────────────────────────────────────────

    @Test
    @DisplayName("끄기: disable(code) 호출, 성공하면 카드가 꺼짐으로(#mfa-account 교체) + 경고 토스트")
    fun disableSuccess() {
        whenever(users.findByEmail(anyString())).thenReturn(Optional.of(user(true)), Optional.of(user(false)))
        whenever(mfa.disable(anyOrNull(), anyOrNull(), anyOrNull())).thenAnswer { ResponseEntity.ok(mapOf("disabled" to true)) }
        val r = disable("123456")
        assertThat(r.response.status).isEqualTo(200)
        val req = argumentCaptor<MfaController.VerifyRequest>()
        verify(mfa).disable(req.capture(), anyOrNull(), anyOrNull())
        assertThat(req.lastValue.code).isEqualTo("123456")
        val d = doc(r)
        val card = d.selectFirst("#mfa-account")!!
        assertThat(card.hasAttr("hx-swap-oob")).isFalse()
        assertThat(card.selectFirst(".badge")!!.text()).isEqualTo("꺼짐")
        assertThat(card.text()).contains("2단계 인증 켜기")
        val t = trigger(r).get("toast")
        assertThat(t.get("kind").asString()).isEqualTo("warn")
        assertThat(t.get("title").asString()).isEqualTo("2단계 인증이 꺼졌습니다.")
    }

    @Test
    @DisplayName("끄기 실패: 401 invalid_code → 코드 불일치, 429 → 요청 과다 문구, 확인창 안 인라인(422 + #mfa-disable-err), 토스트 없음")
    fun disableFailureInline() {
        whenever(mfa.disable(anyOrNull(), anyOrNull(), anyOrNull())).thenAnswer { ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "invalid_code")) }
        val r = disable("123456")
        assertThat(r.response.status).isEqualTo(422)
        assertThat(r.response.getHeader("HX-Retarget")).isEqualTo("#mfa-disable-err")
        assertThat(r.response.getHeader("HX-Trigger")).isNull()
        assertThat(doc(r).text()).isEqualTo(CODE_MISMATCH)

        whenever(mfa.disable(anyOrNull(), anyOrNull(), anyOrNull())).thenAnswer { ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(mapOf("error" to "error")) }
        val many = disable("123456")
        assertThat(doc(many).text()).contains("요청이 너무 많습니다").doesNotContain(CODE_MISMATCH)
    }

    @Test
    @DisplayName("끄기 성공 뒤 계정 정보 새로고침 실패: 카드는 그대로(HX-Reswap none) + 경고 토스트")
    fun disableMeReloadFailure() {
        whenever(users.findByEmail(anyString())).thenReturn(Optional.of(user(true))).thenThrow(IllegalStateException("db"))
        whenever(mfa.disable(anyOrNull(), anyOrNull(), anyOrNull())).thenAnswer { ResponseEntity.ok(mapOf("disabled" to true)) }
        val r = disable("123456")
        assertThat(r.response.status).isEqualTo(200)
        assertThat(r.response.getHeader("HX-Reswap")).isEqualTo("none")
        assertThat(trigger(r).get("toast").get("title").asString()).isEqualTo("계정 정보를 새로고침하지 못했습니다.")
    }

    @Test
    @DisplayName("MFA 변경 요청은 CSRF 가 필요하다")
    fun mutationsRequireCsrf() {
        val r = mvc.perform(post(AccountTabController.CONFIRM_PATH).with(authentication(rp())).header("HX-Request", "true").param("code", "123456")).andReturn()
        assertThat(r.response.status).isEqualTo(403)
        verify(mfa, never()).confirm(anyOrNull(), anyOrNull(), anyOrNull())
    }

    @Test
    @DisplayName("codeErrorText: 401 invalid_code 만 코드 불일치, 그 밖은 describeError 의 '제목. 설명'")
    fun codeErrorTextMapping() {
        assertThat(AccountTabController.codeErrorText(401, "invalid_code")).isEqualTo(CODE_MISMATCH)
        assertThat(AccountTabController.codeErrorText(401, "unauthorized")).isEqualTo("요청을 처리하지 못했습니다. unauthorized")
        assertThat(AccountTabController.codeErrorText(403, "mfa_required")).isEqualTo("권한이 없습니다. 이 작업을 할 권한이 없습니다. 운영자에게 문의하세요.")
        assertThat(AccountTabController.codeErrorText(429, "error")).isEqualTo("요청이 너무 많습니다. 잠시 후 다시 시도하세요.")
        assertThat(AccountTabController.codeErrorText(503, "error")).startsWith("서버가 잠시 응답하지 않습니다.")
    }

    @Test
    @DisplayName("PLATFORM 도 같은 내 계정 탭")
    fun platformSameAccountTab() {
        val d = doc(tab(platform()))
        assertThat(d.selectFirst("#mfa-account")).isNotNull()
    }
}
