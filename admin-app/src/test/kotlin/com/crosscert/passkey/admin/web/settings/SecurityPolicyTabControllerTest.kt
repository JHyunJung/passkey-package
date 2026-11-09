package com.crosscert.passkey.admin.web.settings

import com.crosscert.passkey.admin.audit.AuditChainMonitorController
import com.crosscert.passkey.admin.auth.ActiveTenantController
import com.crosscert.passkey.admin.auth.AdminUserDetails
import com.crosscert.passkey.admin.auth.MfaController
import com.crosscert.passkey.admin.license.LicenseController
import com.crosscert.passkey.admin.operator.SignupRequestController
import com.crosscert.passkey.admin.policy.SecurityPolicyController
import com.crosscert.passkey.admin.policy.SecurityPolicyDto
import com.crosscert.passkey.admin.system.SystemInfoController
import com.crosscert.passkey.admin.web.AdminWebSliceTest
import com.crosscert.passkey.admin.web.format.PolicyValidation
import org.assertj.core.api.Assertions.assertThat
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

/**
 * 설정 › 보안 정책 탭. 원본: SecurityPolicyTab.test.tsx, lib/policyValidation.ts. MockMvc 의 콘솔 origin 은 http://localhost.
 *
 * (Kotlin 전환) 즉시 역참조하는 nullable jsoup 결과(`selectFirst`)의 `!!` 는 원 Java 의 NPE 실패 지점과 같다.
 */
@AdminWebSliceTest(controllers = [SecurityPolicyTabController::class, SettingsPageController::class, AccountTabController::class])
class SecurityPolicyTabControllerTest {

    @Autowired lateinit var mvc: MockMvc
    @MockitoBean lateinit var policy: SecurityPolicyController
    @MockitoBean lateinit var mfa: MfaController
    @MockitoBean lateinit var signups: SignupRequestController
    @MockitoBean lateinit var systemInfo: SystemInfoController
    @MockitoBean lateinit var license: LicenseController
    @MockitoBean lateinit var chain: AuditChainMonitorController
    @MockitoBean lateinit var activeTenants: ActiveTenantController

    companion object {
        const val CONSOLE = "http://localhost"
        const val SAVE = SecurityPolicyTabController.SAVE_PATH

        fun view(idle: Int, mfa: Boolean, vararg cors: String): SecurityPolicyDto.View =
            SecurityPolicyDto.View(idle, mfa, listOf(*cors), OffsetDateTime.parse("2026-10-10T00:00:00Z"), "a@x.com")

        fun platform(): Authentication = auth("PLATFORM_OPERATOR", setOf())
        fun rp(): Authentication = auth("RP_ADMIN", setOf(UUID.randomUUID()))

        private fun auth(role: String, tenants: Set<UUID>): Authentication {
            val p = AdminUserDetails(UUID.randomUUID(), "a@x.com", "x", role, tenants, true, null, Clock.systemUTC())
            return UsernamePasswordAuthenticationToken(p, "x", p.authorities)
        }

        private fun save(idle: String, mfa: String, vararg cors: String): MockHttpServletRequestBuilder {
            val b = put(SAVE).param("sessionIdleTimeoutMinutes", idle).param("mfaRequired", mfa)
            if (cors.isNotEmpty()) b.param("corsAllowlist", *cors)
            return b
        }

        private fun html(r: MvcResult): Document = Jsoup.parse(r.response.getContentAsString(StandardCharsets.UTF_8))

        private fun trigger(r: MvcResult): String? {
            val h = r.response.getHeader("HX-Trigger")
            return if (h == null) null else tools.jackson.databind.ObjectMapper().readValue(h, Map::class.java).toString()
        }
    }

    @BeforeEach
    fun setUp() {
        whenever(policy.get()).thenReturn(view(30, true, "https://a.example.com", CONSOLE))
        whenever(policy.update(anyOrNull(), anyOrNull())).thenReturn(view(45, false, "https://a.example.com", CONSOLE))
        whenever(signups.list()).thenReturn(listOf())
    }

    private fun send(b: MockHttpServletRequestBuilder, a: Authentication, confirm: Boolean): MvcResult {
        val r = b.with(authentication(a)).with(csrf()).header("HX-Request", "true")
        if (confirm) r.header("X-Confirm", "1")
        return mvc.perform(r).andReturn()
    }

