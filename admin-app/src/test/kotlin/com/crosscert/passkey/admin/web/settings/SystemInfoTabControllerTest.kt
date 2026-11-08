package com.crosscert.passkey.admin.web.settings

import com.crosscert.passkey.admin.audit.AuditChainMonitorController
import com.crosscert.passkey.admin.auth.ActiveTenantController
import com.crosscert.passkey.admin.auth.AdminUserDetails
import com.crosscert.passkey.admin.license.LicenseController
import com.crosscert.passkey.admin.system.SystemInfoController
import com.crosscert.passkey.admin.system.SystemInfoView
import com.crosscert.passkey.admin.system.SystemInfoView.Component
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
import java.util.UUID

/**
 * 설정 › 시스템 탭. 원본: SystemInfoTab.test.tsx.
 *
 * (Kotlin 전환) 즉시 역참조하는 nullable jsoup 결과(`selectFirst`·`nextElementSibling`)의 `!!` 는 원 Java 의 NPE 실패 지점과 같다.
 */
@AdminWebSliceTest(controllers = [SystemInfoTabController::class])
class SystemInfoTabControllerTest {

    @Autowired lateinit var mvc: MockMvc
    @MockitoBean lateinit var systemInfo: SystemInfoController
    @MockitoBean lateinit var license: LicenseController
    @MockitoBean lateinit var chain: AuditChainMonitorController
    @MockitoBean lateinit var activeTenants: ActiveTenantController

    companion object {
        val SAMPLE = SystemInfoView(
            "1.2.3", "2026-05-31T09:00+09:00", 4L, SystemInfoView.Host("prod,oracle"),
            listOf(Component("admin-app", "1.2.3", "OK", "Spring Boot"), Component("MDS sync scheduler", "1.2.3", "OK", "매 6시간")),
        )

        fun auth(role: String): Authentication {
            val p = AdminUserDetails(
                UUID.randomUUID(), "a@x.com", "x", role,
                if ("RP_ADMIN" == role) setOf(UUID.randomUUID()) else setOf(), true, null, Clock.systemUTC(),
            )
            return UsernamePasswordAuthenticationToken(p, "x", p.authorities)
        }

        private fun kpi(d: Document, label: String): Element =
            d.select(".kpi").stream().filter { k -> k.selectFirst(".kpi__label")!!.text() == label }.findFirst().orElseThrow()

        private fun list(d: Document): Element? = d.selectFirst("ul[aria-label=백엔드 컴포넌트]")
    }

    @BeforeEach
    fun setUp() {
        whenever(systemInfo.get()).thenReturn(ApiResponse.ok(SAMPLE))
    }

    private fun render(v: SystemInfoView): Document {
        whenever(systemInfo.get()).thenReturn(ApiResponse.ok(v))
        return render()
    }

    private fun render(): Document {
        val r = mvc.perform(get(SystemInfoTabController.TAB_PATH).with(authentication(auth("PLATFORM_OPERATOR"))).header("HX-Request", "true")).andReturn()
        assertThat(r.response.status).isEqualTo(200)
        return Jsoup.parse(r.response.getContentAsString(StandardCharsets.UTF_8))
    }

    @Test
    @DisplayName("버전·KST 배포 시각·환경·컴포넌트 메모·가동 일수, KPI 라벨 한국어('d' 없음)")
    fun rendersInfo() {
        val d = render()
        assertThat(d.text()).contains("1.2.3").contains("2026-05-31 09:00 KST 배포").contains("prod,oracle").contains("매 6시간").contains("4일")
            .doesNotContain("Uptime").doesNotContain("4d")
        assertThat(kpi(d, "서버 버전")).isNotNull()
        assertThat(kpi(d, "가동 시간").selectFirst(".kpi__value")!!.text()).isEqualTo("4일")
        assertThat(d.select(".chips .tag").eachText()).containsExactly("prod", "oracle")
        assertThat(d.select("aside, [role=tablist]")).isEmpty()
    }

    @Test
    @DisplayName("긴 버전은 말줄임(truncate + title), 상세는 kv, 배포 시각 KST")
    fun longVersionTruncates() {
        val longV = "Oracle Database 21c Express Edition Release 21.0.0.0.0 - Production"
        val d = render(
            SystemInfoView(
                "1.2.3", "2026-05-31T09:00+09:00", 4L, SystemInfoView.Host("prod"),
                listOf(Component("Oracle DB", longV, "OK", "primary")),
            ),
        )
        val v = list(d)!!.select(".truncate").stream().filter { e -> e.text() == longV }.findFirst().orElseThrow()
        assertThat(v.attr("title")).isEqualTo(longV)
        assertThat(d.selectFirst("dl")!!.hasClass("kv")).isTrue()
        assertThat(d.selectFirst("dl")!!.text()).contains("2026-05-31 09:00 KST")
    }

