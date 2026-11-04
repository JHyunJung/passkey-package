package com.crosscert.passkey.admin.web.shell;

import com.crosscert.passkey.admin.auth.ActiveTenantController;
import com.crosscert.passkey.admin.auth.AdminUserDetails;
import com.crosscert.passkey.admin.tenant.TenantAdminController;
import com.crosscert.passkey.admin.tenant.TenantAdminDto;
import com.crosscert.passkey.admin.web.AdminWebSliceTest;
import com.crosscert.passkey.core.api.ApiResponse;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 명령 팔레트 결과 조각(GET /admin/_/palette). 원본: extras/CommandPalette.test.tsx.
 * 이 테스트는 서버가 그리는 결과(항목·그룹·순서·비활성·범위)를 지킨다. 화살표 이동·Enter·Esc·포커스·150ms 디바운스는 브라우저 동작이라 JUnit 으로 확인할 수 없다(parity 문서).
 */
@AdminWebSliceTest(controllers = CommandPaletteController.class)
class CommandPaletteControllerTest {

    @Autowired MockMvc mvc;
    @MockBean TenantAdminController tenants;
    @MockBean ActiveTenantController activeTenants;

    static final UUID T1 = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final UUID T2 = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    @BeforeEach
    void defaults() {
        when(tenants.list()).thenReturn(ApiResponse.ok(List.of()));
    }

    private static Authentication auth(String role, Set<UUID> ids) {
        AdminUserDetails p = new AdminUserDetails(UUID.randomUUID(), "a@x.com", "x", role, ids, true, null, Clock.systemUTC());
        return new UsernamePasswordAuthenticationToken(p, "x", p.getAuthorities());
    }

    static Authentication platform() {
        return auth("PLATFORM_OPERATOR", Set.of());
    }

    static Authentication rp() {
        return auth("RP_ADMIN", Set.of(T1, T2));
    }