    private fun tab(): Document {
        val r = send(get(SecurityPolicyTabController.TAB_PATH), platform(), false)
        assertThat(r.response.status).isEqualTo(200)
        return html(r)
    }

    @Test
    @DisplayName("값 표시: idle(1–1440 안내)·MFA 스위치·CORS 칩, 카드 제목+캡션, 저장 바 라벨(저장·취소), PUT 폼")
    fun rendersPolicy() {
        val d = tab()
        assertThat(d.selectFirst(".card__title")!!.text()).isEqualTo("세션 & 인증 정책")
        assertThat(d.selectFirst(".card__cap")!!.text()).isEqualTo("모든 콘솔 운영자에게 적용")
        val idle = d.selectFirst("#sec-idle-timeout")!!
        assertThat(idle.`val`()).isEqualTo("30")
        assertThat(idle.attr("name")).isEqualTo("sessionIdleTimeoutMinutes")
        assertThat(idle.attr("min")).isEqualTo("1")
        assertThat(idle.attr("max")).isEqualTo("1440")
        assertThat(d.select("label[for=sec-idle-timeout]").text()).isEqualTo("세션 idle timeout (분)")
        assertThat(d.text()).contains("1–1440분 (최대 24시간)").contains("만료 직전 연장 모달이 뜹니다.")
        val sw = d.selectFirst("input[role=switch]")!!
        assertThat(sw.attr("aria-checked")).isEqualTo("true")
        assertThat(d.selectFirst("input[type=hidden][name=mfaRequired]")!!.`val`()).isEqualTo("true")
        assertThat(d.text()).contains("모든 운영자에게 MFA 필수")
        assertThat(d.select("[x-data*=chipInput]").attr("x-data")).contains("https://a.example.com").contains("origin-check")
        assertThat(d.text()).contains("https://호스트[:포트] 형식. 경로 · 와일드카드(*) 불가.")
        val form = d.selectFirst("form#sec-form")!!
        assertThat(form.attr("hx-put")).isEqualTo(SAVE)
        assertThat(form.attr("hx-sync")).isEqualTo("this:drop")
        assertThat(form.attr("data-savebar-labels")).contains("세션 idle timeout").contains("MFA 필수").contains("CORS Allowlist")
        assertThat(form.attr("data-savebar-unordered")).isEqualTo("corsAllowlist")
        assertThat(d.selectFirst(".savebar")!!.text()).contains("저장하지 않은 변경").contains("취소").contains("저장")
        assertThat(d.select(".savebar button").eachText()).containsExactly("취소", "저장")
        assertThat(d.selectFirst("#sec-confirm-slot")).isNotNull()
        // 서버가 정한 필드 오류 자리
        assertThat(d.select(".set")).hasSize(3)
        assertThat(d.select("[data-field-error]").eachAttr("id")).contains("sec-err-sessionIdleTimeoutMinutes", "sec-err-corsAllowlist", "sec-err-form")
    }

    @Test
    @DisplayName("콘솔 주소 경고(Alpine)의 기준: 저장된 목록에 콘솔 주소가 있을 때만 savedHasConsole, 저장된 표기(대소문자)를 되돌리기 값으로")
    fun consoleWarningConfig() {
        val d0 = tab()
        assertThat(d0.select(".banner").text()).contains("은 지금 쓰고 있는 콘솔 주소입니다. 이 주소가 CORS 허용 목록에서 빠집니다.").contains("다른 도메인에서 이 콘솔 API를 호출하는 구성이라면 접속이 막힐 수 있습니다.")
            .doesNotContain("같은 출처")
        assertThat(d0.select(".banner button").eachText()).contains("삭제 되돌리기")
        val cfg = d0.selectFirst("form#sec-form")!!.attr("x-data")
        assertThat(cfg).contains("\"savedHasConsole\":true").contains("\"consoleOrigin\":\"http://localhost\"").contains("\"consoleSaved\":\"http://localhost\"")
        whenever(policy.get()).thenReturn(view(30, true, "https://a.example.com"))
        assertThat(tab().selectFirst("form#sec-form")!!.attr("x-data")).contains("\"savedHasConsole\":false")
        whenever(policy.get()).thenReturn(view(30, true, "http://LOCALHOST"))
        assertThat(tab().selectFirst("form#sec-form")!!.attr("x-data")).contains("\"savedHasConsole\":true").contains("\"consoleSaved\":\"http://LOCALHOST\"")
    }

