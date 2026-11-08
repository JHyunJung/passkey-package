package com.crosscert.passkey.admin.web.tenant

import com.crosscert.passkey.admin.audit.AuditChainMonitorController
import com.crosscert.passkey.admin.auth.ActiveTenantController
import com.crosscert.passkey.admin.auth.AdminUserDetails
import com.crosscert.passkey.admin.funnel.FunnelController
import com.crosscert.passkey.admin.funnel.FunnelDto
import com.crosscert.passkey.admin.license.LicenseController
import com.crosscert.passkey.admin.license.LicenseView
import com.crosscert.passkey.admin.policy.AaguidPolicyController
import com.crosscert.passkey.admin.tenant.TenantAdminController
import com.crosscert.passkey.admin.tenant.TenantAdminDto
import com.crosscert.passkey.admin.web.AdminWebSliceTest
import com.crosscert.passkey.core.api.ApiResponse
import com.crosscert.passkey.core.api.BusinessException
import com.crosscert.passkey.core.api.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
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
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.function.IntToLongFunction
import java.util.regex.Pattern

/**
 * 인증 현황 탭(URL 키 funnel). 브라우저 동작(표로 보기 토글)은 마크업까지만 본다.
 *
 * (Kotlin 전환) nullable jsoup 결과(`selectFirst`·`first`·`last`)의 `!!` 는 원 Java 의 NPE 실패 지점과 같다.
 * CSV 줄 나누기는 Java `String.split` 과 같은 결과(끝의 빈 토큰 제거)를 내도록 `Pattern.split` 을 쓴다.
 */
