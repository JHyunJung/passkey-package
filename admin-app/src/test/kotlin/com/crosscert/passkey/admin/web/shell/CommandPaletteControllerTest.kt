package com.crosscert.passkey.admin.web.shell

import com.crosscert.passkey.admin.auth.ActiveTenantController
import com.crosscert.passkey.admin.auth.AdminUserDetails
import com.crosscert.passkey.admin.tenant.TenantAdminController
import com.crosscert.passkey.admin.tenant.TenantAdminDto
import com.crosscert.passkey.admin.web.AdminWebSliceTest
import com.crosscert.passkey.core.api.ApiResponse
import org.assertj.core.api.Assertions.assertThat
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import java.time.Clock
import java.util.UUID

/**
 * 명령 팔레트 결과 조각(GET /admin/_/palette). 원본: extras/CommandPalette.test.tsx.
 * 이 테스트는 서버가 그리는 결과(항목·그룹·순서·비활성·범위)를 지킨다. 화살표 이동·Enter·Esc·포커스·150ms 디바운스는 브라우저 동작이라 JUnit 으로 확인할 수 없다(parity 문서).
 *
 * (Kotlin 전환) 즉시 역참조하는 nullable jsoup 결과(`selectFirst`)의 `!!` 는 원 Java 의 NPE 실패 지점과 같다.
 */
@AdminWebSliceTest(controllers = [CommandPaletteController::class])
class CommandPaletteControllerTest {

    @Autowired lateinit var mvc: MockMvc
    @MockitoBean lateinit var tenants: TenantAdminController
    @MockitoBean lateinit var activeTenants: ActiveTenantController

    companion object {
        val T1: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
        val T2: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000b2")

        private fun auth(role: String, ids: Set<UUID>): Authentication {
            val p = AdminUserDetails(UUID.randomUUID(), "a@x.com", "x", role, ids, true, null, Clock.systemUTC())
            return UsernamePasswordAuthenticationToken(p, "x", p.authorities)
        }

        fun platform(): Authentication = auth("PLATFORM_OPERATOR", setOf())

        fun rp(): Authentication = auth("RP_ADMIN", java.util.Set.of(T1, T2))

        private fun tenant(id: UUID, slug: String, name: String): TenantAdminDto.TenantView =
            TenantAdminDto.TenantView(
                id, slug, name, "ACTIVE", "rp.example.com", name, listOf(), setOf(),
                false, false, "none", 60000, 0, 0, null, null, null,
            )

        private fun labels(d: Document): List<String> = d.select("[role=option] > span:first-of-type").eachText()

        private fun groups(d: Document): List<String> = d.select("[role=group]").eachAttr("aria-label")

        private fun option(d: Document, label: String): Element =
            d.select("[role=option]").stream().filter { e -> e.selectFirst("span")!!.text() == label }.findFirst().orElseThrow()
    }

    @BeforeEach
    fun defaults() {
        whenever(tenants.list()).thenReturn(ApiResponse.ok(listOf()))
    }

    private fun palette(q: String, a: Authentication): Document {
        val r = mvc.perform(get("/admin/_/palette").param("q", q).with(authentication(a))).andReturn()
        assertThat(r.response.status).isEqualTo(200)
        return Jsoup.parse(r.response.contentAsString)
    }

    // ── PLATFORM ────────────────────────────────────────────

    @Test
    @DisplayName("PLATFORM, 검색어 없음: 이동 5 · 도움말 · 액션 2 순서, 그룹 라벨, 이동 항목에 단축키(G T 등) 표시")
    fun platformDefaultList() {
        val d = palette("", platform())
        assertThat(groups(d)).containsExactly("이동", "도움말", "액션")
        assertThat(labels(d)).containsExactly("Tenants 목록", "현황", "Activity", "감사 체인 모니터", "설정", "키보드 단축키", "신규 tenant 생성", "로그아웃")
        // 그룹 제목은 t-over 이고 보조기기에는 숨긴다.
        assertThat(d.select("[role=group] > span.t-over[aria-hidden=true]").eachText()).containsExactly("이동", "도움말", "액션")
        assertThat(option(d, "Tenants 목록").select("kbd").text()).isEqualTo("G T")
        assertThat(option(d, "Activity").select("kbd").text()).isEqualTo("G A")
        assertThat(option(d, "감사 체인 모니터").select("kbd").text()).isEqualTo("G C")
        assertThat(option(d, "설정").select("kbd").text()).isEqualTo("G S")
        assertThat(option(d, "키보드 단축키").select("kbd")).isEmpty()
        // 오른쪽 문구는 path ?? hint: 이동은 path(플랫폼/관리), 나머지는 hint.
        assertThat(option(d, "Tenants 목록").select(".hint").text()).isEqualTo("플랫폼")
        assertThat(option(d, "설정").select(".hint").text()).isEqualTo("관리")
        assertThat(option(d, "키보드 단축키").select(".hint").text()).isEqualTo("?")
        assertThat(option(d, "신규 tenant 생성").select(".hint").text()).isEqualTo("다이얼로그 열기")
        assertThat(option(d, "로그아웃").select(".hint")).isEmpty()
        // 동작하지 않는 항목은 없다.
        assertThat(d.text()).doesNotContain("Hash chain 즉시 검증").doesNotContain("Role 전환 (데모)")
        verify(tenants, never()).list()
        verify(activeTenants, never()).current() // PLATFORM 은 활성 tenant 를 조회하지 않는다
    }

