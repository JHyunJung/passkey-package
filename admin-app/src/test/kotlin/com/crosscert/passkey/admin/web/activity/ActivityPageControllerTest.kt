package com.crosscert.passkey.admin.web.activity

import com.crosscert.passkey.admin.activity.ActivityController
import com.crosscert.passkey.admin.activity.ActivityDetailView
import com.crosscert.passkey.admin.activity.ActivityView
import com.crosscert.passkey.admin.audit.AuditChainMonitorController
import com.crosscert.passkey.admin.auth.ActiveTenantController
import com.crosscert.passkey.admin.auth.AdminUserDetails
import com.crosscert.passkey.admin.license.LicenseController
import com.crosscert.passkey.admin.license.LicenseView
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
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.never
import org.mockito.kotlin.reset
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
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.regex.Pattern

/**
 * 활동 화면. 원본: pages/ActivityPage.test.tsx, activityDiff.test.ts, activityLabels.test.ts(후 둘은 format/ 테스트).
 * 브라우저에서 도는 부분(Alpine 상태·htmx 의 실제 교체·폴링 타이머)은 속성·소스 수준까지만 본다.
 *
 * (Kotlin 전환) 즉시 역참조하는 nullable jsoup 결과(`first`)의 `!!` 는 원 Java 의 NPE 실패 지점과 같다.
 * Java `String.strip()` 은 `trim { Character.isWhitespace(it) }`, `String.split("\n")` 은 `Pattern.split` 로 같은 의미를 지킨다.
 */
