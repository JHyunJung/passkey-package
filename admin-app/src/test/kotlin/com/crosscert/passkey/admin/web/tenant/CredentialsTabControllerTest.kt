package com.crosscert.passkey.admin.web.tenant

import com.crosscert.passkey.admin.audit.AuditChainMonitorController
import com.crosscert.passkey.admin.auth.ActiveTenantController
import com.crosscert.passkey.admin.auth.AdminUserDetails
import com.crosscert.passkey.admin.credential.CredentialAdminController
import com.crosscert.passkey.admin.credential.CredentialAdminDto.AuthEventView
import com.crosscert.passkey.admin.credential.CredentialAdminDto.CredentialView
import com.crosscert.passkey.admin.license.LicenseController
import com.crosscert.passkey.admin.license.LicenseView
import com.crosscert.passkey.admin.policy.AaguidPolicyController
import com.crosscert.passkey.admin.tenant.TenantAdminController
import com.crosscert.passkey.admin.tenant.TenantAdminDto
import com.crosscert.passkey.admin.web.AdminWebSliceTest
import com.crosscert.passkey.core.api.ApiResponse
import com.crosscert.passkey.core.api.BusinessException
import com.crosscert.passkey.core.api.ErrorCode
import com.crosscert.passkey.core.api.PageView
import org.assertj.core.api.Assertions.assertThat
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.regex.Pattern

/**
 * 자격 증명 탭. 원본: CredentialsTab.test.tsx, CredentialDetailDialog.test.tsx, credentialHelpers.test.ts, api/credentials.ts.
 * 브라우저에서 도는 부분(행 클릭 이벤트·다이얼로그 열기/닫기·입력 확인 게이트·디바운스)은 속성·소스 수준까지만 본다.
 *
 * (Kotlin 전환) nullable jsoup 결과(`selectFirst`·`first`·`last`)와 `trigger()` 의 `!!` 는 원 Java 의 NPE 실패 지점과 같다.
 * CSV 줄 나누기는 Java `String.split` 과 같은 결과(끝의 빈 토큰 제거)를 내도록 `Pattern.split` 을 쓴다.
 */
