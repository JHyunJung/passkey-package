package com.crosscert.passkey.admin.web.shell;

import com.crosscert.passkey.admin.audit.AuditChainMonitorController;
import com.crosscert.passkey.admin.auth.ActiveTenantController;
import com.crosscert.passkey.admin.auth.AdminUserDetails;
import com.crosscert.passkey.admin.license.LicenseController;
import com.crosscert.passkey.admin.web.AdminWebSliceTest;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** 도움말 메뉴: 주소가 설정되면 문서 항목은 새 창, 운영자 문의 링크(Header.test.tsx '도움말: 주소가 있으면 …'). */
@AdminWebSliceTest(controllers = {ShellController.class, ShellControllerTest.DummyPage.class})
@Import(ShellControllerTest.DummyPage.class)
@TestPropertySource(properties = {
        "passkey.admin.help.api-docs=https://docs.example/api",
        "passkey.admin.help.rp-guide=https://docs.example/rp",
        "passkey.admin.help.support=mailto:ops@example.com"})
class ShellHelpLinksTest {

    @Autowired MockMvc mvc;
    @MockBean LicenseController license;
    @MockBean AuditChainMonitorController chain;
    @MockBean ActiveTenantController activeTenants;

    @Test
    @DisplayName("API 문서·RP 연동 가이드는 새 창(noopener), 운영자 문의 링크, 단축키 항목 유지")
    void helpMenuWithLinks() throws Exception {
        AdminUserDetails p = new AdminUserDetails(UUID.randomUUID(), "a@x.com", "x", "PLATFORM_OPERATOR", Set.of(), true, null, Clock.systemUTC());
        String html = mvc.perform(get("/admin/settings").with(authentication(new UsernamePasswordAuthenticationToken(p, "x", p.getAuthorities()))))
                .andReturn().getResponse().getContentAsString();
        Element menu = Jsoup.parse(html).selectFirst("div.menu[aria-label=도움말]");
        Element api = menu.selectFirst("a[role=menuitem]:contains(API 문서 열기)");
        assertThat(api.attr("href")).isEqualTo("https://docs.example/api");
        assertThat(api.attr("target")).isEqualTo("_blank");
        assertThat(api.attr("rel")).isEqualTo("noopener noreferrer");
        assertThat(api.text()).contains("새 창");
        assertThat(menu.selectFirst("a:contains(RP 연동 가이드)").attr("href")).isEqualTo("https://docs.example/rp");
        Element support = menu.selectFirst("a:contains(운영자에게 문의)");
        assertThat(support.attr("href")).isEqualTo("mailto:ops@example.com");
        assertThat(support.hasAttr("target")).isFalse();
        assertThat(menu.select("[role=menuitem]").eachText()).containsExactly("API 문서 열기 새 창", "RP 연동 가이드 새 창", "키보드 단축키?", "운영자에게 문의");
        assertThat(menu.select(".menu__sep")).hasSize(1);
    }
}
