package com.crosscert.passkey.admin.web.shell

import com.crosscert.passkey.admin.audit.AuditChainMonitorController
import com.crosscert.passkey.admin.auth.ActiveTenantController
import com.crosscert.passkey.admin.auth.AdminUserDetails
import com.crosscert.passkey.admin.license.LicenseController
import com.crosscert.passkey.admin.web.AdminWebSliceTest
import org.assertj.core.api.Assertions.assertThat
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.stereotype.Controller
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import java.time.Clock
import java.util.UUID
import java.util.regex.Pattern

/**
 * 레이아웃에 들어가는 명령 팔레트·단축키 시트·유휴 세션 모달 조각의 렌더 검사.
 * 원본: extras/ShortcutSheet.test.tsx(행·문구), IdleSessionModal.test.tsx(마크업), CommandPalette.test.tsx(ARIA).
 * 키 입력·타이머·포커스는 브라우저에서 도는 코드라 여기서는 마크업·속성만 본다(parity 문서의 "브라우저: 미확인").
 *
 * (Kotlin 전환) 즉시 역참조하는 nullable jsoup 결과(`selectFirst`·`first`)의 `!!` 는 원 Java 의 NPE 실패 지점과 같다.
 */
@AdminWebSliceTest(controllers = [ShellExtrasRenderTest.Page::class])
@Import(ShellExtrasRenderTest.Page::class)
class ShellExtrasRenderTest {

    @Controller
    class Page {
        @GetMapping("/admin/tenants")
        fun page(model: Model): String {
            model.addAttribute("breadcrumbs", listOf(Crumb.of("Tenants")))
            return "admin/shell-test-page"
        }
    }

    @Autowired lateinit var mvc: MockMvc
    @MockitoBean lateinit var license: LicenseController
    @MockitoBean lateinit var chain: AuditChainMonitorController
    @MockitoBean lateinit var activeTenants: ActiveTenantController

    companion object {
        private fun auth(role: String): Authentication {
            val p = AdminUserDetails(UUID.randomUUID(), "a@x.com", "x", role, setOf(), true, null, Clock.systemUTC())
            return UsernamePasswordAuthenticationToken(p, "x", p.authorities)
        }

        /** (Kotlin 전환) Java `html.split(regex, -1).length - 1` 과 같은 의미. */
        private fun occurrences(html: String, regex: String): Int = Pattern.compile(regex).split(html, -1).size - 1
    }

    private fun page(role: String): Document {
        val r = mvc.perform(get("/admin/tenants").with(authentication(auth(role)))).andReturn()
        assertThat(r.response.status).isEqualTo(200)
        return Jsoup.parse(r.response.contentAsString)
    }

    @Test
    @DisplayName("레이아웃에 command-palette · shortcut-sheet · idle-session-modal 조각이 한 번씩 들어간다")
    fun layoutIncludesFragments() {
        val d = page("PLATFORM_OPERATOR")
        assertThat(d.select("[x-data=commandPalette]")).hasSize(1)
        assertThat(d.select("#palette-input")).hasSize(1)
        assertThat(d.select("[x-data*=shortcut-sheet]")).hasSize(1)
        assertThat(d.select("[x-data=idleSession]")).hasSize(1)
        assertThat(d.select("[x-data=unsavedGuard]")).hasSize(1)
        assertThat(d.select("script[src^=http], link[href^=http]")).isEmpty()
    }

    @Test
    @DisplayName("팔레트 마크업: 대화상자 이름·콤보박스 ARIA·150ms 디바운스 hx-get·결과 listbox·안내 키")
    fun paletteMarkup() {
        val d = page("PLATFORM_OPERATOR")
        val dlg = d.selectFirst("[role=dialog][aria-label=명령 팔레트]")
        assertThat(dlg).isNotNull()
        dlg!!
        assertThat(dlg.hasAttr("aria-modal")).`as`("닫혀 있을 때는 aria-modal 이 정적으로 박혀 있지 않다(Alpine 이 열릴 때만 바인딩)").isFalse()
        assertThat(dlg.attr("x-bind:aria-modal")).isEqualTo("open ? 'true' : null")
        val input = d.selectFirst("#palette-input")!!
        assertThat(input.attr("role")).isEqualTo("combobox")
        assertThat(input.attr("aria-expanded")).isEqualTo("true")
        assertThat(input.attr("aria-controls")).isEqualTo("palette-results")
        assertThat(input.attr("aria-autocomplete")).isEqualTo("list")
        assertThat(input.attr("aria-label")).isEqualTo("검색")
        assertThat(input.attr("placeholder")).isEqualTo("tenant 이름 · slug, 화면, 동작 검색…")
        assertThat(input.attr("name")).isEqualTo("q")
        assertThat(input.attr("hx-get")).isEqualTo("/admin/_/palette")
        assertThat(input.attr("hx-trigger")).startsWith("input changed delay:150ms")
        assertThat(input.attr("hx-target")).isEqualTo("#palette-results")
        assertThat(input.attr("hx-sync")).isEqualTo("this:replace")
        val list = d.selectFirst("#palette-results")!!
        assertThat(list.attr("role")).isEqualTo("listbox")
        assertThat(list.attr("aria-label")).isEqualTo("검색 결과")
        assertThat(d.select(".palette__foot").text()).contains("이동").contains("실행").contains("닫기").contains("개 결과")
        assertThat(input.attr("x-on:input")).isEqualTo("onInput()")
        // 스크림 표시: 열리면 빈 문자열(null 이 아님 — Alpine 은 null 스타일 바인딩에서 이전 display:none 을 지우지 않을 수 있다)
        assertThat(dlg.parents().stream().filter { e -> e.hasClass("scrim") }.findFirst().orElseThrow().attr("x-bind:style"))
            .isEqualTo("open ? '' : 'display: none'")
    }

