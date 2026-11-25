package com.crosscert.passkey.admin.web.insights

import com.crosscert.passkey.admin.audit.AuditChainMonitorController
import com.crosscert.passkey.admin.auth.ActiveTenantController
import com.crosscert.passkey.admin.auth.AdminUserDetails
import com.crosscert.passkey.admin.license.LicenseController
import com.crosscert.passkey.admin.license.LicenseView
import com.crosscert.passkey.admin.usage.UsageDto
import com.crosscert.passkey.admin.usage.UsageSettlementController
import com.crosscert.passkey.admin.usage.UsageSettlementService
import com.crosscert.passkey.admin.web.AdminWebSliceTest
import com.crosscert.passkey.core.api.ApiResponse
import org.assertj.core.api.Assertions.assertThat
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
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
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.UUID

/**
 * 사용량 정산 화면. 숫자·대조 계산은 UsageSettlementServiceTest, 여기서는 권한·표시 문구·기간 대체·CSV 를 본다.
 * REST 컨트롤러는 실제 빈(기간 대체 규칙을 지나가게)이고 서비스만 mock 이다.
 */
@AdminWebSliceTest(controllers = [UsageSettlementPageController::class, UsageSettlementController::class])
class UsageSettlementPageControllerTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var clock: Clock
    @MockitoBean lateinit var service: UsageSettlementService
    @MockitoBean lateinit var license: LicenseController
    @MockitoBean lateinit var chain: AuditChainMonitorController
    @MockitoBean lateinit var activeTenants: ActiveTenantController

    companion object {
        /** 2026-11-25 10:00 KST. */
        val NOW: Instant = Instant.parse("2026-11-25T01:00:00Z")
        val RP1: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
        val A: UUID = UUID.fromString("00000000-0000-0000-0000-00000000000a")
        val B: UUID = UUID.fromString("00000000-0000-0000-0000-00000000000b")
        val CLOSED_AT: OffsetDateTime = OffsetDateTime.parse("2026-11-01T02:00:00+09:00")

        fun platform(): Authentication = auth("PLATFORM_OPERATOR", setOf())
        fun rp(): Authentication = auth("RP_ADMIN", setOf(RP1))

        private fun auth(role: String, tenants: Set<UUID>): Authentication {
            val p = AdminUserDetails(UUID.randomUUID(), "alice@example.com", "x", role, tenants, true, null, Clock.systemUTC())
            return UsernamePasswordAuthenticationToken(p, "x", p.authorities)
        }

        private fun body(r: MvcResult): String = r.response.getContentAsString(StandardCharsets.UTF_8)

        private fun csvText(r: MvcResult): String = String(r.response.contentAsByteArray, StandardCharsets.UTF_8).removePrefix("﻿")
    }

    private fun view(
        period: String,
        status: UsageDto.Status = UsageDto.Status.CLOSED,
        basis: String = "LEDGER",
        verification: UsageDto.Verification = UsageDto.Verification.MATCH,
        zero: Boolean = false,
    ): UsageDto.View {
        val rows = if (zero) {
            listOf(UsageDto.TenantRow(A, "acme", "Acme", 0, 0), UsageDto.TenantRow(B, "beta", "Beta<b>", 0, 0))
        } else {
            listOf(UsageDto.TenantRow(B, "beta", "Beta<b>", 30, 40), UsageDto.TenantRow(A, null, "Acme", 5, 6))
        }
        return UsageDto.View(
            period, status, basis, if (status == UsageDto.Status.IN_PROGRESS) null else CLOSED_AT, verification, rows,
            rows.sumOf { it.regSuccess }, rows.sumOf { it.authSuccess },
            listOf(
                UsageDto.MonthRow("2026-11", UsageDto.Status.IN_PROGRESS, 7, 9, 2, 12),
                UsageDto.MonthRow("2026-10", UsageDto.Status.CLOSED, 35, 46, 2, 50),
                UsageDto.MonthRow("2026-09", UsageDto.Status.CLOSED, 0, 0, 0, 0),
            ),
            listOf("2026-11", "2026-10", "2026-09"),
        )
    }

    @BeforeEach
    fun setUp() {
        whenever(clock.instant()).thenReturn(NOW)
        whenever(clock.zone).thenReturn(ZoneOffset.UTC)
        whenever(license.get()).thenReturn(ApiResponse.ok(LicenseView("saas", "VALID", "acme", "lic_1", NOW.plusSeconds(86400L * 30), 30, listOf(), null, null, null)))
        whenever(service.view(any())).thenAnswer { inv ->
            val m: YearMonth = inv.getArgument(0)
            val current = m == YearMonth.of(2026, 11)
            if (current) {
                view(m.toString(), UsageDto.Status.IN_PROGRESS, verification = UsageDto.Verification.NOT_APPLICABLE)
            } else {
                view(m.toString(), UsageDto.Status.CLOSED, zero = m.year < 2026)
            }
        }
    }

    private fun page(url: String): Document {
        val r = mvc.perform(get(URI.create(url)).with(authentication(platform()))).andReturn()
        assertThat(r.response.status).`as`(url).isEqualTo(200)
        return Jsoup.parse(body(r))
    }

    private fun stubView(v: UsageDto.View) {
        whenever(service.view(any())).thenReturn(v)
    }

    @Test
    @DisplayName("PLATFORM_OPERATOR: 제목·선택 기간·테넌트 행(이름 이스케이프)·합계·추이·탭 줄")
    fun platformOperator_seesPage() {
        val d = page("/admin/insights/usage?period=2026-10")

        assertThat(d.select(".page__head").text()).contains("사용량 정산")
        assertThat(d.select("select[name=period] option[selected]").attr("value")).isEqualTo("2026-10")
        assertThat(d.select("select[name=period] option").eachAttr("value")).containsExactly("2026-11", "2026-10", "2026-09")
        assertThat(d.select(".tabs__btn").eachText()).containsExactly("현황", "사용량 정산")
        assertThat(d.selectFirst(".tabs__btn--active")!!.text()).isEqualTo("사용량 정산")
        val t = d.selectFirst("section[data-usage-tenants]")!!
        assertThat(t.select("thead th").eachText()).containsExactly("테넌트", "등록 성공", "인증 성공", "합계")
        assertThat(t.select("tbody tr").first()!!.select("td").eachText()).containsExactly("Beta<b> beta", "30", "40", "70")
        assertThat(t.select("tfoot td").eachText()).containsExactly("전체", "35", "46", "81")
        assertThat(body(mvc.perform(get("/admin/insights/usage?period=2026-10").with(authentication(platform()))).andReturn())).doesNotContain("Beta<b>")
        val trend = d.selectFirst("section[data-usage-trend]")!!
        assertThat(trend.select("thead th").eachText()).containsExactly("월", "등록 성공", "인증 성공", "사용 테넌트", "인증 성공률(참고)")
        assertThat(trend.select("tbody tr")[1].select("td").eachText()).containsExactly("2026-10", "35", "46", "2", "92.0%")
        assertThat(trend.select("tbody tr")[2].select("td").last()!!.text()).isEqualTo("—")
        assertThat(d.select("a[download]").eachAttr("href")).containsExactly(
            "/admin/insights/usage.csv?period=2026-10&kind=tenants", "/admin/insights/usage.csv?period=2026-10&kind=trend",
        )
    }

    @Test
    @DisplayName("마감 월: 마감·장부·감사 로그와 일치·마감 시각(KST)")
    fun closedMonth_showsLabels() {
        val s = page("/admin/insights/usage?period=2026-10").selectFirst("[data-usage-status]")!!.text()

        assertThat(s).contains("마감").contains("장부").contains("감사 로그와 일치").contains("2026-11-01 02:00")
        assertThat(s).doesNotContain("이전 방식").doesNotContain("불일치")
    }

    @Test
    @DisplayName("이전 방식 기준이면 '이전 방식'")
    fun legacyBasis_showsLabel() {
        stubView(view("2026-10", basis = "LEGACY"))

        assertThat(page("/admin/insights/usage?period=2026-10").selectFirst("[data-usage-status]")!!.text()).contains("이전 방식")
    }

    @Test
    @DisplayName("대조 불일치: '감사 로그와 불일치' 경고")
    fun mismatch_showsWarning() {
        stubView(view("2026-10", verification = UsageDto.Verification.MISMATCH))

        val s = page("/admin/insights/usage?period=2026-10").selectFirst("[data-usage-status]")!!
        assertThat(s.text()).contains("감사 로그와 불일치")
        assertThat(s.select(".badge--danger")).isNotEmpty()
    }

    @Test
    @DisplayName("부분 마감: '부분(원본 삭제됨)'")
    fun partial_showsLabel() {
        stubView(view("2026-10", status = UsageDto.Status.PARTIAL))

        assertThat(page("/admin/insights/usage?period=2026-10").selectFirst("[data-usage-status]")!!.text()).contains("부분(원본 삭제됨)")
    }

    @Test
    @DisplayName("진행 중인 달: '진행 중', 대조 결과는 표시하지 않는다")
    fun inProgress_showsLabel() {
        val s = page("/admin/insights/usage").selectFirst("[data-usage-status]")!!.text()

        assertThat(s).contains("진행 중")
        assertThat(s).doesNotContain("감사 로그")
    }

    @Test
    @DisplayName("RP_ADMIN: 화면은 자기 홈으로, CSV 는 403")
    fun rpAdmin() {
        val r = mvc.perform(get("/admin/insights/usage").with(authentication(rp()))).andReturn()
        assertThat(r.response.status).isEqualTo(302)
        assertThat(r.response.redirectedUrl).isEqualTo("/admin/tenants/$RP1")
        assertThat(mvc.perform(get("/admin/insights/usage.csv?kind=tenants").with(authentication(rp()))).andReturn().response.status).isEqualTo(403)
    }

    @Test
    @DisplayName("잘못된 기간(형식 오류·미래 달)은 이번 달로 대체")
    fun badPeriod_fallsBackToCurrentMonth() {
        for (p in listOf("2026-13", "abc", "2099-01")) {
            val d = page("/admin/insights/usage?period=$p")
            assertThat(d.select("select[name=period] option[selected]").attr("value")).`as`(p).isEqualTo("2026-11")
        }
    }

    @Test
    @DisplayName("데이터 없는 과거 달은 0 으로")
    fun oldPeriodWithoutData_showsZeros() {
        val d = page("/admin/insights/usage?period=2020-01")

        assertThat(d.select("select[name=period] option[selected]").attr("value")).isEqualTo("2020-01") // periods 밖이어도 선택값은 보인다
        assertThat(d.selectFirst("[data-usage-period]")!!.text()).contains("2020-01")
        assertThat(d.select("section[data-usage-tenants] tfoot td").eachText()).containsExactly("전체", "0", "0", "0")
    }

    @Test
    @DisplayName("CSV 테넌트별: 헤더·값·파일명")
    fun csvTenants() {
        val r = mvc.perform(get("/admin/insights/usage.csv?period=2026-10&kind=tenants").with(authentication(platform()))).andReturn()

        assertThat(r.response.status).isEqualTo(200)
        assertThat(r.response.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"usage-settlement-2026-10.csv\"")
        val lines = csvText(r).trim().split("\r\n", "\n")
        assertThat(lines[0]).isEqualTo("period,tenantId,slug,name,registrationSuccess,authenticationSuccess,total,basis,status,closedAt")
        assertThat(lines[1]).isEqualTo("2026-10,$B,beta,Beta<b>,30,40,70,LEDGER,CLOSED,2026-11-01 02:00:00")
        assertThat(lines[2]).isEqualTo("2026-10,$A,,Acme,5,6,11,LEDGER,CLOSED,2026-11-01 02:00:00")
    }

    @Test
    @DisplayName("CSV 진행 중인 달: status IN_PROGRESS, closedAt 빈 칸")
    fun csvTenants_inProgress() {
        val lines = csvText(mvc.perform(get("/admin/insights/usage.csv?kind=tenants").with(authentication(platform()))).andReturn()).trim().split("\r\n", "\n")

        assertThat(lines[1]).endsWith(",LEDGER,IN_PROGRESS,")
    }

    @Test
    @DisplayName("CSV 추이: 헤더·값·성공률 4자리(시도 0 이면 빈 칸)·파일명")
    fun csvTrend() {
        val r = mvc.perform(get("/admin/insights/usage.csv?period=2026-10&kind=trend").with(authentication(platform()))).andReturn()

        assertThat(r.response.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"usage-trend-2026-11-25.csv\"")
        val lines = csvText(r).trim().split("\r\n", "\n")
        assertThat(lines[0]).isEqualTo("period,status,registrationSuccess,authenticationSuccess,activeTenants,authenticationAttempts,authSuccessRate")
        assertThat(lines[1]).isEqualTo("2026-11,IN_PROGRESS,7,9,2,12,0.7500")
        assertThat(lines[3]).isEqualTo("2026-09,CLOSED,0,0,0,0,")
    }

    @Test
    @DisplayName("CSV 알 수 없는 종류: 기존 내보내기와 같은 404")
    fun csvUnknownKind() {
        assertThat(mvc.perform(get("/admin/insights/usage.csv?kind=evil").with(authentication(platform()))).andReturn().response.status).isEqualTo(404)
    }
}