    private Document palette(String q, Authentication a) throws Exception {
        MvcResult r = mvc.perform(get("/admin/_/palette").param("q", q).with(authentication(a))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return Jsoup.parse(r.getResponse().getContentAsString());
    }

    private static TenantAdminDto.TenantView tenant(UUID id, String slug, String name) {
        return new TenantAdminDto.TenantView(id, slug, name, "ACTIVE", "rp.example.com", name, List.of(), Set.of(),
                false, false, "none", 60000, 0, 0, null, null, null);
    }

    private static List<String> labels(Document d) {
        return d.select("[role=option] > span:first-of-type").eachText();
    }

    private static List<String> groups(Document d) {
        return d.select("[role=group]").eachAttr("aria-label");
    }

    private static Element option(Document d, String label) {
        return d.select("[role=option]").stream().filter(e -> e.selectFirst("span").text().equals(label)).findFirst().orElseThrow();
    }

    // ── PLATFORM ────────────────────────────────────────────

    @Test
    @DisplayName("PLATFORM, 검색어 없음: 이동 4 · 도움말 · 액션 2 순서, 그룹 라벨, 이동 항목에 단축키(G T 등) 표시")
    void platformDefaultList() throws Exception {
        Document d = palette("", platform());
        assertThat(groups(d)).containsExactly("이동", "도움말", "액션");
        assertThat(labels(d)).containsExactly("Tenants 목록", "Activity", "감사 체인 모니터", "설정", "키보드 단축키", "신규 tenant 생성", "로그아웃");
        // 그룹 제목은 t-over 이고 보조기기에는 숨긴다.
        assertThat(d.select("[role=group] > span.t-over[aria-hidden=true]").eachText()).containsExactly("이동", "도움말", "액션");
        assertThat(option(d, "Tenants 목록").select("kbd").text()).isEqualTo("G T");
        assertThat(option(d, "Activity").select("kbd").text()).isEqualTo("G A");
        assertThat(option(d, "감사 체인 모니터").select("kbd").text()).isEqualTo("G C");
        assertThat(option(d, "설정").select("kbd").text()).isEqualTo("G S");
        assertThat(option(d, "키보드 단축키").select("kbd")).isEmpty();
        // 오른쪽 문구는 path ?? hint: 이동은 path(플랫폼/관리), 나머지는 hint.
        assertThat(option(d, "Tenants 목록").select(".hint").text()).isEqualTo("플랫폼");
        assertThat(option(d, "설정").select(".hint").text()).isEqualTo("관리");
        assertThat(option(d, "키보드 단축키").select(".hint").text()).isEqualTo("?");
        assertThat(option(d, "신규 tenant 생성").select(".hint").text()).isEqualTo("다이얼로그 열기");
        assertThat(option(d, "로그아웃").select(".hint")).isEmpty();
        // 동작하지 않는 항목은 없다.
        assertThat(d.text()).doesNotContain("Hash chain 즉시 검증").doesNotContain("Role 전환 (데모)");
        verify(tenants, never()).list();
        verify(activeTenants, never()).current(); // PLATFORM 은 활성 tenant 를 조회하지 않는다
    }

    @Test
    @DisplayName("항목: 이동은 data-href, 동작은 data-action, 로그아웃은 위험 스타일, id·role=option·tabindex=-1")
    void itemAttributes() throws Exception {
        Document d = palette("", platform());
        assertThat(option(d, "Tenants 목록").attr("data-href")).isEqualTo("/admin/tenants");
        assertThat(option(d, "Activity").attr("data-href")).isEqualTo("/admin/activity");
        assertThat(option(d, "감사 체인 모니터").attr("data-href")).isEqualTo("/admin/audit-chain");
        assertThat(option(d, "설정").attr("data-href")).isEqualTo("/admin/settings");
        assertThat(option(d, "신규 tenant 생성").attr("data-href")).isEqualTo("/admin/tenants?new=1");
        assertThat(option(d, "키보드 단축키").attr("data-action")).isEqualTo("shortcuts");
        assertThat(option(d, "로그아웃").attr("data-action")).isEqualTo("logout");
        assertThat(option(d, "로그아웃").hasClass("menu__item--danger")).isTrue();
        assertThat(option(d, "설정").hasClass("menu__item--danger")).isFalse();
        Elements opts = d.select("[role=option]");
        assertThat(opts.eachAttr("id")).containsExactly("palette-opt-0", "palette-opt-1", "palette-opt-2", "palette-opt-3",
                "palette-opt-4", "palette-opt-5", "palette-opt-6");
        assertThat(opts.eachAttr("tabindex")).containsOnly("-1");
        assertThat(opts.eachAttr("type")).containsOnly("button");
        assertThat(opts.eachAttr("aria-selected")).containsOnly("false"); // 선택 표시는 클라이언트가 칠한다
        assertThat(opts.stream().filter(e -> e.hasAttr("aria-disabled") || e.hasAttr("disabled"))).isEmpty();
    }

    @Test
    @DisplayName("검색어는 라벨·힌트·그룹에서 대소문자 무시 부분 일치로 거른다(path 는 대상 아님)")
    void filtersByLabelHintGroup() throws Exception {
        assertThat(labels(palette("액션", platform()))).containsExactly("신규 tenant 생성", "로그아웃");           // 그룹
        assertThat(labels(palette("?", platform()))).containsExactly("키보드 단축키");                              // 힌트
        assertThat(labels(palette("ACTIVITY", platform()))).containsExactly("Activity");                           // 라벨, 대소문자
        assertThat(labels(palette("cross-tenant", platform()))).containsExactly("Activity");                       // 힌트
        assertThat(labels(palette("플랫폼", platform()))).isEmpty();                                                // path 는 검색 대상이 아니다
    }

    @Test
    @DisplayName("모든 결과 항목이 자기 검색어를 data-q 로 가진다(입력값과 다르면 클라이언트가 실행을 막는다)")
    void itemsCarryQuery() throws Exception {
        assertThat(palette("로그", platform()).select("[role=option]").eachAttr("data-q")).isNotEmpty().containsOnly("로그");
        // 결과가 있는 패딩된 검색어("tenant " → "신규 tenant 생성")에서 data-q 는 trim 하지 않은 입력값 그대로.
        Document padded = palette("tenant ", platform());
        assertThat(labels(padded)).containsExactly("Activity", "감사 체인 모니터", "신규 tenant 생성"); // 힌트 "cross-tenant …"·"전 tenant …" 도 맞는다
        assertThat(padded.select("[role=option]").eachAttr("data-q")).containsOnly("tenant ");
        assertThat(padded.selectFirst("[data-results-marker]").attr("data-q")).isEqualTo("tenant ");
    }

    @Test
    @DisplayName("검색 결과 없음: 안내 문구(“검색어”와 일치하는 …)와 보조 설명, 빈 검색어면 다른 문구")
    void emptyState() throws Exception {
        Document d = palette("zzzzzz", platform());
        assertThat(d.select("[role=option]")).isEmpty();
        assertThat(d.select(".empty__title").text()).isEqualTo("“zzzzzz”와 일치하는 tenant · 화면이 없습니다.");
        assertThat(d.select(".empty__sub").text()).isEqualTo("credential · 감사 이벤트 ID는 해당 화면의 검색에서 찾으세요.");
        assertThat(d.select(".empty").hasClass("empty--compact")).isTrue();
        assertThat(d.select("img.empty__art").attr("src")).isEqualTo("/admin/img/no-results.webp");
    }

    @Test
    @DisplayName("PLATFORM tenant 검색: 이름·slug·id 부분 일치, 목록 순서 그대로, 힌트 “slug · id 끝 8자”, 이동은 ?tab=overview, 명령 항목 뒤에 붙는다")
    void platformTenantSearch() throws Exception {
        UUID acme = UUID.fromString("11111111-1111-1111-1111-1111aaaaaaa1");
        UUID acmeCo = UUID.fromString("22222222-2222-2222-2222-2222bbbbbbb2");
        UUID other = UUID.fromString("33333333-3333-3333-3333-3333ccccccc3");
        when(tenants.list()).thenReturn(ApiResponse.ok(List.of(
                tenant(acmeCo, "acme-co", "Zeta"),     // slug 로 일치(이름은 다름), 목록에서 먼저
                tenant(other, "other", "Other Inc"),
                tenant(acme, "acme", "Acme Corp"))));  // 이름·slug 로 일치
        Document d = palette("acme", platform());
        assertThat(groups(d)).containsExactly("Tenant");
        assertThat(labels(d)).containsExactly("Zeta", "Acme Corp"); // 입력 순서 유지
        Element first = d.select("[role=option]").get(0);
        assertThat(first.select(".hint").text()).isEqualTo("acme-co · bbbbbbb2");
        assertThat(first.attr("data-href")).isEqualTo("/admin/tenants/" + acmeCo + "?tab=overview");
        assertThat(d.select("[role=option]").get(1).select(".hint").text()).isEqualTo("acme · aaaaaaa1");
        assertThat(d.select("[role=option]").eachAttr("id")).containsExactly("palette-opt-0", "palette-opt-1");
    }

    @Test
    @DisplayName("tenant 검색은 최대 8건, 그리고 명령 항목(이동·액션 등) 뒤에 같은 순서로 이어진다")
    void tenantSearchCapAndOrder() throws Exception {
        List<TenantAdminDto.TenantView> many = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            many.add(tenant(UUID.fromString(String.format("00000000-0000-0000-0000-%012d", i)), "tn-" + i, "Tn " + i));
        }
        when(tenants.list()).thenReturn(ApiResponse.ok(many));
        // "tn" 은 명령 라벨·힌트·그룹에는 안 맞는다 → 결과는 tenant 8건뿐.
        assertThat(labels(palette("tn", platform()))).hasSize(8).first().isEqualTo("Tn 0");
        // "tenant" 는 명령 항목(Tenants 목록 · 신규 tenant 생성 · 힌트 cross-tenant …)과 맞으므로 tenant 그룹은 맨 뒤.
        when(tenants.list()).thenReturn(ApiResponse.ok(List.of(tenant(T1, "tenant-a", "Tenant A"))));
        Document d = palette("tenant", platform());
        assertThat(groups(d).get(groups(d).size() - 1)).isEqualTo("Tenant");
        assertThat(labels(d).get(labels(d).size() - 1)).isEqualTo("Tenant A");
    }

