package com.crosscert.passkey.admin.web.insights

import com.crosscert.passkey.admin.audit.AuditChainMonitorController
import com.crosscert.passkey.admin.auth.ActiveTenantController
import com.crosscert.passkey.admin.auth.AdminUserDetails
import com.crosscert.passkey.admin.funnel.AdoptionDto
import com.crosscert.passkey.admin.funnel.FunnelDto
import com.crosscert.passkey.admin.insights.PlatformInsightsController
import com.crosscert.passkey.admin.insights.PlatformInsightsDto
import com.crosscert.passkey.admin.license.LicenseController
import com.crosscert.passkey.admin.license.LicenseView
import com.crosscert.passkey.admin.web.AdminWebSliceTest
import com.crosscert.passkey.core.api.ApiResponse
import org.assertj.core.api.Assertions.assertThat
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import java.util.regex.Pattern

/**
 * 플랫폼 현황 화면. 집계 정의는 PlatformInsightsService, 여기서는 권한·표시·정렬·CSV 를 본다.
 *
 * (Kotlin 전환) 즉시 역참조하는 nullable jsoup 결과(`selectFirst`·`first`)의 `!!` 는 원 Java 의 NPE 실패 지점과 같다.
 */
@AdminWebSliceTest(controllers = [InsightsPageController::class])
class InsightsPageControllerTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var clock: Clock
    @MockitoBean lateinit var insights: PlatformInsightsController
    @MockitoBean lateinit var license: LicenseController
    @MockitoBean lateinit var chain: AuditChainMonitorController
    @MockitoBean lateinit var activeTenants: ActiveTenantController

    companion object {
        val NOW: Instant = Instant.parse("2026-10-11T03:00:00Z")
        val RP1: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
        val A: UUID = UUID.fromString("00000000-0000-0000-0000-00000000000a")
        val B: UUID = UUID.fromString("00000000-0000-0000-0000-00000000000b")

        /** (Kotlin 전환) Java `String.split("\n")`(끝의 빈 토큰 제거)과 같은 의미. */
        private val NEWLINE: Pattern = Pattern.compile("\n")

        private fun splitLines(s: String): Array<String> = NEWLINE.split(s)

        fun row(id: UUID, name: String, status: String, users: Long, authA: Long, authS: Long, risk: Long, creds: Long, u: Long, single: Long): PlatformInsightsDto.TenantRow =
            PlatformInsightsDto.TenantRow(id, name, name.lowercase(Locale.getDefault()), status, users, authA, authS, 10, 8, risk, creds, u, single, 0, 0, 0, 0, risk)

        fun view(w: Int): PlatformInsightsDto.View {
            val series = ArrayList<FunnelDto.DailyPoint>()
            for (i in 0 until w) series.add(FunnelDto.DailyPoint(String.format("10-%02d", i + 1), 10, 9))
            return PlatformInsightsDto.View(
                w,
                PlatformInsightsDto.Totals(2, 1, 30, 200, 180, 20, 16, 3, 40, 32, 20),
                listOf(row(A, "Acme", "active", 20, 100, 99, 0, 25, 20, 5), row(B, "Beta<b>", "suspended", 10, 100, 81, 3, 15, 12, 15)),
                listOf(
                    PlatformInsightsDto.Alert(B, "Beta<b>", "beta", "RISK", "최근 7일 차단된 위험 인증 3건"),
                    PlatformInsightsDto.Alert(B, "Beta<b>", "beta", "RATE_DROP", "인증 성공률 95.0% → 81.0% (14.0%p 하락, 최근 7일)"),
                ),
                series,
                listOf(AdoptionDto.Month("2026-09", false, 500, 30, 4), AdoptionDto.Month("2026-10", true, 180, 28, 2)),
                listOf(AdoptionDto.Device("iCloud 키체인", 30, 100), AdoptionDto.Device("YubiKey 5", 10, 20)),
                PlatformInsightsDto.Thresholds(5.0, 30, 1, 0.5, 10),
            )
        }

        fun platform(): Authentication = auth("PLATFORM_OPERATOR", setOf())
        fun rp(): Authentication = auth("RP_ADMIN", setOf(RP1))

        private fun auth(role: String, tenants: Set<UUID>): Authentication {
            val p = AdminUserDetails(UUID.randomUUID(), "alice@example.com", "x", role, tenants, true, null, Clock.systemUTC())
            return UsernamePasswordAuthenticationToken(p, "x", p.authorities)
        }

        private fun body(r: MvcResult): String = r.response.getContentAsString(StandardCharsets.UTF_8)
    }

    @BeforeEach
    fun setUp() {
        whenever(clock.instant()).thenReturn(NOW)
        whenever(clock.zone).thenReturn(ZoneOffset.UTC)
        whenever(license.get()).thenReturn(ApiResponse.ok(LicenseView("saas", "VALID", "acme", "lic_1", NOW.plusSeconds(86400L * 30), 30, listOf(), null, null, null)))
        whenever(insights.get(anyInt())).thenAnswer { inv -> view(inv.getArgument(0)) }
    }

    private fun page(url: String): Document {
        val r = mvc.perform(get(URI.create(url)).with(authentication(platform()))).andReturn()
        assertThat(r.response.status).`as`(url).isEqualTo(200)
        return Jsoup.parse(body(r))
    }

    private fun htmx(url: String, a: Authentication): MvcResult =
        mvc.perform(get(URI.create(url)).with(authentication(a)).header("HX-Request", "true")).andReturn()

    @Test
    @DisplayName("RP_ADMIN: 전체 페이지는 자기 tenant 로, 조각·CSV 는 403, REST 미호출")
    fun platformOnly() {
        val r = mvc.perform(get("/admin/insights").with(authentication(rp()))).andReturn()
        assertThat(r.response.status).isEqualTo(302)
        assertThat(r.response.redirectedUrl).isEqualTo("/admin/tenants/$RP1")
        assertThat(htmx("/admin/_/insights?win=7", rp()).response.status).isEqualTo(403)
        assertThat(htmx("/admin/insights.csv?kind=tenants", rp()).response.status).isEqualTo(403)
        verify(insights, never()).get(anyInt())
    }

    @Test
    @DisplayName("전체 페이지: 사이드바 '현황' 활성, 제목·경로, 지표 6개(위험 인증 있으면 경고)")
    fun pageAndKpis() {
        val d = page("/admin/insights")
        verify(insights).get(7)
        assertThat(d.select(".nav-item--active").text()).isEqualTo("현황")
        assertThat(d.select(".page__head").text()).contains("현황")
        assertThat(d.select("#insights-root .kpi__label").eachText()).containsExactly("활성 테넌트", "활성 사용자", "인증 성공", "인증 성공률", "차단된 위험 인증", "누적 패스키")
        assertThat(d.select("#insights-root .kpi__value").eachText()).containsExactly("1개", "30명", "180건", "90.0%", "3건", "40개")
        assertThat(d.select("#insights-root .kpi__hint").eachText()).containsExactly(
            "전체 2개", "최근 7일 인증 성공 사용자", "시도 200건",
            "등록 성공률 80.0%", "최근 7일 검증 단계에서 거절", "사용자 32명 · 1개뿐 62.5%",
        )
        assertThat(d.select("#insights-root .kpi__hint")[4].hasClass("kpi__hint--warn")).isTrue()
    }

    @Test
    @DisplayName("확인 필요: 종류 배지·테넌트 링크(인증 현황 탭)·사유, 기준 문구, 이름은 이스케이프")
    fun alerts() {
        val html = body(htmx("/admin/_/insights?win=7", platform()))
        assertThat(html).doesNotContain("Beta<b>")
        val card = Jsoup.parse(html).selectFirst("section[data-alerts]")!!
        assertThat(card.select("li .badge").eachText()).containsExactly("위험 인증", "성공률 하락")
        assertThat(card.select("li .badge").eachAttr("class")).containsExactly("badge badge--danger", "badge badge--warning")
        assertThat(card.select("li a").eachAttr("href")).containsOnly("/admin/tenants/$B?tab=funnel")
        assertThat(card.select("li a").first()!!.text()).isEqualTo("Beta<b>")
        assertThat(card.select(".card__sub").text()).isEqualTo(
            "기준(최근 7일): 위험 인증 1건 이상 · 인증 성공률 5%p 이상 하락(시도 30건 이상) · 패스키 1개뿐 50% 이상(사용자 10명 이상)",
        )
    }

    @Test
    @DisplayName("확인 필요 없음: 빈 문구")
    fun noAlerts() {
        val v = view(7)
        whenever(insights.get(7)).thenReturn(PlatformInsightsDto.View(7, v.totals, v.tenants, listOf(), v.authSeries, v.months, v.devices, v.thresholds))
        assertThat(page("/admin/insights").selectFirst("section[data-alerts]")!!.text()).contains("확인이 필요한 테넌트가 없어요.")
    }

    @Test
    @DisplayName("테넌트별 비교: 기본은 활성 사용자 순, 머리글 버튼으로 정렬(#insights-root 교체), 위험 값 강조, 상태 배지")
    fun tenantTableAndSort() {
        val d = page("/admin/insights")
        val t = d.selectFirst("section[data-tenants]")!!
        assertThat(t.select("thead th").eachText()).containsExactly("테넌트", "상태", "활성 사용자", "인증 성공", "인증 성공률", "등록 성공률", "위험 인증", "패스키", "1개뿐 비율")
        assertThat(t.select("tbody tr td:first-child a").eachText()).containsExactly("Acme", "Beta<b>")
        assertThat(t.select("tbody tr")[0].select("td").eachText()).containsExactly("Acme acme", "활성", "20", "99", "99.0%", "80.0%", "0", "25", "25.0%")
        assertThat(t.select("tbody tr")[1].select("td")[1].text()).isEqualTo("정지")
        assertThat(t.select("tbody tr")[1].select("td.ins-warn").text()).isEqualTo("3")
        val riskBtn = t.select("button.ins-sort").stream().filter { b -> b.text() == "위험 인증" }.findFirst().orElseThrow()
        assertThat(riskBtn.attr("hx-get")).isEqualTo("/admin/_/insights?win=7&sort=risk")
        assertThat(t.select("th[aria-sort=descending]").text()).isEqualTo("활성 사용자")
        val root = d.selectFirst("#insights-root")!!
        assertThat(root.attr("x-data")).isEqualTo("funnelTab")
        // 회귀: 루트와 그 조상에 hx-target 이 없어야 안의 테넌트 링크(boost)가 일반 페이지 이동을 한다(페이지 안에 페이지 X)
        assertThat(root.hasAttr("hx-target")).isFalse()
        var p = root.parent()
        while (p != null) {
            assertThat(p.hasAttr("hx-target")).`as`(p.tagName()).isFalse()
            p = p.parent()
        }
        for (a in root.select("a[href^=/admin/tenants/]")) {
            assertThat(a.hasAttr("hx-target")).isFalse()
            assertThat(a.attr("hx-boost")).isNotEqualTo("false")
        }
        // 루트를 교체하는 요청은 대상을 직접 가진다
        assertThat(riskBtn.attr("hx-target")).isEqualTo("#insights-root")
        assertThat(riskBtn.attr("hx-swap")).isEqualTo("outerHTML")
        assertThat(riskBtn.attr("hx-sync")).isEqualTo("#insights-root:replace")
        for (b in d.select(".seg button")) assertThat(b.attr("hx-target")).isEqualTo("#insights-root")

        val byRisk = Jsoup.parse(body(htmx("/admin/_/insights?win=7&sort=risk", platform())))
        assertThat(byRisk.select("section[data-tenants] tbody tr td:first-child a").eachText()).containsExactly("Beta<b>", "Acme")
        assertThat(byRisk.select("th[aria-sort=descending]").text()).isEqualTo("위험 인증")
        // 알 수 없는 정렬은 기본값
        assertThat(Jsoup.parse(body(htmx("/admin/_/insights?sort=evil", platform()))).select("th[aria-sort=descending]").text()).isEqualTo("활성 사용자")
    }

    @Test
    @DisplayName("기간 세그먼트: 정렬을 유지한 채 #insights-root 교체, 카드(일별·월간·기기)는 테넌트 탭과 같은 조각")
    fun periodAndCards() {
        val d = Jsoup.parse(body(htmx("/admin/_/insights?win=30&sort=creds", platform())))
        verify(insights).get(30)
        assertThat(d.select(".seg button").eachAttr("hx-get")).containsExactly(
            "/admin/_/insights?win=1&sort=creds", "/admin/_/insights?win=7&sort=creds", "/admin/_/insights?win=30&sort=creds",
        )
        assertThat(d.select("section[data-series=auth] .card__title").text()).isEqualTo("플랫폼 일별 인증 시도와 성공")
        assertThat(d.select("section[data-series=auth] .fn-day")).hasSize(30)
        assertThat(d.select("section[data-monthly] tbody tr")[1].select("td").eachText()).containsExactly("2026년 10월 (진행 중)", "180", "28명", "2")
        assertThat(d.select("section[data-devices] .dv-row__name").eachText()).containsExactly("iCloud 키체인", "YubiKey 5")
        assertThat(d.select("a[download]").eachAttr("href")).containsExactly(
            "/admin/insights.csv?win=30&kind=tenants", "/admin/insights.csv?win=30&kind=daily",
            "/admin/insights.csv?win=30&kind=monthly", "/admin/insights.csv?win=30&kind=devices",
        )
    }

    @Test
    @DisplayName("조회 실패: 지표 없이 LoadError(다시 시도 = 같은 기간·정렬), 내부 메시지 비노출")
    fun loadFailure() {
        whenever(insights.get(anyInt())).thenThrow(IllegalStateException("jdbc:secret"))
        val r = htmx("/admin/_/insights?win=7&sort=risk", platform())
        assertThat(r.response.status).isEqualTo(200)
        assertThat(body(r)).doesNotContain("secret")
        val d = Jsoup.parse(body(r))
        assertThat(d.select("[role=alert]").text()).contains("플랫폼 현황을 불러오지 못했습니다")
        assertThat(d.select(".kpi, section.card, a[download]")).isEmpty()
        val retry = d.select("[role=alert] button").stream().filter { b -> b.text() == "다시 시도" }.findFirst().orElseThrow()
        assertThat(retry.attr("hx-get")).isEqualTo("/admin/_/insights?win=7&sort=risk")
        assertThat(retry.attr("hx-target")).isEqualTo("#insights-root")
    }

    @Test
    @DisplayName("CSV: 테넌트별(과금·라이선스 자료) 헤더·값·비율 toFixed(4), 파일명, 수식 무력화")
    fun csv() {
        val r = htmx("/admin/insights.csv?win=30&kind=tenants", platform())
        assertThat(r.response.status).isEqualTo(200)
        assertThat(r.response.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"platform-tenants-30d-2026-10-11.csv\"")
        val csv = String(r.response.contentAsByteArray, StandardCharsets.UTF_8).substring(1)
        assertThat(splitLines(csv)[0]).isEqualTo("slug,name,status,activeUsers,authAttempts,authSuccess,authSuccessRate,regAttempts,regSuccess,regSuccessRate,riskBlocked,passkeys,users,singlePasskeyUsers")
        assertThat(splitLines(csv)[1]).isEqualTo("acme,Acme,active,20,100,99,0.9900,10,8,0.8000,0,25,20,5")
        val monthly = String(htmx("/admin/insights.csv?kind=monthly", platform()).response.contentAsByteArray, StandardCharsets.UTF_8).substring(1)
        assertThat(splitLines(monthly)).containsExactly("month,inProgress,authSuccess,activeUsers,newPasskeys", "2026-09,N,500,30,4", "2026-10,Y,180,28,2")
        assertThat(htmx("/admin/insights.csv?kind=evil", platform()).response.status).isEqualTo(404)
    }
}
