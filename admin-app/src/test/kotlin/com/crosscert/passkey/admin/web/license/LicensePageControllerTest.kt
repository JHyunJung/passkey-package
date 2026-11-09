package com.crosscert.passkey.admin.web.license

import com.crosscert.passkey.admin.audit.AuditChainMonitorController
import com.crosscert.passkey.admin.auth.ActiveTenantController
import com.crosscert.passkey.admin.auth.AdminUserDetails
import com.crosscert.passkey.admin.license.LicenseController
import com.crosscert.passkey.admin.license.LicenseView
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
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * 라이선스 화면. 원본: LicensePage.test.tsx, App.tsx(RequirePlatform).
 *
 * (Kotlin 전환) 즉시 역참조하는 nullable jsoup 결과(`selectFirst`·`parent`·`nextElementSibling`)의 `!!` 는
 * 원 Java 의 NPE 실패 지점과 같다(`dd` 는 호출부의 `.text()` 대신 도우미 안에서 실패).
 */
@AdminWebSliceTest(controllers = [LicensePageController::class])
class LicensePageControllerTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var clock: Clock
    @MockitoBean lateinit var license: LicenseController
    @MockitoBean lateinit var chain: AuditChainMonitorController
    @MockitoBean lateinit var activeTenants: ActiveTenantController

    companion object {
        val NOW: Instant = Instant.parse("2026-10-11T03:00:00Z")
        val T1: UUID = UUID.fromString("00000000-0000-0000-0000-000000000077")
        val BASE = LicenseView(
            "onprem", "VALID", "acme-bank", "lic_01J9ZQ8V7K3M",
            Instant.parse("2027-04-28T15:00:00Z"), 200, listOf("mds", "audit-export"),
            NOW.minusSeconds((5 * 60).toLong()), null, NOW.plusSeconds((55 * 60).toLong()),
        )

        fun platform(): Authentication = auth("PLATFORM_OPERATOR", setOf())
        fun rp(): Authentication = auth("RP_ADMIN", setOf(T1))

        private fun auth(role: String, tenants: Set<UUID>): Authentication {
            val p = AdminUserDetails(UUID.randomUUID(), "a@x.com", "x", role, tenants, true, null, Clock.systemUTC())
            return UsernamePasswordAuthenticationToken(p, "x", p.authorities)
        }

        private fun dd(d: Document, term: String): Element =
            d.select("main dl dt").stream().filter { e -> e.text() == term }.findFirst().orElseThrow().nextElementSibling()!!
    }

    @BeforeEach
    fun setUp() {
        whenever(clock.instant()).thenReturn(NOW)
        whenever(clock.zone).thenReturn(ZoneOffset.UTC)
        stub(BASE)
    }

    private fun stub(v: LicenseView) {
        whenever(license.get()).thenReturn(ApiResponse.ok(v))
    }

    private fun page(): Document {
        val r = mvc.perform(get("/admin/license").with(authentication(platform()))).andReturn()
        assertThat(r.response.status).isEqualTo(200)
        return Jsoup.parse(r.response.getContentAsString(StandardCharsets.UTF_8))
    }

    @Test
    @DisplayName("licenseRpAdminRedirected: RP_ADMIN 은 자기 tenant 로(RequirePlatform), 다시 확인 조각은 403, 라이선스 조회 안 함")
    fun licenseRpAdminRedirected() {
        val r = mvc.perform(get("/admin/license").with(authentication(rp()))).andReturn()
        assertThat(r.response.status).isEqualTo(302)
        assertThat(r.response.redirectedUrl).isEqualTo("/admin/tenants/$T1")
        val frag = mvc.perform(get(LicensePageController.RECHECK_PATH).with(authentication(rp())).header("HX-Request", "true")).andReturn()
        assertThat(frag.response.status).isEqualTo(403)
        verify(license, never()).get()
    }

    @Test
    @DisplayName("상태 헤드라인: VALID 원문 대신 배지(원문은 툴팁) + 마지막/다음 확인 상대 시간, 루트 .page, 제목")
    fun headline() {
        val d = page()
        assertThat(d.selectFirst("main > .page")).isNotNull()
        assertThat(d.selectFirst("main h1")!!.text()).isEqualTo("라이선스")
        assertThat(d.selectFirst("main .page__sub")!!.text()).isEqualTo("이 설치본(온프레미스)의 라이선스 상태와 상세 정보입니다.")
        assertThat(d.selectFirst("main")!!.text()).doesNotContain("VALID")
        val badge = d.select("main .badge").stream().filter { b -> b.text() == "정상" }.findFirst().orElseThrow()
        assertThat(badge.hasClass("badge--success")).isTrue()
        assertThat(badge.parent()!!.attr("title")).isEqualTo("VALID")
        assertThat(d.selectFirst("main")!!.text()).contains("마지막 확인 5분 전 · 다음 확인 55분 후")
        assertThat(d.selectFirst(".crumbs__current")!!.text()).isEqualTo("라이선스")
    }

    @Test
    @DisplayName("만료까지 일수만(진행 바 없음), '만료일 …'")
    fun daysOnly() {
        val d = page()
        assertThat(d.selectFirst("main")!!.text()).contains("만료까지").contains("200일").contains("만료일 2027. 04. 29.")
            .doesNotContain("Expires at")
        assertThat(d.select("main [role=progressbar]")).isEmpty()
    }

    @Test
    @DisplayName("만료까지 일수의 글자 크기 13px 은 색이 없을 때(VALID)도 유지, WARNING 은 13px + 경고 색")
    fun daysKeepFontSize() {
        val strong = page().select("main strong").stream().filter { e -> e.text() == "200일" }.findFirst().orElseThrow()
        assertThat(strong.attr("style")).isEqualTo("font-size: 13px")
        stub(LicenseView("onprem", "WARNING", "acme-bank", "lic_1", BASE.expiresAt, 30, listOf(), null, null, null))
        val warn = page().select("main strong").stream().filter { e -> e.text() == "30일" }.findFirst().orElseThrow()
        assertThat(warn.attr("style")).isEqualTo("font-size: 13px; color: var(--warn)")
    }

    @Test
    @DisplayName("상세 카드: 한국어 항목 순서, 라이선스 ID mono + 복사 버튼, 기능 태그, 유예 없으면 줄 없음, stack-5 / kv--rows")
    fun detailCard() {
        val d = page()
        assertThat(d.select("main h2.card__title").eachText()).contains("상세")
        assertThat(d.select("main dl dt").eachText()).containsExactly("고객사", "라이선스 ID", "만료일", "남은 일수", "사용 가능한 기능", "마지막 확인", "다음 확인 예정")
        assertThat(dd(d, "고객사").text()).isEqualTo("acme-bank")
        assertThat(dd(d, "라이선스 ID").selectFirst(".mono")!!.text()).isEqualTo("lic_01J9ZQ8V7K3M")
        assertThat(dd(d, "라이선스 ID").select("[aria-label=라이선스 ID 복사], [title=라이선스 ID 복사]")).isNotEmpty()
        assertThat(dd(d, "사용 가능한 기능").select(".tag").eachText()).containsExactly("mds", "audit-export")
        assertThat(d.selectFirst("main .stack-5")).isNotNull()
        assertThat(d.selectFirst("main dl")!!.hasClass("kv")).isTrue()
        assertThat(d.selectFirst("main dl")!!.hasClass("kv--rows")).isTrue()
    }

    @Test
    @DisplayName("NETWORK_GRACE: 경고 배지, 유예 'N시간', 연결 복구 조치 카드(3단계)")
    fun networkGrace() {
        stub(LicenseView("onprem", "NETWORK_GRACE", "acme-bank", "lic_1", BASE.expiresAt, 200, listOf(), BASE.lastVerifiedAt, 12L, BASE.nextHeartbeatAt))
        val d = page()
        assertThat(d.select("main .badge").stream().filter { b -> b.text() == "서버 연결 불가" }.findFirst().orElseThrow().hasClass("badge--warning")).isTrue()
        assertThat(dd(d, "유예 기간 남은 시간").text()).isEqualTo("12시간")
        assertThat(d.selectFirst("main")!!.text()).doesNotContain("hour(s)")
        val card = d.selectFirst("section[aria-label=지금 해야 할 일 — 라이선스 서버 연결 복구]")!!
        assertThat(card.text()).contains("12시간 안에 연결되지 않으면 서비스가 중단됩니다")
        assertThat(card.select("li")).hasSize(3)
    }

    @Test
    @DisplayName("값이 없으면 '—', 상대 시간 줄·복사 버튼 숨김, DEAD 면 새 라이선스 적용 카드")
    fun deadWithNulls() {
        stub(LicenseView("onprem", "DEAD", "acme-bank", null, BASE.expiresAt, 0, listOf(), null, null, null))
        val d = page()
        assertThat(d.select("main .badge").stream().filter { b -> b.text() == "만료" }.findFirst().orElseThrow().hasClass("badge--danger")).isTrue()
        assertThat(d.selectFirst("main")!!.text()).doesNotContainPattern("마지막 확인 .* 전").doesNotContain("(never)").doesNotContain("(none scheduled)")
        assertThat(d.select("[aria-label=라이선스 ID 복사], [title=라이선스 ID 복사]")).isEmpty()
        for (t in listOf("마지막 확인", "다음 확인 예정", "라이선스 ID", "사용 가능한 기능")) assertThat(dd(d, t).text()).isEqualTo("—")
        assertThat(d.selectFirst("section[aria-label=지금 해야 할 일 — 새 라이선스 적용]")!!.text()).contains("관리 콘솔은 계속 쓸 수 있습니다")
    }

    @Test
    @DisplayName("WARNING: 갱신 조치 카드(3단계), VALID 는 조치 카드 없음")
    fun warningAndValid() {
        stub(LicenseView("onprem", "WARNING", "acme-bank", "lic_1", BASE.expiresAt, 30, listOf(), null, null, null))
        val card = page().selectFirst("section[aria-label=지금 해야 할 일 — 라이선스 갱신]")!!
        assertThat(card.text()).contains("라이선스가 30일 후 만료됩니다")
        assertThat(card.select("li")).hasSize(3)

        stub(BASE)
        assertThat(page().select("section[aria-label^=지금 해야 할 일]")).isEmpty()
    }

    @Test
    @DisplayName("SaaS 면 온프레미스 전용 안내(한국어)")
    fun saas() {
        stub(LicenseView("saas", null, null, null, null, 0, listOf(), null, null, null))
        val d = page()
        assertThat(d.selectFirst("main")!!.text()).contains("라이선스 관리는 온프레미스 설치본에서만 사용합니다")
            .contains("클라우드 설치본은 계약에 따라 자동 관리됩니다.").doesNotContain("on-prem only")
        assertThat(d.select("main dl")).isEmpty()
    }

    @Test
    @DisplayName("조회 실패는 LoadError(다시 시도 = 화면 다시 받기), 알 수 없는 예외의 원문은 보이지 않는다")
    fun loadFailure() {
        whenever(license.get()).thenThrow(IllegalStateException("boom"))
        val d = page()
        val alert = d.selectFirst("main [role=alert]")!!
        assertThat(alert.text()).contains("라이선스 정보를 불러오지 못했습니다").doesNotContain("boom")
        val retry = alert.select("button").stream().filter { b -> b.text() == "다시 시도" }.findFirst().orElseThrow()
        assertThat(retry.attr("hx-get")).isEqualTo("/admin/license")
    }

    @Test
    @DisplayName("지금 다시 확인: 버튼은 다시 확인 조각(요청 중 비활성·'확인 중…', 중복 drop), 성공하면 상세 영역 교체")
    fun recheckSuccess() {
        val btn = page().select("main button").stream().filter { b -> b.text().contains("지금 다시 확인") }.findFirst().orElseThrow()
        assertThat(btn.attr("hx-get")).isEqualTo(LicensePageController.RECHECK_PATH)
        assertThat(btn.attr("hx-target")).isEqualTo("#license-body")
        assertThat(btn.attr("hx-swap")).isEqualTo("outerHTML")
        assertThat(btn.attr("hx-sync")).isEqualTo("this:drop")
        assertThat(btn.attr("hx-disabled-elt")).isEqualTo("this")
        assertThat(btn.selectFirst(".license-recheck__busy")!!.text()).isEqualTo("확인 중…")

        val r = mvc.perform(get(LicensePageController.RECHECK_PATH).with(authentication(platform())).header("HX-Request", "true")).andReturn()
        assertThat(r.response.status).isEqualTo(200)
        val d = Jsoup.parse(r.response.getContentAsString(StandardCharsets.UTF_8))
        assertThat(d.selectFirst("#license-body")).isNotNull()
        assertThat(d.selectFirst("#license-recheck-alert")!!.children()).isEmpty()
        assertThat(d.text()).contains("정상")
        assertThat(d.select("aside")).isEmpty()
    }

    @Test
    @DisplayName("지금 다시 확인 실패: 기존 정보 유지(경고 자리만 교체) + '다시 확인하지 못했습니다'")
    fun recheckFailureKeepsPrevious() {
        whenever(license.get()).thenThrow(IllegalStateException("boom"))
        val r = mvc.perform(get(LicensePageController.RECHECK_PATH).with(authentication(platform())).header("HX-Request", "true")).andReturn()
        assertThat(r.response.status).isEqualTo(200)
        assertThat(r.response.getHeader("HX-Retarget")).isEqualTo("#license-recheck-alert")
        assertThat(r.response.getHeader("HX-Reswap")).isEqualTo("innerHTML")
        val d = Jsoup.parse(r.response.getContentAsString(StandardCharsets.UTF_8))
        assertThat(d.selectFirst(".banner__title")!!.text()).isEqualTo("다시 확인하지 못했습니다")
        assertThat(d.text()).contains("서버가 잠시 응답하지 않습니다. 아래는 마지막으로 확인한 정보입니다.").doesNotContain("boom")
        assertThat(d.selectFirst("#license-body")).isNull()
    }

    @Test
    @DisplayName("고객사·기능 값은 이스케이프")
    fun escapes() {
        stub(LicenseView("onprem", "VALID", "<b>x</b>", "lic\"1", BASE.expiresAt, 200, listOf("<i>f</i>"), null, null, null))
        val html = mvc.perform(get("/admin/license").with(authentication(platform()))).andReturn().response.getContentAsString(StandardCharsets.UTF_8)
        assertThat(html).doesNotContain("<b>x</b>").doesNotContain("<i>f</i>").contains("&lt;b&gt;x&lt;/b&gt;")
    }
}