    @Test
    @DisplayName("deployedAt 이 없거나 해석할 수 없으면 '배포 시각 정보 없음' + 상세 '—'")
    fun deployedMissingOrInvalid() {
        for (dep in arrayOf<String?>(null, "not-a-date")) {
            val d = render(SystemInfoView("1.2.3", dep, 4L, SystemInfoView.Host("prod"), listOf(Component("a", "1", "OK", null))))
            assertThat(d.text()).contains("배포 시각 정보 없음").doesNotContain("Invalid Date").doesNotContain("not-a-date")
            val dt = d.select("dt").stream().filter { e -> e.text() == "배포 시각" }.findFirst().orElseThrow()
            assertThat(dt.nextElementSibling()!!.text()).isEqualTo("—")
        }
    }

    @Test
    @DisplayName("컴포넌트 상태 → 배지 클래스(정상·저하·중단), 전체 상태는 가장 나쁜 상태 + 'N / M'")
    fun statusBadgesAndOverall() {
        val d = render(
            SystemInfoView(
                "1.2.3", null, 4L, SystemInfoView.Host("prod"),
                listOf(
                    Component("admin-app", "1.2.3", "OK", "Spring Boot"),
                    Component("Oracle DB", "x", "DEGRADED", "primary"),
                    Component("MDS", "1.2.3", "DOWN", null),
                ),
            ),
        )
        val ul = list(d)!!
        assertThat(ul.select(".badge").stream().filter { b -> b.text() == "정상" }.findFirst().orElseThrow().hasClass("badge--success")).isTrue()
        assertThat(ul.select(".badge").stream().filter { b -> b.text() == "저하" }.findFirst().orElseThrow().hasClass("badge--warning")).isTrue()
        assertThat(ul.select(".badge").stream().filter { b -> b.text() == "중단" }.findFirst().orElseThrow().hasClass("badge--danger")).isTrue()
        assertThat(kpi(d, "전체 상태").text()).contains("중단").contains("컴포넌트 1 / 3")

        val two = render(
            SystemInfoView(
                "1.2.3", null, 4L, SystemInfoView.Host("prod"),
                listOf(Component("admin-app", "1.2.3", "OK", null), Component("Redis", "7", "DEGRADED", null)),
            ),
        )
        assertThat(kpi(two, "전체 상태").text()).contains("저하").contains("컴포넌트 1 / 2")
        // 지연 시간 필드는 서버에 없다 — "ms" 를 그리지 않는다.
        assertThat(list(two)!!.text()).doesNotContainPattern("\\d+ms")
    }

    @Test
    @DisplayName("가동 일수가 없으면 '—'(NaN·undefined 없음)")
    fun uptimeMissing() {
        val d = render(SystemInfoView("1.2.3", null, null, SystemInfoView.Host("prod"), listOf()))
        assertThat(kpi(d, "가동 시간").text()).contains("—")
        assertThat(d.text()).doesNotContain("NaN").doesNotContain("undefined").doesNotContain("null일")
    }

    @Test
    @DisplayName("불러오기 실패 → LoadError(role=alert, 일러스트 장식, 오류 정보 복사, 다시 시도 = 같은 조각을 탭 영역에), 원문 없음")
    fun loadFailure() {
        whenever(systemInfo.get()).thenThrow(IllegalStateException("boom-internal"))
        val d = render()
        val alert = d.selectFirst("[role=alert]")!!
        assertThat(alert.text()).contains("시스템 정보를 불러오지 못했습니다").doesNotContain("boom-internal")
        assertThat(alert.selectFirst("img")!!.attr("alt")).isEmpty()
        assertThat(alert.select("button").eachText()).contains("오류 정보 복사")
        val retry = alert.select("button").stream().filter { b -> b.text() == "다시 시도" }.findFirst().orElseThrow()
        assertThat(retry.attr("hx-get")).isEqualTo(SystemInfoTabController.TAB_PATH)
        assertThat(retry.attr("hx-target")).isEqualTo("#settings-tab")
        assertThat(retry.attr("hx-sync")).isEqualTo("#settings-tab:replace")
    }

    @Test
    @DisplayName("RP_ADMIN 은 403, REST 호출 없음")
    fun rpDenied() {
        val r = mvc.perform(get(SystemInfoTabController.TAB_PATH).with(authentication(auth("RP_ADMIN"))).header("HX-Request", "true")).andReturn()
        assertThat(r.response.status).isEqualTo(403)
        verify(systemInfo, never()).get()
    }

    @Test
    @DisplayName("deployedKst: 해석 가능한 시각만")
    fun deployedKstUnit() {
        assertThat(SystemInfoTabController.deployedKst("2026-05-31T00:00:00Z")).isEqualTo("2026-05-31 09:00 KST")
        assertThat(SystemInfoTabController.deployedKst("")).isNull()
        assertThat(SystemInfoTabController.deployedKst("x")).isNull()
    }
}