    @Test
    @DisplayName("항목: 이동은 data-href, 동작은 data-action, 로그아웃은 위험 스타일, id·role=option·tabindex=-1")
    fun itemAttributes() {
        val d = palette("", platform())
        assertThat(option(d, "Tenants 목록").attr("data-href")).isEqualTo("/admin/tenants")
        assertThat(option(d, "Activity").attr("data-href")).isEqualTo("/admin/activity")
        assertThat(option(d, "감사 체인 모니터").attr("data-href")).isEqualTo("/admin/audit-chain")
        assertThat(option(d, "설정").attr("data-href")).isEqualTo("/admin/settings")
        assertThat(option(d, "신규 tenant 생성").attr("data-href")).isEqualTo("/admin/tenants?new=1")
        assertThat(option(d, "키보드 단축키").attr("data-action")).isEqualTo("shortcuts")
        assertThat(option(d, "로그아웃").attr("data-action")).isEqualTo("logout")
        assertThat(option(d, "로그아웃").hasClass("menu__item--danger")).isTrue()
        assertThat(option(d, "설정").hasClass("menu__item--danger")).isFalse()
        val opts = d.select("[role=option]")
        assertThat(opts.eachAttr("id")).containsExactly(
            "palette-opt-0", "palette-opt-1", "palette-opt-2", "palette-opt-3",
            "palette-opt-4", "palette-opt-5", "palette-opt-6", "palette-opt-7",
        )
        assertThat(opts.eachAttr("tabindex")).containsOnly("-1")
        assertThat(opts.eachAttr("type")).containsOnly("button")
        assertThat(opts.eachAttr("aria-selected")).containsOnly("false") // 선택 표시는 클라이언트가 칠한다
        assertThat(opts.stream().filter { e -> e.hasAttr("aria-disabled") || e.hasAttr("disabled") }).isEmpty()
    }

    @Test
    @DisplayName("검색어는 라벨·힌트·그룹에서 대소문자 무시 부분 일치로 거른다(path 는 대상 아님)")
    fun filtersByLabelHintGroup() {
        assertThat(labels(palette("액션", platform()))).containsExactly("신규 tenant 생성", "로그아웃") // 그룹
        assertThat(labels(palette("?", platform()))).containsExactly("키보드 단축키") // 힌트
        assertThat(labels(palette("ACTIVITY", platform()))).containsExactly("Activity") // 라벨, 대소문자
        assertThat(labels(palette("cross-tenant", platform()))).containsExactly("Activity") // 힌트
        assertThat(labels(palette("플랫폼", platform()))).isEmpty() // path 는 검색 대상이 아니다
    }

    @Test
    @DisplayName("모든 결과 항목이 자기 검색어를 data-q 로 가진다(입력값과 다르면 클라이언트가 실행을 막는다)")
    fun itemsCarryQuery() {
        assertThat(palette("로그", platform()).select("[role=option]").eachAttr("data-q")).isNotEmpty().containsOnly("로그")
        // 결과가 있는 패딩된 검색어("tenant " → "신규 tenant 생성")에서 data-q 는 trim 하지 않은 입력값 그대로.
        val padded = palette("tenant ", platform())
        assertThat(labels(padded)).containsExactly("Activity", "감사 체인 모니터", "신규 tenant 생성") // 힌트 "cross-tenant …"·"전 tenant …" 도 맞는다
        assertThat(padded.select("[role=option]").eachAttr("data-q")).containsOnly("tenant ")
        assertThat(padded.selectFirst("[data-results-marker]")!!.attr("data-q")).isEqualTo("tenant ")
    }

    @Test
    @DisplayName("검색 결과 없음: 안내 문구(“검색어”와 일치하는 …)와 보조 설명, 빈 검색어면 다른 문구")
    fun emptyState() {
        val d = palette("zzzzzz", platform())
        assertThat(d.select("[role=option]")).isEmpty()
        assertThat(d.select(".empty__title").text()).isEqualTo("“zzzzzz”와 일치하는 tenant · 화면이 없습니다.")
        assertThat(d.select(".empty__sub").text()).isEqualTo("credential · 감사 이벤트 ID는 해당 화면의 검색에서 찾으세요.")
        assertThat(d.select(".empty").hasClass("empty--compact")).isTrue()
        assertThat(d.select("img.empty__art").attr("src")).isEqualTo("/admin/img/no-results.webp")
    }