    @Test
    @DisplayName("불러오지 못하면 입력칸·저장 없이 LoadError(기본값처럼 보이지 않음), 원문 없음, 다시 시도 = 탭 다시 받기")
    fun loadFailure() {
        whenever(policy.get()).thenThrow(IllegalStateException("boom-internal"))
        val d = tab()
        assertThat(d.select("form, input#sec-idle-timeout, .savebar")).isEmpty()
        val alert = d.selectFirst("[role=alert]")!!
        assertThat(alert.text()).contains("보안 정책을 불러오지 못했습니다").doesNotContain("boom-internal")
        val retry = alert.select("button").stream().filter { b -> b.text() == "다시 시도" }.findFirst().orElseThrow()
        assertThat(retry.attr("hx-get")).isEqualTo(SecurityPolicyTabController.TAB_PATH)
        assertThat(retry.attr("hx-target")).isEqualTo("#settings-tab")
    }

    @Test
    @DisplayName("저장: SecurityPolicyController.update(바뀐 값), saved + 성공 토스트, 탭 전체를 새 값으로(HX-Retarget #settings-tab)")
    fun savesAndRerendersTab() {
        val r = send(save("45", "false", "https://a.example.com", CONSOLE), platform(), false)
        assertThat(r.response.status).isEqualTo(200)
        verify(policy).update(eq(SecurityPolicyDto.UpdateRequest(45, false, listOf("https://a.example.com", CONSOLE))), any<Authentication>())
        assertThat(trigger(r)).contains("saved=true").contains("보안 정책 저장됨").contains("success")
        assertThat(r.response.getHeader("HX-Retarget")).isEqualTo("#settings-tab")
        assertThat(r.response.getHeader("HX-Reswap")).isEqualTo("innerHTML")
        val d = html(r)
        assertThat(d.selectFirst("#sec-idle-timeout")!!.`val`()).isEqualTo("45") // 응답(저장된 값)으로 다시 그림
        assertThat(d.selectFirst("input[role=switch]")!!.attr("aria-checked")).isEqualTo("false")
    }

    @Test
    @DisplayName("저장 검증: idle 가 비었거나 숫자가 아니거나 0·1441 이면 422 + 필드 오류(OOB), 토스트 없음, REST 미호출")
    fun idleValidation() {
        for (bad in arrayOf("", "abc", "0", "1441", "-5", "3.5", "99999999999999999999")) {
            val r = send(save(bad, "true", "https://a.example.com", CONSOLE), platform(), false)
            assertThat(r.response.status).`as`(bad).isEqualTo(422)
            assertThat(trigger(r)).`as`(bad).isNull()
            val err = html(r).selectFirst("#sec-err-sessionIdleTimeoutMinutes")!!
            assertThat(err.text()).`as`(bad).isEqualTo(PolicyValidation.TIMEOUT_ERROR)
            assertThat(err.attr("hx-swap-oob")).isEqualTo("true")
            assertThat(err.attr("role")).isEqualTo("alert")
        }
        verify(policy, never()).update(anyOrNull(), anyOrNull())
    }

    @Test
    @DisplayName("idle 경계 1·1440 은 통과, 서버에 이미 저장된 값(상한 밖)을 손대지 않았으면 오류로 막지 않는다")
    fun idleBoundariesAndUntouched() {
        assertThat(send(save("1", "true", CONSOLE), platform(), false).response.status).isEqualTo(200)
        assertThat(send(save("1440", "true", CONSOLE), platform(), false).response.status).isEqualTo(200)
        whenever(policy.get()).thenReturn(view(2000, true, CONSOLE))
        assertThat(send(save("2000", "true", CONSOLE), platform(), false).response.status).isEqualTo(200)
        verify(policy).update(eq(SecurityPolicyDto.UpdateRequest(2000, true, listOf(CONSOLE))), anyOrNull())
        // 고치려고 손대면 1–1440 을 요구한다
        assertThat(send(save("2001", "true", CONSOLE), platform(), false).response.status).isEqualTo(422)
    }

