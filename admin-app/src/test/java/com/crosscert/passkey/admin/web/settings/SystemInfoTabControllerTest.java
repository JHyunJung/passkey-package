package com.crosscert.passkey.admin.web.settings;

import com.crosscert.passkey.admin.audit.AuditChainMonitorController;
import com.crosscert.passkey.admin.auth.ActiveTenantController;
import com.crosscert.passkey.admin.auth.AdminUserDetails;
import com.crosscert.passkey.admin.license.LicenseController;
import com.crosscert.passkey.admin.system.SystemInfoController;
import com.crosscert.passkey.admin.system.SystemInfoView;
import com.crosscert.passkey.admin.system.SystemInfoView.Component;
import com.crosscert.passkey.admin.web.AdminWebSliceTest;
import com.crosscert.passkey.core.api.ApiResponse;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** 설정 › 시스템 탭. 원본: SystemInfoTab.test.tsx. */
@AdminWebSliceTest(controllers = SystemInfoTabController.class)
class SystemInfoTabControllerTest {

    @Autowired MockMvc mvc;
    @MockBean SystemInfoController systemInfo;
    @MockBean LicenseController license;
    @MockBean AuditChainMonitorController chain;
    @MockBean ActiveTenantController activeTenants;

    static final SystemInfoView SAMPLE = new SystemInfoView("1.2.3", "2026-05-31T09:00+09:00", 4L, new SystemInfoView.Host("prod,oracle"),
            List.of(new Component("admin-app", "1.2.3", "OK", "Spring Boot"), new Component("MDS sync scheduler", "1.2.3", "OK", "매 6시간")));

    @BeforeEach
    void setUp() {
        when(systemInfo.get()).thenReturn(ApiResponse.ok(SAMPLE));
    }

    static Authentication auth(String role) {
        AdminUserDetails p = new AdminUserDetails(UUID.randomUUID(), "a@x.com", "x", role,
                "RP_ADMIN".equals(role) ? Set.of(UUID.randomUUID()) : Set.of(), true, null, Clock.systemUTC());
        return new UsernamePasswordAuthenticationToken(p, "x", p.getAuthorities());
    }

    private Document render(SystemInfoView v) throws Exception {
        when(systemInfo.get()).thenReturn(ApiResponse.ok(v));
        return render();
    }

