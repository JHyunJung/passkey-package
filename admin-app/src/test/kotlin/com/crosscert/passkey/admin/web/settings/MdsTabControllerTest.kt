package com.crosscert.passkey.admin.web.settings

import com.crosscert.passkey.admin.audit.AuditChainMonitorController
import com.crosscert.passkey.admin.auth.ActiveTenantController
import com.crosscert.passkey.admin.auth.AdminUserDetails
import com.crosscert.passkey.admin.auth.MfaController
import com.crosscert.passkey.admin.license.LicenseController
import com.crosscert.passkey.admin.mds.MdsAdminController
import com.crosscert.passkey.admin.mds.MdsHistoryView
import com.crosscert.passkey.admin.mds.MdsSchedulerService
import com.crosscert.passkey.admin.mds.MdsStatusView
import com.crosscert.passkey.admin.operator.SignupRequestController
import com.crosscert.passkey.admin.system.SystemInfoController
import com.crosscert.passkey.admin.web.AdminWebSliceTest
import com.crosscert.passkey.core.api.ApiResponse
import org.assertj.core.api.Assertions.assertThat
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * 설정 › MDS Status 탭. 원본: MdsStatusTab.test.tsx, api/mdsStatus.test.ts.
 *
 * (Kotlin 전환) 즉시 역참조하는 nullable jsoup 결과(`selectFirst`)의 `!!` 는 원 Java 의 NPE 실패 지점과 같다.
 */