    @Test
    @DisplayName("저장 검증: CORS origin 형식·경로·와일드카드·기본 포트·길이·중복·65개 초과는 422 + corsAllowlist 오류, REST 미호출")
    fun corsValidation() {
        val tooMany = ArrayList<String>()
        for (i in 0 until 65) tooMany.add("https://h" + i + ".example.com")
        val cases = arrayOf(
            arrayOf(PolicyValidation.ORIGIN_FORMAT_ERROR, "http://evil.example.com"),
            arrayOf(PolicyValidation.ORIGIN_FORMAT_ERROR, "https://a.example.com/path"),
            arrayOf(PolicyValidation.ORIGIN_FORMAT_ERROR, "https://*.example.com"),
            arrayOf(PolicyValidation.ORIGIN_DEFAULT_PORT_ERROR, "https://a.example.com:443"),
            arrayOf(PolicyValidation.ORIGIN_DUPLICATE_ERROR, "https://a.example.com", "https://A.example.com"),
        )
        for (c in cases) {
            val r = send(save("30", "true", *java.util.Arrays.copyOfRange(c, 1, c.size)), platform(), false)
            assertThat(r.response.status).isEqualTo(422)
            assertThat(html(r).selectFirst("#sec-err-corsAllowlist")!!.text()).isEqualTo(c[0])
        }
        val many = send(save("30", "true", *tooMany.toTypedArray()), platform(), false)
        assertThat(many.response.status).isEqualTo(422)
        assertThat(html(many).selectFirst("#sec-err-corsAllowlist")!!.text()).isEqualTo(PolicyValidation.ORIGIN_COUNT_ERROR)
        val longO = send(save("30", "true", "https://" + "a".repeat(260) + ".com"), platform(), false)
        assertThat(longO.response.status).isEqualTo(422)
        assertThat(html(longO).selectFirst("#sec-err-corsAllowlist")!!.text()).isEqualTo(PolicyValidation.ORIGIN_LENGTH_ERROR)
        verify(policy, never()).update(anyOrNull(), anyOrNull())
    }

    @Test
    @DisplayName("MFA 값이 true/false 가 아니면 422(일반 오류), 빈 허용 목록·localhost http 는 통과")
    fun mfaAndEmptyList() {
        val bad = send(save("30", "maybe", CONSOLE), platform(), false)
        assertThat(bad.response.status).isEqualTo(422)
        assertThat(html(bad).selectFirst("#sec-err-form")!!.text()).contains("저장할 수 없는 값이 있습니다")
        whenever(policy.get()).thenReturn(view(30, true))
        assertThat(send(save("30", "true"), platform(), false).response.status).isEqualTo(200)
        verify(policy).update(eq(SecurityPolicyDto.UpdateRequest(30, true, listOf())), anyOrNull())
        assertThat(send(save("30", "true", "http://localhost:3000"), platform(), false).response.status).isEqualTo(200)
    }

    @Test
    @DisplayName("콘솔 주소를 목록에서 빼면 저장하지 않고 422 + L2 확인 다이얼로그(주소·안내, 비교한 값을 hidden, confirmLockout=1, 확인 = PUT)")
    fun removingConsoleAsksConfirmation() {
        val r = send(save("45", "false", "https://a.example.com"), platform(), false)
        assertThat(r.response.status).isEqualTo(422)
        verify(policy, never()).update(anyOrNull(), anyOrNull())
        assertThat(trigger(r)).isNull()
        val d = html(r)
        val form = d.selectFirst("form#sec-confirm-form")
        assertThat(form).isNotNull()
        form!!
        assertThat(form.select("input[name=sessionIdleTimeoutMinutes]").`val`()).isEqualTo("45")
        assertThat(form.select("input[name=mfaRequired]").`val`()).isEqualTo("false")
        assertThat(form.select("input[name=corsAllowlist]").eachAttr("value")).containsExactly("https://a.example.com")
        assertThat(form.select("input[name=confirmLockout]").`val`()).isEqualTo("1")
        val text = form.html()
        assertThat(text).contains("현재 콘솔 주소를 빼고 저장할까요?").contains("http://localhost")
            .contains("이 CORS 허용 목록에서 빠집니다").contains("필요하면 이 주소를 다시 추가해 저장하세요.")
        val confirm = form.selectFirst("button[data-confirm]")!!
        assertThat(confirm.attr("hx-put")).isEqualTo(SAVE)
        assertThat(confirm.attr("hx-target")).isEqualTo("#settings-tab")
        assertThat(confirm.text()).contains("저장")
        assertThat(confirm.hasClass("btn--danger-solid")).isFalse() // danger=false → primary
        assertThat(d.selectFirst("#sec-err-form")!!.text()).isEmpty() // 오류 자리는 비워 둔다
    }