    private Document render() throws Exception {
        MvcResult r = mvc.perform(get(SystemInfoTabController.TAB_PATH).with(authentication(auth("PLATFORM_OPERATOR"))).header("HX-Request", "true")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return Jsoup.parse(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static Element kpi(Document d, String label) {
        return d.select(".kpi").stream().filter(k -> k.selectFirst(".kpi__label").text().equals(label)).findFirst().orElseThrow();
    }

    private static Element list(Document d) {
        return d.selectFirst("ul[aria-label=백엔드 컴포넌트]");
    }

    @Test
    @DisplayName("버전·KST 배포 시각·환경·컴포넌트 메모·가동 일수, KPI 라벨 한국어('d' 없음)")
    void rendersInfo() throws Exception {
        Document d = render();
        assertThat(d.text()).contains("1.2.3").contains("2026-05-31 09:00 KST 배포").contains("prod,oracle").contains("매 6시간").contains("4일")
                .doesNotContain("Uptime").doesNotContain("4d");
        assertThat(kpi(d, "서버 버전")).isNotNull();
        assertThat(kpi(d, "가동 시간").selectFirst(".kpi__value").text()).isEqualTo("4일");
        assertThat(d.select(".chips .tag").eachText()).containsExactly("prod", "oracle");
        assertThat(d.select("aside, [role=tablist]")).isEmpty();
    }

    @Test
    @DisplayName("긴 버전은 말줄임(truncate + title), 상세는 kv, 배포 시각 KST")
    void longVersionTruncates() throws Exception {
        String longV = "Oracle Database 21c Express Edition Release 21.0.0.0.0 - Production";
        Document d = render(new SystemInfoView("1.2.3", "2026-05-31T09:00+09:00", 4L, new SystemInfoView.Host("prod"),
                List.of(new Component("Oracle DB", longV, "OK", "primary"))));
        Element v = list(d).select(".truncate").stream().filter(e -> e.text().equals(longV)).findFirst().orElseThrow();
        assertThat(v.attr("title")).isEqualTo(longV);
        assertThat(d.selectFirst("dl").hasClass("kv")).isTrue();
        assertThat(d.selectFirst("dl").text()).contains("2026-05-31 09:00 KST");
    }

    @Test
    @DisplayName("deployedAt 이 없거나 해석할 수 없으면 '배포 시각 정보 없음' + 상세 '—'")
    void deployedMissingOrInvalid() throws Exception {
        for (String dep : new String[] {null, "not-a-date"}) {
            Document d = render(new SystemInfoView("1.2.3", dep, 4L, new SystemInfoView.Host("prod"), List.of(new Component("a", "1", "OK", null))));
            assertThat(d.text()).contains("배포 시각 정보 없음").doesNotContain("Invalid Date").doesNotContain("not-a-date");
            Element dt = d.select("dt").stream().filter(e -> e.text().equals("배포 시각")).findFirst().orElseThrow();
            assertThat(dt.nextElementSibling().text()).isEqualTo("—");
        }
    }

    @Test
    @DisplayName("컴포넌트 상태 → 배지 클래스(정상·저하·중단), 전체 상태는 가장 나쁜 상태 + 'N / M'")
    void statusBadgesAndOverall() throws Exception {
        Document d = render(new SystemInfoView("1.2.3", null, 4L, new SystemInfoView.Host("prod"), List.of(
                new Component("admin-app", "1.2.3", "OK", "Spring Boot"),
                new Component("Oracle DB", "x", "DEGRADED", "primary"),
                new Component("MDS", "1.2.3", "DOWN", null))));
        Element ul = list(d);
        assertThat(ul.select(".badge").stream().filter(b -> b.text().equals("정상")).findFirst().orElseThrow().hasClass("badge--success")).isTrue();
        assertThat(ul.select(".badge").stream().filter(b -> b.text().equals("저하")).findFirst().orElseThrow().hasClass("badge--warning")).isTrue();
        assertThat(ul.select(".badge").stream().filter(b -> b.text().equals("중단")).findFirst().orElseThrow().hasClass("badge--danger")).isTrue();
        assertThat(kpi(d, "전체 상태").text()).contains("중단").contains("컴포넌트 1 / 3");

        Document two = render(new SystemInfoView("1.2.3", null, 4L, new SystemInfoView.Host("prod"), List.of(
                new Component("admin-app", "1.2.3", "OK", null), new Component("Redis", "7", "DEGRADED", null))));
        assertThat(kpi(two, "전체 상태").text()).contains("저하").contains("컴포넌트 1 / 2");
        // 지연 시간 필드는 서버에 없다 — "ms" 를 그리지 않는다.
        assertThat(list(two).text()).doesNotContainPattern("\\d+ms");
    }

    @Test
    @DisplayName("가동 일수가 없으면 '—'(NaN·undefined 없음)")
    void uptimeMissing() throws Exception {
        Document d = render(new SystemInfoView("1.2.3", null, null, new SystemInfoView.Host("prod"), List.of()));
        assertThat(kpi(d, "가동 시간").text()).contains("—");
        assertThat(d.text()).doesNotContain("NaN").doesNotContain("undefined").doesNotContain("null일");
    }

    @Test
    @DisplayName("불러오기 실패 → LoadError(role=alert, 일러스트 장식, 오류 정보 복사, 다시 시도 = 같은 조각을 탭 영역에), 원문 없음")
    void loadFailure() throws Exception {
        when(systemInfo.get()).thenThrow(new IllegalStateException("boom-internal"));
        Document d = render();
        Element alert = d.selectFirst("[role=alert]");
        assertThat(alert.text()).contains("시스템 정보를 불러오지 못했습니다").doesNotContain("boom-internal");
        assertThat(alert.selectFirst("img").attr("alt")).isEmpty();
        assertThat(alert.select("button").eachText()).contains("오류 정보 복사");
        Element retry = alert.select("button").stream().filter(b -> b.text().equals("다시 시도")).findFirst().orElseThrow();
        assertThat(retry.attr("hx-get")).isEqualTo(SystemInfoTabController.TAB_PATH);
        assertThat(retry.attr("hx-target")).isEqualTo("#settings-tab");
        assertThat(retry.attr("hx-sync")).isEqualTo("#settings-tab:replace");
    }

    @Test
    @DisplayName("RP_ADMIN 은 403, REST 호출 없음")
    void rpDenied() throws Exception {
        MvcResult r = mvc.perform(get(SystemInfoTabController.TAB_PATH).with(authentication(auth("RP_ADMIN"))).header("HX-Request", "true")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        verify(systemInfo, never()).get();
    }

    @Test
    @DisplayName("deployedKst: 해석 가능한 시각만")
    void deployedKstUnit() {
        assertThat(SystemInfoTabController.deployedKst("2026-05-31T00:00:00Z")).isEqualTo("2026-05-31 09:00 KST");
        assertThat(SystemInfoTabController.deployedKst("")).isNull();
        assertThat(SystemInfoTabController.deployedKst("x")).isNull();
    }
}