    @Test
    @DisplayName("tenant 조회가 실패해도 팔레트는 명령 항목으로 동작한다(검색 실패는 무시)")
    void tenantSearchFailureIsIgnored() throws Exception {
        when(tenants.list()).thenThrow(new IllegalStateException("db down"));
        assertThat(labels(palette("설정", platform()))).containsExactly("설정");
    }

    // ── RP_ADMIN ────────────────────────────────────────────

    @Test
    @DisplayName("RP 관리자: 첫 tenant 가 아니라 활성 tenant 기준, 그룹 라벨 “현재 Tenant · 이름”, 탭 7개 순서, 신규 tenant 생성은 비활성 + 권한 없음")
    void rpUsesActiveTenant() throws Exception {
        when(activeTenants.current()).thenReturn(new ActiveTenantController.ActiveTenantView(T2, List.of(T1, T2),
                List.of(new ActiveTenantController.TenantRef(T1, "Acme"), new ActiveTenantController.TenantRef(T2, "Globex"))));
        Document d = palette("", rp());
        assertThat(groups(d)).containsExactly("현재 Tenant · Globex", "도움말", "액션");
        assertThat(labels(d)).containsExactly("개요", "WebAuthn", "AAGUID 정책", "API 키", "Credentials", "감사 로그", "퍼널",
                "키보드 단축키", "신규 tenant 생성", "로그아웃");
        assertThat(option(d, "개요").attr("data-href")).isEqualTo("/admin/tenants/" + T2 + "?tab=overview");
        assertThat(d.select("[role=option]").eachAttr("data-href").stream().filter(h -> h.startsWith("/admin/tenants/")))
                .hasSize(7).allMatch(h -> h.contains(T2.toString()));
        assertThat(d.select("[data-href*=\"" + T1 + "\"]")).isEmpty();
        // G 이동·플랫폼 항목은 없다.
        assertThat(d.select("[role=group][aria-label=이동]")).isEmpty();
        Element create = option(d, "신규 tenant 생성");
        assertThat(create.hasAttr("disabled")).isTrue();
        assertThat(create.attr("aria-disabled")).isEqualTo("true");
        assertThat(create.select(".hint").text()).isEqualTo("권한 없음");
        assertThat(create.hasAttr("data-href")).isFalse();
    }