@AdminWebSliceTest(controllers = [MdsTabController::class, SettingsPageController::class, AccountTabController::class])
class MdsTabControllerTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var clock: Clock
    @MockitoBean lateinit var mds: MdsAdminController
    @MockitoBean lateinit var mfa: MfaController
    @MockitoBean lateinit var signups: SignupRequestController
    @MockitoBean lateinit var systemInfo: SystemInfoController
    @MockitoBean lateinit var license: LicenseController
    @MockitoBean lateinit var chain: AuditChainMonitorController
    @MockitoBean lateinit var activeTenants: ActiveTenantController

    companion object {
        val NOW: Instant = Instant.parse("2026-10-11T00:00:00Z")

        fun status(version: Long, next: String?, fetched: String?, mode: String?, ok: Int, total: Int): MdsStatusView =
            MdsStatusView(version, next, fetched, 42, mode, MdsStatusView.SuccessRate(ok, total))

        fun run(id: Long, startedAt: String?, version: Long?, status: String?, summary: String?, ms: Int?, error: String?): MdsHistoryView =
            MdsHistoryView(id, startedAt, startedAt, version, status, summary, ms, error)

        fun platform(): Authentication = auth("PLATFORM_OPERATOR", setOf())
        fun rp(): Authentication = auth("RP_ADMIN", setOf(UUID.randomUUID()))

        private fun auth(role: String, tenants: Set<UUID>): Authentication {
            val p = AdminUserDetails(UUID.randomUUID(), "a@x.com", "x", role, tenants, true, null, Clock.systemUTC())
            return UsernamePasswordAuthenticationToken(p, "x", p.authorities)
        }

        private fun html(r: MvcResult): Document = Jsoup.parse(r.response.getContentAsString(StandardCharsets.UTF_8))

        private fun trigger(r: MvcResult): String? {
            val h = r.response.getHeader("HX-Trigger")
            return if (h == null) null else tools.jackson.databind.ObjectMapper().readValue(h, Map::class.java).toString()
        }

        private fun kpi(d: Document, label: String): Element =
            d.select(".kpi").stream().filter { k -> k.selectFirst(".kpi__label")!!.text() == label }.findFirst().orElseThrow()
    }

    @BeforeEach
    fun setUp() {
        whenever(clock.instant()).thenReturn(NOW)
        whenever(clock.zone).thenReturn(ZoneOffset.UTC)
        whenever(signups.list()).thenReturn(listOf())
        whenever(mds.status()).thenReturn(ApiResponse.ok(status(12, "2026-10-12", "2026-10-10T23:57:00Z", "MDS_STRICT_OPTIONAL", 28, 30)))
        whenever(mds.history(anyInt())).thenReturn(
            ApiResponse.ok(
                listOf(
                    run(3, "2026-10-10T18:00:00Z", 12L, "SYNCED", "+2 / -0", 1200, null),
                    run(2, "2026-10-09T18:00:00Z", null, "SKIPPED", null, null, null),
                    run(1, "2026-10-08T18:00:00Z", null, "FAILED", "무시", 90, "boom\n  <b>x</b>"),
                ),
            ),
        )
        whenever(mds.sync()).thenReturn(ApiResponse.ok(MdsSchedulerService.SyncResult.synced(13)))
    }

    private fun send(b: MockHttpServletRequestBuilder, a: Authentication): MvcResult =
        mvc.perform(b.with(authentication(a)).with(csrf()).header("HX-Request", "true")).andReturn()

    private fun tab(): Document {
        val r = send(get(MdsTabController.TAB_PATH), platform())
        assertThat(r.response.status).isEqualTo(200)
        return html(r)
    }

    @Test
    @DisplayName("헤드라인·KPI·설정: 정상 · BLOB #12, 다음 갱신, 앵커 수, 마지막 동기화(초 단위 상대), 성공률 93%, 신뢰 모드 라벨+설명(원문은 title)")
    fun headlineKpisSettings() {
        whenever(mds.history(anyInt())).thenReturn(ApiResponse.ok(listOf(run(3, "2026-10-10T18:00:00Z", 12L, "SYNCED", "+2 / -0", 1200, null))))
        val d = tab()
        assertThat(d.selectFirst("section.mds-head strong")!!.text()).isEqualTo("정상 · BLOB #12")
        assertThat(d.selectFirst("section.mds-head .card__sub")!!.text()).isEqualTo("FIDO MDS3 BLOB 을 매일 03:00 KST 에 갱신합니다 · 다음 갱신 2026-10-12 03:00 KST")
        assertThat(kpi(d, "MDS BLOB 버전").selectFirst(".mono")!!.text()).isEqualTo("#12")
        assertThat(kpi(d, "신뢰 앵커 (Trust anchors)").selectFirst(".kpi__value")!!.text()).isEqualTo("42")
        assertThat(kpi(d, "마지막 동기화").selectFirst(".kpi__value")!!.text()).isEqualTo("3분 전")
        val dl = d.selectFirst("dl.kv")!!
        assertThat(dl.selectFirst("dd.kv__nowrap")!!.text()).isEqualTo("https://mds3.fidoalliance.org/")
        assertThat(dl.text()).contains("https://mds3.fidoalliance.org/").contains("2026-10-12 03:00 KST").contains("93%").contains("28 / 30")
        val trust = d.selectFirst("dd span[title=MDS_STRICT_OPTIONAL]")!!
        assertThat(trust.text()).isEqualTo("MDS 확인 권장")
        assertThat(dl.text()).contains("MDS 에 없는 인증기도 기본은 허용합니다.")
        assertThat(d.text()).doesNotContain("next 갱신 예정")
    }

    @Test
    @DisplayName("이력 표는 최근 6행(상태 라벨 한국어), 스트립 막대 수 = 이력 수(오래된 것부터), 이력은 limit=30 으로 요청")
    fun historyTableAndStrip() {
        val many = ArrayList<MdsHistoryView>()
        for (i in 0 until 9) many.add(run((100 - i).toLong(), "2026-10-10T18:00:00Z", 12L, if (i == 2) "FAILED" else "SYNCED", "ok", 100, if (i == 2) "e" else null))
        whenever(mds.history(anyInt())).thenReturn(ApiResponse.ok(many))
        val d = tab()
        verify(mds).history(30)
        assertThat(d.select("table tbody tr")).hasSize(6)
        assertThat(d.select("table tbody tr")[0].text()).contains("동기화됨")
        val strip = d.selectFirst(".strip")!!
        assertThat(strip.select(".strip__bar")).hasSize(9)
        assertThat(strip.attr("aria-label")).isEqualTo("최근 9회 동기화 결과 · 실패 1회")
        assertThat(strip.select(".strip__bar--bad")).hasSize(1)
    }

    @Test
    @DisplayName("상태 라벨: 동기화됨/건너뜀/실패, 시각은 KST, 버전 #n·—, 응답 시간 ms·—, 변경 요약 —")
    fun rowLabelsAndKst() {
        val d = tab()
        val rows = d.select("table tbody tr").eachText()
        assertThat(rows[0]).contains("2026-10-11 03:00").contains("#12").contains("+2 / -0").contains("동기화됨").contains("1200ms")
        assertThat(rows[1]).contains("2026-10-10 03:00").contains("건너뜀").contains("—")
        assertThat(rows[2]).contains("실패").contains("90ms")
    }

    @Test
    @DisplayName("실패 행은 errorMessage 를 한 줄 평문으로(마크업 아님, 공백 접기, 80자 줄임), 전체는 title 툴팁")
    fun failureReasonIsPlain() {
        val longMsg = "x".repeat(200)
        whenever(mds.history(anyInt())).thenReturn(
            ApiResponse.ok(
                listOf(
                    run(2, "2026-10-10T18:00:00Z", null, "FAILED", "무시", 90, "boom\n  <b>x</b>"),
                    run(1, "2026-10-09T18:00:00Z", null, "FAILED", "무시", 90, longMsg),
                ),
            ),
        )
        val d = tab()
        val first = d.select("table tbody tr")[0].selectFirst("td:eq(2) span")!!
        assertThat(first.text()).isEqualTo("boom <b>x</b>")
        assertThat(d.select("table tbody b")).isEmpty()
        assertThat(first.attr("title")).isEqualTo("boom <b>x</b>")
        assertThat(d.select("table tbody tr")[0].text()).doesNotContain("무시")
        val second = d.select("table tbody tr")[1].selectFirst("td:eq(2) span")!!
        assertThat(second.text()).hasSize(80).endsWith("…")
        assertThat(second.attr("title")).isEqualTo(longMsg)
    }

    @Test
    @DisplayName("최신 실행이 실패면 '동기화 실패 · BLOB #n' 헤드라인(실패 톤), 이력이 비면 표에 안내")
    fun failedHeadlineAndEmpty() {
        whenever(mds.history(anyInt())).thenReturn(ApiResponse.ok(listOf(run(1, "2026-10-10T18:00:00Z", null, "FAILED", null, 10, "e"))))
        val d = tab()
        assertThat(d.selectFirst("section.mds-head strong")!!.text()).isEqualTo("동기화 실패 · BLOB #12")
        assertThat(d.selectFirst(".mds-head__icon")!!.hasClass("mds-head__icon--bad")).isTrue()
        whenever(mds.history(anyInt())).thenReturn(ApiResponse.ok(listOf()))
        val e = tab()
        assertThat(e.selectFirst("section.mds-head strong")!!.text()).isEqualTo("정상 · BLOB #12")
        assertThat(e.text()).contains("동기화 이력이 없습니다.")
        assertThat(e.select(".strip")).isEmpty()
    }

    @Test
    @DisplayName("한 번도 동기화되지 않은 설치(version 0 / 1970 시드 / 2000년 이전 fetchedAt)는 중립 '동기화 이력 없음', '—' 표시, 20000일 전 없음")
    fun neverSynced() {
        for (
            s in listOf(
                status(0, "1970-01-01", "1970-01-01T00:00:00Z", "MDS_STRICT_OPTIONAL", 0, 0),
                status(5, null, "1999-12-31T23:59:59Z", "MDS_STRICT_OPTIONAL", 0, 0),
            )
        ) {
            whenever(mds.status()).thenReturn(ApiResponse.ok(s))
            val d = tab()
            assertThat(d.selectFirst("section.mds-head strong")!!.text()).isEqualTo("동기화 이력 없음")
            assertThat(d.selectFirst(".mds-head__icon")!!.hasClass("mds-head__icon--neutral")).isTrue()
            assertThat(kpi(d, "MDS BLOB 버전").text()).contains("—")
            assertThat(kpi(d, "마지막 동기화").selectFirst(".kpi__value")!!.text()).isEqualTo("—")
            assertThat(d.text()).doesNotContain("일 전").doesNotContain("다음 갱신 1970")
            assertThat(d.selectFirst("dl.kv")!!.text()).contains("다음 갱신 예정 —").contains("동기화 성공률 (30일) —")
        }
    }

    @Test
    @DisplayName("신뢰 모드: 모르는 값(Object.prototype 이름 포함)은 원문 그대로·설명 없음, 값이 없으면 '— 설정되지 않음'")
    fun trustModes() {
        for (m in listOf("CUSTOM_MODE", "constructor", "toString")) {
            whenever(mds.status()).thenReturn(ApiResponse.ok(status(12, null, "2026-10-10T23:57:00Z", m, 1, 1)))
            val dd = tab().selectFirst("dl.kv dd:has(span[title])")!!
            assertThat(dd.text()).isEqualTo(m)
            assertThat(dd.selectFirst("span[title]")!!.attr("title")).isEqualTo(m)
        }
        whenever(mds.status()).thenReturn(ApiResponse.ok(status(12, null, "2026-10-10T23:57:00Z", null, 1, 1)))
        assertThat(tab().selectFirst("dl.kv")!!.text()).contains("— 설정되지 않음")
    }

    @Test
    @DisplayName("상태 조회 실패는 '이력 없음'이 아니라 LoadError(다시 시도 = 탭 다시 받기, 오류 정보 복사), 원문 없음")
    fun statusFailure() {
        whenever(mds.status()).thenThrow(IllegalStateException("boom-internal"))
        val d = tab()
        val alert = d.selectFirst("[role=alert]")!!
        assertThat(alert.text()).contains("MDS 상태를 불러오지 못했습니다").doesNotContain("boom-internal")
        assertThat(alert.select("button").eachText()).contains("오류 정보 복사")
        val retry = alert.select("button").stream().filter { b -> b.text() == "다시 시도" }.findFirst().orElseThrow()
        assertThat(retry.attr("hx-get")).isEqualTo(MdsTabController.TAB_PATH)
        assertThat(d.text()).doesNotContain("동기화 이력 없음").doesNotContain("정상")
    }

    @Test
    @DisplayName("이력만 실패: 이력 영역에 LoadError, 헤드라인은 '정상'이 아니라 '이력 확인 불가', 상태 KPI 는 그대로")
    fun historyOnlyFailure() {
        whenever(mds.history(anyInt())).thenThrow(IllegalStateException("boom-internal"))
        val d = tab()
        assertThat(d.selectFirst("section.mds-head strong")!!.text()).isEqualTo("이력 확인 불가 · BLOB #12")
        assertThat(d.text()).contains("동기화 이력을 불러오지 못했습니다").doesNotContain("boom-internal").doesNotContain("정상 ·").doesNotContain("동기화 이력이 없습니다")
        assertThat(d.select("table")).isEmpty()
        assertThat(kpi(d, "신뢰 앵커 (Trust anchors)").text()).contains("42")
        assertThat(d.select(".strip")).isEmpty()
    }

    @Test
    @DisplayName("즉시 갱신 버튼: POST, 요청 중 비활성(hx-disabled-elt)·중복 방지(hx-sync=this:drop)·120초 제한, 대상 #mds-root")
    fun syncButtonAttrs() {
        val d = tab()
        val btn = d.selectFirst("section.mds-head button")!!
        assertThat(btn.attr("hx-post")).isEqualTo(MdsTabController.SYNC_PATH)
        assertThat(btn.attr("hx-target")).isEqualTo("#mds-root")
        assertThat(btn.attr("hx-swap")).isEqualTo("outerHTML")
        assertThat(btn.attr("hx-sync")).isEqualTo("this:drop")
        assertThat(btn.attr("hx-disabled-elt")).isEqualTo("this")
        assertThat(btn.attr("hx-request")).contains("120000")
        assertThat(btn.select(".mds-sync__idle").text()).isEqualTo("즉시 갱신")
        assertThat(btn.select(".mds-sync__busy").text()).isEqualTo("갱신 중…")
        assertThat(d.selectFirst("#mds-root")).isNotNull()
    }

    @Test
    @DisplayName("즉시 갱신 SYNCED: MdsAdminController.sync 호출, 완료 토스트(버전), 상태·이력을 다시 읽어 #mds-root 를 돌려준다")
    fun syncSynced() {
        val r = send(post(MdsTabController.SYNC_PATH), platform())
        assertThat(r.response.status).isEqualTo(200)
        verify(mds).sync()
        assertThat(trigger(r)).contains("MDS 즉시 갱신 완료").contains("버전 13").contains("success")
        assertThat(html(r).selectFirst("#mds-root")).isNotNull()
        verify(mds).status()
        verify(mds).history(30)
    }

    @Test
    @DisplayName("즉시 갱신 SKIPPED·FAILED: 경고 토스트 / 쉬운 오류 문구(서버 원문 error 는 싣지 않음), 둘 다 화면은 다시 읽는다")
    fun syncSkippedAndFailed() {
        whenever(mds.sync()).thenReturn(ApiResponse.ok(MdsSchedulerService.SyncResult.skipped()))
        val skipped = send(post(MdsTabController.SYNC_PATH), platform())
        assertThat(trigger(skipped)).contains("MDS 갱신 건너뜀").contains("다른 인스턴스가 동기화 중입니다.").contains("warn")
        assertThat(html(skipped).selectFirst("#mds-root")).isNotNull()

        whenever(mds.sync()).thenReturn(ApiResponse.ok(MdsSchedulerService.SyncResult("FAILED", null, "secret-internal-error")))
        val failed = send(post(MdsTabController.SYNC_PATH), platform())
        assertThat(failed.response.status).isEqualTo(200)
        assertThat(trigger(failed)).contains("MDS 갱신 실패").contains("갱신하지 못했습니다. 잠시 후 다시 시도하세요.").doesNotContain("secret-internal-error")
        assertThat(failed.response.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("secret-internal-error")
    }

    @Test
    @DisplayName("즉시 갱신 예외: 'MDS 갱신 실패' 오류 토스트(원문 없음) + 실제 오류 상태 + 본문 없음(탭은 그대로, 다시 시도 가능)")
    fun syncException() {
        whenever(mds.sync()).thenThrow(IllegalStateException("db-secret"))
        val r = send(post(MdsTabController.SYNC_PATH), platform())
        assertThat(r.response.status).isEqualTo(500)
        assertThat(trigger(r)).contains("MDS 갱신 실패").contains("error").doesNotContain("db-secret")
        assertThat(r.response.contentAsString).isEmpty()
        verify(mds, never()).status()
    }

    @Test
    @DisplayName("갱신 뒤 다시 읽기가 실패하면 화면을 바꾸지 않고(HX-Reswap none) 경고 자리(#mds-warn)만 OOB, 갱신 토스트는 나감")
    fun syncThenReloadFailsKeepsDom() {
        assertThat(tab().selectFirst("#mds-root #mds-warn")).isNotNull()
        whenever(mds.status()).thenThrow(IllegalStateException("boom-internal"))
        val r = send(post(MdsTabController.SYNC_PATH), platform())
        assertThat(r.response.status).isEqualTo(200)
        assertThat(r.response.getHeader("HX-Reswap")).isEqualTo("none")
        assertThat(trigger(r)).contains("MDS 즉시 갱신 완료")
        val d = html(r)
        val warn = d.selectFirst("#mds-warn")!!
        assertThat(warn.attr("hx-swap-oob")).isEqualTo("true")
        assertThat(warn.text()).contains("다시 확인하지 못했습니다").contains("아래는 마지막으로 읽은 MDS 상태입니다.").doesNotContain("boom-internal")
        assertThat(warn.select("button").eachText()).contains("다시 시도")
        assertThat(d.select("#mds-root")).isEmpty()
    }

    @Test
    @DisplayName("갱신 뒤 이력만 못 읽어도 화면 유지 + 경고(이전 이력을 지우지 않는다)")
    fun syncThenHistoryFailsKeepsDom() {
        whenever(mds.history(anyInt())).thenThrow(IllegalStateException("boom"))
        val r = send(post(MdsTabController.SYNC_PATH), platform())
        assertThat(r.response.getHeader("HX-Reswap")).isEqualTo("none")
        assertThat(html(r).selectFirst("#mds-warn")!!.text()).contains("다시 확인하지 못했습니다")
    }

    @Test
    @DisplayName("모든 엔드포인트는 RP_ADMIN 에게 403 이고 REST 를 부르지 않는다")
    fun rpAdminForbidden() {
        assertThat(send(get(MdsTabController.TAB_PATH), rp()).response.status).isEqualTo(403)
        assertThat(send(post(MdsTabController.SYNC_PATH), rp()).response.status).isEqualTo(403)
        verify(mds, never()).status()
        verify(mds, never()).history(anyInt())
        verify(mds, never()).sync()
    }

    @Test
    @DisplayName("리터럴 …/tab/mds 가 일반 탭 매핑보다 우선(PLATFORM 200, RP 는 계정 탭으로 떨어지지 않고 403)")
    fun literalWinsOverGeneric() {
        val r = send(get("/admin/_/settings/tab/mds"), platform())
        assertThat(r.response.status).isEqualTo(200)
        assertThat(html(r).selectFirst("#mds-root")).isNotNull()
        assertThat(send(get("/admin/_/settings/tab/mds"), rp()).response.status).isEqualTo(403)
    }

    @Test
    @DisplayName("단위: timeAgo(초 단위), isSeedTime, failureReason 80자 줄임")
    fun units() {
        assertThat(MdsTabController.timeAgo("2026-10-10T23:59:30Z", NOW)).isEqualTo("30초 전")
        assertThat(MdsTabController.timeAgo("2026-10-10T22:00:00Z", NOW)).isEqualTo("2시간 전")
        assertThat(MdsTabController.timeAgo("2026-10-08T00:00:00Z", NOW)).isEqualTo("3일 전")
        assertThat(MdsTabController.isSeedTime(null)).isTrue()
        assertThat(MdsTabController.isSeedTime("2000-01-01T00:00:00Z")).isFalse()
        assertThat(MdsTabController.failureReason("a".repeat(81))).hasSize(80).endsWith("…")
    }
}