    @Test
    @DisplayName("PLATFORM tenant 검색: 이름·slug·id 부분 일치, 목록 순서 그대로, 힌트 “slug · id 끝 8자”, 이동은 ?tab=overview, 명령 항목 뒤에 붙는다")
    fun platformTenantSearch() {
        val acme = UUID.fromString("11111111-1111-1111-1111-1111aaaaaaa1")
        val acmeCo = UUID.fromString("22222222-2222-2222-2222-2222bbbbbbb2")
        val other = UUID.fromString("33333333-3333-3333-3333-3333ccccccc3")
        whenever(tenants.list()).thenReturn(
            ApiResponse.ok(
                listOf(
                    tenant(acmeCo, "acme-co", "Zeta"), // slug 로 일치(이름은 다름), 목록에서 먼저
                    tenant(other, "other", "Other Inc"),
                    tenant(acme, "acme", "Acme Corp"), // 이름·slug 로 일치
                ),
            ),
        )
        val d = palette("acme", platform())
        assertThat(groups(d)).containsExactly("Tenant")
        assertThat(labels(d)).containsExactly("Zeta", "Acme Corp") // 입력 순서 유지
        val first = d.select("[role=option]")[0]
        assertThat(first.select(".hint").text()).isEqualTo("acme-co · bbbbbbb2")
        assertThat(first.attr("data-href")).isEqualTo("/admin/tenants/" + acmeCo + "?tab=overview")
        assertThat(d.select("[role=option]")[1].select(".hint").text()).isEqualTo("acme · aaaaaaa1")
        assertThat(d.select("[role=option]").eachAttr("id")).containsExactly("palette-opt-0", "palette-opt-1")
    }

    @Test
    @DisplayName("tenant 검색은 최대 8건, 그리고 명령 항목(이동·액션 등) 뒤에 같은 순서로 이어진다")
    fun tenantSearchCapAndOrder() {
        val many = ArrayList<TenantAdminDto.TenantView>()
        for (i in 0 until 12) {
            many.add(tenant(UUID.fromString(String.format("00000000-0000-0000-0000-%012d", i)), "tn-" + i, "Tn " + i))
        }
        whenever(tenants.list()).thenReturn(ApiResponse.ok(many))
        // "tn" 은 명령 라벨·힌트·그룹에는 안 맞는다 → 결과는 tenant 8건뿐.
        assertThat(labels(palette("tn", platform()))).hasSize(8).first().isEqualTo("Tn 0")
        // "tenant" 는 명령 항목(Tenants 목록 · 신규 tenant 생성 · 힌트 cross-tenant …)과 맞으므로 tenant 그룹은 맨 뒤.
        whenever(tenants.list()).thenReturn(ApiResponse.ok(listOf(tenant(T1, "tenant-a", "Tenant A"))))
        val d = palette("tenant", platform())
        assertThat(groups(d)[groups(d).size - 1]).isEqualTo("Tenant")
        assertThat(labels(d)[labels(d).size - 1]).isEqualTo("Tenant A")
    }

    @Test
    @DisplayName("tenant 조회가 실패해도 팔레트는 명령 항목으로 동작한다(검색 실패는 무시)")
    fun tenantSearchFailureIsIgnored() {
        whenever(tenants.list()).thenThrow(IllegalStateException("db down"))
        assertThat(labels(palette("설정", platform()))).containsExactly("설정")
    }

    // ── RP_ADMIN ────────────────────────────────────────────

    @Test
    @DisplayName("RP 관리자: 첫 tenant 가 아니라 활성 tenant 기준, 그룹 라벨 “현재 Tenant · 이름”, 탭 7개 순서, 신규 tenant 생성은 비활성 + 권한 없음")
    fun rpUsesActiveTenant() {
        whenever(activeTenants.current()).thenReturn(
            ActiveTenantController.ActiveTenantView(
                T2, listOf(T1, T2),
                listOf(ActiveTenantController.TenantRef(T1, "Acme"), ActiveTenantController.TenantRef(T2, "Globex")),
            ),
        )
        val d = palette("", rp())
        assertThat(groups(d)).containsExactly("현재 Tenant · Globex", "도움말", "액션")
        assertThat(labels(d)).containsExactly(
            "개요", "WebAuthn", "AAGUID 정책", "API 키", "Credentials", "감사 로그", "인증 현황",
            "키보드 단축키", "신규 tenant 생성", "로그아웃",
        )
        assertThat(option(d, "개요").attr("data-href")).isEqualTo("/admin/tenants/" + T2 + "?tab=overview")
        assertThat(d.select("[role=option]").eachAttr("data-href").stream().filter { h -> h.startsWith("/admin/tenants/") })
            .hasSize(7).allMatch { h -> h.contains(T2.toString()) }
        assertThat(d.select("[data-href*=\"" + T1 + "\"]")).isEmpty()
        // G 이동·플랫폼 항목은 없다.
        assertThat(d.select("[role=group][aria-label=이동]")).isEmpty()
        val create = option(d, "신규 tenant 생성")
        assertThat(create.hasAttr("disabled")).isTrue()
        assertThat(create.attr("aria-disabled")).isEqualTo("true")
        assertThat(create.select(".hint").text()).isEqualTo("권한 없음")
        assertThat(create.hasAttr("data-href")).isFalse()
    }