    @Test
    @DisplayName("확인(confirmLockout=1)이면 콘솔 주소를 뺀 채로 저장한다. 대소문자만 다른 콘솔 주소가 남아 있으면 확인 없이 저장")
    fun confirmedAndCaseInsensitive() {
        val ok = send(save("45", "false", "https://a.example.com").param("confirmLockout", "1"), platform(), true)
        assertThat(ok.response.status).isEqualTo(200)
        verify(policy).update(eq(SecurityPolicyDto.UpdateRequest(45, false, listOf("https://a.example.com"))), anyOrNull())
        assertThat(trigger(ok)).contains("saved=true")

        val kept = send(save("45", "false", "https://a.example.com", "HTTP://LOCALHOST"), platform(), false)
        assertThat(kept.response.status).isEqualTo(200) // 대소문자만 다른 콘솔 주소가 남아 있으면 빠진 것이 아니다(확인 없이 저장)
        val kept2 = send(save("45", "false", "https://a.example.com", "http://localhost"), platform(), false)
        assertThat(kept2.response.status).isEqualTo(200)
    }

    @Test
    @DisplayName("저장된 목록에 콘솔 주소가 없었으면 확인 없이 저장")
    fun noConsoleInSavedList() {
        whenever(policy.get()).thenReturn(view(30, true, "https://a.example.com"))
        assertThat(send(save("45", "false", "https://a.example.com"), platform(), false).response.status).isEqualTo(200)
    }

    @Test
    @DisplayName("저장 실패: 저장 바 경로는 오류 토스트('…입력한 내용은 그대로 있습니다.'), 확인 경로는 확인창 안 인라인, 원문 없음")
    fun saveFailure() {
        whenever(policy.update(anyOrNull(), anyOrNull())).thenThrow(IllegalStateException("db-secret"))
        val bar = send(save("45", "false", "https://a.example.com", CONSOLE), platform(), false)
        assertThat(bar.response.status).isEqualTo(500)
        assertThat(trigger(bar)).contains("보안 정책을 저장하지 못했습니다").contains("입력한 내용은 그대로 있습니다.").contains("error").doesNotContain("db-secret")
        assertThat(bar.response.contentAsString).isEmpty()

        val dlg = send(save("45", "false", "https://a.example.com").param("confirmLockout", "1"), platform(), true)
        assertThat(dlg.response.status).isEqualTo(500)
        assertThat(trigger(dlg)).contains("confirmError").contains("보안 정책을 저장하지 못했습니다").doesNotContain("db-secret").doesNotContain("error=")
    }

    @Test
    @DisplayName("저장 중 폼 잠금(x-bind:inert), 클라이언트 검증은 htmx:before-request 에서 취소, 취소 = 서버 재조회 주소")
    fun formHooks() {
        val form = tab().selectFirst("form#sec-form")!!
        assertThat(form.hasAttr("x-on:submit")).isFalse()
        assertThat(form.attr("x-on:htmx:before-request")).isEqualTo("onBefore(\$event)")
        assertThat(form.attr("x-on:htmx:after-request")).isEqualTo("onAfter(\$event)")
        assertThat(form.attr("x-bind:inert")).isEqualTo("saving")
        assertThat(form.attr("data-revert-url")).isEqualTo(SecurityPolicyTabController.TAB_PATH + "?revert=1")
    }