@AdminWebSliceTest(controllers = [ActivityPageController::class])
class ActivityPageControllerTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var clock: Clock
    @MockitoBean lateinit var activity: ActivityController
    @MockitoBean lateinit var tenants: TenantAdminController
    @MockitoBean lateinit var license: LicenseController
    @MockitoBean lateinit var chain: AuditChainMonitorController
    @MockitoBean lateinit var activeTenants: ActiveTenantController

    companion object {
        val NOW: Instant = Instant.parse("2026-10-11T03:00:00Z") // KST 2026-10-11 12:00
        val RP1: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
        val T1: UUID = UUID.fromString("00000000-0000-0000-0000-000000000011")

        fun id(n: Int): UUID = UUID.fromString(String.format("00000000-0000-0000-0000-%012d", n))

        val E1: UUID = id(101)
        val E2: UUID = id(102)
        val E3: UUID = id(103)

        // ── fixtures ────────────────────────────────────────────

        fun platform(): Authentication = auth("PLATFORM_OPERATOR", setOf())
        fun rp(): Authentication = auth("RP_ADMIN", setOf(RP1))

        private fun auth(role: String, tenants: Set<UUID>): Authentication {
            val p = AdminUserDetails(UUID.randomUUID(), "alice@example.com", "x", role, tenants, true, null, Clock.systemUTC())
            return UsernamePasswordAuthenticationToken(p, "x", p.authorities)
        }

        fun ev(id: UUID, createdAt: String, action: String, category: String, tenantId: UUID?, slug: String?, actor: String?): ActivityView.Event =
            ActivityView.Event(id, action, actor, "TENANT", "tid-0000-1111-2222", tenantId, slug, OffsetDateTime.parse(createdAt), category)

        fun view(feed: List<ActivityView.Event>): ActivityView =
            ActivityView(ActivityView.Kpi(3, 2, 1, null), listOf(ActivityView.TopTenant(T1, "acme", 3)), feed)

        val FEED: List<ActivityView.Event> = listOf(
            ev(E1, "2026-10-11T02:48:00Z", "TENANT_UPDATE", "ops", T1, "acme", "op@x.com"), // 오늘 11:48
            ev(E2, "2026-10-10T09:00:00Z", "ADMIN_LOGIN_FAILED", "security", null, null, null), // 어제 18:00
            ev(E3, "2026-10-08T01:00:00Z", "API_KEY_ISSUE", "ops", T1, "acme", "op@x.com"), // 2026-10-08 10:00
        )

        private fun enc(s: String): String = java.net.URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20")

        private fun body(r: MvcResult): String = r.response.getContentAsString(StandardCharsets.UTF_8)

        private fun doc(r: MvcResult): Document = Jsoup.parse(body(r))

        private fun rowTypes(d: Document, scope: String): List<String> = d.select(scope + " .feed-row .tag").eachText()

        private fun strip(s: String): String = s.trim { Character.isWhitespace(it) }

        private val NEWLINE: Pattern = Pattern.compile("\n")

        // ── 상세 ────────────────────────────────────────────────

        const val PAYLOAD = "{\"before\":{\"webauthnTimeoutMs\":60000,\"allowedOrigins\":[\"https://acme.com\"]}," +
            "\"after\":{\"webauthnTimeoutMs\":120000,\"allowedOrigins\":[\"https://acme.com\",\"https://b.acme.com\"]}," +
            "\"changedFields\":[\"allowedOrigins\",\"webauthnTimeoutMs\"]}"

        fun detail(actor: String?, payload: String?): ActivityDetailView =
            ActivityDetailView(
                E1, "TENANT_UPDATE", UUID.randomUUID(), actor, "TENANT", "tid-0000-1111-2222", T1, "acme",
                OffsetDateTime.parse("2026-10-11T02:48:00Z"), "ops", payload,
            )

        /** 50건짜리 쪽. 쪽 번호 p 의 행 id 는 10000 + p*50 + i, 시각은 p·i 가 클수록 과거. */
        private fun fullPage(p: Int): List<ActivityView.Event> {
            val out = java.util.ArrayList<ActivityView.Event>()
            for (i in 0 until 50) {
                out.add(ev(id(10000 + p * 50 + i), NOW.minusSeconds(60L * (p * 50 + i + 1)).toString(), "TENANT_UPDATE", "ops", T1, "acme", "op@x.com"))
            }
            return out
        }

        // ── 헬퍼 ────────────────────────────────────────────────

        fun tenant(id: UUID, name: String, slug: String): TenantAdminDto.TenantView =
            TenantAdminDto.TenantView(
                id, slug, name, "ACTIVE", slug + ".example.org", name, listOf("https://" + slug + ".example.org"),
                setOf("none"), true, false, "NONE", 60000, 0, 0, null,
                OffsetDateTime.parse("2026-10-01T00:00:00Z"), OffsetDateTime.parse("2026-10-01T00:00:00Z"),
            )
    }

    @BeforeEach
    fun setUp() {
        whenever(clock.instant()).thenReturn(NOW)
        whenever(clock.zone).thenReturn(ZoneOffset.UTC)
        whenever(license.get()).thenReturn(ApiResponse.ok(LicenseView("saas", "VALID", "acme", "lic_1", NOW.plusSeconds(86400L * 30), 30, listOf(), null, null, null)))
        whenever(tenants.list()).thenReturn(ApiResponse.ok(listOf()))
        feed(view(FEED))
    }

    private fun feed(v: ActivityView) {
        whenever(activity.activity(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(ApiResponse.ok(v))
    }

    private fun call(url: String, a: Authentication): MvcResult =
        mvc.perform(get(URI.create(url)).with(authentication(a)).header("HX-Request", "true")).andReturn()

    private fun frag(url: String): MvcResult = call(url, platform())

    private fun page(url: String): Document {
        val r = mvc.perform(get(URI.create(url)).with(authentication(platform()))).andReturn()
        assertThat(r.response.status).`as`(url).isEqualTo(200)
        return doc(r)
    }

    // ── 권한 ────────────────────────────────────────────────

    @Test
    @DisplayName("rpAdminRedirectedHome: RP_ADMIN 은 전체 페이지에서 자기 tenant 로 보내지고 REST 를 부르지 않는다")
    fun rpAdminRedirectedHome() {
        val r = mvc.perform(get("/admin/activity").with(authentication(rp()))).andReturn()
        assertThat(r.response.status).isEqualTo(302)
        assertThat(r.response.redirectedUrl).isEqualTo("/admin/tenants/" + RP1)
        verify(activity, never()).activity(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
    }

    @Test
    @DisplayName("조각·상세·CSV 도 PLATFORM 전용(RP_ADMIN 403, REST 미호출)")
    fun fragmentsRejectRpAdmin() {
        for (
            u in listOf(
                "/admin/_/activity/rows", "/admin/_/activity/poll", "/admin/_/activity/more?before=2026-10-11T02:48:00Z",
                "/admin/_/activity/" + E1, "/admin/_/activity/export",
            )
        ) {
            val r = call(u, rp())
            assertThat(r.response.status).`as`(u).isEqualTo(403)
        }
        verify(activity, never()).activity(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
        verify(activity, never()).detail(anyOrNull())
    }

    @Test
    @DisplayName("미인증은 로그인으로(화면·조각 모두)")
    fun unauthenticated() {
        assertThat(mvc.perform(get("/admin/activity")).andReturn().response.status).isIn(302, 401)
        assertThat(mvc.perform(get("/admin/_/activity/rows").header("HX-Request", "true")).andReturn().response.status).isIn(302, 401)
    }

    // ── 화면 ────────────────────────────────────────────────

    @Test
    @DisplayName("피드를 KST 날짜(오늘/어제/YYYY-MM-DD)로 묶고, KPI·활발한 Tenant 를 그린다")
    fun groupsByKstDay() {
        val d = page("/admin/activity")
        assertThat(d.select("title").text()).contains("Activity")
        // 원본 groupByDay: KST 날짜 그룹(role=group, aria-label=라벨), 머리글 + 그 날짜의 행 목록
        assertThat(d.select("#activity-feed > .feed-group").eachAttr("aria-label")).containsExactly("오늘", "어제", "2026-10-08")
        assertThat(d.select("#activity-feed > .feed-group").eachAttr("data-day")).containsExactly("2026-10-11", "2026-10-10", "2026-10-08")
        assertThat(d.select("#activity-feed > .feed-group[role=group]")).hasSize(3)
        assertThat(d.select("#day-2026-10-11 > .feed-day").text()).isEqualTo("오늘")
        assertThat(d.select("#day-2026-10-11-rows > .feed-row")).hasSize(1)
        assertThat(d.select("#day-2026-10-10-rows .tag").text()).isEqualTo("ADMIN_LOGIN_FAILED")
        assertThat(d.select("#activity-feed .feed-row .feed-row__time").eachText()).containsExactly("11:48", "18:00", "10:00")
        assertThat(d.select("#activity-kpis .kpi__value").eachText()).containsExactly("3", "2", "1")
        assertThat(d.select("#activity-kpis").hasClass("kpis--3")).isTrue() // p95 없음 → 카드 3개
        assertThat(d.select("#activity-tenants-card").text()).contains("활발한 Tenant").contains("acme")
        assertThat(d.select("#activity-feed-card").text()).contains("최근 이벤트").contains("이전 24시간 더 보기")
        // 문장: 행위자·테넌트·대상·라벨
        assertThat(d.select("#activity-feed .feed-row__sentence").first()!!.text()).isEqualTo("op@x.com 님이 acme 테넌트에 tid-0000-1111-2222 · 테넌트 수정")
        assertThat(d.select("#activity-feed .feed-row__sentence")[1].text()).isEqualTo("system 님이 플랫폼에 tid-0000-1111-2222 · 로그인 실패")
    }

    @Test
    @DisplayName("알 수 없는 category 는 전체로, 보안 세그먼트는 category=security 로 조회한다")
    fun categoryNormalizedAndForwarded() {
        page("/admin/activity?category=bogus")
        verify(activity).activity(isNull(), isNull(), isNull(), isNull(), isNull())
        reset(activity)
        feed(view(FEED))
        page("/admin/activity?category=security&tenantId=" + T1)
        verify(activity).activity(isNull(), eq("security"), isNull(), isNull(), eq(T1))
    }

    @Test
    @DisplayName("카테고리·tenant 필터는 URL 로 열면 그대로 적용된다(세그먼트·칩·선택 상자)")
    fun filtersFromUrl() {
        whenever(tenants.list()).thenReturn(ApiResponse.ok(listOf(tenant(T1, "Acme Corp", "acme"))))
        val d = page("/admin/activity?category=ops&tenantId=" + T1)
        assertThat(d.select("#activity-seg .seg__btn--on").text()).isEqualTo("운영")
        assertThat(d.select("#activity-tenant option[selected]").attr("value")).isEqualTo(T1.toString())
        assertThat(d.select("#activity-tenant option").eachText()).contains("테넌트 · 전체", "Acme Corp (acme)")
        assertThat(d.select("#activity-applied .chip").eachText()).containsExactly("카테고리: 운영", "테넌트: acme")
        assertThat(d.select("#activity-cat").`val`()).isEqualTo("ops")
        assertThat(d.select("#activity-tenants-card button.top-row").attr("aria-pressed")).isEqualTo("true")
    }

    @Test
    @DisplayName("첫 조회 실패: 200 + 오류 카드(role=alert), KPI \"—\", 필터는 그대로, 폴링 자리도 있다")
    fun firstLoadFailure() {
        whenever(activity.activity(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())).thenThrow(BusinessException(ErrorCode.INTERNAL_SERVER_ERROR))
        val d = page("/admin/activity")
        assertThat(d.select("#activity-main [role=alert]").text()).contains("활동을 불러오지 못했습니다")
        assertThat(d.select("#activity-feed")).isEmpty()
        assertThat(d.select("#activity-kpis .kpi__value").eachText()).containsExactly("—", "—", "—")
        assertThat(d.select("#activity-seg")).isNotEmpty()
        assertThat(d.select("#activity-loaded").`val`()).isEqualTo("0")
        assertThat(d.select("#activity-root").attr("data-loaded")).isEqualTo("false")
        assertThat(d.select("#activity-poll")).isNotEmpty()
        // 다시 시도: 현재 필터를 싣고 목록 영역을 바꾼다
        val retry = d.select("#activity-main button:contains(다시 시도)").first()!!
        assertThat(retry.attr("hx-get")).isEqualTo("/admin/_/activity/rows")
        assertThat(retry.attr("hx-include")).contains("#activity-cat", "#activity-tenant")
    }

    @Test
    @DisplayName("tenant 목록 조회 실패는 선택 상자만 비운다(원본 catch → [])")
    fun tenantListFailureIsSilent() {
        whenever(tenants.list()).thenThrow(BusinessException(ErrorCode.INTERNAL_SERVER_ERROR))
        val d = page("/admin/activity")
        assertThat(d.select("#activity-tenant option")).hasSize(1)
        assertThat(d.select("#activity-feed .feed-row")).hasSize(3)
    }

    @Test
    @DisplayName("평균 응답(p95) 이 있으면 KPI 4개, 없으면 3개")
    fun p95Kpi() {
        feed(ActivityView(ActivityView.Kpi(1234, 2, 0, 87L), listOf(), FEED))
        val d = page("/admin/activity")
        assertThat(d.select("#activity-kpis").hasClass("kpis--3")).isFalse()
        assertThat(d.select("#activity-kpis .kpi__value").eachText()).containsExactly("1,234", "2", "0", "87ms")
        assertThat(d.select("#activity-kpis .kpi__hint").text()).isEqualTo("p95 87ms")
        assertThat(d.select("#activity-tenants-card").text()).contains("최근 24시간 이벤트가 없습니다.")
    }

    @Test
    @DisplayName("이벤트가 없으면 빈 상태(조건에 맞는 이벤트가 없습니다), 더 보기 버튼은 그대로")
    fun emptyFeed() {
        feed(view(listOf()))
        val d = page("/admin/activity")
        assertThat(d.select("#activity-empty").text()).contains("조건에 맞는 이벤트가 없습니다")
        assertThat(d.select("#activity-more")).isNotEmpty()
        assertThat(d.select("#activity-newest").`val`()).isEmpty()
    }

    @Test
    @DisplayName("모두 지우기: 서버 필터(카테고리·tenant)가 있을 때만 목록을 다시 읽고, 검색어만 있을 땐 클라이언트로만 지운다")
    fun clearAllVariants() {
        val none = page("/admin/activity").select("#activity-applied button:contains(모두 지우기)").first()!!
        assertThat(none.attr("x-on:click")).isEqualTo("clearQ()")
        assertThat(none.hasAttr("hx-get")).isFalse() // 더 보기 이력을 버리지 않는다
        val withFilter = page("/admin/activity?category=ops").select("#activity-applied button:contains(모두 지우기)").first()!!
        assertThat(withFilter.attr("x-on:click")).isEqualTo("clearQ()")
        assertThat(withFilter.attr("hx-get")).isEqualTo("/admin/_/activity/rows?category=all&tenantId=&reset=category,tenant")
        // rows 응답이 만드는 OOB 칩 영역도 같은 규칙
        val d = Jsoup.parseBodyFragment(body(frag("/admin/_/activity/rows")))
        assertThat(d.select("#activity-applied button:contains(모두 지우기)").hasAttr("hx-get")).isFalse()
    }

    @Test
    @DisplayName("더 보기는 필터 교체 중이거나 필터 전환 실패(stale) 뒤에는 비활성")
    fun moreDisabledWhileBusyOrStale() {
        assertThat(page("/admin/activity").select("#activity-more").attr("x-bind:disabled")).isEqualTo("rowsBusy || stale")
    }

    @Test
    @DisplayName("행에는 클라이언트 검색 키(data-type 대문자·data-label 한글)가 있고, 검색 입력은 서버를 부르지 않는다")
    fun clientSideSearchKeys() {
        val d = page("/admin/activity")
        val row = d.select("#day-2026-10-10-rows .feed-row").first()!!
        assertThat(row.attr("data-type")).isEqualTo("ADMIN_LOGIN_FAILED")
        assertThat(row.attr("data-label")).isEqualTo("로그인 실패")
        val q = d.select("#activity-q").first()!!
        assertThat(q.attributes().asList().stream().anyMatch { a -> a.key.startsWith("hx-") }).isFalse()
        assertThat(q.attr("x-on:input")).isEqualTo("applyFilter()")
        // 검색어 칩·빈 결과·모두 지우기는 Alpine(qv/noMatch/clearQ)
        assertThat(d.select("#activity-applied .chip[x-show=\"qv !== ''\"] button").attr("x-on:click")).isEqualTo("clearQ()")
        assertThat(d.select("#activity-nomatch").attr("x-show")).isEqualTo("noMatch")
        assertThat(d.select("#activity-applied button:contains(모두 지우기)").attr("x-on:click")).isEqualTo("clearQ()")
        // 서버 요청에는 검색어를 싣지 않는다
        for (e in d.select("[hx-include]")) assertThat(e.attr("hx-include")).`as`(e.outerHtml()).doesNotContain("activity-q")
    }

    @Test
    @DisplayName("행위자·tenant 이름·diff 값은 이스케이프된다(HTML 로 해석되지 않음)")
    fun userValuesAreEscaped() {
        val evil = "<img src=x onerror=alert(1)>"
        feed(view(listOf(ev(E1, "2026-10-11T02:48:00Z", "TENANT_UPDATE", "ops", T1, "<b>acme</b>", evil))))
        val r = mvc.perform(get("/admin/activity").with(authentication(platform()))).andReturn()
        val html = body(r)
        assertThat(html).doesNotContain(evil).doesNotContain("<b>acme</b>")
        assertThat(html).contains("&lt;img src=x onerror=alert(1)&gt;")
        assertThat(Jsoup.parse(html).select("#activity-feed img")).isEmpty()
    }

    // ── 폴링 ────────────────────────────────────────────────

    @Test
    @DisplayName("폴링 자리: POLL_MS(5초)·drop 동기화·swap none·현재 필터와 상태를 싣는다·조용함(data-quiet), 컨테이너 hx-* 상속 없음")
    fun pollingElementAttributes() {
        val d = page("/admin/activity")
        val poll = d.select("#activity-poll").first()!!
        assertThat(poll.attr("hx-get")).isEqualTo("/admin/_/activity/poll")
        assertThat(poll.attr("hx-trigger")).isEqualTo("every " + (ActivityPageController.POLL_MS / 1000) + "s")
        assertThat(ActivityPageController.POLL_MS).isEqualTo(5000)
        assertThat(poll.attr("hx-sync")).isEqualTo("#activity-root:drop")
        assertThat(poll.attr("hx-swap")).isEqualTo("none")
        assertThat(poll.hasAttr("data-quiet")).isTrue()
        assertThat(poll.attr("hx-include")).contains(
            "#activity-cat", "#activity-tenant", "#activity-loaded", "#activity-newest",
            "#activity-newest-day", "#activity-tenants-sig",
        )
        // 폴링은 필터 교체를 끊지 않는다(drop), 필터 교체는 폴링을 끊는다(replace) — 둘 다 같은 #activity-root 로 동기화
        for (sel in listOf("#activity-seg .seg__btn", "#activity-tenant", "#activity-tenants-card button.top-row")) {
            assertThat(d.select(sel).first()!!.attr("hx-sync")).`as`(sel).isEqualTo("#activity-root:replace")
        }
        assertThat(d.select("#activity-root").hasAttr("hx-target")).isFalse()
        assertThat(d.select("#activity-root").hasAttr("hx-sync")).isFalse()
        // 새로고침은 진행 중인 요청 뒤에 이어서(queue last), 누르는 동안 비활성
        val refresh = d.select("button:contains(새로고침)").first()!!
        assertThat(refresh.attr("hx-sync")).isEqualTo("#activity-root:queue last")
        assertThat(refresh.attr("hx-disabled-elt")).isEqualTo("this")
        assertThat(refresh.hasAttr("data-quiet")).isTrue()
    }

    @Test
    @DisplayName("폴링: 같은 KST 날짜의 새 행은 그 날짜 그룹의 행 목록 앞에(afterbegin:#day-KEY-rows), 새 날짜는 새 그룹을 목록 맨 위에 붙인다")
    fun pollPrependsIntoSameDayGroupAndNewGroups() {
        val fresh1 = ev(id(201), "2026-10-11T02:59:00Z", "ADMIN_LOGIN", "system", null, null, "a@x.com")
        val fresh2 = ev(id(202), "2026-10-11T02:58:00Z", "TENANT_CREATE", "ops", T1, "acme", "a@x.com")
        // 어제(KST 10-10 18:00) 이전과 다른 새 날짜: KST 2026-10-12 00:10 (= UTC 10-11 15:10)
        val nextDay = ev(id(203), "2026-10-11T15:10:00Z", "TENANT_UPDATE", "ops", T1, "acme", "a@x.com")
        val latest = java.util.ArrayList(listOf(nextDay, fresh1, fresh2))
        latest.addAll(FEED)
        feed(view(latest))
        val r = frag("/admin/_/activity/poll?top=" + E1 + "&day=2026-10-11&topSig=zzz")
        assertThat(r.response.status).isEqualTo(200)
        val d = doc(r)
        // 같은 날짜(2026-10-11) → 기존 그룹의 행 목록 앞. 머리글을 다시 그리지 않는다(행만).
        val same = d.select("[hx-swap-oob=afterbegin:#day-2026-10-11-rows]").first()
        assertThat(same).isNotNull()
        same!!
        assertThat(same.select(".feed-row .tag").eachText()).containsExactly("ADMIN_LOGIN", "TENANT_CREATE")
        assertThat(same.select(".feed-day, .feed-group")).isEmpty()
        // 새 날짜 → 새 그룹을 #activity-feed 맨 위에
        val fresh = d.select("[hx-swap-oob=afterbegin:#activity-feed]").first()!!
        assertThat(fresh.select("> .feed-group").eachAttr("data-day")).containsExactly("2026-10-12")
        assertThat(fresh.select(".feed-group .feed-day").text()).isEqualTo("2026-10-12")
        assertThat(fresh.select("#day-2026-10-12-rows .tag").eachText()).containsExactly("TENANT_UPDATE")
        assertThat(d.select("#activity-newest[hx-swap-oob]").`val`()).isEqualTo(id(203).toString())
        assertThat(d.select("#activity-newest-day[hx-swap-oob]").`val`()).isEqualTo("2026-10-12")
        assertThat(d.select("#activity-kpis[hx-swap-oob]")).isNotEmpty()
        // 본문은 OOB 뿐(목록 전체를 교체하지 않는다)
        assertThat(d.select("#activity-feed-card, #activity-main")).isEmpty()
        assertThat(r.response.getHeader("HX-Retarget")).isNull()
    }

    @Test
    @DisplayName("폴링: 새 행이 없으면 목록 OOB 도 없다. 자정을 넘겨 라벨이 바뀌어도 날짜 키로 이어 붙는다")
    fun pollNoNewRowsAndMidnightContinuity() {
        val none = doc(frag("/admin/_/activity/poll?top=" + E1))
        assertThat(none.select("[hx-swap-oob^=afterbegin]")).isEmpty()
        assertThat(none.select("#activity-newest")).isEmpty()

        // 시계가 다음 날로 넘어가 같은 날짜 그룹의 라벨이 "어제"가 돼도 키(KST 날짜)가 같으면 그 그룹에 붙는다
        whenever(clock.instant()).thenReturn(NOW.plusSeconds(86400))
        feed(view(listOf(ev(id(201), "2026-10-11T02:59:00Z", "ADMIN_LOGIN", "system", null, null, "a@x.com"), FEED[0])))
        val d = doc(frag("/admin/_/activity/poll?top=" + E1 + "&day=2026-10-11"))
        assertThat(d.select("[hx-swap-oob=afterbegin:#day-2026-10-11-rows] .feed-row")).hasSize(1)
        assertThat(d.select("[hx-swap-oob=afterbegin:#activity-feed]")).isEmpty()
    }

    @Test
    @DisplayName("폴링: 화면의 가장 새 행이 최신 한 쪽에 없으면 쪽 전체가 새 행, 목록이 비어 있었으면 아래쪽 커서도 정하고 빈 상태를 지운다")
    fun pollTopNotFoundAndEmptyList() {
        val d = doc(frag("/admin/_/activity/poll?top=" + id(999) + "&day=2026-10-11"))
        assertThat(d.select("[hx-swap-oob=afterbegin:#day-2026-10-11-rows] .feed-row")).hasSize(1)
        assertThat(d.select("[hx-swap-oob=afterbegin:#activity-feed] > .feed-group")).hasSize(2)
        assertThat(d.select("#activity-before")).isEmpty() // 비어 있지 않았다 — 커서는 그대로
        assertThat(d.select("#activity-empty[hx-swap-oob=delete]")).isEmpty()

        val empty = doc(frag("/admin/_/activity/poll"))
        assertThat(empty.select("[hx-swap-oob=afterbegin:#activity-feed] > .feed-group")).hasSize(3)
        assertThat(empty.select("#activity-before-id[hx-swap-oob]").`val`()).isEqualTo(E3.toString())
        assertThat(empty.select("#activity-before[hx-swap-oob]").`val`()).isEqualTo("2026-10-08T01:00:00Z")
        assertThat(empty.select("#activity-last-day[hx-swap-oob]").`val`()).isEqualTo("2026-10-08")
        // :has() 를 못 쓰는 브라우저를 위한 대체: 빈 상태 요소를 OOB 로 지운다
        assertThat(empty.select("#activity-empty[hx-swap-oob=delete]")).isNotEmpty()
    }

    @Test
    @DisplayName("폴링은 현재 필터(카테고리·tenant)를 REST 에 싣는다")
    fun pollKeepsFilters() {
        frag("/admin/_/activity/poll?category=ops&tenantId=" + T1 + "&top=" + E1)
        verify(activity).activity(isNull(), eq("ops"), isNull(), isNull(), eq(T1))
    }

    @Test
    @DisplayName("폴링 실패는 503 + 빈 본문 + 토스트 없음(화면이 \"연결 끊김\"으로만 알린다)")
    fun pollFailureIsQuiet() {
        whenever(activity.activity(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())).thenThrow(BusinessException(ErrorCode.INTERNAL_SERVER_ERROR))
        val r = frag("/admin/_/activity/poll?top=" + E1)
        assertThat(r.response.status).isEqualTo(503)
        assertThat(r.response.getHeader("HX-Trigger")).isNull()
        assertThat(strip(body(r))).isEmpty()
    }

    @Test
    @DisplayName("이전 조회가 실패한 상태(loaded=0)에서 폴링이 성공하면 목록 영역을 통째로 교체한다")
    fun pollRecoversFromLoadError() {
        val r = frag("/admin/_/activity/poll?loaded=0")
        assertThat(r.response.getHeader("HX-Retarget")).isEqualTo("#activity-main")
        assertThat(r.response.getHeader("HX-Reswap")).isEqualTo("innerHTML")
        assertThat(doc(r).select("#activity-feed .feed-row")).hasSize(3)
    }

    @Test
    @DisplayName("활발한 Tenant 는 서명이 같으면 다시 그리지 않는다(포커스 보존), 다르면 OOB 로 교체")
    fun tenantsCardOnlyWhenChanged() {
        val first = doc(frag("/admin/_/activity/poll?top=" + E1))
        val sig = first.select("#activity-tenants-sig").`val`()
        assertThat(sig).isNotBlank()
        val same = doc(frag("/admin/_/activity/poll?top=" + E1 + "&topSig=" + sig))
        assertThat(same.select("#activity-tenants-card")).isEmpty()
        assertThat(first.select("#activity-tenants-card[hx-swap-oob]")).isNotEmpty()
    }

    // ── 필터 교체(rows) ──────────────────────────────────────

    @Test
    @DisplayName("rows 조각: 레이아웃 없이 목록 영역 + OOB(KPI·칩·활발한 Tenant·상태), HX-Replace-Url 에 필터(검색어 제외)")
    fun rowsFragment() {
        val r = frag("/admin/_/activity/rows?category=security&tenantId=" + T1)
        assertThat(r.response.status).isEqualTo(200)
        val html = body(r)
        assertThat(html).doesNotContain("<html").doesNotContain("class=\"app\"")
        val d = Jsoup.parseBodyFragment(html)
        assertThat(d.select("#activity-feed-card")).isNotEmpty()
        assertThat(d.select("#activity-kpis[hx-swap-oob]")).isNotEmpty()
        assertThat(d.select("#activity-applied[hx-swap-oob]")).isNotEmpty()
        assertThat(d.select("#activity-tenants-card[hx-swap-oob]")).isNotEmpty()
        assertThat(d.select("#activity-state[hx-swap-oob]")).isNotEmpty()
        assertThat(d.select("#activity-cat").`val`()).isEqualTo("security")
        // 되돌려 쓰기 요청이 아니면 사용자가 방금 고른 컨트롤을 다시 그리지 않는다(포커스·타이핑 보존)
        assertThat(d.select("#activity-seg, #activity-tenant-wrap, #activity-q-wrap")).isEmpty()
        assertThat(d.select("#activity-feed > .feed-group")).hasSize(3)
        assertThat(r.response.getHeader("HX-Replace-Url")).isEqualTo("/admin/activity?category=security&tenantId=" + T1)
        verify(activity).activity(isNull(), eq("security"), isNull(), isNull(), eq(T1))
    }

    @Test
    @DisplayName("rows: reset 한 컨트롤만 OOB 로 되돌려 쓴다(칩 제거·모두 지우기·활발한 Tenant 클릭)")
    fun rowsResetControls() {
        val d = Jsoup.parseBodyFragment(body(frag("/admin/_/activity/rows?category=all&tenantId=&reset=category,tenant")))
        assertThat(d.select("#activity-seg[hx-swap-oob]")).isNotEmpty()
        assertThat(d.select("#activity-tenant-wrap[hx-swap-oob]")).isNotEmpty()
        assertThat(d.select("#activity-q-wrap")).isEmpty() // 검색어는 클라이언트 상태 — 서버가 되돌려 쓰지 않는다
        val one = Jsoup.parseBodyFragment(body(frag("/admin/_/activity/rows?tenantId=" + T1 + "&reset=tenant")))
        assertThat(one.select("#activity-tenant-wrap[hx-swap-oob]")).isNotEmpty()
        assertThat(one.select("#activity-seg, #activity-q-wrap")).isEmpty()
    }

    @Test
    @DisplayName("rows 조회 실패: 422 + 같은 영역의 오류 카드, 토스트 없음, 이전 목록·KPI 를 남기지 않는다(KPI \"—\")")
    fun rowsFailure() {
        whenever(activity.activity(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())).thenThrow(BusinessException(ErrorCode.INTERNAL_SERVER_ERROR))
        val r = frag("/admin/_/activity/rows?category=ops")
        assertThat(r.response.status).isEqualTo(422)
        assertThat(r.response.getHeader("HX-Trigger")).isNull()
        val d = Jsoup.parseBodyFragment(body(r))
        assertThat(d.select("[role=alert]").text()).contains("활동을 불러오지 못했습니다")
        assertThat(d.select("#activity-kpis .kpi__value").eachText()).containsExactly("—", "—", "—")
        assertThat(d.select("#activity-feed")).isEmpty()
        assertThat(d.select("#activity-loaded").`val`()).isEqualTo("0")
    }

    @Test
    @DisplayName("필터 컨트롤(세그먼트·선택 상자·검색·칩)은 모두 현재 나머지 필터를 싣는다 — 합치면 같은 파라미터가 두 번 가지 않는다")
    fun controlsCarryOtherFilters() {
        whenever(tenants.list()).thenReturn(ApiResponse.ok(listOf(tenant(T1, "Acme", "acme"))))
        val d = page("/admin/activity?category=ops&tenantId=" + T1 + "&q=ignored")
        val ops = d.select("#activity-seg .seg__btn[data-value=security]").first()!!
        assertThat(ops.attr("hx-get")).isEqualTo("/admin/_/activity/rows?category=security")
        assertThat(ops.attr("hx-include")).isEqualTo("#activity-tenant")
        val sel = d.select("#activity-tenant").first()!!
        assertThat(sel.attr("hx-trigger")).isEqualTo("change")
        assertThat(sel.attr("hx-include")).isEqualTo("#activity-cat")
        assertThat(d.select("#activity-applied button:contains(모두 지우기)").attr("hx-get"))
            .isEqualTo("/admin/_/activity/rows?category=all&tenantId=&reset=category,tenant")
        for (e in d.select("#activity-seg .seg__btn, #activity-tenant, #activity-applied button[hx-get]")) {
            assertThat(e.attr("hx-target")).`as`(e.outerHtml()).isEqualTo("#activity-main")
            assertThat(e.attr("hx-swap")).isEqualTo("innerHTML")
        }
    }

    // ── 더 보기 ──────────────────────────────────────────────

    @Test
    @DisplayName("더 보기: 가장 오래된 행의 (시각, id) 커서로 조회하고 아래에 붙이며(OOB beforeend) 커서·머리글 라벨을 갱신한다")
    fun loadMore() {
        val older1 = ev(id(301), "2026-10-08T00:30:00Z", "TENANT_UPDATE", "ops", T1, "acme", "op@x.com") // KST 10-08 09:30
        val older2 = ev(id(302), "2026-10-07T14:00:00Z", "TENANT_UPDATE", "ops", T1, "acme", "op@x.com") // KST 10-07 23:00
        feed(view(listOf(older1, older2)))
        val r = frag("/admin/_/activity/more?category=ops&tenantId=" + T1 + "&before=2026-10-08T01:00:00Z&beforeId=" + E3 + "&lastDay=2026-10-08")
        val d = doc(r)
        val before = argumentCaptor<OffsetDateTime>()
        verify(activity).activity(isNull(), eq("ops"), before.capture(), eq(E3), eq(T1))
        assertThat(before.lastValue.toInstant()).isEqualTo(Instant.parse("2026-10-08T01:00:00Z"))
        // 같은 KST 날짜(10-08)의 행은 그 그룹의 행 목록 뒤에, 새 날짜(10-07)는 새 그룹을 목록 맨 아래에
        assertThat(d.select("[hx-swap-oob=beforeend:#day-2026-10-08-rows] .feed-row")).hasSize(1)
        assertThat(d.select("[hx-swap-oob=beforeend:#day-2026-10-08-rows] .feed-day")).isEmpty()
        val newGroups = d.select("[hx-swap-oob=beforeend:#activity-feed]").first()!!
        assertThat(newGroups.select("> .feed-group").eachAttr("data-day")).containsExactly("2026-10-07")
        assertThat(newGroups.select("#day-2026-10-07-rows .feed-row")).hasSize(1)
        assertThat(d.select("#activity-before-id[hx-swap-oob]").`val`()).isEqualTo(id(302).toString())
        assertThat(d.select("#activity-before[hx-swap-oob]").`val`()).isEqualTo("2026-10-07T14:00:00Z")
        assertThat(d.select("#activity-last-day[hx-swap-oob]").`val`()).isEqualTo("2026-10-07")
        assertThat(r.response.getHeader("HX-Trigger")).isNull()
    }

    @Test
    @DisplayName("더 보기: 커서가 없으면 아무 것도 하지 않고, 더 불러올 행이 없으면 커서를 그대로 둔다")
    fun loadMoreNoopCases() {
        assertThat(strip(body(frag("/admin/_/activity/more")))).isEmpty()
        verify(activity, never()).activity(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
        feed(view(listOf()))
        val d = doc(frag("/admin/_/activity/more?before=2026-10-08T01:00:00Z&beforeId=" + E3))
        assertThat(d.select("[hx-swap-oob]")).isEmpty()
    }

    @Test
    @DisplayName("더 보기 실패: 실제 상태 + 토스트(\"이전 기록을 불러오지 못했습니다\"), 본문 없음")
    fun loadMoreFailureToasts() {
        whenever(activity.activity(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())).thenThrow(BusinessException(ErrorCode.INTERNAL_SERVER_ERROR))
        val r = frag("/admin/_/activity/more?before=2026-10-08T01:00:00Z&beforeId=" + E3)
        assertThat(r.response.status).isGreaterThanOrEqualTo(500)
        assertThat(tools.jackson.databind.ObjectMapper().readTree(r.response.getHeader("HX-Trigger")).at("/toast/title").asString()).isEqualTo("이전 기록을 불러오지 못했습니다")
        assertThat(strip(body(r))).isEmpty()
    }

    @Test
    @DisplayName("더 보기 버튼: 한 번에 하나(this:drop), 현재 필터·커서·머리글을 싣는다")
    fun moreButtonAttributes() {
        val more = page("/admin/activity").select("#activity-more").first()!!
        assertThat(more.attr("hx-get")).isEqualTo("/admin/_/activity/more")
        assertThat(more.attr("hx-sync")).isEqualTo("this:drop")
        assertThat(more.attr("hx-swap")).isEqualTo("none")
        assertThat(more.attr("hx-include")).contains("#activity-before", "#activity-before-id", "#activity-last-day", "#activity-cat")
        // 필터 교체가 진행 중이면 비활성(새 필터에 옛 커서를 싣지 않게)
        assertThat(more.attr("x-bind:disabled")).isEqualTo("rowsBusy || stale")
    }

    // ── 상세 ────────────────────────────────────────────────

    @Test
    @DisplayName("diffRendersSameAsOriginal: 상세 패널 5개 섹션과 TENANT_UPDATE before/after diff(배열은 − / + 칩, 값은 from → to)")
    fun diffRendersSameAsOriginal() {
        whenever(activity.detail(E1)).thenReturn(ApiResponse.ok(detail("op@x.com", PAYLOAD)))
        val r = frag("/admin/_/activity/" + E1)
        assertThat(r.response.status).isEqualTo(200)
        val d = Jsoup.parseBodyFragment(body(r))
        assertThat(d.select("section[aria-label=이벤트 상세]")).hasSize(1)
        assertThat(d.select("[role=group]").eachAttr("aria-label")).containsExactly("언제", "누가", "테넌트 · 대상", "어떻게 바뀜", "원본 JSON")
        assertThat(d.select(".card__head .tag").text()).isEqualTo("TENANT_UPDATE")
        assertThat(d.select(".card__head .tag").attr("title")).isEqualTo("테넌트 수정")
        assertThat(d.select("[aria-label=언제]").text()).contains("2026-10-11 11:48:00 KST").contains("2026-10-11T02:48:00Z UTC")
        assertThat(d.select("[aria-label=누가]").text()).contains("op@x.com")
        assertThat(d.select("[aria-label=누가] .avatar").text()).isEqualTo("O")
        assertThat(d.select("[aria-label=\"테넌트 · 대상\"] a").attr("href")).isEqualTo("/admin/tenants/" + T1)
        assertThat(d.select("[aria-label=\"테넌트 · 대상\"]").text()).contains("acme").contains("· TENANT").contains("…111-2222")
        val diff = d.select("[aria-label=\"어떻게 바뀜\"]").first()!!
        val lines = diff.select("div[style*=font-size: 12px]")
        assertThat(lines).hasSize(2)
        assertThat(lines[0].select(".mono").text()).isEqualTo("allowedOrigins")
        assertThat(lines[0].select(".tag").eachText()).containsExactly("+ https://b.acme.com")
        assertThat(lines[1].select(".tag").eachText()).containsExactly("60000", "120000")
        assertThat(lines[1].select(".sr-only").text()).isEqualTo("webauthnTimeoutMs: 60000 에서 120000 로 변경")
        // 원본 JSON(들여쓰기 2칸) + 복사 버튼
        assertThat(d.select("pre").text()).contains("\"webauthnTimeoutMs\": 60000")
        assertThat(d.select("[aria-label=\"원본 JSON\"] button").text()).contains("복사")
    }

    @Test
    @DisplayName("상세: 행위자 없으면 system, tenant 없으면 플랫폼, diff 없으면 섹션 없음, JSON 이 아니면 원문")
    fun detailFallbacks() {
        whenever(activity.detail(E2)).thenReturn(
            ApiResponse.ok(
                ActivityDetailView(
                    E2, "ADMIN_LOGIN_FAILED", null, null, null, null, null, null,
                    OffsetDateTime.parse("2026-10-10T09:00:00Z"), "security", "not json",
                ),
            ),
        )
        val d = Jsoup.parseBodyFragment(body(frag("/admin/_/activity/" + E2)))
        assertThat(d.select("[aria-label=누가]").text()).contains("system")
        assertThat(d.select("[aria-label=누가] .avatar").text()).isEqualTo("S")
        assertThat(d.select("[aria-label=\"테넌트 · 대상\"]").text()).contains("플랫폼").contains("· —")
        assertThat(d.select("[aria-label=\"어떻게 바뀜\"]")).isEmpty()
        assertThat(d.select("pre").text()).isEqualTo("not json")
        assertThat(d.select("[aria-label=언제]").text()).contains("2026-10-10 18:00:00 KST")
    }

    @Test
    @DisplayName("상세의 사용자 값(행위자·payload·diff 값)은 HTML 로 해석되지 않는다")
    fun detailEscapes() {
        val evil = "<script>alert(1)</script>"
        val payload = "{\"before\":{\"name\":\"" + evil + "\"},\"after\":{\"name\":\"ok\"}}"
        whenever(activity.detail(E1)).thenReturn(ApiResponse.ok(detail(evil, payload)))
        val html = body(frag("/admin/_/activity/" + E1))
        assertThat(html).doesNotContain(evil)
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;")
        assertThat(Jsoup.parseBodyFragment(html).select("script")).isEmpty()
    }

    @Test
    @DisplayName("상세 조회 실패: 422 + 패널 안 오류(다시 시도 = 같은 주소 재요청, 조용함), 토스트 없음")
    fun detailFailureInline() {
        whenever(activity.detail(E1)).thenThrow(BusinessException(ErrorCode.INTERNAL_SERVER_ERROR))
        val r = frag("/admin/_/activity/" + E1)
        assertThat(r.response.status).isEqualTo(422)
        assertThat(r.response.getHeader("HX-Trigger")).isNull()
        val d = Jsoup.parseBodyFragment(body(r))
        assertThat(d.select("[role=alert]").text()).contains("이벤트 상세를 불러오지 못했습니다")
        val retry = d.select("button:contains(다시 시도)").first()!!
        assertThat(retry.attr("hx-get")).isEqualTo("/admin/_/activity/" + E1)
        assertThat(retry.attr("hx-target")).isEqualTo("#activity-detail")
        assertThat(retry.attr("hx-sync")).isEqualTo("#activity-detail:replace")
        assertThat(retry.hasAttr("data-quiet")).isTrue()
        assertThat(d.select("button[aria-label=닫기]")).isNotEmpty()
        // 잘못된 id 도 같은 패널 오류(REST 미호출)
        reset(activity)
        val bad = frag("/admin/_/activity/not-a-uuid")
        assertThat(bad.response.status).isEqualTo(422)
        verify(activity, never()).detail(anyOrNull())
    }

    @Test
    @DisplayName("상세 열기 버튼: 마지막으로 연 상세만 반영(replace)·닫기로 중단 가능한 동기화 대상·조용함, 선택 행 강조는 Alpine 상태")
    fun detailButtonsAndSelection() {
        val d = page("/admin/activity")
        val btn = d.select("#activity-feed .feed-row button[aria-label=상세 열기]").first()!!
        assertThat(btn.attr("hx-get")).isEqualTo("/admin/_/activity/" + E1)
        assertThat(btn.attr("hx-target")).isEqualTo("#activity-detail")
        assertThat(btn.attr("hx-sync")).isEqualTo("#activity-detail:replace")
        assertThat(btn.hasAttr("data-quiet")).isTrue()
        assertThat(btn.attr("x-on:click")).isEqualTo("openDetail(\$el.dataset.id)")
        val row = d.select("#activity-feed .feed-row").first()!!
        assertThat(row.attr("x-bind:aria-current")).contains("selectedId")
        assertThat(d.select("#activity-detail").hasAttr("data-quiet")).isTrue()
        // 불러오는 중·네트워크 실패 패널(Alpine)
        assertThat(d.select("section[aria-label=이벤트 상세][x-show]")).hasSize(2)
        assertThat(d.select("[aria-busy=true]").attr("aria-label")).isEqualTo("상세 불러오는 중")
    }

    // ── CSV ─────────────────────────────────────────────────

    @Test
    @DisplayName("CSV: 열·파일명(activity-KST오늘.csv)·필터 전달, 커서(beforeId)까지의 행만")
    fun csvExport() {
        val r = mvc.perform(get(URI.create("/admin/_/activity/export?category=ops&tenantId=" + T1 + "&beforeId=" + E2)).with(authentication(platform()))).andReturn()
        val res = r.response
        assertThat(res.status).isEqualTo(200)
        assertThat(res.getHeader("Content-Disposition")).contains("activity-2026-10-11.csv")
        assertThat(res.contentType).startsWith("text/csv")
        val csv = res.getContentAsString(StandardCharsets.UTF_8)
        assertThat(csv).contains("timestamp,tenant,type,category,actor,subject")
        assertThat(csv).contains("2026-10-11T02:48:00Z,acme,TENANT_UPDATE,ops,op@x.com,tid-0000-1111-2222")
        assertThat(csv).contains("ADMIN_LOGIN_FAILED,security,,") // 행위자 없음 → 빈 값, tenant 없음 → 빈 값
        assertThat(csv).doesNotContain("API_KEY_ISSUE") // 커서(E2) 아래 행은 아직 읽어 오지 않았다
        verify(activity).activity(isNull(), eq("ops"), isNull(), isNull(), eq(T1))
    }

    @Test
    @DisplayName("CSV: 경계 행(beforeId)에 닿지 못하면 조용히 일부만 내보내지 않고 5xx")
    fun csvBoundaryNotReachedFails() {
        val r = mvc.perform(get(URI.create("/admin/_/activity/export?beforeId=" + id(999))).with(authentication(platform()))).andReturn()
        assertThat(r.response.status).isGreaterThanOrEqualTo(500)
        assertThat(r.response.contentAsString).doesNotContain("timestamp,tenant")
    }

    @Test
    @DisplayName("CSV: 더 보기로 40쪽(2,000건)을 넘게 불러온 경계도 끝까지 이어 읽는다(상한 40쪽 아님), 경계 행까지만")
    fun csvExportBeyondFortyPages() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        whenever(activity.activity(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())).thenAnswer { ApiResponse.ok(view(fullPage(calls.getAndIncrement()))) }
        val boundary = id(10000 + 45 * 50 + 9) // 46번째 쪽(인덱스 45)의 10번째 행
        val r = mvc.perform(get(URI.create("/admin/_/activity/export?beforeId=" + boundary)).with(authentication(platform()))).andReturn()
        assertThat(r.response.status).isEqualTo(200)
        val lines = NEWLINE.split(r.response.getContentAsString(StandardCharsets.UTF_8).substring(1))
        assertThat(lines).hasSize(1 + 45 * 50 + 10)
        assertThat(calls.get()).isEqualTo(46)
    }

    @Test
    @DisplayName("CSV: 그 사이 위에 새 행이 생겨도 경계는 id 로 찾는다(새 행 포함, 경계 행에서 끝)")
    fun csvExportNewRowsAtTop() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        // 첫 쪽: 새 행 3개 + 원래 첫 쪽의 앞 47개 — 경계(원래 첫 쪽의 마지막 행)는 둘째 쪽 맨 앞으로 밀린다
        whenever(activity.activity(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())).thenAnswer {
            val c = calls.getAndIncrement()
            val orig = fullPage(0)
            val page = java.util.ArrayList<ActivityView.Event>()
            if (c == 0) {
                for (i in 0 until 3) page.add(ev(id(90000 + i), NOW.plusSeconds((10 - i).toLong()).toString(), "API_KEY_ISSUE", "ops", T1, "acme", "new@x.com"))
                page.addAll(orig.subList(0, 47))
            } else {
                page.addAll(orig.subList(47, 50))
                page.addAll(fullPage(1).subList(0, 47))
            }
            ApiResponse.ok(view(page))
        }
        val boundary = id(10000 + 49)
        val r = mvc.perform(get(URI.create("/admin/_/activity/export?beforeId=" + boundary)).with(authentication(platform()))).andReturn()
        assertThat(r.response.status).isEqualTo(200)
        val lines = NEWLINE.split(r.response.getContentAsString(StandardCharsets.UTF_8).substring(1))
        assertThat(lines).hasSize(1 + 53) // 새 행 3 + 원래 50
        assertThat(lines[1]).contains("new@x.com")
        assertThat(calls.get()).isEqualTo(2)
    }

    @Test
    @DisplayName("CSV: 안전 상한(400쪽 = 20,000건) 안에 경계에 닿지 못하면 422 + 이유 문구(JSON message), 일부만 담은 파일 없음")
    fun csvExportSanityLimit() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        whenever(activity.activity(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())).thenAnswer { ApiResponse.ok(view(fullPage(calls.getAndIncrement()))) }
        val r = mvc.perform(get(URI.create("/admin/_/activity/export?beforeId=" + id(999))).with(authentication(platform()))).andReturn()
        assertThat(r.response.status).isEqualTo(422)
        assertThat(r.response.contentType).startsWith("application/json")
        val body = r.response.getContentAsString(StandardCharsets.UTF_8)
        assertThat(body).contains("\"message\"").contains("20,000건").doesNotContain("timestamp,tenant")
        assertThat(calls.get()).isEqualTo(400)
    }

    @Test
    @DisplayName("CSV 조회 실패는 5xx(부분 파일 없음) — 공통 다운로드 헬퍼가 토스트한다")
    fun csvFailure() {
        whenever(activity.activity(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())).thenThrow(BusinessException(ErrorCode.INTERNAL_SERVER_ERROR))
        val r = mvc.perform(get("/admin/_/activity/export").with(authentication(platform()))).andReturn()
        assertThat(r.response.status).isGreaterThanOrEqualTo(500)
    }

    @Test
    @DisplayName("CSV 링크는 공통 헬퍼(data-csv-download, hx-boost=false), 읽은 행이 없으면 원본처럼 경고(JS)")
    fun csvLinkAttributes() {
        val a = page("/admin/activity").select("#activity-export").first()!!
        assertThat(a.attr("href")).isEqualTo("/admin/_/activity/export?category=all")
        assertThat(a.hasAttr("data-csv-download")).isTrue()
        assertThat(a.attr("hx-boost")).isEqualTo("false")
        assertThat(a.attr("x-on:click")).isEqualTo("exportClick(\$event)")
    }

    // ── 상대 시간·라이브 상태 ────────────────────────────────

    @Test
    @DisplayName("실시간 상태·연결 끊김 배너·새로고침: Alpine 상태에 묶이고 폴링 주기를 data 로 받는다")
    fun liveStatusMarkup() {
        val d = page("/admin/activity")
        val root = d.select("#activity-root").first()!!
        assertThat(root.attr("x-data")).isEqualTo("activityLive")
        assertThat(root.attr("data-poll-sec")).isEqualTo("5")
        assertThat(root.attr("data-loaded")).isEqualTo("true")
        assertThat(d.select("[x-text=liveText]").text()).isEqualTo("실시간 · 0초 전 갱신")
        // 초 단위 카운터는 live 영역(role=status) 밖
        assertThat(d.select("[role=status] [x-text=liveText]")).isEmpty()
        assertThat(d.select("[role=status][x-text]").size).isGreaterThanOrEqualTo(1)
        assertThat(d.select(".banner").text()).contains("새 이벤트를 받지 못하고 있습니다")
        assertThat(d.select(".banner button").text()).contains("지금 다시 연결")
        assertThat(d.select(".banner button").attr("hx-sync")).isEqualTo("#activity-root:queue last")
        assertThat(root.attr("x-on:htmx:after-request")).isEqualTo("afterRequest(\$event)")
    }
}
