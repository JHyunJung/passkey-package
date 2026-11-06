package com.crosscert.passkey.admin.web.license;

import com.crosscert.passkey.admin.audit.AuditChainMonitorController;
import com.crosscert.passkey.admin.auth.ActiveTenantController;
import com.crosscert.passkey.admin.auth.AdminUserDetails;
import com.crosscert.passkey.admin.license.LicenseController;
import com.crosscert.passkey.admin.license.LicenseView;
import com.crosscert.passkey.admin.web.AdminWebSliceTest;
import com.crosscert.passkey.core.api.ApiResponse;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** 라이선스 화면. 원본: LicensePage.test.tsx, App.tsx(RequirePlatform). */
@AdminWebSliceTest(controllers = LicensePageController.class)
class LicensePageControllerTest {

    @Autowired MockMvc mvc;
    @Autowired Clock clock;
    @MockitoBean LicenseController license;
    @MockitoBean AuditChainMonitorController chain;
    @MockitoBean ActiveTenantController activeTenants;

    static final Instant NOW = Instant.parse("2026-10-11T03:00:00Z");
    static final UUID T1 = UUID.fromString("00000000-0000-0000-0000-000000000077");
    static final LicenseView BASE = new LicenseView("onprem", "VALID", "acme-bank", "lic_01J9ZQ8V7K3M",
            Instant.parse("2027-04-28T15:00:00Z"), 200, List.of("mds", "audit-export"),
            NOW.minusSeconds(5 * 60), null, NOW.plusSeconds(55 * 60));

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        stub(BASE);
    }

    private void stub(LicenseView v) {
        when(license.get()).thenReturn(ApiResponse.ok(v));
    }

    static Authentication platform() { return auth("PLATFORM_OPERATOR", Set.of()); }
    static Authentication rp() { return auth("RP_ADMIN", Set.of(T1)); }

    private static Authentication auth(String role, Set<UUID> tenants) {
        AdminUserDetails p = new AdminUserDetails(UUID.randomUUID(), "a@x.com", "x", role, tenants, true, null, Clock.systemUTC());
        return new UsernamePasswordAuthenticationToken(p, "x", p.getAuthorities());
    }

    private Document page() throws Exception {
        MvcResult r = mvc.perform(get("/admin/license").with(authentication(platform()))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return Jsoup.parse(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static Element dd(Document d, String term) {
        return d.select("main dl dt").stream().filter(e -> e.text().equals(term)).findFirst().orElseThrow().nextElementSibling();
    }

    @Test
    @DisplayName("licenseRpAdminRedirected: RP_ADMIN 은 자기 tenant 로(RequirePlatform), 다시 확인 조각은 403, 라이선스 조회 안 함")
    void licenseRpAdminRedirected() throws Exception {
        MvcResult r = mvc.perform(get("/admin/license").with(authentication(rp()))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(302);
        assertThat(r.getResponse().getRedirectedUrl()).isEqualTo("/admin/tenants/" + T1);
        MvcResult frag = mvc.perform(get(LicensePageController.RECHECK_PATH).with(authentication(rp())).header("HX-Request", "true")).andReturn();
        assertThat(frag.getResponse().getStatus()).isEqualTo(403);
        verify(license, never()).get();
    }

    @Test
    @DisplayName("상태 헤드라인: VALID 원문 대신 배지(원문은 툴팁) + 마지막/다음 확인 상대 시간, 루트 .page, 제목")
    void headline() throws Exception {
        Document d = page();
        assertThat(d.selectFirst("main > .page")).isNotNull();
        assertThat(d.selectFirst("main h1").text()).isEqualTo("라이선스");
        assertThat(d.selectFirst("main .page__sub").text()).isEqualTo("이 설치본(온프레미스)의 라이선스 상태와 상세 정보입니다.");
        assertThat(d.selectFirst("main").text()).doesNotContain("VALID");
        Element badge = d.select("main .badge").stream().filter(b -> b.text().equals("정상")).findFirst().orElseThrow();
        assertThat(badge.hasClass("badge--success")).isTrue();
        assertThat(badge.parent().attr("title")).isEqualTo("VALID");
        assertThat(d.selectFirst("main").text()).contains("마지막 확인 5분 전 · 다음 확인 55분 후");
        assertThat(d.selectFirst(".crumbs__current").text()).isEqualTo("라이선스");
    }

    @Test
    @DisplayName("만료까지 일수만(진행 바 없음), '만료일 …'")
    void daysOnly() throws Exception {
        Document d = page();
        assertThat(d.selectFirst("main").text()).contains("만료까지").contains("200일").contains("만료일 2027. 04. 29.")
                .doesNotContain("Expires at");
        assertThat(d.select("main [role=progressbar]")).isEmpty();
    }

    @Test
    @DisplayName("만료까지 일수의 글자 크기 13px 은 색이 없을 때(VALID)도 유지, WARNING 은 13px + 경고 색")
    void daysKeepFontSize() throws Exception {
        Element strong = page().select("main strong").stream().filter(e -> e.text().equals("200일")).findFirst().orElseThrow();
        assertThat(strong.attr("style")).isEqualTo("font-size: 13px");
        stub(new LicenseView("onprem", "WARNING", "acme-bank", "lic_1", BASE.expiresAt(), 30, List.of(), null, null, null));
        Element warn = page().select("main strong").stream().filter(e -> e.text().equals("30일")).findFirst().orElseThrow();
        assertThat(warn.attr("style")).isEqualTo("font-size: 13px; color: var(--warn)");
    }

    @Test
    @DisplayName("상세 카드: 한국어 항목 순서, 라이선스 ID mono + 복사 버튼, 기능 태그, 유예 없으면 줄 없음, stack-5 / kv--rows")
    void detailCard() throws Exception {
        Document d = page();
        assertThat(d.select("main h2.card__title").eachText()).contains("상세");
        assertThat(d.select("main dl dt").eachText()).containsExactly("고객사", "라이선스 ID", "만료일", "남은 일수", "사용 가능한 기능", "마지막 확인", "다음 확인 예정");
        assertThat(dd(d, "고객사").text()).isEqualTo("acme-bank");
        assertThat(dd(d, "라이선스 ID").selectFirst(".mono").text()).isEqualTo("lic_01J9ZQ8V7K3M");
        assertThat(dd(d, "라이선스 ID").select("[aria-label=라이선스 ID 복사], [title=라이선스 ID 복사]")).isNotEmpty();
        assertThat(dd(d, "사용 가능한 기능").select(".tag").eachText()).containsExactly("mds", "audit-export");
        assertThat(d.selectFirst("main .stack-5")).isNotNull();
        assertThat(d.selectFirst("main dl").hasClass("kv")).isTrue();
        assertThat(d.selectFirst("main dl").hasClass("kv--rows")).isTrue();
    }

    @Test
    @DisplayName("NETWORK_GRACE: 경고 배지, 유예 'N시간', 연결 복구 조치 카드(3단계)")
    void networkGrace() throws Exception {
        stub(new LicenseView("onprem", "NETWORK_GRACE", "acme-bank", "lic_1", BASE.expiresAt(), 200, List.of(), BASE.lastVerifiedAt(), 12L, BASE.nextHeartbeatAt()));
        Document d = page();
        assertThat(d.select("main .badge").stream().filter(b -> b.text().equals("서버 연결 불가")).findFirst().orElseThrow().hasClass("badge--warning")).isTrue();
        assertThat(dd(d, "유예 기간 남은 시간").text()).isEqualTo("12시간");
        assertThat(d.selectFirst("main").text()).doesNotContain("hour(s)");
        Element card = d.selectFirst("section[aria-label=지금 해야 할 일 — 라이선스 서버 연결 복구]");
        assertThat(card.text()).contains("12시간 안에 연결되지 않으면 서비스가 중단됩니다");
        assertThat(card.select("li")).hasSize(3);
    }

    @Test
    @DisplayName("값이 없으면 '—', 상대 시간 줄·복사 버튼 숨김, DEAD 면 새 라이선스 적용 카드")
    void deadWithNulls() throws Exception {
        stub(new LicenseView("onprem", "DEAD", "acme-bank", null, BASE.expiresAt(), 0, List.of(), null, null, null));
        Document d = page();
        assertThat(d.select("main .badge").stream().filter(b -> b.text().equals("만료")).findFirst().orElseThrow().hasClass("badge--danger")).isTrue();
        assertThat(d.selectFirst("main").text()).doesNotContainPattern("마지막 확인 .* 전").doesNotContain("(never)").doesNotContain("(none scheduled)");
        assertThat(d.select("[aria-label=라이선스 ID 복사], [title=라이선스 ID 복사]")).isEmpty();
        for (String t : List.of("마지막 확인", "다음 확인 예정", "라이선스 ID", "사용 가능한 기능")) assertThat(dd(d, t).text()).isEqualTo("—");
        assertThat(d.selectFirst("section[aria-label=지금 해야 할 일 — 새 라이선스 적용]").text()).contains("관리 콘솔은 계속 쓸 수 있습니다");
    }

    @Test
    @DisplayName("WARNING: 갱신 조치 카드(3단계), VALID 는 조치 카드 없음")
    void warningAndValid() throws Exception {
        stub(new LicenseView("onprem", "WARNING", "acme-bank", "lic_1", BASE.expiresAt(), 30, List.of(), null, null, null));
        Element card = page().selectFirst("section[aria-label=지금 해야 할 일 — 라이선스 갱신]");
        assertThat(card.text()).contains("라이선스가 30일 후 만료됩니다");
        assertThat(card.select("li")).hasSize(3);

        stub(BASE);
        assertThat(page().select("section[aria-label^=지금 해야 할 일]")).isEmpty();
    }

    @Test
    @DisplayName("SaaS 면 온프레미스 전용 안내(한국어)")
    void saas() throws Exception {
        stub(new LicenseView("saas", null, null, null, null, 0, List.of(), null, null, null));
        Document d = page();
        assertThat(d.selectFirst("main").text()).contains("라이선스 관리는 온프레미스 설치본에서만 사용합니다")
                .contains("클라우드 설치본은 계약에 따라 자동 관리됩니다.").doesNotContain("on-prem only");
        assertThat(d.select("main dl")).isEmpty();
    }

    @Test
    @DisplayName("조회 실패는 LoadError(다시 시도 = 화면 다시 받기), 알 수 없는 예외의 원문은 보이지 않는다")
    void loadFailure() throws Exception {
        when(license.get()).thenThrow(new IllegalStateException("boom"));
        Document d = page();
        Element alert = d.selectFirst("main [role=alert]");
        assertThat(alert.text()).contains("라이선스 정보를 불러오지 못했습니다").doesNotContain("boom");
        Element retry = alert.select("button").stream().filter(b -> b.text().equals("다시 시도")).findFirst().orElseThrow();
        assertThat(retry.attr("hx-get")).isEqualTo("/admin/license");
    }

    @Test
    @DisplayName("지금 다시 확인: 버튼은 다시 확인 조각(요청 중 비활성·'확인 중…', 중복 drop), 성공하면 상세 영역 교체")
    void recheckSuccess() throws Exception {
        Element btn = page().select("main button").stream().filter(b -> b.text().contains("지금 다시 확인")).findFirst().orElseThrow();
        assertThat(btn.attr("hx-get")).isEqualTo(LicensePageController.RECHECK_PATH);
        assertThat(btn.attr("hx-target")).isEqualTo("#license-body");
        assertThat(btn.attr("hx-swap")).isEqualTo("outerHTML");
        assertThat(btn.attr("hx-sync")).isEqualTo("this:drop");
        assertThat(btn.attr("hx-disabled-elt")).isEqualTo("this");
        assertThat(btn.selectFirst(".license-recheck__busy").text()).isEqualTo("확인 중…");

        MvcResult r = mvc.perform(get(LicensePageController.RECHECK_PATH).with(authentication(platform())).header("HX-Request", "true")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        Document d = Jsoup.parse(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(d.selectFirst("#license-body")).isNotNull();
        assertThat(d.selectFirst("#license-recheck-alert").children()).isEmpty();
        assertThat(d.text()).contains("정상");
        assertThat(d.select("aside")).isEmpty();
    }

    @Test
    @DisplayName("지금 다시 확인 실패: 기존 정보 유지(경고 자리만 교체) + '다시 확인하지 못했습니다'")
    void recheckFailureKeepsPrevious() throws Exception {
        when(license.get()).thenThrow(new IllegalStateException("boom"));
        MvcResult r = mvc.perform(get(LicensePageController.RECHECK_PATH).with(authentication(platform())).header("HX-Request", "true")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getHeader("HX-Retarget")).isEqualTo("#license-recheck-alert");
        assertThat(r.getResponse().getHeader("HX-Reswap")).isEqualTo("innerHTML");
        Document d = Jsoup.parse(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(d.selectFirst(".banner__title").text()).isEqualTo("다시 확인하지 못했습니다");
        assertThat(d.text()).contains("서버가 잠시 응답하지 않습니다. 아래는 마지막으로 확인한 정보입니다.").doesNotContain("boom");
        assertThat(d.selectFirst("#license-body")).isNull();
    }

    @Test
    @DisplayName("고객사·기능 값은 이스케이프")
    void escapes() throws Exception {
        stub(new LicenseView("onprem", "VALID", "<b>x</b>", "lic\"1", BASE.expiresAt(), 200, List.of("<i>f</i>"), null, null, null));
        String html = mvc.perform(get("/admin/license").with(authentication(platform()))).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).doesNotContain("<b>x</b>").doesNotContain("<i>f</i>").contains("&lt;b&gt;x&lt;/b&gt;");
    }
}