    @Test
    @DisplayName("취소(?revert=1): 서버 값을 다시 읽어 탭을 돌려준다. 읽기 실패면 화면을 그대로 두고(스왑 없는 오류 상태) '되돌리지 못했습니다' 토스트")
    fun revertRefetch() {
        val ok = send(get(SecurityPolicyTabController.TAB_PATH).param("revert", "1"), platform(), false)
        assertThat(ok.response.status).isEqualTo(200)
        assertThat(html(ok).selectFirst("#sec-idle-timeout")!!.`val`()).isEqualTo("30")
        whenever(policy.get()).thenThrow(IllegalStateException("boom-internal"))
        val fail = send(get(SecurityPolicyTabController.TAB_PATH).param("revert", "1"), platform(), false)
        assertThat(fail.response.status).isEqualTo(500)
        assertThat(trigger(fail)).contains("되돌리지 못했습니다").contains("error").doesNotContain("boom-internal")
        assertThat(fail.response.contentAsString).isEmpty()
    }

    @Test
    @DisplayName("저장 전 현재 정책을 읽지 못하면 저장하지 않고 오류(REST update 미호출)")
    fun currentPolicyUnreadable() {
        whenever(policy.get()).thenThrow(IllegalStateException("boom"))
        val r = send(save("45", "false", CONSOLE), platform(), false)
        assertThat(r.response.status).isEqualTo(500)
        verify(policy, never()).update(anyOrNull(), anyOrNull())
    }

    @Test
    @DisplayName("origin-check: 통과하면 본문 비움 + X-Normalized-Value(소문자), 오류면 문구, 중복·잘못된 형식")
    fun originCheck() {
        val ok = send(get(SecurityPolicyTabController.ORIGIN_CHECK_PATH).param("value", " https://New.Example.com "), platform(), false)
        assertThat(ok.response.status).isEqualTo(200)
        assertThat(ok.response.contentAsString).isEmpty()
        assertThat(ok.response.getHeader("X-Normalized-Value")).isEqualTo("https://new.example.com")
        val bad = send(get(SecurityPolicyTabController.ORIGIN_CHECK_PATH).param("value", "https://a.example.com/x"), platform(), false)
        assertThat(bad.response.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(PolicyValidation.ORIGIN_FORMAT_ERROR)
        assertThat(bad.response.getHeader("X-Normalized-Value")).isNull()
        val dup = send(get(SecurityPolicyTabController.ORIGIN_CHECK_PATH).param("value", "https://A.example.com").param("existing", "https://a.example.com"), platform(), false)
        assertThat(dup.response.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(PolicyValidation.ORIGIN_DUPLICATE_ERROR)
    }

    @Test
    @DisplayName("모든 엔드포인트는 RP_ADMIN 에게 403 이고 REST 를 부르지 않는다")
    fun rpAdminForbidden() {
        assertThat(send(get(SecurityPolicyTabController.TAB_PATH), rp(), false).response.status).isEqualTo(403)
        assertThat(send(save("45", "false", CONSOLE), rp(), false).response.status).isEqualTo(403)
        assertThat(send(save("45", "false", CONSOLE).param("confirmLockout", "1"), rp(), true).response.status).isEqualTo(403)
        assertThat(send(get(SecurityPolicyTabController.ORIGIN_CHECK_PATH).param("value", "https://x.example.com"), rp(), false).response.status).isEqualTo(403)
        verify(policy, never()).get()
        verify(policy, never()).update(anyOrNull(), anyOrNull())
    }

    @Test
    @DisplayName("리터럴 …/tab/security 가 일반 탭 매핑보다 우선(PLATFORM 200, RP 는 계정 탭으로 떨어지지 않고 403)")
    fun literalWinsOverGeneric() {
        val r = send(get("/admin/_/settings/tab/security"), platform(), false)
        assertThat(r.response.status).isEqualTo(200)
        assertThat(html(r).selectFirst("form#sec-form")).isNotNull()
        assertThat(send(get("/admin/_/settings/tab/security"), rp(), false).response.status).isEqualTo(403)
    }

    @Test
    @DisplayName("removesConsole 단위: 저장된 목록엔 있고 새 목록엔 없을 때만, 대소문자 무시")
    fun removesConsoleUnit() {
        assertThat(SecurityPolicyTabController.removesConsole(listOf("http://localhost"), listOf(), "http://localhost")).isTrue()
        assertThat(SecurityPolicyTabController.removesConsole(listOf("http://LOCALHOST"), listOf("http://localhost"), "http://localhost")).isFalse()
        assertThat(SecurityPolicyTabController.removesConsole(listOf("https://a.example.com"), listOf(), "http://localhost")).isFalse()
    }
}