    @Test
    @DisplayName("RP 관리자 범위는 서버가 정한다: 클라이언트가 tenantId 를 보내도, 검색어가 다른 tenant 이름이어도 활성 tenant 항목과 명령뿐이고 tenant 검색은 하지 않는다")
    fun rpScopeIsServerSide() {
        whenever(activeTenants.current()).thenReturn(
            ActiveTenantController.ActiveTenantView(T1, listOf(T1), listOf(ActiveTenantController.TenantRef(T1, "Acme"))),
        )
        val r = mvc.perform(
            get("/admin/_/palette").param("q", "acme").param("tenantId", T2.toString())
                .param("tenantIds", T2.toString()).with(authentication(rp())),
        ).andReturn()
        val d = Jsoup.parse(r.response.contentAsString)
        assertThat(groups(d)).containsExactly("현재 Tenant · Acme")
        assertThat(d.select("[role=option]").eachAttr("data-href")).allMatch { h -> h.contains(T1.toString()) }
        assertThat(d.select("[role=group][aria-label=Tenant]")).isEmpty()
        verify(tenants, never()).list() // PLATFORM 에게만 열린 tenant 검색을 RP 가 부르지 않는다
    }

    @Test
    @DisplayName("활성 tenant 조회 실패: 첫 tenant 를 짐작하지 않고 탭은 비활성 + 사유, “현재 tenant 다시 확인”(닫지 않음) 제공")
    fun rpActiveTenantFailure() {
        whenever(activeTenants.current()).thenThrow(IllegalStateException("x"))
        val d = palette("", rp())
        assertThat(groups(d)).containsExactly("현재 Tenant", "도움말", "액션")
        val overview = option(d, "개요")
        assertThat(overview.hasAttr("disabled")).isTrue()
        assertThat(overview.attr("aria-disabled")).isEqualTo("true")
        assertThat(overview.select(".hint").text()).isEqualTo("현재 tenant를 확인하지 못했습니다")
        assertThat(overview.hasAttr("data-href")).isFalse()
        val retry = option(d, "현재 tenant 다시 확인")
        assertThat(retry.attr("data-action")).isEqualTo("recheck")
        assertThat(retry.attr("data-keep-open")).isEqualTo("true")
        assertThat(retry.hasAttr("disabled")).isFalse()
        assertThat(labels(d)).containsSubsequence("인증 현황", "현재 tenant 다시 확인", "키보드 단축키")
    }

    @Test
    @DisplayName("활성 tenant 가 없으면(null) 실패와 같게 취급, tenantIds 도 없으면 현재 Tenant 그룹 자체를 숨긴다")
    fun rpNoActiveTenant() {
        whenever(activeTenants.current()).thenReturn(ActiveTenantController.ActiveTenantView(null, listOf(), listOf()))
        // me.tenantIds 가 비어 있고 활성도 없음 → 그룹 없음
        val d = palette("", auth("RP_ADMIN", setOf()))
        assertThat(groups(d)).containsExactly("도움말", "액션")
        // tenantIds 가 있는데 활성이 없으면 비활성 + 다시 확인
        val d2 = palette("", rp())
        assertThat(groups(d2)).contains("현재 Tenant")
        assertThat(option(d2, "개요").attr("aria-disabled")).isEqualTo("true")
    }

    @Test
    @DisplayName("RP: 검색어 “개요”는 비활성이 아닌 활성 tenant 의 개요 항목을 보인다 / 상태 변화마다 서버가 새로 읽는다")
    fun rpFilterAndFreshRead() {
        whenever(activeTenants.current())
            .thenReturn(ActiveTenantController.ActiveTenantView(T1, listOf(T1), listOf(ActiveTenantController.TenantRef(T1, "Acme"))))
            .thenReturn(ActiveTenantController.ActiveTenantView(T2, listOf(T2), listOf(ActiveTenantController.TenantRef(T2, "Globex"))))
        assertThat(option(palette("개요", rp()), "개요").attr("data-href")).contains(T1.toString())
        assertThat(option(palette("개요", rp()), "개요").attr("data-href")).contains(T2.toString()) // 전환 직후 다시 열면 새 tenant
    }
}