    @Test
    @DisplayName("단축키 시트(PLATFORM): 제목·행·보조 문구·G 이동 4줄, 팔레트 표기는 platformKey 로 OS 에 맞춘다")
    fun shortcutSheetPlatform() {
        val d = page("PLATFORM_OPERATOR")
        val root = d.selectFirst("[data-go-shortcuts]")
        assertThat(root).isNotNull()
        assertThat(root!!.attr("data-go-shortcuts")).isEqualTo("true")
        assertThat(d.select("[x-data*=shortcut-sheet]")).`as`("dialog id=shortcut-sheet").hasSize(1)
        // 열리기 전에는 x-if 템플릿 안에 있다.
        val html = d.html()
        assertThat(html).contains("키보드 단축키").contains("명령 팔레트 열기").contains("Tenants 목록으로 이동").contains("활동으로 이동")
            .contains("감사 체인으로 이동").contains("설정으로 이동").contains("이 단축키 목록").contains("탭 사이 이동")
            .contains("표에서 선택한 행 열기").contains("다이얼로그 · 메뉴 닫기")
            .contains("입력란에 포커스가 있으면 G · ? 단축키는 동작하지 않습니다.").contains("키보드 단축키 목록")
        for (k in listOf("G T", "G A", "G C", "G S")) assertThat(html).contains("<kbd>" + k + "</kbd>")
        assertThat(html).contains("Windows · Ctrl K").contains("Mac · ⌘K").contains("x-data=\"platformKey\"")
        // 조각 인자 원본(th:remove)은 화면에 따로 남지 않는다: 문구가 dialog 안쪽에 한 번만 있다.
        assertThat(occurrences(html, "명령 팔레트 열기")).isEqualTo(1)
        assertThat(occurrences(html, "<kbd>G T</kbd>")).isEqualTo(1)
        val sheet = d.selectFirst("[x-data*=shortcut-sheet]")!!
        assertThat(sheet.select(".dialog[role=dialog][aria-labelledby=shortcut-sheet-title] .dialog__title").text()).isEqualTo("키보드 단축키")
        assertThat(sheet.select(".dialog__foot-start").text()).isEqualTo("입력란에 포커스가 있으면 G · ? 단축키는 동작하지 않습니다.")
        assertThat(sheet.select(".dialog__foot button").text()).isEqualTo("닫기")
        assertThat(sheet.select("table.table caption.sr-only").text()).isEqualTo("키보드 단축키 목록")
        assertThat(sheet.select("table.table tbody tr")).hasSize(9)
    }

    @Test
    @DisplayName("단축키 시트(RP): G 이동 줄과 go 단축키 활성 표식이 없다")
    fun shortcutSheetRp() {
        val d = page("RP_ADMIN")
        assertThat(d.select("[data-go-shortcuts]")).isEmpty()
        val html = d.html()
        assertThat(html).doesNotContain("Tenants 목록으로 이동").doesNotContain("<kbd>G T</kbd>").contains("명령 팔레트 열기").contains("이 단축키 목록")
    }

    @Test
    @DisplayName("유휴 세션 모달: 전체 분(data-minutes), 알림 대화상자·기본 포커스 연장·진행 막대·손실 경고·로그아웃 POST 폼(CSRF)")
    fun idleModalMarkup() {
        val d = page("PLATFORM_OPERATOR")
        val root = d.selectFirst("[x-data=idleSession]")!!
        assertThat(root.attr("data-minutes")).matches("\\d+")
        val form = d.selectFirst("#idle-logout-form")!!
        assertThat(form.attr("method")).isEqualToIgnoringCase("post")
        assertThat(form.attr("action")).isEqualTo("/admin/logout")
        assertThat(form.attr("hx-boost")).isEqualTo("false")
        assertThat(form.select("input[name=_csrf]")).`as`("th:action 이 CSRF 토큰을 넣는다").hasSize(1)
        val html = d.html()
        assertThat(html).contains("role=\"alertdialog\"").contains("aria-modal=\"true\"").contains("세션이 곧 만료됩니다")
            .contains("role=\"progressbar\"").contains("세션 만료까지 남은 시간").contains("보안 정책에 따라")
            .contains("로그아웃하면 저장하지 않은 변경이 사라집니다.").contains("세션 연장").contains("aria-live=\"polite\"")
        assertThat(html).doesNotContain("mutation").doesNotContain("audit log")
        // 기본 포커스는 "세션 연장"(autofocus)
        assertThat(d.select("button[autofocus]").stream().anyMatch { b -> b.text() == "세션 연장" }).isTrue()
        // 닫을 수 없다: 스크림 mousedown·Esc 핸들러 없음
        val scrim = d.select("[x-data=idleSession] .scrim").first()!!
        assertThat(scrim.attributes().asList().stream().map { a -> a.key }).noneMatch { k -> k.startsWith("x-on:mousedown") || k.startsWith("x-on:keydown") }
    }
}
