package com.crosscert.passkey.admin.web.audit

import com.crosscert.passkey.admin.audit.AuditChainMonitorController
import com.crosscert.passkey.admin.audit.AuditChainOverview
import com.crosscert.passkey.admin.audit.AuditChainTenantOverview
import com.crosscert.passkey.admin.audit.IncidentConflictException
import com.crosscert.passkey.admin.audit.IncidentDto
import com.crosscert.passkey.admin.audit.IncidentNotFoundException
import com.crosscert.passkey.admin.audit.IncidentNotTamperedException
import com.crosscert.passkey.admin.auth.ActiveTenantController
import com.crosscert.passkey.admin.auth.AdminUserDetails
import com.crosscert.passkey.admin.license.LicenseController
import com.crosscert.passkey.admin.license.LicenseView
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
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.reset
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * 감사 체인 화면. 원본: pages/AuditChainPage.test.tsx, AuditChainPage.backfillGuard.test.tsx.
 * 브라우저에서 도는 부분(Alpine 상태·htmx 의 실제 교체·타이머·다운로드)은 속성·소스 수준까지만 본다 — parity 문서 참조.
 *
 * (Kotlin 전환) 즉시 역참조하는 nullable jsoup 결과(`selectFirst`·`first`)와 `trigger()` 의 `!!` 는 원 Java 의 NPE 실패 지점과 같다.
 * Java `List.of(selectFirst(..), ..)` 는 null 이면 NPE 이므로 원소에 `!!` 를 둔다.
 */