@AdminWebSliceTest(controllers = [CredentialsTabController::class])
class CredentialsTabControllerTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var clock: Clock
    @MockitoBean lateinit var tenants: TenantAdminController
    @MockitoBean lateinit var credentials: CredentialAdminController
    @MockitoBean lateinit var aaguid: AaguidPolicyController
    @MockitoBean lateinit var license: LicenseController
    @MockitoBean lateinit var chain: AuditChainMonitorController
    @MockitoBean lateinit var activeTenants: ActiveTenantController

    companion object {
        val JSON = ObjectMapper()
        val NOW: Instant = Instant.parse("2026-10-11T00:00:00Z")
        val T1: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val OTHER: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000ff")
        val BASE = "/admin/_/tenants/$T1"
        const val CA = "credAAAAAAAAAAAAAAAAAAAA1"
        const val CB = "credBBBBBBBBBBBBBBBBBBBB2"
        const val CC = "credCCCCCCCCCCCCCCCCCCCC3"
        const val FOREIGN = "credFOREIGNxxxxxxxxxxxx9"
        const val AAGUID_A = "ea9b8d664d8f4b9d9a0f1c3b5e7a2d41"
        const val AAGUID_B = "00000000000000000000000000000000"

        private val NEWLINE: Pattern = Pattern.compile("\n")

        // ── fixtures ────────────────────────────────────────────

        fun at(days: Double): OffsetDateTime =
            OffsetDateTime.ofInstant(NOW.plusSeconds((days * 86400).toLong()), ZoneOffset.UTC)

        fun cred(id: String, user: String, label: String?, aaguid: String?, name: String?, transports: String?, count: Long, last: OffsetDateTime?): CredentialView =
            CredentialView(id, user, label, aaguid, name, "packed", transports, count, last, at(-30.0))

        fun page(items: List<CredentialView>, total: Long): PageView<CredentialView> = PageView(items, 0, 50, total, false)

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

        private fun confirm(b: MockHttpServletRequestBuilder, a: Authentication): MockHttpServletRequestBuilder =
            htmx(b, a).header("X-Confirm", "1")

        private fun body(r: MvcResult): String = r.response.getContentAsString(StandardCharsets.UTF_8)

        private fun doc(r: MvcResult): Document = Jsoup.parse(body(r))

        private fun trigger(r: MvcResult): JsonNode? {
            val h = r.response.getHeader("HX-Trigger")
            return if (h == null) null else JSON.readTree(h)
        }

        private fun rowOf(d: Document, userHandle: String): Element =
            d.select("#cred-results tbody tr").first { tr -> tr.text().contains(userHandle) }
    }

    @BeforeEach
    fun setUp() {
        whenever(clock.instant()).thenReturn(NOW)
        whenever(clock.zone).thenReturn(ZoneOffset.UTC)
        whenever(license.get()).thenReturn(ApiResponse.ok(LicenseView("saas", "VALID", "acme", "lic_1", NOW.plusSeconds(86400L * 30), 30, listOf(), null, null, null)))
        whenever(tenants.get(T1.toString())).thenReturn(ApiResponse.ok(tenant()))
        stubList(
            listOf(
                cred(CA, "user-a", "내 폰", AAGUID_A, "YubiKey 5", "usb,nfc", 5, at(-0.01)),
                cred(CB, "user-b", null, AAGUID_B, null, "internal,hybrid", 0, at(-1.0)),
                cred(CC, "user-c", null, null, null, null, 0, null),
            ),
        )
        // 기본: id 검색(q=credentialId)은 못 찾음
        whenever(credentials.list(eq(T1), eq(0), eq(50), eq(FOREIGN))).thenReturn(ApiResponse.ok(page(listOf(), 0)))
        whenever(credentials.authEvents(eq(T1), anyOrNull(), eq(0), eq(20))).thenReturn(
            ApiResponse.ok(
                PageView(
                    listOf(AuthEventView("SUCCESS", null, 6, at(-0.01)), AuthEventView("FAILURE", "BAD_SIGNATURE", 5, at(-2.0))),
                    0, 20, 2, false,
                ),
            ),
        )
    }

    private fun stubList(items: List<CredentialView>) {
        whenever(credentials.list(eq(T1), eq(0), eq(50), isNull())).thenReturn(ApiResponse.ok(page(items, items.size.toLong())))
    }

    private fun hget(url: String, a: Authentication): MvcResult =
        mvc.perform(htmx(get(URI.create(url)), a)).andReturn()

    private fun tab(q: String): Document {
        val r = hget("$BASE/tab/credentials$q", platform())
        assertThat(r.response.status).isEqualTo(200)
        return doc(r)
    }

    // ── 탭 화면 ─────────────────────────────────────────────

    @Test
    @DisplayName("열: Credential ID(+복사)·사용자(title 전체값)·별칭·인증기(MDS 이름 → aaguid 끝 8자 → unknown)·전송 방식(한국어, 원문 title)·카운터·상태·마지막 사용·회수(ACTIVE)")
    fun rendersColumnsAndCells() {
        val r = hget("$BASE/tab/credentials", platform())
        val html = body(r)
        assertThat(html).doesNotContain("<html").doesNotContain("class=\"shell")
        val d = Jsoup.parse(html)
        assertThat(d.select("#cred-results th").eachText()).containsExactly("Credential ID", "사용자", "별칭", "인증기", "전송 방식", "서명 카운터", "상태", "마지막 사용", "회수")
        assertThat(d.select("#cred-results th .sr-only").text()).isEqualTo("회수")
        assertThat(d.select("#cred-root #cred-card #cred-results")).hasSize(1)
        assertThat(d.select("#cred-detail-slot, #cred-confirm-slot")).hasSize(2)

        val a = rowOf(d, "user-a")
        assertThat(a.select("td")[0].select(".mono").attr("title")).isEqualTo(CA)
        assertThat(a.select("button[aria-label=Credential ID 복사]")).hasSize(1)
        assertThat(a.select("td")[1].select(".mono").attr("title")).isEqualTo("user-a")
        assertThat(a.select("td")[2].text()).isEqualTo("내 폰")
        assertThat(a.select("td")[3].text()).isEqualTo("YubiKey 5")
        assertThat(a.select("td")[3].select("[title]").attr("title")).isEqualTo(AAGUID_A)
        assertThat(a.select("td")[4].select(".tag").eachText()).containsExactly("USB", "NFC")
        assertThat(a.select("td")[4].select(".tag").eachAttr("title")).containsExactly("usb", "nfc")
        assertThat(a.select("td")[5].text()).isEqualTo("5")
        assertThat(a.select("td")[6].select(".badge").text()).isEqualTo("활성")
        assertThat(a.select("td")[7].text()).isEqualTo("14분 전")
        assertThat(a.select("button[aria-label=회수 · …" + CA.substring(CA.length - 8) + "]")).hasSize(1)

        val b = rowOf(d, "user-b")
        assertThat(b.select("td")[2].text()).isEqualTo("—")
        assertThat(b.select("td")[3].text()).isEqualTo("00000000") // aaguid 끝 8자
        assertThat(b.select("td")[4].select(".tag").eachText()).containsExactly("내장", "휴대폰 연동")
        assertThat(b.select("td")[5].text()).isEqualTo("N/A") // 인증됐는데 counter 0
        assertThat(b.select("td")[5].select("[title]").attr("title")).contains("counterless")
        val c = rowOf(d, "user-c")
        assertThat(c.select("td")[3].text()).isEqualTo("unknown")
        assertThat(c.select("td")[5].text()).isEqualTo("0") // 한 번도 안 씀
        assertThat(c.select("td")[7].text()).isEqualTo("—")
    }

    @Test
    @DisplayName("행 클릭 = 상세(cred-open 이벤트 → hx-get, 상세 자리로), 셀 안 버튼·링크는 행 클릭이 아님, 키보드(Enter/Space)")
    fun rowOpensDetail() {
        val tr = rowOf(tab(""), "user-a")
        assertThat(tr.attr("hx-get")).isEqualTo("$BASE/credentials/$CA?page=1&size=50")
        assertThat(tr.attr("hx-trigger")).isEqualTo("cred-open")
        assertThat(tr.attr("hx-target")).isEqualTo("#cred-detail-slot")
        assertThat(tr.attr("hx-swap")).isEqualTo("innerHTML")
        assertThat(tr.attr("x-on:click")).contains("closest('a, button')").contains("cred-open")
        assertThat(tr.hasAttr("x-on:keydown.self.enter.prevent")).isTrue()
        assertThat(tr.attr("tabindex")).isEqualTo("0")
        // 회수 버튼은 확인 자리로 가고 행 이벤트와 별개
        val rev = tr.selectFirst("button[aria-label^=회수]")!!
        assertThat(rev.attr("hx-get")).isEqualTo("$BASE/credentials/$CA/revoke-confirm?page=1&size=50")
        assertThat(rev.attr("hx-target")).isEqualTo("#cred-confirm-slot")
    }

    @Test
    @DisplayName("credential 데이터(사용자 핸들·별칭·인증기 이름·AAGUID)는 th:text 로만: 이스케이프되고 JS 평가 속성·x-data 에 들어가지 않는다")
    fun userInfluencedDataIsEscapedAndNotInJsAttrs() {
        val evil = "<img src=x onerror=alert(1)>\"'"
        stubList(listOf(cred(CA, evil, evil, "ea9b$evil", evil, evil, 1, at(-1.0))))
        val r = hget("$BASE/tab/credentials", platform())
        val html = body(r)
        assertThat(html).doesNotContain("<img src=x").doesNotContain("onerror=alert(1)>")
        val d = Jsoup.parse(html)
        val tr = d.select("#cred-results tbody tr").first()!!
        assertThat(tr.select("td")[1].text()).isEqualTo(evil)
        assertThat(tr.select("td")[2].text()).isEqualTo(evil)
        for (e in d.select("#cred-results [x-data], #cred-results [x-on\\:click], #cred-results [x-bind\\:class]")) {
            for (at in e.attributes()) {
                if (at.key.startsWith("x-")) assertThat(at.value).`as`(at.key).doesNotContain("onerror").doesNotContain("<img")
            }
        }
    }

    @Test
    @DisplayName("검색 입력: 실제 검색 범위 placeholder + 별칭은 안 된다는 title, 현재 입력값(hx-include)을 싣고 #cred-results 만 교체(#tenant-tab 아님)")
    fun searchInputContract() {
        val d = tab("?q=%20abc%20")
        val q = d.selectFirst("#cred-q")!!
        assertThat(q.attr("placeholder")).isEqualTo("Credential ID · 사용자 ID 검색")
        assertThat(q.attr("title")).isEqualTo("Credential ID 또는 사용자 ID 의 일부로 찾습니다. 별칭·인증기 이름은 검색되지 않습니다.")
        assertThat(q.attr("aria-label")).isEqualTo("Credential 검색")
        assertThat(q.`val`()).isEqualTo("abc") // 앞뒤 공백 제거
        assertThat(q.attr("hx-get")).isEqualTo("$BASE/tab/credentials/list")
        assertThat(q.attr("hx-trigger")).contains("input changed delay:300ms")
        assertThat(q.attr("hx-include")).contains("#cred-aaguid").contains("#cred-status").contains("#cred-size")
        assertThat(q.attr("hx-target")).isEqualTo("#cred-results")
        assertThat(q.attr("hx-swap")).isEqualTo("outerHTML")
        // 탭 영역(#tenant-tab)을 교체하는 요청은 이 조각 안에 없다
        for (e in d.select("[hx-target]")) assertThat(e.attr("hx-target")).isNotEqualTo("#tenant-tab")
        assertThat(d.select("#cred-card").attr("hx-sync")).isEqualTo("this:replace")
        // 검색어는 서버 REST q 로(공백 제거)
        verify(credentials).list(eq(T1), eq(0), eq(50), eq("abc"))
    }

    @Test
    @DisplayName("페이징·크기·필터 컨트롤은 현재 입력값(q·aaguid·status·size)을 hx-include 로 싣는다 — 새 요청이 입력을 잃지 않는다")
    fun pagingControlsIncludeCurrentInputs() {
        val many = (0 until 50).map { i -> cred("c" + "x".repeat(20) + i, "u$i", null, null, null, null, 1, null) }
        whenever(credentials.list(eq(T1), eq(0), eq(50), isNull())).thenReturn(ApiResponse.ok(page(many, 120)))
        val d = doc(hget("$BASE/tab/credentials/list?status=ACTIVE&aaguid=ea9b", platform()))
        val next = d.selectFirst("button[aria-label=다음 페이지]")!!
        assertThat(next.attr("hx-get")).isEqualTo("$BASE/tab/credentials/list?page=2")
        assertThat(next.attr("hx-include")).contains("#cred-q").contains("#cred-aaguid").contains("#cred-status").contains("#cred-size")
        assertThat(next.attr("hx-target")).isEqualTo("#cred-results")
        assertThat(d.selectFirst("button[aria-label=이전 페이지]")!!.hasAttr("disabled")).isTrue()
        assertThat(d.select(".pager > span").first()!!.text()).isEqualTo("1–50 / 120건")
        assertThat(d.select(".pager > span").last()!!.text()).isEqualTo("1 / 3")
        val size = d.selectFirst(".pager select")!!
        assertThat(size.select("option").eachText()).containsExactly("50", "100", "200")
        assertThat(size.attr("hx-include")).contains("#cred-q").contains("#cred-aaguid").contains("#cred-status").doesNotContain("#cred-size")
        assertThat(size.attr("hx-trigger")).isEqualTo("change")
        assertThat(d.select("#cred-status").`val`()).isEqualTo("ACTIVE")
        assertThat(d.select("#cred-size").`val`()).isEqualTo("50")
    }

    @Test
    @DisplayName("목록 조각: 레이아웃 없음, 페이지·크기·검색어가 REST 로(page-1), 숫자가 아닌 page·size 는 기본값, 크기는 50/100/200 만")
    fun listFragmentParamsAndDefaults() {
        val r = hget("$BASE/tab/credentials/list?page=abc&size=zzz&q=x", platform())
        assertThat(r.response.status).isEqualTo(200)
        assertThat(body(r)).doesNotContain("<html").doesNotContain("class=\"shell")
        verify(credentials).list(eq(T1), eq(0), eq(50), eq("x"))
        whenever(credentials.list(eq(T1), eq(1), eq(100), isNull())).thenReturn(ApiResponse.ok(PageView(listOf(cred(CB, "p2", null, null, null, null, 1, null)), 1, 100, 101, false)))
        val d = doc(hget("$BASE/tab/credentials/list?page=2&size=100", platform()))
        assertThat(d.select("#cred-results tbody tr")).hasSize(1)
        assertThat(d.select(".pager > span").last()!!.text()).isEqualTo("2 / 2")
        assertThat(d.select("#cred-size").`val`()).isEqualTo("100")
        whenever(credentials.list(eq(T1), eq(0), eq(50), isNull())).thenReturn(ApiResponse.ok(page(listOf(), 0)))
        hget("$BASE/tab/credentials/list?size=77&page=0", platform())
        verify(credentials).list(eq(T1), eq(0), eq(50), isNull())
    }

    @Test
    @DisplayName("지운 뒤 마지막 페이지가 비어 버리면 새 마지막 페이지로 한 번 보정")
    fun pageBeyondLastClamps() {
        whenever(credentials.list(eq(T1), eq(2), eq(50), isNull())).thenReturn(ApiResponse.ok(PageView(listOf(), 2, 50, 100, false)))
        whenever(credentials.list(eq(T1), eq(1), eq(50), isNull())).thenReturn(ApiResponse.ok(PageView(listOf(cred(CA, "last-one", null, null, null, null, 1, null)), 1, 50, 100, false)))
        val d = doc(hget("$BASE/tab/credentials/list?page=3", platform()))
        assertThat(d.select("#cred-results tbody tr").text()).contains("last-one")
        assertThat(d.select(".pager > span").last()!!.text()).isEqualTo("2 / 2")
    }

    @Test
    @DisplayName("목록 응답에는 필터 컨트롤 OOB(건수·CSV)가 들어 있고, 검색 입력·필터 패널은 reset 일 때만 OOB(타이핑 중 되돌려 쓰지 않는다)")
    fun listResponseOob() {
        val d = doc(hget("$BASE/tab/credentials/list?q=a", platform()))
        assertThat(d.selectFirst("#cred-controls")!!.attr("hx-swap-oob")).isEqualTo("true")
        assertThat(d.select("#cred-q")).isEmpty()
        assertThat(d.select("#cred-filterpanel")).isEmpty()
        val all = doc(hget("$BASE/tab/credentials/list?reset=all&q=a&aaguid=zz&status=ACTIVE", platform()))
        assertThat(all.selectFirst("#cred-q")!!.attr("hx-swap-oob")).isEqualTo("true")
        assertThat(all.selectFirst("#cred-q")!!.`val`()).isEmpty()
        assertThat(all.selectFirst("#cred-filterpanel")!!.attr("hx-swap-oob")).isEqualTo("true")
        assertThat(all.selectFirst("#cred-aaguid")!!.`val`()).isEmpty()
        assertThat(all.select("#cred-status").`val`()).isEqualTo("ALL")
        assertThat(all.select(".chip")).isEmpty()
        val ag = doc(hget("$BASE/tab/credentials/list?reset=aaguid&q=a&aaguid=zz", platform()))
        assertThat(ag.select("#cred-q")).isEmpty() // 검색어는 그대로(입력이 보낸 값)
        assertThat(ag.selectFirst("#cred-aaguid")!!.`val`()).isEmpty()
    }

    @Test
    @DisplayName("건수·CSV 라벨: 필터가 없으면 서버 총 건수, 있으면 '이 페이지 N건 중 M건'")
    fun countAndCsvLabels() {
        whenever(credentials.list(eq(T1), eq(0), eq(50), isNull())).thenReturn(
            ApiResponse.ok(
                page(listOf(cred(CA, "user-a", null, AAGUID_A, null, null, 1, null), cred(CB, "user-b", null, AAGUID_B, null, null, 1, null)), 1234),
            ),
        )
        val d = tab("")
        assertThat(d.select("#cred-controls .hint").text()).isEqualTo("1,234건")
        assertThat(d.select("#cred-controls a.btn").text()).isEqualTo("CSV 내보내기 (현재 페이지 2건)")
        val f = doc(hget("$BASE/tab/credentials/list?aaguid=EA9B8D66-4D8F", platform()))
        assertThat(f.select("#cred-controls .hint").text()).isEqualTo("이 페이지 2건 중 1건")
        assertThat(f.select("#cred-controls a.btn").text()).isEqualTo("CSV 내보내기 (현재 페이지 1건)")
        assertThat(f.select("#cred-results tbody tr")).hasSize(1)
        assertThat(f.select("#cred-results tbody").text()).contains("user-a").doesNotContain("user-b")
    }

    @Test
    @DisplayName("필터: aaguid 는 하이픈·대소문자 무시 부분 일치, 상태는 현재 페이지 안(서버 뷰에 status 없음 → 모두 활성, 회수됨은 0건), 적용 칩 + 제거 속성")
    fun clientFilterAndChips() {
        val d = doc(hget("$BASE/tab/credentials/list?status=REVOKED&aaguid=ea9b", platform()))
        assertThat(d.select("#cred-results tbody tr")).hasSize(1)
        assertThat(d.select("#cred-results .empty__title").text()).isEqualTo("검색 결과가 없습니다.")
        assertThat(d.select("#cred-results .empty__sub").text()).isEqualTo("검색어나 필터를 바꿔 보세요.")
        assertThat(d.select("#cred-results .empty__art").attr("src")).endsWith("/admin/img/no-results.webp")
        val clear = d.select("#cred-results .empty button").first { e -> e.text() == "필터 지우기" }
        assertThat(clear.attr("hx-get")).isEqualTo("$BASE/tab/credentials/list?reset=all")
        assertThat(clear.attr("hx-target")).isEqualTo("#cred-results")
        val chip = d.select("#cred-controls .chip").first { e -> e.text().startsWith("aaguid:") }
        assertThat(chip.text()).isEqualTo("aaguid: ea9b")
        assertThat(chip.selectFirst("button")!!.attr("aria-label")).isEqualTo("aaguid 필터 제거")
        assertThat(chip.selectFirst("button")!!.attr("hx-get")).isEqualTo("$BASE/tab/credentials/list?reset=aaguid")
        val st = d.select("#cred-controls .chip").first { e -> e.text().startsWith("상태:") }
        assertThat(st.text()).isEqualTo("상태: 회수됨")
        assertThat(st.attr("title")).isEqualTo("REVOKED")
        assertThat(st.selectFirst("button")!!.attr("aria-label")).isEqualTo("상태 필터 제거")
        // 상태 필터 패널: 세그먼트(전체·활성·회수됨, title=원문)
        val panel = d.selectFirst("#cred-filterpanel")
        assertThat(panel).isNull() // 패널은 처음 렌더에만(reset 이 아니면 OOB 로 되돌려 쓰지 않음)
        val t = tab("?status=ACTIVE")
        assertThat(t.select("#cred-filterpanel .seg__btn").eachText()).containsExactly("전체", "활성", "회수됨")
        assertThat(t.select("#cred-filterpanel .seg__btn").eachAttr("title")).contains("ACTIVE", "REVOKED")
        assertThat(t.select("#cred-filterpanel .seg__btn--on").text()).isEqualTo("활성")
        val seg = t.select("#cred-filterpanel .seg__btn")[2]
        assertThat(seg.attr("hx-get")).isEqualTo("$BASE/tab/credentials/list?status=REVOKED")
        assertThat(seg.attr("hx-include")).contains("#cred-q").contains("#cred-aaguid")
        assertThat(t.selectFirst("#cred-aaguid")!!.attr("placeholder")).isEqualTo("예: ea9b8d66")
        assertThat(t.select("#cred-filterpanel .hint").text()).isEqualTo("현재 페이지 안에서 거릅니다.")
        assertThat(t.selectFirst("#cred-filterpanel")!!.attr("x-show")).isEqualTo("filtersOpen")
        assertThat(t.selectFirst("#cred-controls .fchip")!!.text()).isEqualTo("필터 · aaguid · 상태")
    }

    @Test
    @DisplayName("빈 상태: credential 이 하나도 없으면 일러스트 + 안내(필터 지우기 없음), 검색 결과 0 은 결과 없음")
    fun emptyStates() {
        whenever(credentials.list(eq(T1), eq(0), eq(50), isNull())).thenReturn(ApiResponse.ok(page(listOf(), 0)))
        val d = tab("")
        assertThat(d.select(".empty__title").text()).isEqualTo("Credential이 없습니다")
        assertThat(d.select(".empty__sub").text()).isEqualTo("사용자가 RP 에서 패스키를 등록하면 여기에 나타납니다.")
        assertThat(d.select(".empty__art").attr("src")).endsWith("/admin/img/empty-credentials.webp")
        assertThat(d.select(".empty button")).isEmpty()
        whenever(credentials.list(eq(T1), eq(0), eq(50), eq("zzz"))).thenReturn(ApiResponse.ok(page(listOf(), 0)))
        val s = doc(hget("$BASE/tab/credentials/list?q=zzz", platform()))
        assertThat(s.select(".empty__title").text()).isEqualTo("검색 결과가 없습니다.")
        assertThat(s.select("#cred-controls button").first { b -> b.text().startsWith("CSV") }.hasAttr("disabled")).isTrue()
    }

    @Test
    @DisplayName("로드 실패: '없음'이 아니라 LoadError + 다시 시도(#cred-results 만 교체), 건수 —, CSV 비활성, 페이지네이션 없음, 내부 메시지 비노출")
    fun loadError() {
        whenever(credentials.list(eq(T1), eq(0), eq(50), isNull())).thenThrow(IllegalStateException("db down secret-sql"))
        val r = hget("$BASE/tab/credentials", platform())
        assertThat(r.response.status).isEqualTo(200)
        val html = body(r)
        assertThat(html).doesNotContain("secret-sql").doesNotContain("Credential이 없습니다")
        val d = Jsoup.parse(html)
        assertThat(d.select("[role=alert] .empty__title").text()).isEqualTo("Credential 목록을 불러오지 못했습니다")
        val retry = d.select("button").first { e -> e.text() == "다시 시도" }
        assertThat(retry.attr("hx-get")).isEqualTo("$BASE/tab/credentials/list?page=1&size=50")
        assertThat(retry.attr("hx-target")).isEqualTo("#cred-results")
        assertThat(d.select("#cred-controls .hint").text()).isEqualTo("—")
        assertThat(d.select("#cred-controls button[disabled]").any { b -> b.text().startsWith("CSV") }).isTrue()
        assertThat(d.select(".pager")).isEmpty()
    }

    @Test
    @DisplayName("hx-* 주소는 사용자 입력을 인코딩해 담는다(따옴표·&·꺾쇠가 속성을 깨지 못함)")
    fun urlsEncodeUserInput() {
        val q = "a&b\"<c>"
        whenever(credentials.list(eq(T1), eq(0), eq(50), eq(q))).thenReturn(ApiResponse.ok(page(listOf(cred(CA, "u", null, null, null, null, 1, null)), 1)))
        val r = hget("$BASE/tab/credentials/list?q=" + java.net.URLEncoder.encode(q, StandardCharsets.UTF_8), platform())
        assertThat(body(r)).doesNotContain("<c>")
        val tr = doc(r).select("#cred-results tbody tr").first()!!
        assertThat(tr.attr("hx-get")).contains("q=a%26b%22%3Cc%3E")
    }

    @Test
    @DisplayName("RP_ADMIN 자기 tenant 는 탭이 보인다")
    fun rpAdminOwnTenant() {
        val r = hget("$BASE/tab/credentials", rp(T1))
        assertThat(r.response.status).isEqualTo(200)
        assertThat(doc(r).select("#cred-results tbody tr")).hasSize(3)
    }

    // ── CSV ─────────────────────────────────────────────────

    @Test
    @DisplayName("CSV: 헤더·열 순서·transports '|' 구분·현재 페이지에 보이는 행만, 파일명 credentials-{slug}-{KST 오늘}.csv")
    fun csvExport() {
        val r = mvc.perform(htmx(get(URI.create("$BASE/credentials.csv?aaguid=ea9b")), platform())).andReturn()
        assertThat(r.response.status).isEqualTo(200)
        assertThat(r.response.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"credentials-acme-2026-10-11.csv\"")
        val csv = r.response.getContentAsString(StandardCharsets.UTF_8)
        val lines = NEWLINE.split(csv.replace("﻿", ""))
        assertThat(lines[0]).isEqualTo("credentialId,externalUserId,label,authenticatorName,status,aaguid,transports,signatureCounter,lastUsedAt,createdAt")
        assertThat(lines).hasSize(2)
        assertThat(lines[1]).startsWith("$CA,user-a,내 폰,YubiKey 5,ACTIVE,$AAGUID_A,usb|nfc,5,")
        // 컨트롤의 CSV 링크가 같은 상태를 가리킨다
        assertThat(tab("?aaguid=ea9b").selectFirst("#cred-controls a.btn")!!.attr("href")).isEqualTo("$BASE/credentials.csv?page=1&size=50&aaguid=ea9b")
    }

    @Test
    @DisplayName("CSV 링크는 공통 다운로드 표식(data-csv-download)")
    fun csvLinkCarriesMarker() {
        assertThat(tab("").selectFirst("#cred-controls a[download]")!!.hasAttr("data-csv-download")).isTrue()
    }

    // ── 상세 ────────────────────────────────────────────────

    @Test
    @DisplayName("상세: 이미 가진 값으로 식별 정보·인증기 섹션(dlg-sec), 인증 기록 표(결과 배지·사유·카운터), 열린 채(open=true), 푸터 회수·닫기, 조각만")
    fun detailRenders() {
        val r = hget("$BASE/credentials/$CA", platform())
        assertThat(r.response.status).isEqualTo(200)
        assertThat(body(r)).doesNotContain("<html").doesNotContain("class=\"shell")
        val d = doc(r)
        assertThat(d.selectFirst("[x-data^=dialog]")!!.attr("x-data")).contains("\"id\":\"cred-detail\"").contains("\"open\":true")
        val dlg = d.selectFirst("[role=dialog][aria-labelledby=cred-detail-title]")!!
        assertThat(dlg.select("#cred-detail-title").text()).startsWith("Credential 상세").contains("활성")
        assertThat(dlg.select(".dialog__sub .mono").text()).isEqualTo(CA)
        assertThat(dlg.select(".dialog__sub button[aria-label=credentialId 복사]")).hasSize(1)
        assertThat(dlg.select("section.dlg-sec > .t-over").eachText()).containsExactly("식별 정보", "인증기", "인증 기록 (최근 20건)")
        assertThat(dlg.select("dl")[0].select("dt").eachText()).containsExactly("externalUserId", "별칭", "생성", "마지막 사용")
        assertThat(dlg.select("dl")[0].select("dd").eachText()).containsExactly("user-a", "내 폰", "2026-09-11 09:00", "14분 전")
        assertThat(dlg.select("dl")[1].select("dt").eachText()).containsExactly("인증기", "전송 방식", "서명 카운터", "AAGUID", "attestation")
        assertThat(dlg.select("dl")[1].select("dd").eachText()).containsExactly("YubiKey 5", "usbnfc", "5", AAGUID_A, "packed")
        assertThat(dlg.select("dl")[1].select(".tag").eachText()).containsExactly("usb", "nfc") // 원문(번역 없음)
        assertThat(dlg.select("table th").eachText()).containsExactly("시간", "결과", "사유", "서명 카운터")
        assertThat(dlg.select("table tbody tr")[0].select("td").eachText()).containsExactly("2026-10-11 08:45", "성공", "—", "6")
        val fail = dlg.select("table tbody tr")[1]
        assertThat(fail.select(".badge").text()).isEqualTo("실패")
        assertThat(fail.select(".badge").hasClass("badge--danger")).isTrue()
        assertThat(fail.select(".tag").text()).isEqualTo("BAD_SIGNATURE")
        assertThat(dlg.select(".dialog__foot-start button").text()).isEqualTo("Credential 회수…")
        assertThat(dlg.select(".dialog__foot > button").text()).isEqualTo("닫기")
        verify(credentials).authEvents(T1, CA, 0, 20)
    }

    @Test
    @DisplayName("상세: 값이 없으면 —/unknown, 인증 이력이 없으면 안내, 이동 UI 는 목록 안에서 찾았을 때만")
    fun detailEmptyValuesAndEvents() {
        whenever(credentials.authEvents(eq(T1), eq(CC), eq(0), eq(20))).thenReturn(ApiResponse.ok(PageView(listOf(), 0, 20, 0, false)))
        val d = doc(hget("$BASE/credentials/$CC", platform()))
        assertThat(d.select("dl")[0].select("dd").eachText()).containsExactly("user-c", "—", "2026-09-11 09:00", "—")
        assertThat(d.select("dl")[1].select("dd").eachText()).containsExactly("unknown", "—", "0", "—", "packed")
        assertThat(d.select(".dlg-sec p.muted").text()).isEqualTo("아직 인증 이력이 없습니다.")
        assertThat(d.select("table")).isEmpty()
    }

    @Test
    @DisplayName("상세: 인증 기록 조회 실패는 그 섹션만 compact LoadError(+다시 시도), 상세 값은 그대로, 내부 메시지 비노출")
    fun detailEventsFailureIsLocal() {
        whenever(credentials.authEvents(eq(T1), eq(CA), eq(0), eq(20))).thenThrow(IllegalStateException("boom secret-trace"))
        val r = hget("$BASE/credentials/$CA?page=1&size=50", platform())
        assertThat(r.response.status).isEqualTo(200)
        assertThat(body(r)).doesNotContain("secret-trace")
        val d = doc(r)
        assertThat(d.select("[role=alert] .empty__title").text()).isEqualTo("인증 기록을 불러오지 못했습니다")
        assertThat(d.selectFirst("[role=alert]")!!.hasClass("empty--compact")).isTrue()
        assertThat(d.select("dl")[0].select("dd").first()!!.text()).isEqualTo("user-a")
        val retry = d.select("[role=alert] button").first { e -> e.text() == "다시 시도" }
        assertThat(retry.attr("hx-get")).isEqualTo("$BASE/credentials/$CA?page=1&size=50")
        assertThat(retry.attr("hx-target")).isEqualTo("#cred-detail-slot")
    }

    @Test
    @DisplayName("상세 이전/다음: 페이지 안은 바로, 위치 'index / total', 처음·끝에서 해당 방향 비활성(첫 페이지 첫 항목)")
    fun detailNavWithinPage() {
        val mid = doc(hget("$BASE/credentials/$CB?page=1&size=50", platform()))
        assertThat(mid.select("#cred-detail-title .hint").text()).isEqualTo("2 / 3")
        val prev = mid.selectFirst("button[aria-label=이전 credential]")!!
        val next = mid.selectFirst("button[aria-label=다음 credential]")!!
        assertThat(prev.attr("hx-get")).isEqualTo("$BASE/credentials/$CA?page=1&size=50")
        assertThat(next.attr("hx-get")).isEqualTo("$BASE/credentials/$CC?page=1&size=50")
        assertThat(prev.attr("hx-target")).isEqualTo("#cred-detail-slot")
        val first = doc(hget("$BASE/credentials/$CA?page=1&size=50", platform()))
        assertThat(first.selectFirst("button[aria-label=이전 credential]")!!.hasAttr("disabled")).isTrue()
        assertThat(first.selectFirst("button[aria-label=다음 credential]")!!.hasAttr("disabled")).isFalse()
        val last = doc(hget("$BASE/credentials/$CC?page=1&size=50", platform()))
        assertThat(last.selectFirst("button[aria-label=다음 credential]")!!.hasAttr("disabled")).isTrue()
    }

    @Test
    @DisplayName("상세 이전/다음이 페이지 경계를 넘으면 이웃 페이지의 마지막/첫 항목(list=1 → 그 페이지 목록도 OOB), 필터가 있으면 경계에서 비활성")
    fun detailNavAcrossPages() {
        // 2페이지(size 50, 총 120): 첫 항목 CB → 이전 = 1페이지 마지막, 마지막 항목 → 다음 = 3페이지 첫 항목
        val p2 = listOf(cred(CB, "user-b", null, null, null, null, 1, null), cred(CC, "user-c", null, null, null, null, 1, null))
        whenever(credentials.list(eq(T1), eq(1), eq(50), isNull())).thenReturn(ApiResponse.ok(PageView(p2, 1, 50, 120, true)))
        whenever(credentials.list(eq(T1), eq(0), eq(50), isNull())).thenReturn(ApiResponse.ok(page(listOf(cred(CA, "p1-last", null, null, null, null, 1, null)), 120)))
        whenever(credentials.list(eq(T1), eq(2), eq(50), isNull())).thenReturn(ApiResponse.ok(PageView(listOf(cred("credNEXT0000000000000000", "p3-first", null, null, null, null, 1, null)), 2, 50, 120, false)))
        val d = doc(hget("$BASE/credentials/$CB?page=2&size=50", platform()))
        assertThat(d.select("#cred-detail-title .hint").text()).isEqualTo("51 / 120")
        assertThat(d.selectFirst("button[aria-label=이전 credential]")!!.attr("hx-get")).isEqualTo("$BASE/credentials/$CA?page=1&size=50&list=1")
        val e = doc(hget("$BASE/credentials/$CC?page=2&size=50", platform()))
        assertThat(e.selectFirst("button[aria-label=다음 credential]")!!.attr("hx-get")).isEqualTo("$BASE/credentials/credNEXT0000000000000000?page=3&size=50&list=1")
        // list=1 응답: 상세 + 그 페이지 목록 OOB
        val oob = doc(hget("$BASE/credentials/$CA?page=1&size=50&list=1", platform()))
        assertThat(oob.selectFirst("#cred-results")!!.attr("hx-swap-oob")).isEqualTo("true")
        assertThat(oob.selectFirst("#cred-controls")!!.attr("hx-swap-oob")).isEqualTo("true")
        assertThat(oob.select("#cred-results tbody").text()).contains("p1-last")
        // list=1 이 아니면 목록 OOB 없음
        assertThat(doc(hget("$BASE/credentials/$CB?page=2&size=50", platform())).select("#cred-results")).isEmpty()
        // 필터가 있으면 경계에서 이웃 페이지로 넘지 않는다
        val f = doc(hget("$BASE/credentials/$CB?page=2&size=50&aaguid=zz", platform()))
        assertThat(f.select("#cred-detail-title .hint")).isEmpty() // zz 에 맞는 항목이 없어 창에서 못 찾음 → 이동 UI 없음
        verify(credentials, never()).list(eq(T1), eq(3), eq(50), anyOrNull())
    }

    @Test
    @DisplayName("상세: 목록 창에 없으면 id 검색으로 찾고(이동 UI 없음), 그래도 없으면 404 + authEvents 호출 없음")
    fun detailLookupFallbackAndNotFound() {
        stubList(listOf(cred(CA, "user-a", null, null, null, null, 1, null)))
        whenever(credentials.list(eq(T1), eq(0), eq(50), eq(CB))).thenReturn(ApiResponse.ok(page(listOf(cred(CB, "via-search", null, null, null, null, 1, null)), 1)))
        val d = doc(hget("$BASE/credentials/$CB", platform()))
        assertThat(d.select("dl")[0].select("dd").first()!!.text()).isEqualTo("via-search")
        assertThat(d.select("#cred-detail-title .hint")).isEmpty()
        val nf = hget("$BASE/credentials/$FOREIGN", platform())
        assertThat(nf.response.status).isEqualTo(404)
        verify(credentials, never()).authEvents(anyOrNull(), eq(FOREIGN), anyInt(), anyInt())
        // base64url 이 아닌 id 는 REST 를 부르지 않고 404
        val bad = hget("$BASE/credentials/not%20valid!", platform())
        assertThat(bad.response.status).isIn(404, 400)
    }

    // ── 경계 ────────────────────────────────────────────────

    @Test
    @DisplayName("RP_ADMIN 이 다른 tenant 의 탭·목록·상세·회수 확인·회수·CSV → 403, REST 목록·이벤트·변경 호출 없음")
    fun rpAdminOtherTenantDenied() {
        whenever(tenants.get(OTHER.toString())).thenThrow(BusinessException(ErrorCode.ACCESS_DENIED, "RP_ADMIN cannot access tenant $OTHER"))
        val base = "/admin/_/tenants/$OTHER"
        val rp = rp(T1)
        assertThat(hget("$base/tab/credentials", rp).response.status).isEqualTo(403)
        assertThat(hget("$base/tab/credentials/list", rp).response.status).isEqualTo(403)
        assertThat(hget("$base/credentials/$CA", rp).response.status).isEqualTo(403)
        assertThat(hget("$base/credentials/$CA/revoke-confirm", rp).response.status).isEqualTo(403)
        assertThat(hget("$base/credentials.csv", rp).response.status).isEqualTo(403)
        assertThat(mvc.perform(confirm(delete(URI.create("$base/credentials/$CA")), rp)).andReturn().response.status).isEqualTo(403)
        verify(credentials, never()).list(anyOrNull(), anyInt(), anyInt(), anyOrNull())
        verify(credentials, never()).authEvents(anyOrNull(), anyOrNull(), anyInt(), anyInt())
        verify(credentials, never()).revoke(anyOrNull(), anyOrNull(), anyOrNull())
    }

    @Test
    @DisplayName("자기 tenant id 에 다른 tenant 의 credential id → 상세·회수 확인·회수 모두 404, authEvents·revoke 호출 없음")
    fun foreignCredentialViaOwnTenantNotTouched() {
        val rp = rp(T1)
        assertThat(hget("$BASE/credentials/$FOREIGN", rp).response.status).isEqualTo(404)
        assertThat(hget("$BASE/credentials/$FOREIGN/revoke-confirm", rp).response.status).isEqualTo(404)
        assertThat(mvc.perform(confirm(delete(URI.create("$BASE/credentials/$FOREIGN")), rp)).andReturn().response.status).isEqualTo(404)
        verify(credentials, never()).authEvents(anyOrNull(), anyOrNull(), anyInt(), anyInt())
        verify(credentials, never()).revoke(anyOrNull(), anyOrNull(), anyOrNull())
        // 목록이 (잘못) 같은 id 를 섞어 줘도 정확히 같은 id 만 인정 — 부분 일치로 다른 credential 을 지우지 않는다
        whenever(credentials.list(eq(T1), eq(0), eq(50), eq(CA + "x"))).thenReturn(ApiResponse.ok(page(listOf(cred(CA, "u", null, null, null, null, 1, null)), 1)))
        assertThat(mvc.perform(confirm(delete(URI.create("$BASE/credentials/${CA}x")), rp)).andReturn().response.status).isEqualTo(404)
        verify(credentials, never()).revoke(anyOrNull(), anyOrNull(), anyOrNull())
    }

    // ── 회수 ────────────────────────────────────────────────

    @Test
    @DisplayName("회수 확인: L3 — 마지막 8자 입력(expected), 확인 버튼 속성(X-Confirm·DELETE 주소에 현재 목록 상태), 본문 KV, 열린 채 응답")
    fun revokeConfirmDialog() {
        whenever(credentials.list(eq(T1), eq(0), eq(50), eq("user"))).thenReturn(
            ApiResponse.ok(page(listOf(cred(CA, "user-a", "내 폰", AAGUID_A, "YubiKey 5", "usb,nfc", 5, at(-0.01))), 1)),
        )
        val r = hget("$BASE/credentials/$CA/revoke-confirm?page=1&size=50&q=user", platform())
        assertThat(r.response.status).isEqualTo(200)
        val d = doc(r)
        assertThat(d.selectFirst("[x-init]")!!.attr("x-init")).contains("dialog:open").contains("cred-revoke")
        val dlg = d.selectFirst("[x-data^=dialog]")!!
        assertThat(dlg.attr("x-data")).contains("\"id\":\"cred-revoke\"").contains("\"level\":\"L3\"").contains("\"typing\":true")
        val tail8 = CA.substring(CA.length - 8)
        assertThat(d.selectFirst("[x-data^=confirmByTyping]")!!.attr("x-data")).contains("\"expected\":\"$tail8\"")
        assertThat(d.select("label.label").text()).isEqualTo("확인을 위해 마지막 8자 $tail8 를 입력하세요")
        assertThat(d.select(".dialog__title").text()).isEqualTo("Credential을 회수할까요?")
        assertThat(d.select(".dialog__sub").text()).isEqualTo("이 사용자는 해당 패스키로 더 이상 로그인할 수 없습니다. 되돌릴 수 없습니다.")
        assertThat(d.select("dl dt").eachText()).containsExactly("credentialId", "사용자", "인증기")
        assertThat(d.select("dl dd").eachText()).containsExactly("…$tail8", "user-a", "YubiKey 5")
        val ok = d.selectFirst("button[data-confirm]")!!
        assertThat(ok.attr("hx-delete")).isEqualTo("$BASE/credentials/$CA?page=1&size=50&q=user")
        assertThat(ok.attr("hx-target")).isEqualTo("#cred-results")
        assertThat(ok.attr("hx-swap")).isEqualTo("outerHTML")
        assertThat(ok.attr("hx-headers")).contains("X-Confirm")
        assertThat(ok.hasClass("btn--danger-solid")).isTrue()
        assertThat(ok.text()).contains("회수")
    }

    @Test
    @DisplayName("회수: X-Confirm 없으면 400 + REST 호출 없음")
    fun revokeRequiresConfirmHeader() {
        val r = mvc.perform(htmx(delete(URI.create("$BASE/credentials/$CA")), platform())).andReturn()
        assertThat(r.response.status).isEqualTo(400)
        verify(credentials, never()).revoke(anyOrNull(), anyOrNull(), anyOrNull())
    }

    @Test
    @DisplayName("회수: REST revoke(tenant, credentialId, auth), 성공 토스트(제목+끝 12자·사용자), 갱신된 목록(#cred-results 본 대상)+OOB 컨트롤")
    fun revokeCallsRestAndRefreshes() {
        @Suppress("UNUSED_VARIABLE")
        val holder = arrayOfNulls<MvcResult>(1)
        whenever(credentials.revoke(eq(T1), eq(CA), anyOrNull())).thenAnswer {
            stubList(listOf(cred(CB, "user-b", null, null, null, null, 1, null)))
            ResponseEntity.ok(ApiResponse.ok())
        }
        val r = mvc.perform(confirm(delete(URI.create("$BASE/credentials/$CA?page=1&size=50")), platform())).andReturn()
        assertThat(r.response.status).isEqualTo(200)
        verify(credentials).revoke(eq(T1), eq(CA), any<Authentication>())
        val t = trigger(r)!!
        val toast = t.get("toast")
        assertThat(toast.get("kind").asString()).isEqualTo("success")
        assertThat(toast.get("title").asString()).isEqualTo("Credential을 회수했습니다.")
        assertThat(toast.get("message").asString()).isEqualTo(CA.substring(CA.length - 12) + " · user-a")
        val d = doc(r)
        val res = d.selectFirst("#cred-results")!!
        assertThat(res.hasAttr("hx-swap-oob")).isFalse() // 본 대상(outerHTML)
        assertThat(res.select("tbody tr")).hasSize(1)
        assertThat(res.text()).contains("user-b").doesNotContain("user-a")
        assertThat(d.selectFirst("#cred-controls")!!.attr("hx-swap-oob")).isEqualTo("true")
    }

    @Test
    @DisplayName("회수 성공 뒤 목록 새로고침이 실패해도 회수는 성공(토스트) — 목록 자리에 LoadError")
    fun revokeSucceedsEvenIfRefreshFails() {
        whenever(credentials.revoke(eq(T1), eq(CA), anyOrNull())).thenAnswer {
            whenever(credentials.list(eq(T1), eq(0), eq(50), isNull())).thenThrow(IllegalStateException("db down"))
            ResponseEntity.ok(ApiResponse.ok())
        }
        val r = mvc.perform(confirm(delete(URI.create("$BASE/credentials/$CA")), platform())).andReturn()
        assertThat(r.response.status).isEqualTo(200)
        assertThat(trigger(r)!!.get("toast").get("title").asString()).isEqualTo("Credential을 회수했습니다.")
        assertThat(doc(r).select("#cred-results [role=alert] .empty__title").text()).isEqualTo("Credential 목록을 불러오지 못했습니다")
    }

    @Test
    @DisplayName("회수 실패는 확인 다이얼로그 안 인라인(confirmError), 토스트 없음")
    fun revokeFailureIsInline() {
        whenever(credentials.revoke(eq(T1), eq(CA), anyOrNull())).thenThrow(BusinessException(ErrorCode.ENTITY_NOT_FOUND, "credential 없음"))
        val r = mvc.perform(confirm(delete(URI.create("$BASE/credentials/$CA")), platform())).andReturn()
        assertThat(r.response.status).isGreaterThanOrEqualTo(400)
        val t = trigger(r)!!
        assertThat(t.has("confirmError")).isTrue()
        assertThat(t.has("toast")).isFalse()
    }

    @Test
    @DisplayName("fix1: 상세 이전/다음·인증 기록 다시 시도 버튼은 cred-open 이 아니라 click(기본)으로 발동 — 렌더된 hx-trigger")
    fun detailButtonsFireOnClick() {
        val mid = doc(hget("$BASE/credentials/$CB?page=1&size=50", platform()))
        for (label in listOf("이전 credential", "다음 credential")) {
            val b = mid.selectFirst("button[aria-label=$label]")!!
            assertThat(b.attr("hx-trigger")).`as`(label).isIn("click", "")
            assertThat(b.attr("hx-trigger")).doesNotContain("cred-open")
        }
        whenever(credentials.authEvents(eq(T1), eq(CA), eq(0), eq(20))).thenThrow(IllegalStateException("x"))
        val d = doc(hget("$BASE/credentials/$CA?page=1&size=50&list=1", platform()))
        val retry = d.select("[role=alert] button").first { e -> e.text() == "다시 시도" }
        assertThat(retry.attr("hx-trigger")).doesNotContain("cred-open")
        assertThat(retry.attr("hx-target")).isEqualTo("#cred-detail-slot") // list=1 이어도 목록 재시도로 덮이지 않는다
        // 행만 cred-open
        assertThat(rowOf(tab(""), "user-a").attr("hx-trigger")).isEqualTo("cred-open")
    }

    @Test
    @DisplayName("fix3: 필터(aaguid 입력·상태 세그먼트·칩 제거)는 현재 페이지(#cred-page)를 싣고, 검색·페이지 크기는 싣지 않는다(1페이지로)")
    fun filterRequestsKeepCurrentPage() {
        whenever(credentials.list(eq(T1), eq(1), eq(50), isNull())).thenReturn(ApiResponse.ok(PageView(listOf(cred(CB, "p2", null, AAGUID_B, null, null, 1, null)), 1, 50, 120, true)))
        val d = doc(hget("$BASE/tab/credentials/list?page=2&status=ACTIVE&aaguid=0000", platform()))
        assertThat(d.select("#cred-page").`val`()).isEqualTo("2")
        assertThat(d.selectFirst("#cred-aaguid")).isNull() // 패널은 OOB 아님 — 탭 렌더에서 확인
        val t = doc(hget("$BASE/tab/credentials?page=2&status=ACTIVE&aaguid=0000", platform()))
        assertThat(t.selectFirst("#cred-aaguid")!!.attr("hx-include")).contains("#cred-page")
        for (seg in t.select("#cred-filterpanel .seg__btn")) assertThat(seg.attr("hx-include")).contains("#cred-page")
        for (x in t.select("#cred-controls .chip__x")) assertThat(x.attr("hx-include")).contains("#cred-page")
        assertThat(t.selectFirst("#cred-q")!!.attr("hx-include")).doesNotContain("#cred-page")
        assertThat(t.selectFirst(".pager select")!!.attr("hx-include")).doesNotContain("#cred-page")
        // 서버: 칩 제거 요청이 page=2 를 받으면 그 페이지를 읽는다
        hget("$BASE/tab/credentials/list?reset=aaguid&page=2&status=ACTIVE", platform())
        verify(credentials, atLeastOnce()).list(eq(T1), eq(1), eq(50), isNull())
    }

    @Test
    @DisplayName("fix4: CSV 시각은 서버 JSON 과 같은 ISO 문자열(초 포함)")
    fun csvTimestampsMatchJson() {
        val t = OffsetDateTime.parse("2026-10-10T23:45:00Z")
        stubList(listOf(CredentialView(CA, "user-a", null, null, null, "packed", "usb", 1, t, t)))
        val csv = mvc.perform(htmx(get(URI.create("$BASE/credentials.csv")), platform())).andReturn().response.getContentAsString(StandardCharsets.UTF_8)
        val expected = tools.jackson.databind.json.JsonMapper.builder()
            .disable(tools.jackson.databind.cfg.DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS).build().convertValue(t, String::class.java)
        assertThat(expected).isEqualTo("2026-10-10T23:45:00Z")
        assertThat(csv).contains(",2026-10-10T23:45:00Z,2026-10-10T23:45:00Z")
    }

    @Test
    @DisplayName("fix5: 상세는 ?? '—' 의미 — null 만 —/unknown, 빈 문자열은 그대로")
    fun detailNullishSemantics() {
        stubList(listOf(CredentialView(CA, "user-a", "", AAGUID_A, "", "", "usb", 1, null, at(-1.0))))
        val d = doc(hget("$BASE/credentials/$CA?page=1&size=50", platform()))
        assertThat(d.select("dl")[0].select("dd")[1].text()).isEmpty() // 별칭 ""
        assertThat(d.select("dl")[1].select("dd")[0].text()).isEmpty() // authenticatorName ""
        assertThat(d.select("dl")[1].select("dd")[4].text()).isEmpty() // attestation ""
    }
}
