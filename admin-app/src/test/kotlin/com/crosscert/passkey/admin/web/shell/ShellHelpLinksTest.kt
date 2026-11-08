package com.crosscert.passkey.admin.web.shell

import com.crosscert.passkey.admin.audit.AuditChainMonitorController
import com.crosscert.passkey.admin.auth.ActiveTenantController
import com.crosscert.passkey.admin.auth.AdminUserDetails
import com.crosscert.passkey.admin.license.LicenseController
import com.crosscert.passkey.admin.web.AdminWebSliceTest
import org.assertj.core.api.Assertions.assertThat
import org.jsoup.Jsoup
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import java.time.Clock
import java.util.UUID

/**
 * 도움말 메뉴: 주소가 설정되면 문서 항목은 새 창, 운영자 문의 링크(Header.test.tsx '도움말: 주소가 있으면 …').
 *
 * (Kotlin 전환) 즉시 역참조하는 nullable jsoup 결과(`selectFirst`)의 `!!` 는 원 Java 의 NPE 실패 지점과 같다.
 */
@AdminWebSliceTest(controllers = [ShellController::class, ShellControllerTest.DummyPage::class])
@Import(ShellControllerTest.DummyPage::class)
@TestPropertySource(
    properties = [
        "passkey.admin.help.api-docs=https://docs.example/api",
        "passkey.admin.help.rp-guide=https://docs.example/rp",
        "passkey.admin.help.support=mailto:ops@example.com",
    ],
)
class ShellHelpLinksTest {

    @Autowired lateinit var mvc: MockMvc
    @MockitoBean lateinit var license: LicenseController
    @MockitoBean lateinit var chain: AuditChainMonitorController
    @MockitoBean lateinit var activeTenants: ActiveTenantController

    @Test
    @DisplayName("API 문서·RP 연동 가이드는 새 창(noopener), 운영자 문의 링크, 단축키 항목 유지")
    fun helpMenuWithLinks() {
        val p = AdminUserDetails(UUID.randomUUID(), "a@x.com", "x", "PLATFORM_OPERATOR", setOf(), true, null, Clock.systemUTC())
        val html = mvc.perform(get("/admin/settings").with(authentication(UsernamePasswordAuthenticationToken(p, "x", p.authorities))))
            .andReturn().response.contentAsString
        val menu = Jsoup.parse(html).selectFirst("div.menu[aria-label=도움말]")!!
        val api = menu.selectFirst("a[role=menuitem]:contains(API 문서 열기)")!!
        assertThat(api.attr("href")).isEqualTo("https://docs.example/api")
        assertThat(api.attr("target")).isEqualTo("_blank")
        assertThat(api.attr("rel")).isEqualTo("noopener noreferrer")
        assertThat(api.text()).contains("새 창")
        assertThat(menu.selectFirst("a:contains(RP 연동 가이드)")!!.attr("href")).isEqualTo("https://docs.example/rp")
        val support = menu.selectFirst("a:contains(운영자에게 문의)")!!
        assertThat(support.attr("href")).isEqualTo("mailto:ops@example.com")
        assertThat(support.hasAttr("target")).isFalse()
        assertThat(menu.select("[role=menuitem]").eachText()).containsExactly("API 문서 열기 새 창", "RP 연동 가이드 새 창", "키보드 단축키?", "운영자에게 문의")
        assertThat(menu.select(".menu__sep")).hasSize(1)
    }
}