@AdminWebSliceTest(controllers = [AuditChainPageController::class])
class AuditChainPageControllerTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var clock: Clock
    @MockitoBean lateinit var chain: AuditChainMonitorController
    @MockitoBean lateinit var license: LicenseController
    @MockitoBean lateinit var activeTenants: ActiveTenantController

    companion object {
        val NOW: Instant = Instant.parse("2026-10-11T03:00:00Z") // KST 2026-10-11 12:00
        val RP1: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
        val T1: UUID = UUID.fromString("00000000-0000-0000-0000-000000000011")
        val T2: UUID = UUID.fromString("00000000-0000-0000-0000-000000000022")
        val ENTRY: UUID = UUID.fromString("00000000-0000-0000-0000-00000000abcd")
        val INC1: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
        val JSON = ObjectMapper()

        private const val OVERVIEW = AuditChainPageController.OVERVIEW_PATH
        private const val INCIDENTS = AuditChainPageController.INCIDENTS_PATH
        private const val RESOLVE = AuditChainPageController.RESOLVE_PATH

        // ── fixtures ────────────────────────────────────────────

        fun platform(): Authentication = auth("PLATFORM_OPERATOR", setOf())
        fun rp(): Authentication = auth("RP_ADMIN", setOf(RP1))

        private fun auth(role: String, tenants: Set<UUID>): Authentication {
            val p = AdminUserDetails(UUID.randomUUID(), "alice@example.com", "x", role, tenants, true, null, Clock.systemUTC())
            return UsernamePasswordAuthenticationToken(p, "x", p.authorities)
        }

        fun ten(id: UUID?, name: String, ok: Boolean, rows: Long, entry: UUID?): AuditChainTenantOverview =
            AuditChainTenantOverview(id, name, ok, rows, listOf(), entry)

        fun ov(tenants: List<AuditChainTenantOverview>): AuditChainOverview {
            val bad = tenants.stream().filter { t -> !t.intact }.count().toInt()
            return AuditChainOverview(
                NOW.minusSeconds(180), 24, 60,
                AuditChainOverview.Totals(tenants.size - bad, tenants.size, bad, tenants.stream().mapToLong { it.verifiedRows }.sum(), 42),
                tenants,
            )
        }

        fun intact(): AuditChainOverview = ov(listOf(ten(null, "[platform]", true, 5, null), ten(T1, "Acme", true, 1200, null)))

        fun tampered(): AuditChainOverview = ov(listOf(ten(T1, "Acme", true, 1200, null), ten(T2, "Globex", false, 1200, ENTRY)))

        fun inc(id: UUID, tenant: UUID, name: String, status: String): IncidentDto.IncidentView =
            IncidentDto.IncidentView(
                id.toString(), tenant.toString(), name, ENTRY.toString(), "AUDIT_CHAIN_TAMPERED", "HIGH", status, null,
                "2026-10-11T02:00:00Z", "op@example.com", null, null, null,
            )

        private fun htmx(b: MockHttpServletRequestBuilder, a: Authentication): MockHttpServletRequestBuilder =
            b.with(authentication(a)).with(csrf()).header("HX-Request", "true")

        private fun confirm(b: MockHttpServletRequestBuilder, a: Authentication): MockHttpServletRequestBuilder = htmx(b, a).header("X-Confirm", "1")

        private fun body(r: MvcResult): String = r.response.getContentAsString(StandardCharsets.UTF_8)

        private fun doc(r: MvcResult): Document = Jsoup.parse(body(r))

        private fun status(r: MvcResult): Int = r.response.status

        private fun trigger(r: MvcResult): JsonNode? {
            val res = r.response
            val h = res.getHeader("HX-Trigger")
            return if (h == null) null else JSON.readTree(h)
        }
    }

    @BeforeEach
    fun setUp() {
        reset(chain)
        whenever(clock.instant()).thenReturn(NOW)
        whenever(clock.zone).thenReturn(ZoneOffset.UTC)
        whenever(license.get()).thenReturn(ApiResponse.ok(LicenseView("saas", "VALID", "acme", "lic_1", NOW.plusSeconds(86400L * 30), 30, listOf(), null, null, null)))
        whenever(chain.overview(anyInt())).thenReturn(intact())
        whenever(chain.listIncidents()).thenReturn(listOf())
    }

    private fun getFrag(url: String, a: Authentication): MvcResult = mvc.perform(htmx(get(URI.create(url)), a)).andReturn()

    private fun postFrag(url: String, a: Authentication): MvcResult = mvc.perform(htmx(post(URI.create(url)), a)).andReturn()

    private fun overviewDoc(): Document {
        val r = getFrag(AuditChainPageController.OVERVIEW_PATH, platform())
        assertThat(status(r)).isEqualTo(200)
        return doc(r)
    }

    // ── 접근 ────────────────────────────────────────────────

    @Test
    @DisplayName("페이지는 골격만(스켈레톤 + load 로 개요 요청) — 개요 검증은 부르지 않는다")
    fun pageIsSkeleton() {
        val r = mvc.perform(get("/admin/audit-chain").with(authentication(platform()))).andReturn()
        assertThat(status(r)).isEqualTo(200)
        val root = doc(r).selectFirst("#chain-root")
        assertThat(root).isNotNull()
        root!!
        assertThat(root.attr("hx-get")).isEqualTo(OVERVIEW + "?first=1")
        assertThat(root.attr("hx-trigger")).isEqualTo("load")
        assertThat(root.attr("hx-target")).isEqualTo("this")
        assertThat(root.attr("hx-swap")).isEqualTo("outerHTML")
        // 골격에는 [name=view] 가 없다 — 없는 요소를 가리키는 hx-include 는 htmx 경고를 낸다.
        assertThat(root.hasAttr("hx-include")).isFalse()
        assertThat(doc(r).title()).contains("감사 체인 모니터")
        assertThat(root.select("[role=status][aria-busy=true]")).isNotEmpty()
        verify(chain, never()).overview(anyInt())
    }

    @Test
    @DisplayName("PLATFORM 전용 — RP 는 페이지는 자기 홈으로, 모든 조각·동작은 403 이고 REST 를 부르지 않는다")
    fun platformOnly() {
        val page = mvc.perform(get("/admin/audit-chain").with(authentication(rp()))).andReturn()
        assertThat(status(page)).isEqualTo(302)
        assertThat(page.response.redirectedUrl).isEqualTo("/admin/tenants/" + RP1)

        assertThat(status(getFrag(OVERVIEW, rp()))).isEqualTo(403)
        assertThat(status(getFrag(AuditChainPageController.VERIFY_PATH + "?tenantId=" + T1, rp()))).isEqualTo(403)
        assertThat(status(mvc.perform(confirm(post(URI.create(AuditChainPageController.BACKFILL_PATH)), rp())).andReturn())).isEqualTo(403)
        assertThat(status(postFrag(INCIDENTS + "?tenantId=" + T1, rp()))).isEqualTo(403)
        assertThat(status(mvc.perform(htmx(post(URI.create(RESOLVE)), rp()).param("id", INC1.toString()).param("note", "x")).andReturn())).isEqualTo(403)
        verify(chain, never()).overview(anyInt())
        verify(chain, never()).verifyTenant(anyOrNull())
        verify(chain, never()).backfill()
        verify(chain, never()).listIncidents()
        verify(chain, never()).createIncident(anyOrNull(), anyOrNull())
        verify(chain, never()).resolveIncident(anyOrNull(), anyOrNull(), anyOrNull())
    }

    // ── 개요 ────────────────────────────────────────────────

    @Test
    @DisplayName("정상 개요: 전체 상태·KPI·표·빈 사건 상태, 백필 메뉴 활성, 조각만(레이아웃 없음)")
    fun overviewIntact() {
        val r = getFrag(OVERVIEW, platform())
        val d = doc(r)
        verify(chain).overview(24)
        assertThat(d.selectFirst("html > head > title")).isNull()
        assertThat(d.selectFirst("#chain-root")!!.hasClass("page")).isTrue()
        assertThat(d.select("section[aria-label=전체 상태] strong").text()).isEqualTo("모든 tenant 정상 · 2 / 2")
        assertThat(d.select("section[aria-label=전체 상태]").text()).contains("마지막 검증 3분 전")
        assertThat(d.select(".kpi").eachText()).anyMatch { t -> t.contains("정상 / 전체") && t.contains("2 / 2") }
        assertThat(d.select(".kpi").text()).contains("검증된 audit row", "1,205", "검증 방식", "요청 시", "화면 진입 · 지금 검증", "검증 소요", "42ms")
        assertThat(d.select("tbody tr[data-tampered]")).hasSize(2)
        assertThat(d.select("tbody tr[data-tampered]").first()!!.text()).contains("P", "[platform]", "5", "정상")
        assertThat(d.select("[role=alert]").text()).doesNotContain("위변조 의심 —")
        assertThat(d.select("#chain-incidents").text()).contains("등록된 incident가 없습니다", "모든 chain 이 정상입니다.")
        assertThat(d.select("#chain-incidents .badge")).isEmpty()
        // 백필: 활성 메뉴 항목 + 확인 다이얼로그 존재
        val item = d.selectFirst("[role=menuitem]")!!
        assertThat(item.text()).contains("체인 백필…")
        assertThat(item.attr("aria-disabled")).isEmpty()
        assertThat(body(r)).contains("chain-backfill")
        // 검증 버튼은 "지금 검증" 하나
        assertThat(d.select("button.btn--primary").eachText().stream().filter { t -> t.contains("검증") }.toList()).containsExactly("지금 검증")
    }

    @Test
    @DisplayName("위변조: bad 배너(모든 tenant 나열·entry 끝 8자) + Incident 생성 + 감사 로그 열기, 백필 메뉴는 비활성+사유이고 다이얼로그가 없다")
    fun tamperedBannerAndBackfillDisabled() {
        whenever(chain.overview(anyInt())).thenReturn(tampered())
        val r = getFrag(OVERVIEW, platform())
        val d = doc(r)
        assertThat(d.select("section[aria-label=전체 상태] strong").text()).isEqualTo("1개 tenant 위변조 의심 · 1 / 2 정상")
        val banner = d.selectFirst(".banner--danger[role=alert]")!!
        assertThat(banner.selectFirst(".banner__title")!!.text()).isEqualTo("위변조 의심 — 1개 tenant 모두 조사가 필요합니다")
        assertThat(banner.text()).contains("Globex", "chain 에서 hash 불일치가 감지되었습니다.", "불일치 entry …0000abcd")
        assertThat(banner.selectFirst("a[href=/admin/tenants/" + T2 + "?tab=audit]")!!.text()).contains("감사 로그 열기")
        val create = banner.selectFirst("button[data-dlg=chain-ic-" + T2 + "]")!!
        assertThat(create.text()).isEqualTo("Incident 생성")
        assertThat(create.hasAttr("disabled")).isFalse()
        // 위변조만 필터용 데이터
        assertThat(d.select("tbody tr[data-tampered=1]")).hasSize(1)
        // 백필
        val item = d.selectFirst("[role=menuitem]")!!
        assertThat(item.attr("aria-disabled")).isEqualTo("true")
        assertThat(item.text()).contains("위변조 의심 tenant가 있으면 쓸 수 없습니다. 먼저 조사·해결하세요.")
        assertThat(item.hasAttr("x-on:click.capture")).isFalse()
        assertThat(body(r)).doesNotContain("chain-backfill")
    }

    @Test
    @DisplayName("플랫폼 체인은 Incident 대상이 아니다(생성 버튼 비활성+사유) / OPEN incident 가 있으면 생성 대신 \"Incident 처리 중\"")
    fun createButtonRules() {
        whenever(chain.overview(anyInt())).thenReturn(ov(listOf(ten(null, "[platform]", false, 5, ENTRY), ten(T2, "Globex", false, 9, ENTRY))))
        whenever(chain.listIncidents()).thenReturn(listOf(inc(INC1, T2, "Globex", "OPEN")))
        val d = overviewDoc()
        val banner = d.selectFirst(".banner--danger")!!
        assertThat(banner.select("li")).hasSize(2)
        val platformLi = banner.select("li")[0]
        val btn = platformLi.selectFirst("button.btn--danger-solid")!!
        assertThat(btn.hasAttr("disabled")).isTrue()
        assertThat(btn.attr("title")).isEqualTo("플랫폼 체인은 incident 대상이 아닙니다")
        assertThat(platformLi.selectFirst("a")).isNull() // 감사 로그 열기는 비활성 버튼
        val globexLi = banner.select("li")[1]
        assertThat(globexLi.text()).contains("Incident 처리 중")
        assertThat(globexLi.select("button.btn--danger-solid")).isEmpty()
        assertThat(d.select("#chain-incidents .card__head .badge").text()).isEqualTo("처리중 1")
    }

    @Test
    @DisplayName("Incident 목록 실패는 빈 상태로 오인시키지 않는다 — 오류 알림 + 다시 시도(개요 전체), 생성 버튼은 비활성")
    fun incidentListFailureIsNotEmpty() {
        whenever(chain.overview(anyInt())).thenReturn(tampered())
        whenever(chain.listIncidents()).thenThrow(RuntimeException("boom"))
        val d = overviewDoc()
        val card = d.selectFirst("#chain-incidents")!!
        assertThat(card.text()).contains("Incident 목록을 불러오지 못했습니다.")
        assertThat(card.text()).doesNotContain("등록된 incident가 없습니다")
        val retry = card.selectFirst("button")!!
        assertThat(retry.text()).contains("다시 시도")
        assertThat(retry.attr("hx-get")).isEqualTo(INCIDENTS) // 목록만 다시 읽는다
        val create = d.selectFirst(".banner--danger button.btn--danger-solid")!!
        assertThat(create.hasAttr("disabled")).isTrue()
        assertThat(create.attr("title")).isEqualTo("Incident 목록을 확인한 뒤 생성할 수 있습니다")
    }

    @Test
    @DisplayName("사건 표: 상태·tenant·유형·생성시각(KST 분)·생성자, OPEN 만 해결 버튼(다이얼로그 id)")
    fun incidentTable() {
        whenever(chain.listIncidents()).thenReturn(listOf(inc(INC1, T2, "Globex", "OPEN"), inc(UUID.randomUUID(), T1, "Acme", "RESOLVED")))
        val d = overviewDoc()
        val rows = d.select("#chain-incidents tbody tr")
        assertThat(rows).hasSize(2)
        assertThat(rows[0].text()).contains("처리중", "Globex", "AUDIT_CHAIN_TAMPERED", "2026-10-11 11:00", "op@example.com", "해결")
        assertThat(rows[0].selectFirst("button")!!.attr("data-iid")).isEqualTo(INC1.toString())
        assertThat(rows[0].selectFirst("button")!!.attr("data-label")).isEqualTo("Globex · AUDIT_CHAIN_TAMPERED")
        assertThat(rows[1].text()).contains("해결", "Acme")
        assertThat(rows[1].select("button")).isEmpty()
        assertThat(d.select("#chain-incidents .card__head .badge").text()).isEqualTo("처리중 1")
        // 해결 다이얼로그: 메모 필수 textarea(maxlength 1024), 요청 요소는 다이얼로그 밖의 form
        val form = d.selectFirst("form#chain-resolve-form")!!
        assertThat(form.attr("hx-post")).isEqualTo(RESOLVE)
        assertThat(form.html()).contains("name=\"id\"", "name=\"note\"")
        assertThat(form.attr("hx-trigger")).isEqualTo("rs-submit")
    }

    @Test
    @DisplayName("이름·이메일·tenant 이름은 이스케이프된다(th:text)")
    fun escapesUserValues() {
        whenever(chain.overview(anyInt())).thenReturn(ov(listOf(ten(T1, "<script>alert(1)</script>", false, 1, ENTRY))))
        val i = IncidentDto.IncidentView(
            INC1.toString(), T1.toString(), "<img src=x onerror=alert(2)>", null, "T", "HIGH", "OPEN", null,
            "2026-10-11T02:00:00Z", "<b>x</b>@e.com", null, null, null,
        )
        whenever(chain.listIncidents()).thenReturn(listOf(i))
        val html = body(getFrag(OVERVIEW, platform()))
        assertThat(html).doesNotContain("<script>alert(1)").doesNotContain("<img src=x").doesNotContain("<b>x</b>")
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;")
    }

    @Test
    @DisplayName("처음 불러오기 실패는 오류 카드(422) + 다시 시도, 원문은 싣지 않는다")
    fun firstLoadFailure() {
        whenever(chain.overview(anyInt())).thenThrow(RuntimeException("secret internal detail"))
        val r = getFrag(OVERVIEW + "?first=1", platform())
        assertThat(status(r)).isEqualTo(422)
        val d = doc(r)
        assertThat(d.selectFirst("#chain-root [role=alert]")!!.text()).contains("감사 체인 상태를 불러오지 못했습니다")
        assertThat(body(r)).doesNotContain("secret internal detail")
        val retry = d.selectFirst("#chain-root button.btn--primary")!!
        assertThat(retry.attr("hx-get")).isEqualTo(OVERVIEW + "?first=1")
        assertThat(retry.attr("hx-target")).isEqualTo("#chain-root")
        assertThat(retry.hasAttr("hx-include")).isFalse()
        assertThat(d.select("h1").text()).isEqualTo("감사 체인 모니터")
    }

    @Test
    @DisplayName("다시 검증 실패는 이전 결과를 남기고(HX-Retarget #chain-warn) 경고 + 다시 시도만 — 토스트 없음")
    fun reverifyFailureKeepsPreviousResult() {
        whenever(chain.overview(anyInt())).thenThrow(BusinessException(ErrorCode.INTERNAL_SERVER_ERROR))
        val r = getFrag(OVERVIEW, platform())
        assertThat(status(r)).isEqualTo(422)
        assertThat(r.response.getHeader("HX-Retarget")).isEqualTo("#chain-warn")
        assertThat(r.response.getHeader("HX-Reswap")).isEqualTo("innerHTML")
        val t = trigger(r)
        assertThat(t == null || !t.has("toast")).isTrue()
        val d = doc(r)
        assertThat(d.select(".banner__title").text()).isEqualTo("다시 검증하지 못했습니다")
        assertThat(d.text()).contains("검증 결과입니다.")
        assertThat(d.selectFirst("button")!!.attr("hx-get")).isEqualTo(OVERVIEW)
        assertThat(body(r)).contains("chain-failed")
    }

    // ── 요청 속성(중복 방지·타임아웃) ─────────────────────────

    @Test
    @DisplayName("검증·재검증·행 검증 요청은 같은 동기화 그룹(drop)·120초 타임아웃·비활성 표식을 가진다(더블 클릭 중복 방지)")
    fun verifyRequestsAreSyncedAndTimeboxed() {
        val d = overviewDoc()
        val reverify = d.selectFirst("button.btn--primary[hx-get]")!!
        assertThat(reverify.attr("hx-get")).isEqualTo(OVERVIEW)
        assertThat(reverify.attr("hx-target")).isEqualTo("#chain-root")
        assertThat(reverify.attr("hx-swap")).isEqualTo("outerHTML")
        assertThat(reverify.attr("hx-sync")).isEqualTo("#chain-root:drop")
        assertThat(reverify.attr("hx-request")).isEqualTo("{\"timeout\": 120000}")
        assertThat(reverify.attr("hx-disabled-elt")).isEqualTo("this")
        assertThat(reverify.hasAttr("data-chain-verify")).isTrue()
        assertThat(reverify.attr("hx-include")).contains("[name=view]")
        val rowVerify = d.selectFirst("tbody button[data-vid=" + T1 + "]")!!
        assertThat(rowVerify.attr("hx-get")).isEqualTo(AuditChainPageController.VERIFY_PATH + "?tenantId=" + T1)
        assertThat(rowVerify.attr("hx-sync")).isEqualTo("#chain-root:drop")
        assertThat(rowVerify.attr("hx-request")).contains("120000")
        // 플랫폼 체인 행은 열기·검증이 비활성
        val platformRow = d.select("tbody tr").first()!!
        assertThat(platformRow.select("button[disabled]").eachText()).containsExactly("열기", "검증")
        // 루트에는 컨테이너 hx-target/swap/sync 가 없다(안의 링크가 상속하지 않는다)
        val root = d.selectFirst("#chain-root")!!
        assertThat(root.hasAttr("hx-target") || root.hasAttr("hx-swap") || root.hasAttr("hx-sync") || root.hasAttr("hx-get")).isFalse()
    }

    // ── 한 tenant 검증 ──────────────────────────────────────

    @Test
    @DisplayName("tenant 검증: 정상이면 \"검증 완료\" 토스트 + 재검증 결과 교체")
    fun verifyTenantIntact() {
        whenever(chain.verifyTenant(T1.toString())).thenReturn(AuditChainMonitorController.TenantVerifyResponse(T1, true, null, NOW))
        val r = getFrag(AuditChainPageController.VERIFY_PATH + "?tenantId=" + T1, platform())
        assertThat(status(r)).isEqualTo(200)
        val toast = trigger(r)!!.get("toast")
        assertThat(toast.get("kind").asString()).isEqualTo("success")
        assertThat(toast.get("title").asString()).isEqualTo("검증 완료")
        assertThat(toast.get("message").asString()).isEqualTo("chain 이 정상입니다.")
        verify(chain).verifyTenant(T1.toString())
        verify(chain).overview(24)
        assertThat(doc(r).selectFirst("#chain-root")).isNotNull()
    }

    @Test
    @DisplayName("tenant 검증: 위변조면 \"위변조 의심\" 오류 토스트(불일치 entry)")
    fun verifyTenantTampered() {
        whenever(chain.verifyTenant(T2.toString())).thenReturn(AuditChainMonitorController.TenantVerifyResponse(T2, false, ENTRY, NOW))
        val toast = trigger(getFrag(AuditChainPageController.VERIFY_PATH + "?tenantId=" + T2, platform()))!!.get("toast")
        assertThat(toast.get("kind").asString()).isEqualTo("error")
        assertThat(toast.get("title").asString()).isEqualTo("위변조 의심")
        assertThat(toast.get("message").asString()).isEqualTo("불일치 entry " + ENTRY)
    }

    @Test
    @DisplayName("tenant 검증 실패 토스트는 쉬운 문구, 원문은 \"자세히\"(detail)에도 상태만 — 본문 없음, 루트 교체 안 함")
    fun verifyTenantFailure() {
        whenever(chain.verifyTenant(anyString())).thenThrow(RuntimeException("jdbc: secret"))
        val r = getFrag(AuditChainPageController.VERIFY_PATH + "?tenantId=" + T1, platform())
        assertThat(status(r)).isEqualTo(500)
        assertThat(body(r)).isEmpty()
        val toast = trigger(r)!!.get("toast")
        assertThat(toast.get("title").asString()).isEqualTo("검증하지 못했습니다")
        assertThat(toast.get("message").asString()).doesNotContain("secret")
        assertThat(toast.get("detail").asString()).isEqualTo("HTTP 500")
        verify(chain, never()).overview(anyInt())
    }

    @Test
    @DisplayName("tenant 검증: tenantId 가 UUID 가 아니면 400, REST 호출 없음")
    fun verifyTenantBadId() {
        assertThat(status(getFrag(AuditChainPageController.VERIFY_PATH + "?tenantId=nope", platform()))).isEqualTo(400)
        assertThat(status(getFrag(AuditChainPageController.VERIFY_PATH, platform()))).isEqualTo(400)
        verify(chain, never()).verifyTenant(anyOrNull())
    }

    // ── 백필 ────────────────────────────────────────────────

    private fun backfill(withConfirm: Boolean): MvcResult {
        val b = post(URI.create(AuditChainPageController.BACKFILL_PATH))
        return mvc.perform(if (withConfirm) confirm(b, platform()) else htmx(b, platform())).andReturn()
    }

    @Test
    @DisplayName("백필 다이얼로그는 L3 확인(체크) + X-Confirm 요청, 원본 문구·긴 타임아웃·동기화")
    fun backfillDialogMarkup() {
        val r = getFrag(OVERVIEW, platform())
        val d = doc(r)
        val html = body(r)
        assertThat(html).contains(
            "체인 백필을 실행할까요?", "백필은 검증이 아닙니다. 이전 행과 링크가 맞지 않는 모든 행의 hash 를 다시 계산해 덮어씁니다. 그 뒤의 체인도 모두 다시 연결합니다.",
            "행이 삭제된 체인에 실행하면 빈자리가 다시 이어져, 삭제 흔적(위변조 증거)이 사라집니다. 되돌릴 수 없습니다.",
            "hash 가 비어 있는 기존 데이터를 체인에 편입하는 마이그레이션 용도로만 사용하세요.",
            "위변조 흔적이 사라질 수 있고 되돌릴 수 없다는 것을 이해했습니다", "백필 실행",
        )
        val confirmBtn = Jsoup.parse(html).selectFirst("button[data-confirm]")!!
        assertThat(confirmBtn.attr("hx-post")).isEqualTo(AuditChainPageController.BACKFILL_PATH)
        assertThat(confirmBtn.attr("hx-headers")).contains("X-Confirm")
        assertThat(confirmBtn.attr("hx-target")).isEqualTo("#chain-root")
        assertThat(confirmBtn.attr("hx-sync")).isEqualTo("#chain-root:drop")
        assertThat(confirmBtn.attr("hx-request")).contains("120000")
        assertThat(confirmBtn.hasAttr("data-quiet")).isTrue()
        // 열린 사이 검증이 돌면(busy)·재검증이 실패하면(failed) 다이얼로그 자체가 접힌다: x-if
        val tpl = d.selectFirst("#chain-root > template[x-if]")!!
        assertThat(tpl.attr("x-if")).isEqualTo("!blocked")
    }

    @Test
    @DisplayName("백필 가드: 위변조가 있으면 backfill 을 부르지 않는다(409 + confirmError, 토스트 아님)")
    fun backfillGuardBlocksTampered() {
        whenever(chain.overview(anyInt())).thenReturn(tampered())
        val r = backfill(true)
        assertThat(status(r)).isEqualTo(409)
        verify(chain, never()).backfill()
        val t = trigger(r)!!
        assertThat(t.has("toast")).isFalse()
        assertThat(t.get("confirmError").get("message").asString()).isEqualTo("위변조 의심 tenant가 있으면 쓸 수 없습니다. 먼저 조사·해결하세요.")
    }

    @Test
    @DisplayName("백필 가드: 재검증이 실패하면 backfill 을 부르지 않는다")
    fun backfillGuardBlocksWhenVerifyFails() {
        whenever(chain.overview(anyInt())).thenThrow(RuntimeException("down"))
        val r = backfill(true)
        assertThat(status(r)).isEqualTo(409)
        verify(chain, never()).backfill()
        assertThat(trigger(r)!!.get("confirmError").get("message").asString()).isEqualTo("최근 검증에 실패했습니다. 다시 검증해 정상임을 확인한 뒤 쓸 수 있습니다.")
    }

    @Test
    @DisplayName("backfillGuardBlocksSecondRun — 같은 동기화 그룹(drop)이라 진행 중인 실행에 겹친 요청은 버려지고, 확인 헤더 없으면 400, 서버는 위변조가 생기면 두 번째 실행을 막는다")
    fun backfillGuardBlocksSecondRun() {
        // 더블 제출: 확인 버튼은 #chain-root:drop 그룹(진행 중이면 뒤의 요청을 버림) + 다이얼로그 busy 로 잠긴다. 서비스는 별도 거절 없이 DB 락으로 직렬화한다.
        // 확인 절차(L3 의 X-Confirm) 없이는 실행되지 않는다
        assertThat(status(backfill(false))).isEqualTo(400)
        verify(chain, never()).backfill()
        verify(chain, never()).overview(anyInt())

        assertThat(Jsoup.parse(body(getFrag(OVERVIEW, platform()))).selectFirst("button[data-confirm]")!!.attr("hx-sync")).isEqualTo("#chain-root:drop")
        reset(chain)
        whenever(chain.overview(anyInt())).thenReturn(intact())
        whenever(chain.listIncidents()).thenReturn(listOf())
        whenever(chain.backfill()).thenReturn(AuditChainMonitorController.BackfillResponse(2, 1234, 0))
        val first = backfill(true)
        assertThat(status(first)).isEqualTo(200)
        verify(chain, times(1)).backfill()
        val toast = trigger(first)!!.get("toast")
        assertThat(toast.get("title").asString()).isEqualTo("백필 완료")
        assertThat(toast.get("message").asString()).isEqualTo("tenant 2개 처리 · 행 1,234개 갱신")
        // 성공 후 reverify: 가드용 1회 + 재검증 1회
        verify(chain, times(2)).overview(24)
        assertThat(doc(first).selectFirst("#chain-root")).isNotNull()

        whenever(chain.overview(anyInt())).thenReturn(tampered())
        assertThat(status(backfill(true))).isEqualTo(409)
        verify(chain, times(1)).backfill()
    }

    @Test
    @DisplayName("백필 실패는 확인 다이얼로그 안 인라인(confirmError), 토스트 없음")
    fun backfillFailureIsInline() {
        whenever(chain.backfill()).thenThrow(RuntimeException("jdbc: secret"))
        val r = backfill(true)
        assertThat(status(r)).isEqualTo(500)
        val t = trigger(r)!!
        assertThat(t.has("toast")).isFalse()
        assertThat(t.get("confirmError").get("message").asString()).doesNotContain("secret")
    }

    // ── Incident ────────────────────────────────────────────

    @Test
    @DisplayName("Incident 생성 다이얼로그: tenantId 만 싣는 요청 요소(다이얼로그 밖), 원본 문구")
    fun createDialogMarkup() {
        whenever(chain.overview(anyInt())).thenReturn(tampered())
        val r = getFrag(OVERVIEW, platform())
        val d = doc(r)
        val req = d.selectFirst("#chain-dialogs > div[hx-post]")!!
        assertThat(req.attr("hx-post")).isEqualTo(INCIDENTS + "?tenantId=" + T2)
        assertThat(req.attr("hx-trigger")).isEqualTo("ic-submit")
        assertThat(req.attr("hx-sync")).isEqualTo("#chain-root:drop")
        assertThat(req.attr("hx-target")).isEqualTo("#chain-root")
        val html = body(r)
        assertThat(html).contains(
            "Incident 생성", "Globex 의 위변조 의심을 보안 incident 로 등록하고 대응 이력을 남깁니다.",
            "생성 시점에 서버가 재검증해 위변조 entry 를 기록합니다. 이미 진행 중인 incident 가 있거나 재검증 결과 위변조가 아니면 생성되지 않습니다.",
            "chain-ic-issue-" + T2,
        )
    }

    @Test
    @DisplayName("Incident 생성: tenantId 로 REST 호출 + 성공 토스트 + 재검증 결과 교체")
    fun createIncidentOk() {
        whenever(chain.createIncident(anyOrNull(), anyOrNull())).thenReturn(inc(INC1, T2, "Globex", "OPEN"))
        val r = postFrag(INCIDENTS + "?tenantId=" + T2, platform())
        assertThat(status(r)).isEqualTo(200)
        val cap = argumentCaptor<IncidentDto.CreateRequest>()
        verify(chain).createIncident(cap.capture(), any<Authentication>())
        assertThat(cap.lastValue.tenantId).isEqualTo(T2.toString())
        val toast = trigger(r)!!.get("toast")
        assertThat(toast.get("kind").asString()).isEqualTo("success")
        assertThat(toast.get("title").asString()).isEqualTo("Incident 생성됨")
        assertThat(toast.get("message").asString()).isEqualTo("보안팀 대응 incident 가 등록되었습니다.")
        verify(chain).overview(24)
        assertThat(doc(r).selectFirst("#chain-root")).isNotNull()
    }

    @Test
    @DisplayName("incidentConflictShowsToast — 생성 409(IncidentConflict)는 경고 토스트 \"이미 진행 중\" + 닫고(루트 교체) 목록 동기화")
    fun incidentConflictShowsToast() {
        whenever(chain.createIncident(anyOrNull(), anyOrNull())).thenThrow(IncidentConflictException("open incident already exists"))
        whenever(chain.listIncidents()).thenReturn(listOf(inc(INC1, T2, "Globex", "OPEN")))
        val r = postFrag(INCIDENTS + "?tenantId=" + T2, platform())
        assertThat(status(r)).isEqualTo(200)
        val toast = trigger(r)!!.get("toast")
        assertThat(toast.get("kind").asString()).isEqualTo("warn")
        assertThat(toast.get("title").asString()).isEqualTo("이미 진행 중")
        assertThat(toast.get("message").asString()).isEqualTo("해당 tenant 에 OPEN 상태 incident 가 이미 있습니다.")
        // 목록만 다시 읽는다(원본 refreshIncidents) — 개요 재검증 없음, 본문은 OOB(swap none), 배너 배지도 갱신
        verify(chain).listIncidents()
        verify(chain, never()).overview(anyInt())
        assertThat(r.response.getHeader("HX-Reswap")).isEqualTo("none")
        val d = doc(r)
        assertThat(d.selectFirst("#chain-root")).isNull()
        assertThat(d.selectFirst("#chain-incidents[hx-swap-oob]")).isNotNull()
        assertThat(d.selectFirst("#chain-act-" + T2 + "[hx-swap-oob]")!!.text()).contains("Incident 처리 중")
    }

    @Test
    @DisplayName("Incident 생성 422(IncidentNotTampered)는 열린 다이얼로그 안에 설명(HX-Retarget 슬롯, 422), 토스트 없음")
    fun createNotTamperedInline() {
        whenever(chain.createIncident(anyOrNull(), anyOrNull())).thenThrow(IncidentNotTamperedException("not tampered"))
        val r = postFrag(INCIDENTS + "?tenantId=" + T2, platform())
        assertThat(status(r)).isEqualTo(422)
        assertThat(r.response.getHeader("HX-Retarget")).isEqualTo("#chain-ic-issue-" + T2)
        assertThat(r.response.getHeader("HX-Reswap")).isEqualTo("innerHTML")
        val t = trigger(r)
        assertThat(t == null || !t.has("toast")).isTrue()
        val d = doc(r)
        assertThat(d.select(".banner--warning .banner__title").text()).isEqualTo("위변조가 확인되지 않았습니다")
        assertThat(d.text()).contains("재검증 결과 위변조가 확인되지 않아 incident 를 생성하지 않았습니다.")
    }

    @Test
    @DisplayName("Incident 생성 그 밖의 실패는 다이얼로그 안에 쉬운 문구로, 원문([CODE] message)은 보이지 않는다 + 목록을 다시 읽어 OOB")
    fun createFailureInlineNoRaw() {
        whenever(chain.createIncident(anyOrNull(), anyOrNull())).thenThrow(RuntimeException("jdbc: secret"))
        whenever(chain.listIncidents()).thenReturn(listOf(inc(INC1, T2, "Globex", "OPEN")))
        val r = postFrag(INCIDENTS + "?tenantId=" + T2, platform())
        assertThat(status(r)).isEqualTo(422)
        val d = doc(r)
        assertThat(d.select(".banner--danger[role=alert] .banner__title").text()).isEqualTo("Incident를 생성하지 못했습니다")
        assertThat(body(r)).doesNotContain("secret")
        assertThat(d.selectFirst("#chain-incidents[hx-swap-oob]")).isNotNull()
        assertThat(d.select("#chain-incidents tbody tr")).hasSize(1)
        assertThat(trigger(r) == null || !trigger(r)!!.has("toast")).isTrue()
    }

    @Test
    @DisplayName("Incident 해결: 메모를 trim 해 REST 호출 + 성공 토스트 + 재검증 결과 교체")
    fun resolveOk() {
        whenever(chain.resolveIncident(anyString(), anyOrNull(), anyOrNull())).thenReturn(inc(INC1, T2, "Globex", "RESOLVED"))
        val r = mvc.perform(htmx(post(URI.create(RESOLVE)), platform()).param("id", INC1.toString()).param("note", "  조치 완료  ")).andReturn()
        assertThat(status(r)).isEqualTo(200)
        val cap = argumentCaptor<IncidentDto.ResolveRequest>()
        verify(chain).resolveIncident(eq(INC1.toString()), cap.capture(), any<Authentication>())
        assertThat(cap.lastValue.note).isEqualTo("조치 완료")
        val toast = trigger(r)!!.get("toast")
        assertThat(toast.get("title").asString()).isEqualTo("해결됨")
        assertThat(toast.get("message").asString()).isEqualTo("incident 가 RESOLVED 로 표시되었습니다.")
        verify(chain).overview(24)
    }

    @Test
    @DisplayName("Incident 해결은 메모가 필수 — 공백뿐이면 REST 호출 없이 인라인 안내")
    fun resolveRequiresNote() {
        val r = mvc.perform(htmx(post(URI.create(RESOLVE)), platform()).param("id", INC1.toString()).param("note", "   ")).andReturn()
        assertThat(status(r)).isEqualTo(422)
        assertThat(r.response.getHeader("HX-Retarget")).isEqualTo("#chain-rs-issue")
        verify(chain, never()).resolveIncident(anyOrNull(), anyOrNull(), anyOrNull())
        val tooLong = mvc.perform(htmx(post(URI.create(RESOLVE)), platform()).param("id", INC1.toString()).param("note", "x".repeat(1025))).andReturn()
        assertThat(status(tooLong)).isEqualTo(422)
        verify(chain, never()).resolveIncident(anyOrNull(), anyOrNull(), anyOrNull())
    }

    @Test
    @DisplayName("해결 409(IncidentConflict)는 경고 토스트 \"이미 해결됨\" + 루트 교체")
    fun resolveConflictToast() {
        whenever(chain.resolveIncident(anyString(), anyOrNull(), anyOrNull())).thenThrow(IncidentConflictException("already resolved"))
        val r = mvc.perform(htmx(post(URI.create(RESOLVE)), platform()).param("id", INC1.toString()).param("note", "x")).andReturn()
        assertThat(status(r)).isEqualTo(200)
        val toast = trigger(r)!!.get("toast")
        assertThat(toast.get("kind").asString()).isEqualTo("warn")
        assertThat(toast.get("title").asString()).isEqualTo("이미 해결됨")
        assertThat(toast.get("message").asString()).isEqualTo("해당 incident 는 이미 RESOLVED 상태입니다.")
        verify(chain, never()).overview(anyInt())
        assertThat(r.response.getHeader("HX-Reswap")).isEqualTo("none")
        assertThat(doc(r).selectFirst("#chain-incidents[hx-swap-oob]")).isNotNull()
    }

    @Test
    @DisplayName("해결 404(IncidentNotFound)는 다이얼로그 안 \"이미 처리되었거나 없는 incident입니다\" + 해결 버튼 잠금(resolve-gone) + 목록 새로고침(OOB), 토스트 없음")
    fun resolveNotFoundInline() {
        whenever(chain.resolveIncident(anyString(), anyOrNull(), anyOrNull())).thenThrow(IncidentNotFoundException("gone"))
        val r = mvc.perform(htmx(post(URI.create(RESOLVE)), platform()).param("id", INC1.toString()).param("note", "메모")).andReturn()
        assertThat(status(r)).isEqualTo(422)
        assertThat(r.response.getHeader("HX-Retarget")).isEqualTo("#chain-rs-issue")
        val d = doc(r)
        assertThat(d.select(".banner--warning .banner__title").text()).isEqualTo("이미 처리되었거나 없는 incident입니다")
        assertThat(d.text()).contains("목록을 새로 고쳤습니다. 다이얼로그를 닫고 목록에서 확인하세요.")
        assertThat(body(r)).contains("resolve-gone")
        assertThat(d.selectFirst("#chain-incidents[hx-swap-oob]")).isNotNull()
        assertThat(trigger(r) == null || !trigger(r)!!.has("toast")).isTrue()
    }

    @Test
    @DisplayName("해결 그 밖의 실패는 다이얼로그 안 쉬운 문구, 원문 없음")
    fun resolveFailureInline() {
        whenever(chain.resolveIncident(anyString(), anyOrNull(), anyOrNull())).thenThrow(RuntimeException("jdbc: secret"))
        val r = mvc.perform(htmx(post(URI.create(RESOLVE)), platform()).param("id", INC1.toString()).param("note", "메모")).andReturn()
        assertThat(status(r)).isEqualTo(422)
        assertThat(doc(r).select(".banner--danger .banner__title").text()).isEqualTo("해결로 표시하지 못했습니다")
        assertThat(body(r)).doesNotContain("secret")
    }

    @Test
    @DisplayName("사건 변경 요청은 CSRF 가 필요하다")
    fun mutationsNeedCsrf() {
        val r = mvc.perform(post(URI.create(INCIDENTS + "?tenantId=" + T2)).with(authentication(platform())).header("HX-Request", "true")).andReturn()
        assertThat(status(r)).isEqualTo(403)
        verify(chain, never()).createIncident(anyOrNull(), anyOrNull())
    }

    // ── 월간 보고서 ─────────────────────────────────────────

    @Test
    @DisplayName("monthlyReportLinkPointsToExistingApi — 링크는 기존 GET /admin/api/audit/chain/monthly-report 를 가리키고(공통 다운로드 도우미), 기본 기간은 KST 직전 달")
    fun monthlyReportLinkPointsToExistingApi() {
        val d = overviewDoc()
        val wrap = d.selectFirst("#chain-report-wrap")!!
        assertThat(wrap.attr("data-base")).isEqualTo("/admin/api/audit/chain/monthly-report")
        assertThat(wrap.attr("data-from")).isEqualTo("2026-09-01")
        assertThat(wrap.attr("data-to")).isEqualTo("2026-09-30")
        val link = d.selectFirst("a[data-csv-download]")!!
        assertThat(link.attr("href")).isEqualTo("/admin/api/audit/chain/monthly-report")
        assertThat(link.attr("x-bind:href")).isEqualTo("href")
        assertThat(wrap.attr("x-data")).contains("encodeURIComponent(this.from)", "encodeURIComponent(this.to)", "dataset.base")
        assertThat(link.attr("hx-boost")).isEqualTo("false")
        assertThat(link.hasAttr("download")).isTrue()
        assertThat(link.attr("data-download-error")).isEqualTo("PDF 생성 실패")
        assertThat(link.text()).contains("PDF 생성 → 다운로드")
        // 다이얼로그 문구·포함 tenant 목록(체크박스 없음)
        val html = d.html()
        assertThat(html).contains(
            "월간 무결성 보고서 발급", "기간 내 전체 tenant 의 chain 검증 결과를 PDF 로 묶습니다.", "시작일", "종료일",
            "시작일이 종료일보다 늦습니다. 날짜는 KST 기준입니다.", "포함 tenant · 2개", "1,200 rows",
        )
        assertThat(d.select("#chain-report-wrap input[type=checkbox]")).isEmpty()
        assertThat(d.select("#chain-report-wrap input[type=date]")).hasSize(2)
        // 헤더 버튼
        assertThat(d.select(".page__head .btn").eachText()).anyMatch { t -> t.contains("월간 보고서") }
    }

    @Test
    @DisplayName("위변조만 필터는 표를 클라이언트에서 거른다 — 현재 필터가 재요청에 실려 다시 그려도 유지된다")
    fun viewFilterSurvivesReload() {
        whenever(chain.overview(anyInt())).thenReturn(tampered())
        val r = getFrag(OVERVIEW + "?view=tampered", platform())
        val d = doc(r)
        assertThat(d.selectFirst("#chain-root")!!.attr("data-view")).isEqualTo("tampered")
        val seg = d.selectFirst(".seg")!!
        assertThat(seg.select(".seg__btn--on").text()).isEqualTo("위변조만")
        assertThat(seg.select("input[name=view]").attr("value")).isEqualTo("tampered")
        assertThat(d.select("tbody tr.table__empty")).isEmpty() // 위변조 tenant 가 있으면 빈 메시지 행 없음
        whenever(chain.overview(anyInt())).thenReturn(intact())
        val e = doc(getFrag(OVERVIEW + "?view=tampered", platform()))
        assertThat(e.select("tr.table__empty").text()).contains("위변조 의심 tenant 가 없습니다.")
        // 알 수 없는 값은 all
        assertThat(doc(getFrag(OVERVIEW + "?view=zzz", platform())).selectFirst("#chain-root")!!.attr("data-view")).isEqualTo("all")
    }

    // ── Fix round 1 ─────────────────────────────────────────

    @Test
    @DisplayName("대화상자 진행 상태: 래퍼는 reqBusy, 본문 x-effect 가 dialog 의 busy 로 비추고 버튼·라벨은 reqBusy 를 읽는다(안쪽 busy 가 가리지 않음)")
    fun dialogBusyIsMirroredNotShadowed() {
        whenever(chain.overview(anyInt())).thenReturn(tampered())
        whenever(chain.listIncidents()).thenReturn(listOf(inc(INC1, T2, "Globex", "OPEN")))
        val html = body(getFrag(OVERVIEW, platform()))
        assertThat(html).doesNotContain("x-data=\"{ busy: false }\"")
        val d = Jsoup.parse(html)
        for (wrap in listOf(d.selectFirst("#chain-dialogs > div[hx-post]")!!, d.selectFirst("form#chain-resolve-form")!!)) {
            assertThat(wrap.attr("x-data")).contains("reqBusy: false").doesNotContain(" busy:")
            assertThat(wrap.attr("x-on:htmx:before-request")).contains("reqBusy = true")
            assertThat(wrap.attr("x-on:htmx:after-request")).contains("reqBusy = false").contains("dialog:close")
            assertThat(wrap.select("[x-effect]").attr("x-effect")).isEqualTo("busy = reqBusy")
            for (b in wrap.select(".dialog__foot button")) {
                assertThat(b.attr("x-bind:disabled")).contains("reqBusy")
                assertThat(b.attr("x-on:click")).doesNotContain("dialog:close")
            }
            assertThat(wrap.select(".dialog__foot [x-show=reqBusy]")).hasSize(2 - 1)
        }
    }

    @Test
    @DisplayName("해결 다이얼로그는 하나를 공유한다 — 새로 생긴 OPEN 사건의 해결 버튼도 같은 다이얼로그를 열고 id 는 폼 값으로 간다")
    fun sharedResolveDialog() {
        whenever(chain.listIncidents()).thenReturn(listOf(inc(INC1, T2, "Globex", "OPEN")))
        val d = overviewDoc()
        assertThat(d.select("#chain-dialogs form")).hasSize(1)
        assertThat(d.selectFirst("#chain-incidents tbody button")!!.attr("x-on:click")).contains("resolve-open", "chain-resolve")
        assertThat(d.selectFirst("form#chain-resolve-form")!!.attr("x-on:resolve-open.window")).contains("rsId = \$event.detail.id", "note = ''")
    }

    @Test
    @DisplayName("재검증 요청 표식: 지금 검증·경고의 다시 시도만 data-chain-reverify — 실패(타임아웃·네트워크·비 2xx)는 failed 로 백필을 막는다. 행 검증은 아님")
    fun reverifyMarkersBlockBackfillOnFailure() {
        val d = overviewDoc()
        assertThat(d.selectFirst("button.btn--primary[hx-get]")!!.hasAttr("data-chain-reverify")).isTrue()
        assertThat(d.selectFirst("tbody button[data-vid]")!!.hasAttr("data-chain-reverify")).isFalse()
        val after = d.selectFirst("#chain-root")!!.attr("x-on:htmx:after-request")
        assertThat(after).contains("data-chain-reverify").contains("!\$event.detail.successful").contains("failed = true")
        whenever(chain.overview(anyInt())).thenThrow(RuntimeException("x"))
        assertThat(doc(getFrag(OVERVIEW, platform())).selectFirst("button")!!.hasAttr("data-chain-reverify")).isTrue()
    }

    @Test
    @DisplayName("변경 성공 뒤 재검증이 실패해도 목록·배너는 독립으로 갱신(OOB)하고 다이얼로그는 닫힌다(200) — 낡은 목록 없음")
    fun mutationSuccessThenOverviewFailureRefreshesList() {
        whenever(chain.createIncident(anyOrNull(), anyOrNull())).thenReturn(inc(INC1, T2, "Globex", "OPEN"))
        whenever(chain.overview(anyInt())).thenThrow(RuntimeException("down"))
        whenever(chain.listIncidents()).thenReturn(listOf(inc(INC1, T2, "Globex", "OPEN")))
        val r = postFrag(INCIDENTS + "?tenantId=" + T2, platform())
        assertThat(status(r)).isEqualTo(200) // 요청 요소의 after-request successful → 래퍼가 다이얼로그를 닫는다
        assertThat(r.response.getHeader("HX-Retarget")).isEqualTo("#chain-warn")
        val d = doc(r)
        assertThat(d.select(".banner__title").text()).isEqualTo("다시 검증하지 못했습니다")
        assertThat(d.selectFirst("#chain-incidents[hx-swap-oob]")!!.text()).contains("Globex")
        assertThat(d.selectFirst("#chain-act-" + T2 + "[hx-swap-oob]")!!.text()).contains("Incident 처리 중")
        assertThat(trigger(r)!!.get("toast").get("title").asString()).isEqualTo("Incident 생성됨")
    }

    @Test
    @DisplayName("목록 다시 시도: 목록만 다시 읽는다(개요 재검증 없음, swap none). 실패하면 카드가 오류 상태 + incidentsKnown=false")
    fun incidentRetryRefreshesListOnly() {
        whenever(chain.listIncidents()).thenThrow(RuntimeException("x"))
        val d = overviewDoc()
        val retryBtn = d.selectFirst("#chain-incidents button")!!
        assertThat(retryBtn.attr("hx-get")).isEqualTo(INCIDENTS)
        assertThat(retryBtn.attr("hx-swap")).isEqualTo("none")
        reset(chain)
        whenever(chain.listIncidents()).thenReturn(listOf())
        val ok = getFrag(INCIDENTS, platform())
        assertThat(status(ok)).isEqualTo(200)
        verify(chain, never()).overview(anyInt())
        assertThat(ok.response.getHeader("HX-Reswap")).isEqualTo("none")
        val o = doc(ok)
        assertThat(o.selectFirst("#chain-incidents[hx-swap-oob]")!!.text()).contains("등록된 incident가 없습니다")
        assertThat(body(ok)).contains("incidents-known', true")
        whenever(chain.listIncidents()).thenThrow(RuntimeException("x"))
        val bad = getFrag(INCIDENTS, platform())
        assertThat(doc(bad).selectFirst("#chain-incidents")!!.text()).contains("Incident 목록을 불러오지 못했습니다.")
        assertThat(body(bad)).contains("incidents-known', false")
        assertThat(status(getFrag(INCIDENTS, rp()))).isEqualTo(403)
    }

    @Test
    @DisplayName("생성 버튼은 목록을 확인하기 전에는 비활성(Alpine incidentsKnown), 플랫폼 체인 행은 항상 비활성이고 바인딩이 없다")
    fun createButtonBindings() {
        whenever(chain.overview(anyInt())).thenReturn(ov(listOf(ten(null, "[platform]", false, 5, ENTRY), ten(T2, "Globex", false, 9, ENTRY))))
        val d = overviewDoc()
        val plat = d.select(".banner--danger li")[0].selectFirst("button.btn--danger-solid")!!
        assertThat(plat.hasAttr("x-bind:disabled")).isFalse()
        val globex = d.select(".banner--danger li")[1].selectFirst("button.btn--danger-solid")!!
        assertThat(globex.attr("x-bind:disabled")).isEqualTo("!incidentsKnown")
        assertThat(d.selectFirst("#chain-root")!!.attr("x-data")).contains("incidentsKnown")
    }

    @Test
    @DisplayName("백필 메뉴 항목: 검증 중·재검증 실패에는 Alpine 이 aria-disabled + 사유(title)로 바꾸고 클릭은 다이얼로그를 열지 않는다(토스트를 지어내지 않음)")
    fun backfillMenuItemTracksBlocked() {
        val d = overviewDoc()
        val item = d.selectFirst("[role=menuitem]")!!
        assertThat(item.attr("x-bind:aria-disabled")).isEqualTo("blocked ? 'true' : null")
        assertThat(item.attr("x-bind:title")).isEqualTo("blocked ? blockedReason : null")
        assertThat(item.attr("x-on:click.capture")).startsWith("if (!blocked)").doesNotContain("toast")
        assertThat(d.selectFirst("#chain-root")!!.attr("x-data")).contains("검증 중에는 쓸 수 없습니다. 검증이 끝난 뒤 다시 시도하세요.", "최근 검증에 실패했습니다. 다시 검증해 정상임을 확인한 뒤 쓸 수 있습니다.")
    }

    @Test
    @DisplayName("PDF 링크만 120초 타임아웃을 선택한다(CSV 링크는 그대로)")
    fun pdfLinkOptsIntoTimeout() {
        assertThat(overviewDoc().selectFirst("a[data-csv-download]")!!.attr("data-download-timeout")).isEqualTo("120000")
    }

    // ── Fix round 2 ─────────────────────────────────────────

    @Test
    @DisplayName("incidentsKnown 상태 전달은 OOB 싱크(#chain-known-sink)로 — refresh 성공·실패, 인라인 알림, 변경 후 경고 응답 모두, 루트에는 싱크 자리가 있다")
    fun knownStateCarrierIsOob() {
        assertThat(overviewDoc().select("#chain-root #chain-known-sink")).hasSize(1)
        // refresh 성공 / 실패 (swap none 이라 OOB 가 아닌 요소는 버려진다)
        val ok = doc(getFrag(INCIDENTS, platform())).selectFirst("#chain-known-sink[hx-swap-oob]")!!
        assertThat(ok.attr("x-init")).isEqualTo("\$dispatch('incidents-known', true)")
        whenever(chain.listIncidents()).thenThrow(RuntimeException("x"))
        val bad = doc(getFrag(INCIDENTS, platform())).selectFirst("#chain-known-sink[hx-swap-oob]")!!
        assertThat(bad.attr("x-init")).isEqualTo("\$dispatch('incidents-known', false)")
        // 인라인 알림(422)
        reset(chain)
        whenever(chain.createIncident(anyOrNull(), anyOrNull())).thenThrow(IncidentNotTamperedException("n"))
        whenever(chain.listIncidents()).thenThrow(RuntimeException("x"))
        assertThat(doc(postFrag(INCIDENTS + "?tenantId=" + T2, platform())).selectFirst("#chain-known-sink[hx-swap-oob]")!!.attr("x-init")).contains("false")
        reset(chain)
        whenever(chain.createIncident(anyOrNull(), anyOrNull())).thenThrow(IncidentNotTamperedException("n"))
        whenever(chain.listIncidents()).thenReturn(listOf())
        assertThat(doc(postFrag(INCIDENTS + "?tenantId=" + T2, platform())).selectFirst("#chain-known-sink[hx-swap-oob]")!!.attr("x-init")).contains("true")
        // 변경 성공 뒤 재검증 실패
        reset(chain)
        whenever(chain.createIncident(anyOrNull(), anyOrNull())).thenReturn(inc(INC1, T2, "Globex", "OPEN"))
        whenever(chain.listIncidents()).thenReturn(listOf())
        whenever(chain.overview(anyInt())).thenThrow(RuntimeException("down"))
        assertThat(doc(postFrag(INCIDENTS + "?tenantId=" + T2, platform())).selectFirst("#chain-known-sink[hx-swap-oob]")!!.attr("x-init")).contains("true")
        // 어느 응답에도 OOB 가 아닌 incidents-known 디스패치는 없다
        assertThat(doc(getFrag(INCIDENTS, platform())).select("[x-init*=incidents-known]:not([hx-swap-oob])")).isEmpty()
    }

    @Test
    @DisplayName("생성 다이얼로그를 닫으면 포커스가 떨어져 나간 버튼 대신 그 tenant 의 새 버튼으로 돌아간다(data-tid 조회)")
    fun createDialogRestoresFocusToReplacedButton() {
        whenever(chain.overview(anyInt())).thenReturn(tampered())
        val wrap = overviewDoc().selectFirst("#chain-dialogs > div[hx-post]")!!
        assertThat(wrap.attr("data-tid")).isEqualTo(T2.toString())
        assertThat(wrap.attr("x-on:dialog:close.window")).contains("#chain-act-", "dataset.tid", ".focus()")
    }
}