@AdminWebSliceTest(controllers = [FunnelTabController::class])
class FunnelTabControllerTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var clock: Clock
    @MockitoBean lateinit var tenants: TenantAdminController
    @MockitoBean lateinit var funnel: FunnelController
    @MockitoBean lateinit var adoption: com.crosscert.passkey.admin.funnel.AdoptionController
    @MockitoBean lateinit var aaguid: AaguidPolicyController
    @MockitoBean lateinit var license: LicenseController
    @MockitoBean lateinit var chain: AuditChainMonitorController
    @MockitoBean lateinit var activeTenants: ActiveTenantController

    companion object {
        val NOW: Instant = Instant.parse("2026-10-11T00:00:00Z")
        val T1: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val OTHER: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000ff")
        val BASE = "/admin/_/tenants/$T1"

        /** 인증 품질 지표 줄(도입·사용 지표 줄 제외). */
        const val Q = "div.kpis:not([data-adoption]) "

        private val NEWLINE: Pattern = Pattern.compile("\n")

        fun days(n: Int, success: IntToLongFunction): List<FunnelDto.DailyPoint> = days(n, { 10 }, success)

        fun days(n: Int, attempts: IntToLongFunction, success: IntToLongFunction): List<FunnelDto.DailyPoint> {
            val out = ArrayList<FunnelDto.DailyPoint>()
            for (i in 0 until n) out.add(FunnelDto.DailyPoint(String.format("10-%02d", i + 1), attempts.applyAsLong(i), success.applyAsLong(i)))
            return out
        }

        val FAILS: List<FunnelDto.FailureCount> = listOf(
            FunnelDto.FailureCount("SIGNATURE_INVALID", 5), FunnelDto.FailureCount("SIGN_COUNT_REPLAY", 2),
        )

        /** 등록 100→80, 인증 70→63. 일별: 인증은 시도 10, 등록은 시도 4. */
        fun data(w: Int, reg: List<FunnelDto.DailyPoint>, auth: List<FunnelDto.DailyPoint>, fails: List<FunnelDto.FailureCount>): FunnelDto.View =
            FunnelDto.View(w, FunnelDto.Stage(100, 80, 0.8), FunnelDto.Stage(70, 63, 0.9), 0.84, listOf(), listOf(), reg, auth, fails)

        fun data(w: Int, auth: List<FunnelDto.DailyPoint>): FunnelDto.View = data(w, days(w, { 4 }, { 3 }), auth, FAILS)

        fun adoptionData(w: Int): com.crosscert.passkey.admin.funnel.AdoptionDto.View =
            com.crosscert.passkey.admin.funnel.AdoptionDto.View(
                w, 20, 3, 12, 9, 15, 10,
                listOf(
                    com.crosscert.passkey.admin.funnel.AdoptionDto.Month("2026-08", false, 300, 40, 5),
                    com.crosscert.passkey.admin.funnel.AdoptionDto.Month("2026-09", false, 1234, 52, 7),
                    com.crosscert.passkey.admin.funnel.AdoptionDto.Month("2026-10", true, 90, 9, 3),
                ),
                listOf(
                    com.crosscert.passkey.admin.funnel.AdoptionDto.Device("iCloud 키체인", 15, 40),
                    com.crosscert.passkey.admin.funnel.AdoptionDto.Device("<b>x</b>", 5, 2),
                ),
            )

        fun platform(): Authentication = auth("PLATFORM_OPERATOR", setOf())
        fun rp(vararg ids: UUID): Authentication = auth("RP_ADMIN", java.util.Set.of(*ids))

        private fun auth(role: String, tenants: Set<UUID>): Authentication {
            val p = AdminUserDetails(UUID.randomUUID(), "alice@example.com", "x", role, tenants, true, null, Clock.systemUTC())
            return UsernamePasswordAuthenticationToken(p, "x", p.authorities)
        }

        fun tenant(): TenantAdminDto.TenantView =
            TenantAdminDto.TenantView(
                T1, "acme", "Acme", "ACTIVE", "passkey.acme.com", "Acme",
                listOf("https://passkey.acme.com"), setOf("none"), true, false, "NONE", 60000, 3, 1, null,
                OffsetDateTime.parse("2026-03-01T16:00:00Z"), OffsetDateTime.parse("2026-03-01T16:00:00Z"),
            )

        private fun htmx(b: MockHttpServletRequestBuilder, a: Authentication): MockHttpServletRequestBuilder =
            b.with(authentication(a)).with(csrf()).header("HX-Request", "true")

        private fun body(r: MvcResult): String = r.response.getContentAsString(StandardCharsets.UTF_8)
    }

    @BeforeEach
    fun setUp() {
        whenever(adoption.get(eq(T1), anyInt())).thenAnswer { inv -> adoptionData(inv.getArgument(1)) }
        whenever(clock.instant()).thenReturn(NOW)
        whenever(clock.zone).thenReturn(ZoneOffset.UTC)
        whenever(license.get()).thenReturn(ApiResponse.ok(LicenseView("saas", "VALID", "acme", "lic_1", NOW.plusSeconds(86400L * 30), 30, listOf(), null, null, null)))
        whenever(tenants.get(T1.toString())).thenReturn(ApiResponse.ok(tenant()))
        // 30일: 등록은 최근 7일 80% vs 그 앞 7일 50%(+30.0%p), 인증은 90% 그대로(±0.0%p)
        whenever(funnel.get(eq(T1), eq(30))).thenReturn(data(30, days(30, { 10 }, { i -> if (i >= 23) 8 else 5 }), days(30) { 9 }, FAILS))
        whenever(funnel.get(eq(T1), eq(7))).thenReturn(data(7, days(7) { 9 }))
        whenever(funnel.get(eq(T1), eq(1))).thenReturn(data(1, days(1) { 9 }))
    }

    private fun hget(url: String, a: Authentication): MvcResult =
        mvc.perform(htmx(get(URI.create(url)), a)).andReturn()

    private fun tab(q: String): Document {
        val r = hget("$BASE/tab/funnel$q", platform())
        assertThat(r.response.status).isEqualTo(200)
        return Jsoup.parse(body(r))
    }

    // ── 요약 ────────────────────────────────────────────────

    @Test
    @DisplayName("7d 지표: 등록·인증 성공률을 따로(성공/시도, 직전 7일 대비는 각자의 30일 series), 인증 실패 건수와 원인, 기간 — '전환' 표현 없음")
    fun summaryKpis() {
        val r = hget("$BASE/tab/funnel", platform())
        assertThat(body(r)).doesNotContain("<html").doesNotContain("class=\"shell")
        val d = Jsoup.parse(body(r))
        assertThat(d.text()).doesNotContain("전환율").doesNotContain("Funnel").doesNotContain("이탈")
        verify(funnel).get(T1, 7)
        verify(funnel).get(T1, 30)
        assertThat(d.select(Q + ".kpi__label").eachText()).containsExactly("등록 성공률", "인증 성공률", "차단된 위험 인증", "기간")
        assertThat(d.select(Q + ".kpi__value").eachText()).containsExactly("80.0%", "90.0%", "7건", "최근 7일")
        assertThat(d.select(Q + ".kpi__hint").eachText()).containsExactly(
            "성공 80 / 시도 100 · 직전 7일 대비 +30.0%p",
            "성공 63 / 시도 70 · 직전 7일 대비 ±0.0%p",
            "서명 검증 실패 5 · 서명 카운터 재사용 2",
            "2026-10-05 – 2026-10-11",
        )
        assertThat(d.select(Q + ".kpi__hint")[2].hasClass("kpi__hint--warn")).isTrue()
        assertThat(d.select("#funnel-root p.hint").text()).contains("사용자 취소와 시간 초과")
    }

    @Test
    @DisplayName("시도 0·실패 0: 성공률은 '—' + '시도 없음', 실패는 '0건' + '실패 없음'(경고 아님), 실패 원인 카드는 빈 문구만")
    fun emptyKpis() {
        whenever(funnel.get(eq(T1), eq(7))).thenReturn(
            FunnelDto.View(
                7, FunnelDto.Stage(0, 0, 0.0), FunnelDto.Stage(0, 0, 0.0), 0.0,
                listOf(), listOf(), days(7, { 0 }, { 0 }), days(7, { 0 }, { 0 }), listOf(),
            ),
        )
        val d = tab("")
        assertThat(d.select(Q + ".kpi__value").eachText()).containsExactly("—", "—", "0건", "최근 7일")
        assertThat(d.select(Q + ".kpi__hint").eachText()).startsWith("시도 없음", "시도 없음", "실패 없음")
        assertThat(d.select(Q + ".kpi__hint")[2].hasClass("kpi__hint--warn")).isFalse()
        val card = d.selectFirst("section[data-failures]")!!
        assertThat(card.text()).contains("기간 안에 인증 실패가 없습니다.")
        assertThat(card.select("table, a[download]")).isEmpty()
        // 시도 0 인 날의 막대 높이는 0, 성공률은 —
        assertThat(d.select("section[data-series=auth] .fn-day > span").eachAttr("style")).containsOnly("height: 0.0%")
        assertThat(d.select("section[data-series=auth] table tbody tr").first()!!.select("td").eachText()).containsExactly("10-01", "0", "0", "—")
    }

    @Test
    @DisplayName("24h·30d 에서는 직전 기간 대비를 계산·표시하지 않고 30일 비교 조회도 하지 않는다")
    fun noComparisonOutside7d() {
        val d24 = tab("?win=1")
        assertThat(d24.select(Q + ".kpi__hint").eachText()).noneMatch { t -> t.contains("직전 7일 대비") }
        assertThat(d24.select(Q + ".kpi__value")[3].text()).isEqualTo("최근 24시간")
        assertThat(d24.select(Q + ".kpi__hint")[3].text()).isEqualTo("2026-10-10 – 2026-10-11") // 24h: 하루 전부터
        verify(funnel, never()).get(T1, 30)

        val d30 = tab("?win=30")
        assertThat(d30.select(Q + ".kpi__hint").eachText()).noneMatch { t -> t.contains("직전 7일 대비") }
        assertThat(d30.select(Q + ".kpi__value")[3].text()).isEqualTo("최근 30일")
        verify(funnel, times(1)).get(T1, 30) // 본 조회 1회(비교 조회 없음)
        // 알 수 없는 기간은 7일
        assertThat(tab("?win=999").select(Q + ".kpi__value")[3].text()).isEqualTo("최근 7일")
    }

    @Test
    @DisplayName("30일 비교 조회가 실패해도 비교 문구만 생략하고 나머지는 그린다")
    fun comparisonFailureOmitsLineOnly() {
        whenever(funnel.get(eq(T1), eq(30))).thenThrow(IllegalStateException("boom"))
        val d = tab("")
        assertThat(d.select(Q + ".kpi__value").first()!!.text()).isEqualTo("80.0%")
        assertThat(d.select(Q + ".kpi__hint").first()!!.text()).isEqualTo("성공 80 / 시도 100")
        assertThat(d.select(Q + ".kpi__hint")[1].text()).isEqualTo("성공 63 / 시도 70")
    }

    @Test
    @DisplayName("기간 세그먼트: 24h·7d·30d, 선택 상태, 주소는 #funnel-root(this) 교체 — #tenant-tab 아님")
    fun windowSegment() {
        val d = tab("?win=30")
        val seg = d.selectFirst("#funnel-root .seg")!!
        assertThat(seg.attr("aria-label")).isEqualTo("기간")
        assertThat(seg.select("button").eachText()).containsExactly("24h", "7d", "30d")
        assertThat(seg.select("button").eachAttr("hx-get")).containsExactly("$BASE/tab/funnel?win=1", "$BASE/tab/funnel?win=7", "$BASE/tab/funnel?win=30")
        assertThat(seg.select("button")[2].attr("aria-pressed")).isEqualTo("true")
        val root = d.selectFirst("#funnel-root")!!
        assertThat(root.attr("hx-target")).isEqualTo("this")
        assertThat(root.attr("hx-swap")).isEqualTo("outerHTML")
        assertThat(d.select("[hx-target=#tenant-tab]")).isEmpty()
        assertThat(d.select("[hx-trigger]").eachAttr("hx-trigger")).noneMatch { t -> t.contains("every") }
    }

    // ── 차트·표 ─────────────────────────────────────────────

    @Test
    @DisplayName("일별 카드 2장(인증·등록 따로): 날짜별 막대 두 개(시도·성공 높이 숫자), 축 라벨 3개, 표로 보기(헤더+7행), CSV 링크")
    fun seriesCards() {
        val d = tab("")
        assertThat(d.select("section[data-series] .card__title").eachText()).containsExactly("일별 인증 시도와 성공", "일별 등록 시도와 성공")

        val auth = d.selectFirst("section[data-series=auth]")!!
        assertThat(auth.attr("x-data")).isEqualTo("{ asTable: false }")
        assertThat(auth.select(".fn-day")).hasSize(7)
        // 날짜 말풍선(마우스 오버): 날짜 + 시도·성공·성공률, 보조기기에는 숨김(표로 보기가 같은 데이터)
        val tip = auth.select(".fn-day").first()!!.selectFirst(".fn-tip")!!
        assertThat(tip.attr("aria-hidden")).isEqualTo("true")
        assertThat(tip.selectFirst("strong")!!.text()).isEqualTo("10월 1일")
        assertThat(tip.selectFirst("span")!!.text()).isEqualTo("시도 10 · 성공 9 · 90.0%")
        assertThat(auth.select(".fn-day").last()!!.selectFirst(".fn-tip strong")!!.text()).isEqualTo("10월 7일")
        assertThat(auth.select(".fn-day").first()!!.select("> span").eachAttr("style")).containsExactly("height: 100.0%", "height: 90.0%")
        assertThat(auth.selectFirst("[role=img]")!!.attr("aria-label")).isEqualTo("일별 인증 시도와 성공 막대 차트, 7일")
        assertThat(auth.select("div.row.hint > span").eachText()).containsExactly("10-01", "10-04", "오늘")
        assertThat(auth.select("table th").eachText()).containsExactly("날짜", "시도", "성공", "성공률")
        assertThat(auth.select("table tbody tr")).hasSize(7)
        assertThat(auth.select("table tbody tr").first()!!.select("td").eachText()).containsExactly("10-01", "10", "9", "90.0%")
        val toggle = auth.select("button").first { b -> b.text() == "표로 보기" }
        assertThat(toggle.attr("x-bind:aria-pressed")).isEqualTo("asTable")
        assertThat(auth.select("a[download]").attr("href")).isEqualTo("/admin/tenants/$T1/funnel.csv?win=7&kind=auth")

        // 등록 카드는 등록 값만(인증과 섞이지 않는다)
        val reg = d.selectFirst("section[data-series=reg]")!!
        assertThat(reg.select(".fn-day").first()!!.select(".fn-tip span").text()).isEqualTo("시도 4 · 성공 3 · 75.0%")
        assertThat(reg.select(".fn-day").first()!!.select("> span").eachAttr("style")).containsExactly("height: 100.0%", "height: 75.0%")
        assertThat(reg.select("table tbody tr").first()!!.select("td").eachText()).containsExactly("10-01", "4", "3", "75.0%")
        assertThat(reg.select("a[download]").attr("href")).isEqualTo("/admin/tenants/$T1/funnel.csv?win=7&kind=reg")

        // 색: 시도는 회색(fn-att), 성공은 카드별(인증 fn-ok-auth, 등록 fn-ok-reg) — 범례도 같은 색
        assertThat(auth.select(".fn-day").first()!!.select("> span").eachAttr("class")).containsExactly("fn-att", "fn-ok-auth")
        assertThat(reg.select(".fn-day").first()!!.select("> span").eachAttr("class")).containsExactly("fn-att", "fn-ok-reg")
        assertThat(auth.select(".fn-legend-dot").eachAttr("class")).containsExactly("fn-legend-dot fn-att", "fn-legend-dot fn-ok-auth")
        assertThat(reg.select(".fn-legend-dot").eachAttr("class")).containsExactly("fn-legend-dot fn-att", "fn-legend-dot fn-ok-reg")
        // style 속성에는 숫자 + % 만
        for (e in d.select(".fn-day > span")) assertThat(e.attr("style")).matches("height: [0-9.]+%")
    }

    @Test
    @DisplayName("인증 실패 원인: 한글 이름·의미·건수, 원래 코드는 title, 모르는 코드는 그대로, 원인 없음은 '원인 미기록', CSV 링크")
    fun failureCard() {
        whenever(funnel.get(eq(T1), eq(7))).thenReturn(
            data(
                7, days(7, { 4 }, { 3 }), days(7) { 9 },
                listOf(FunnelDto.FailureCount("SIGNATURE_INVALID", 5), FunnelDto.FailureCount("NEW_REASON", 2), FunnelDto.FailureCount(null, 1)),
            ),
        )
        val card = tab("").selectFirst("section[data-failures]")!!
        assertThat(card.select(".card__title").text()).isEqualTo("인증 실패 원인")
        assertThat(card.select("table th").eachText()).containsExactly("원인", "의미", "건수")
        assertThat(card.select("tbody tr strong").eachText()).containsExactly("서명 검증 실패", "NEW_REASON", "원인 미기록")
        assertThat(card.select("tbody tr strong").eachAttr("title")).containsExactly("SIGNATURE_INVALID", "NEW_REASON") // 원인 없음은 title 없음
        assertThat(card.select("tbody tr").first()!!.select("td")[1].text()).contains("위조 시도")
        assertThat(card.select("tbody tr td.num").eachText()).containsExactly("5", "2", "1")
        assertThat(card.select("a[download]").attr("href")).isEqualTo("/admin/tenants/$T1/funnel.csv?win=7&kind=failures")
    }

    @Test
    @DisplayName("실패 원인 코드는 이스케이프된다(th:text/th:title) — 속성 중 JS 로 평가되는 곳에는 없다")
    fun escapesFailureReason() {
        whenever(funnel.get(eq(T1), eq(7))).thenReturn(
            data(7, days(7) { 1 }, days(7) { 1 }, listOf(FunnelDto.FailureCount("<img src=x onerror=alert(1)>", 3))),
        )
        val html = body(hget("$BASE/tab/funnel", platform()))
        assertThat(html).doesNotContain("<img src=x")
        val d = Jsoup.parse(html)
        assertThat(d.select("img[src=x]")).isEmpty()
        assertThat(d.select("section[data-failures] tbody strong").text()).isEqualTo("<img src=x onerror=alert(1)>")
    }

    // ── 패스키 도입·사용 ─────────────────────────────────────

    @Test
    @DisplayName("도입·사용 지표: 활성 사용자·신규·누적 패스키·패스키 1개뿐인 사용자 비율(50% 이상이면 경고), 기간은 탭과 같다")
    fun adoptionKpis() {
        val d = tab("?win=30")
        verify(adoption).get(T1, 30)
        val row = d.selectFirst("div.kpis[data-adoption]")!!
        assertThat(row.select(".kpi__label").eachText()).containsExactly("활성 사용자", "신규 패스키", "누적 패스키", "패스키 1개뿐인 사용자")
        assertThat(row.select(".kpi__value").eachText()).containsExactly("9명", "3개", "20개", "66.7%")
        assertThat(row.select(".kpi__hint").eachText()).containsExactly(
            "기간 중 인증 성공 · 사용된 패스키 12개", "기간 중 새로 등록", "사용자 15명",
            "10명 / 15명 · 기기를 잃으면 로그인할 수 없어요",
        )
        assertThat(row.select(".kpi__hint")[3].hasClass("kpi__hint--warn")).isTrue()
        assertThat(d.select("h2.fn-section").eachText()).containsExactly("인증 품질", "패스키 도입·사용")
    }

    @Test
    @DisplayName("사용자 0명: 1개뿐 비율은 '—' + '사용자 없음'(경고 아님), 기기 분포는 빈 문구만")
    fun adoptionEmpty() {
        whenever(adoption.get(eq(T1), anyInt())).thenReturn(com.crosscert.passkey.admin.funnel.AdoptionDto.View(7, 0, 0, 0, 0, 0, 0, listOf(), listOf()))
        val d = tab("")
        val row = d.selectFirst("div.kpis[data-adoption]")!!
        assertThat(row.select(".kpi__value").eachText()).containsExactly("0명", "0개", "0개", "—")
        assertThat(row.select(".kpi__hint")[3].text()).isEqualTo("사용자 없음")
        assertThat(row.select(".kpi__hint")[3].hasClass("kpi__hint--warn")).isFalse()
        val dev = d.selectFirst("section[data-devices]")!!
        assertThat(dev.text()).contains("등록된 패스키가 없습니다.")
        assertThat(dev.select(".dv-row, a[download]")).isEmpty()
    }

    @Test
    @DisplayName("월간 사용량: 최근 3개월(이번 달은 진행 중), 인증 성공·활성 사용자·신규 패스키, CSV 링크")
    fun monthlyCard() {
        val card = tab("").selectFirst("section[data-monthly]")!!
        assertThat(card.select(".card__title").text()).isEqualTo("월간 사용량")
        assertThat(card.select("table th").eachText()).containsExactly("월", "인증 성공", "활성 사용자", "신규 패스키")
        assertThat(card.select("tbody tr")[1].select("td").eachText()).containsExactly("2026년 9월", "1,234", "52명", "7")
        assertThat(card.select("tbody tr")[2].select("td").first()!!.text()).isEqualTo("2026년 10월 (진행 중)")
        assertThat(card.select("a[download]").attr("href")).isEqualTo("/admin/tenants/$T1/funnel.csv?win=7&kind=monthly")
    }

    @Test
    @DisplayName("기기 분포: 인증기 이름(이스케이프)·비율 막대(숫자만 style)·비율·개수·기간 인증 건수, CSV 링크")
    fun devicesCard() {
        val html = body(hget("$BASE/tab/funnel", platform()))
        assertThat(html).doesNotContain("<b>x</b>")
        val card = Jsoup.parse(html).selectFirst("section[data-devices]")!!
        assertThat(card.select(".dv-row__name").eachText()).containsExactly("iCloud 키체인", "<b>x</b>")
        assertThat(card.select(".dv-row__bar").eachAttr("style")).containsExactly("width: 75.0%", "width: 25.0%")
        assertThat(card.select(".dv-row__num").eachText()).containsExactly("75.0% 15개", "25.0% 5개")
        assertThat(card.select(".dv-row__sub").eachText()).containsExactly("기간 인증 40건", "기간 인증 2건")
        assertThat(card.select("a[download]").attr("href")).isEqualTo("/admin/tenants/$T1/funnel.csv?win=7&kind=devices")
    }

    @Test
    @DisplayName("도입 지표 조회가 실패해도 인증 품질 영역은 그대로, 도입 영역만 오류(내부 메시지 비노출)")
    fun adoptionFailureIsolated() {
        whenever(adoption.get(eq(T1), anyInt())).thenThrow(IllegalStateException("jdbc:secret"))
        val r = hget("$BASE/tab/funnel", platform())
        assertThat(r.response.status).isEqualTo(200)
        assertThat(body(r)).doesNotContain("secret")
        val d = Jsoup.parse(body(r))
        assertThat(d.select(Q + ".kpi__value").first()!!.text()).isEqualTo("80.0%")
        assertThat(d.select("[role=alert]").text()).contains("패스키 도입 지표를 불러오지 못했습니다")
        assertThat(d.select("div.kpis[data-adoption], section[data-monthly], section[data-devices]")).isEmpty()
    }

    @Test
    @DisplayName("CSV: 월간 사용량·기기 분포 — 퍼널 조회 없이 도입 지표만, 수식 문자 무력화")
    fun adoptionCsv() {
        val disp = arrayOfNulls<String>(1)
        val monthly = csv("?win=7&kind=monthly", disp)
        assertThat(disp[0]).isEqualTo("attachment; filename=\"monthly-usage-acme-2026-10-11.csv\"")
        assertThat(NEWLINE.split(monthly)).containsExactly(
            "month,inProgress,authSuccess,activeUsers,newPasskeys",
            "2026-08,N,300,40,5", "2026-09,N,1234,52,7", "2026-10,Y,90,9,3",
        )
        whenever(adoption.get(eq(T1), eq(30))).thenReturn(
            com.crosscert.passkey.admin.funnel.AdoptionDto.View(
                30, 1, 0, 0, 0, 1, 1, listOf(),
                listOf(com.crosscert.passkey.admin.funnel.AdoptionDto.Device("=cmd", 1, 0)),
            ),
        )
        val devices = csv("?win=30&kind=devices", disp)
        assertThat(disp[0]).isEqualTo("attachment; filename=\"devices-acme-30d-2026-10-11.csv\"")
        assertThat(NEWLINE.split(devices)).containsExactly("authenticator,passkeys,authSuccessInWindow", "'=cmd,1,0")
        verify(funnel, never()).get(anyOrNull(), anyInt())
    }

    // ── 오류 ────────────────────────────────────────────────

    @Test
    @DisplayName("조회 실패: 지표·차트 없이 LoadError(다시 시도 = #funnel-root 재요청), 내보내기 비활성, 내부 메시지 비노출, 7d 비교 조회 없음")
    fun loadFailure() {
        whenever(funnel.get(eq(T1), eq(7))).thenThrow(IllegalStateException("jdbc:secret"))
        val r = hget("$BASE/tab/funnel", platform())
        assertThat(r.response.status).isEqualTo(200)
        assertThat(body(r)).doesNotContain("secret")
        val d = Jsoup.parse(body(r))
        assertThat(d.select("[role=alert]").text()).contains("인증 현황을 불러오지 못했습니다").contains("오류 정보 복사")
        assertThat(d.select(".kpi, section.card")).isEmpty()
        assertThat(d.select("#funnel-root > .row button[disabled]").text()).isEqualTo("내보내기")
        assertThat(d.select("#funnel-root > .row a[download]")).isEmpty()
        val retry = d.select("[role=alert] button").first { b -> b.text() == "다시 시도" }
        assertThat(retry.attr("hx-get")).isEqualTo("$BASE/tab/funnel?win=7")
        verify(funnel, never()).get(T1, 30)
    }

    @Test
    @DisplayName("기간 전환 중: 루트가 pending 을 들고 이전 결과를 숨기고 스켈레톤을, 내보내기·CSV 링크는 비활성(요청 실패 시 after-request 로 복구 + 선택을 보이는 기간 data-win 으로)")
    fun pendingStateHidesResults() {
        val root = tab("").selectFirst("#funnel-root")!!
        // pending·실패 시 되돌리기는 Alpine funnelTab(app.js) — 화면에 보이는 기간은 data-win(서버 값)
        assertThat(root.attr("x-data")).isEqualTo("funnelTab")
        assertThat(root.attr("x-on:htmx:before-request")).isEqualTo("begin()")
        assertThat(root.attr("x-on:htmx:after-request")).isEqualTo("end(\$event)")
        assertThat(root.attr("data-win")).isEqualTo("7")
        assertThat(tab("?win=30").selectFirst("#funnel-root")!!.attr("data-win")).isEqualTo("30")
        assertThat(root.select("[x-show=!pending]")).isNotEmpty()
        assertThat(root.select("[role=status][aria-busy=true]")).hasSize(1)
        for (a in root.select("a[download]")) assertThat(a.attr("x-on:click")).isEqualTo("if (pending) \$event.preventDefault()")
    }

    @Test
    @DisplayName("CSV 링크 6개(내보내기·일별 인증·일별 등록·실패 원인·월간 사용량·기기 분포) 모두 공통 다운로드 표식")
    fun csvLinksCarryMarker() {
        val d = tab("")
        assertThat(d.select("a[download]")).hasSize(6)
        assertThat(d.select("a[download]").all { a -> a.hasAttr("data-csv-download") }).isTrue()
    }

    // ── CSV ─────────────────────────────────────────────────

    private fun csv(q: String, disposition: Array<String?>): String {
        val r = mvc.perform(htmx(get(URI.create("/admin/tenants/$T1/funnel.csv$q")), platform())).andReturn()
        assertThat(r.response.status).isEqualTo(200)
        disposition[0] = r.response.getHeader("Content-Disposition")
        val raw = r.response.contentAsByteArray
        assertThat(raw[0].toInt() and 0xff).isEqualTo(0xEF)
        return String(raw, StandardCharsets.UTF_8).substring(1)
    }

    @Test
    @DisplayName("CSV 4종: 요약(내보내기) · 일별 인증 · 일별 등록 · 실패 원인 — 코드와 한글 이름을 함께, 성공률 toFixed(4), 파일명")
    fun csvExports() {
        val disp = arrayOfNulls<String>(1)
        val summary = csv("?win=7&kind=summary", disp)
        assertThat(disp[0]).isEqualTo("attachment; filename=\"auth-status-acme-7d-2026-10-11.csv\"")
        assertThat(NEWLINE.split(summary)).containsExactly(
            "ceremony,label,attempts,success,successRate",
            "registration,등록,100,80,0.8000", "authentication,인증,70,63,0.9000",
        )
        val auth = csv("?win=7&kind=auth", disp)
        assertThat(disp[0]).isEqualTo("attachment; filename=\"auth-daily-acme-7d-2026-10-11.csv\"")
        assertThat(NEWLINE.split(auth)).hasSize(8)
        assertThat(NEWLINE.split(auth)[0]).isEqualTo("day,attempts,success,successRate")
        assertThat(NEWLINE.split(auth)[1]).isEqualTo("10-01,10,9,0.9000")
        val reg = csv("?win=7&kind=reg", disp)
        assertThat(disp[0]).isEqualTo("attachment; filename=\"registration-daily-acme-7d-2026-10-11.csv\"")
        assertThat(NEWLINE.split(reg)[1]).isEqualTo("10-01,4,3,0.7500")
        val fails = csv("?win=30&kind=failures", disp)
        assertThat(disp[0]).isEqualTo("attachment; filename=\"auth-failures-acme-30d-2026-10-11.csv\"")
        assertThat(NEWLINE.split(fails)).containsExactly("reason,label,n", "SIGNATURE_INVALID,서명 검증 실패,5", "SIGN_COUNT_REPLAY,서명 카운터 재사용,2")
        // 기본 kind 는 요약, 탭의 내보내기 링크가 같은 주소
        assertThat(csv("?win=7", disp)).startsWith("ceremony,label")
        val d = tab("?win=30")
        assertThat(d.select("#funnel-root > .row a[download]").attr("href")).isEqualTo("/admin/tenants/$T1/funnel.csv?win=30&kind=summary")
    }

    @Test
    @DisplayName("CSV: 실패 원인 코드의 수식 문자는 무력화(' 접두)")
    fun csvNeutralizesFormula() {
        whenever(funnel.get(eq(T1), eq(7))).thenReturn(data(7, days(1) { 1 }, days(1) { 1 }, listOf(FunnelDto.FailureCount("=HYPERLINK(\"x\")", 3))))
        val disp = arrayOfNulls<String>(1)
        assertThat(csv("?win=7&kind=failures", disp)).contains("\"'=HYPERLINK(\"\"x\"\")\",")
    }

    // ── 경계 ────────────────────────────────────────────────

    @Test
    @DisplayName("RP_ADMIN 이 다른 tenant 의 탭·CSV → 403, 퍼널 REST 호출 없음 (TenantTabs.load 먼저)")
    fun crossTenantForbidden() {
        whenever(tenants.get(OTHER.toString())).thenThrow(BusinessException(ErrorCode.ACCESS_DENIED, "RP_ADMIN cannot access tenant $OTHER"))
        assertThat(hget("/admin/_/tenants/$OTHER/tab/funnel", rp(T1)).response.status).isEqualTo(403)
        assertThat(hget("/admin/tenants/$OTHER/funnel.csv", rp(T1)).response.status).isEqualTo(403)
        verify(funnel, never()).get(anyOrNull(), anyInt())
    }

    @Test
    @DisplayName("리터럴 탭 매핑이 일반 {tab} 매핑(개요)보다 우선하고, 자기 tenant 의 RP_ADMIN 은 볼 수 있다")
    fun literalMappingWinsAndRpOwnTenant() {
        val r = hget("$BASE/tab/funnel", rp(T1))
        assertThat(r.response.status).isEqualTo(200)
        assertThat(Jsoup.parse(body(r)).select("#funnel-root")).hasSize(1)
    }
}