    @Test
    @DisplayName("RP 관리자 범위는 서버가 정한다: 클라이언트가 tenantId 를 보내도, 검색어가 다른 tenant 이름이어도 활성 tenant 항목과 명령뿐이고 tenant 검색은 하지 않는다")
    void rpScopeIsServerSide() throws Exception {
        when(activeTenants.current()).thenReturn(new ActiveTenantController.ActiveTenantView(T1, List.of(T1),
                List.of(new ActiveTenantController.TenantRef(T1, "Acme"))));
        MvcResult r = mvc.perform(get("/admin/_/palette").param("q", "acme").param("tenantId", T2.toString())
                .param("tenantIds", T2.toString()).with(authentication(rp()))).andReturn();
        Document d = Jsoup.parse(r.getResponse().getContentAsString());
        assertThat(groups(d)).containsExactly("현재 Tenant · Acme");
        assertThat(d.select("[role=option]").eachAttr("data-href")).allMatch(h -> h.contains(T1.toString()));
        assertThat(d.select("[role=group][aria-label=Tenant]")).isEmpty();
        verify(tenants, never()).list(); // PLATFORM 에게만 열린 tenant 검색을 RP 가 부르지 않는다
    }

    @Test
    @DisplayName("활성 tenant 조회 실패: 첫 tenant 를 짐작하지 않고 탭은 비활성 + 사유, “현재 tenant 다시 확인”(닫지 않음) 제공")
    void rpActiveTenantFailure() throws Exception {
        when(activeTenants.current()).thenThrow(new IllegalStateException("x"));
        Document d = palette("", rp());
        assertThat(groups(d)).containsExactly("현재 Tenant", "도움말", "액션");
        Element overview = option(d, "개요");
        assertThat(overview.hasAttr("disabled")).isTrue();
        assertThat(overview.attr("aria-disabled")).isEqualTo("true");
        assertThat(overview.select(".hint").text()).isEqualTo("현재 tenant를 확인하지 못했습니다");
        assertThat(overview.hasAttr("data-href")).isFalse();
        Element retry = option(d, "현재 tenant 다시 확인");
        assertThat(retry.attr("data-action")).isEqualTo("recheck");
        assertThat(retry.attr("data-keep-open")).isEqualTo("true");
        assertThat(retry.hasAttr("disabled")).isFalse();
        assertThat(labels(d)).containsSubsequence("퍼널", "현재 tenant 다시 확인", "키보드 단축키");
    }

    @Test
    @DisplayName("활성 tenant 가 없으면(null) 실패와 같게 취급, tenantIds 도 없으면 현재 Tenant 그룹 자체를 숨긴다")
    void rpNoActiveTenant() throws Exception {
        when(activeTenants.current()).thenReturn(new ActiveTenantController.ActiveTenantView(null, List.of(), List.of()));
        // me.tenantIds 가 비어 있고 활성도 없음 → 그룹 없음
        Document d = palette("", auth("RP_ADMIN", Set.of()));
        assertThat(groups(d)).containsExactly("도움말", "액션");
        // tenantIds 가 있는데 활성이 없으면 비활성 + 다시 확인
        Document d2 = palette("", rp());
        assertThat(groups(d2)).contains("현재 Tenant");
        assertThat(option(d2, "개요").attr("aria-disabled")).isEqualTo("true");
    }

    @Test
    @DisplayName("RP: 검색어 “개요”는 비활성이 아닌 활성 tenant 의 개요 항목을 보인다 / 상태 변화마다 서버가 새로 읽는다")
    void rpFilterAndFreshRead() throws Exception {
        when(activeTenants.current())
                .thenReturn(new ActiveTenantController.ActiveTenantView(T1, List.of(T1), List.of(new ActiveTenantController.TenantRef(T1, "Acme"))))
                .thenReturn(new ActiveTenantController.ActiveTenantView(T2, List.of(T2), List.of(new ActiveTenantController.TenantRef(T2, "Globex"))));
        assertThat(option(palette("개요", rp()), "개요").attr("data-href")).contains(T1.toString());
        assertThat(option(palette("개요", rp()), "개요").attr("data-href")).contains(T2.toString()); // 전환 직후 다시 열면 새 tenant
    }
}
