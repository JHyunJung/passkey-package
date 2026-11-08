package com.crosscert.passkey.admin.web.tenant

import com.crosscert.passkey.admin.apikey.ApiKeyAdminController
import com.crosscert.passkey.admin.apikey.ApiKeyAdminDto
import com.crosscert.passkey.admin.audit.AuditChainMonitorController
import com.crosscert.passkey.admin.auth.ActiveTenantController
import com.crosscert.passkey.admin.auth.AdminUserDetails
import com.crosscert.passkey.admin.license.LicenseController
import com.crosscert.passkey.admin.license.LicenseView
import com.crosscert.passkey.admin.policy.AaguidPolicyController
import com.crosscert.passkey.admin.tenant.TenantAdminController
import com.crosscert.passkey.admin.tenant.TenantAdminDto
import com.crosscert.passkey.admin.web.AdminWebSliceTest
import com.crosscert.passkey.admin.web.tenant.ApiKeysTabController.ExpiryBadge
import com.crosscert.passkey.core.api.ApiResponse
import com.crosscert.passkey.core.api.BusinessException
import com.crosscert.passkey.core.api.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Collections
import java.util.UUID
import java.util.function.Function

/**
 * API 키 탭. 원본: ApiKeysTab.test.tsx, apiKeyHelpers.test.ts, apiKeyExpiry.test.ts, api/apiKeys.ts.
 * 브라우저에서 도는 부분(다이얼로그 열기·체크 뒤 닫기·이동 막기·클립보드)은 속성·소스 수준까지만 본다.
 *
 * (Kotlin 전환) nullable jsoup 결과(`selectFirst`·`first`·`last`·`parent`·`closest`)와 `trigger()` 의 `!!` 는
 * 원 Java 의 NPE 실패 지점과 같다.
 */
@AdminWebSliceTest(controllers = [ApiKeysTabController::class])
class ApiKeysTabControllerTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var clock: Clock
    @MockitoBean lateinit var tenants: TenantAdminController
    @MockitoBean lateinit var apiKeys: ApiKeyAdminController
    @MockitoBean lateinit var aaguid: AaguidPolicyController
    @MockitoBean lateinit var license: LicenseController
    @MockitoBean lateinit var chain: AuditChainMonitorController
    @MockitoBean lateinit var activeTenants: ActiveTenantController

    companion object {
        val JSON = ObjectMapper()
        val NOW: Instant = Instant.parse("2026-10-11T00:00:00Z")
        const val DAY = 86_400L
        val T1: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val OTHER: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000ff")
        val KA: UUID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001")
        val KR: UUID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002")
        val KE: UUID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000003")
        val KNEW: UUID = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000a2")
        val BASE = "/admin/_/tenants/$T1"
        const val SECRET = "pk_Zx9secretpart.ONLY-ONCE-4f1c2e"

        // ── fixtures ────────────────────────────────────────────

        fun at(days: Double): OffsetDateTime =
            OffsetDateTime.ofInstant(NOW.plusSeconds((days * DAY).toLong()), ZoneOffset.UTC)

        /** (Kotlin 전환) Java 는 int 인자를 double 로 넓혀 받았다 — Kotlin 은 넓히지 않으므로 Int 오버로드로 위임. */
        fun at(days: Int): OffsetDateTime = at(days.toDouble())

        fun platform(): Authentication = auth("PLATFORM_OPERATOR", setOf())
        fun rp(vararg ids: UUID): Authentication = auth("RP_ADMIN", java.util.Set.of(*ids))

        private fun auth(role: String, tenants: Set<UUID>): Authentication {
            val p = AdminUserDetails(UUID.randomUUID(), "alice@example.com", "x", role, tenants, true, null, Clock.systemUTC())
            return UsernamePasswordAuthenticationToken(p, "x", p.authorities)
        }

        fun tenant(): TenantAdminDto.TenantView = tenant(3)

        fun tenant(activeKeys: Long): TenantAdminDto.TenantView =
            TenantAdminDto.TenantView(
                T1, "acme", "Acme", "ACTIVE", "passkey.acme.com", "Acme",
                listOf("https://passkey.acme.com"), setOf("none"), true, false, "NONE", 60000, 0, activeKeys, null,
                OffsetDateTime.parse("2026-03-01T16:00:00Z"), OffsetDateTime.parse("2026-03-01T16:00:00Z"),
            )

        fun key(id: UUID, name: String, expiresAt: OffsetDateTime?, revokedAt: OffsetDateTime?, createdAt: OffsetDateTime?): ApiKeyAdminDto.ApiKeyView =
            key(id, T1, name, setOf("registration"), expiresAt, revokedAt, createdAt)

        fun key(
            id: UUID, tenantId: UUID, name: String, scopes: Set<String>, expiresAt: OffsetDateTime?,
            revokedAt: OffsetDateTime?, createdAt: OffsetDateTime?,
        ): ApiKeyAdminDto.ApiKeyView =
            ApiKeyAdminDto.ApiKeyView(id, tenantId, name, "pk_live_ab", scopes, createdAt, expiresAt, revokedAt, null)

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

        private fun badge(d: Document, text: String): Element =
            d.select("td span.badge").firstOrNull { e -> e.text() == text } ?: throw AssertionError("no badge $text")

        private fun assertSecretOnlyInBody(r: MvcResult) {
            assertThat(body(r)).contains(SECRET)
            val s = r.request.getSession(false)
            if (s != null) {
                for (n in Collections.list(s.attributeNames)) {
                    assertThat(java.lang.String.valueOf(s.getAttribute(n))).`as`("session attribute $n").doesNotContain(SECRET)
                }
            }
            assertThat(r.flashMap.isEmpty()).isTrue()
            for (h in r.response.headerNames) {
                for (v in r.response.getHeaders(h)) assertThat(v).`as`("header $h").doesNotContain(SECRET).doesNotContain("ONLY-ONCE")
            }
            assertThat(r.response.getHeader("Cache-Control")).contains("no-store")
            assertThat(r.response.redirectedUrl).isNull()
        }

        // ── Fix round 1 ─────────────────────────────────────────

        private fun keyCount(d: Document): Element? = d.selectFirst("#tenant-apikey-count")
    }

    @BeforeEach
    fun setUp() {
        whenever(clock.instant()).thenReturn(NOW)
        whenever(clock.zone).thenReturn(ZoneOffset.UTC)
        whenever(license.get()).thenReturn(ApiResponse.ok(LicenseView("saas", "VALID", "acme", "lic_1", NOW.plusSeconds(DAY * 30), 30, listOf(), null, null, null)))
        whenever(tenants.get(T1.toString())).thenReturn(ApiResponse.ok(tenant()))
        whenever(apiKeys.list(T1.toString())).thenReturn(
            ApiResponse.ok(
                listOf(
                    key(KA, "prod", null, null, at(-10)),
                    key(KR, "old", null, at(-1), at(-200)),
                    key(KE, "exp", at(-1), null, at(-50)),
                ),
            ),
        )
    }

    private fun tab(a: Authentication): Document {
        val r = mvc.perform(htmx(get(URI.create("$BASE/tab/apikeys")), a)).andReturn()
        assertThat(r.response.status).isEqualTo(200)
        return doc(r)
    }

    private fun issueReq(name: String, months: String, vararg scopes: String): MockHttpServletRequestBuilder {
        val b = htmx(post(URI.create("$BASE/api-keys")), platform()).param("name", name).param("expiresInMonths", months)
        if (scopes.size > 0) b.param("scopes", *scopes)
        return b
    }

    private fun stubIssue() {
        whenever(apiKeys.issue(anyOrNull(), anyOrNull())).thenReturn(
            ApiResponse.ok(
                ApiKeyAdminDto.ApiKeyCreateResponse(
                    KNEW, SECRET, "pk_Zx9secretpart".substring(0, 11), LinkedHashSet(listOf("registration", "authentication")), OffsetDateTime.parse("2028-10-11T00:00:00Z"),
                ),
            ),
        )
    }

    // ── 화면 ────────────────────────────────────────────────

    @Test
    @DisplayName("⋯ 메뉴는 ACTIVE 키에만, KPI(총·활성/만료/회수·초과 키), 접두사 뒤 …, 조각만(레이아웃 없음)")
    fun rendersKpisRowsAndMenuForActiveOnly() {
        whenever(apiKeys.list(T1.toString())).thenReturn(
            ApiResponse.ok(
                listOf(
                    key(KA, "prod", null, null, at(-10)),
                    key(KR, "old", null, at(-1), at(-200)),
                    key(KE, "exp", at(-1), null, at(-50)),
                    key(UUID.randomUUID(), "stale", null, null, at(-100)),
                ),
            ),
        )
        val r = mvc.perform(htmx(get(URI.create("$BASE/tab/apikeys")), platform())).andReturn()
        val html = body(r)
        assertThat(html).doesNotContain("<html").doesNotContain("class=\"shell")
        val d = Jsoup.parse(html)
        assertThat(d.select("button[aria-label=prod 키 액션]")).hasSize(1)
        assertThat(d.select("button[aria-label=stale 키 액션]")).hasSize(1)
        assertThat(d.select("button[aria-label=old 키 액션]")).isEmpty()
        assertThat(d.select("button[aria-label=exp 키 액션]")).isEmpty()
        val menu = d.selectFirst("button[aria-label=prod 키 액션]")!!.parent()!!
        assertThat(menu.select("[role=menuitem]").eachText()).containsExactly("회전", "접두사 복사", "회수")
        assertThat(menu.select(".menu__section").text()).isEqualTo("위험")
        assertThat(menu.selectFirst(".menu__item--danger")!!.text()).isEqualTo("회수")

        assertThat(d.select(".kpi__label").eachText()).containsExactly("총 API Key", "최근 발급", "권장 회전 주기")
        assertThat(d.select(".kpi__value").eachText()).containsExactly("4", "10일 전", "90일")
        assertThat(d.select(".kpi__hint").eachText()).contains("활성 2 · 만료 1 · 회수 1", "초과 키 1개")
        val overdue = d.select(".kpi__hint").first { e -> e.text() == "초과 키 1개" }
        assertThat(overdue.hasClass("kpi__hint--warn")).isTrue()
        assertThat(html).doesNotContain("권장 rotation")

        assertThat(d.select("td .mono").eachText()).contains("pk_live_ab…")
        assertThat(d.select(".card__sub").text()).isEqualTo("키 원문은 발급 직후 한 번만 보입니다. 서버는 해시만 저장합니다.")
        assertThat(d.select("#ak-root #ak-list")).hasSize(1)
        assertThat(d.select("#ak-issued-slot")).hasSize(1)
    }

    @Test
    @DisplayName("권한 범위는 한국어 라벨이고 원문은 title")
    fun scopeLabelsKoreanWithRawTitle() {
        whenever(apiKeys.list(T1.toString())).thenReturn(
            ApiResponse.ok(listOf(key(KA, T1, "prod", LinkedHashSet(listOf("registration", "authentication")), null, null, at(-1)))),
        )
        val d = tab(platform())
        assertThat(d.select("span.tag[title=registration]").text()).isEqualTo("패스키 등록")
        assertThat(d.select("span.tag[title=authentication]").text()).isEqualTo("패스키 인증")
    }

    @Test
    @DisplayName("만료 배지 = apiKeyHelpers.expiryBadge (무기한·정상·만료 임박 N일·만료됨·해당 없음) + 표의 배지 클래스")
    fun expiryBadgeMatchesHelper() {
        val tone = Function<ExpiryBadge, Any?> { it.tone }
        val label = Function<ExpiryBadge, Any?> { it.label }
        assertThat(ApiKeysTabController.expiryBadge("ACTIVE", null, NOW)).isEqualTo(ExpiryBadge("neutral", "무기한", "만료일 없음"))
        assertThat(ApiKeysTabController.expiryBadge("ACTIVE", at(100), NOW)).extracting(tone, label).containsExactly("ok", "정상")
        assertThat(ApiKeysTabController.expiryBadge("ACTIVE", at(10), NOW)).extracting(tone, label).containsExactly("warn", "만료 임박 · 10일 남음")
        assertThat(ApiKeysTabController.expiryBadge("ACTIVE", at(9.2), NOW).label).isEqualTo("만료 임박 · 10일 남음") // daysLeft 올림
        assertThat(ApiKeysTabController.expiryBadge("ACTIVE", at(30), NOW).tone).isEqualTo("warn")
        assertThat(ApiKeysTabController.expiryBadge("ACTIVE", at(31), NOW).tone).isEqualTo("ok")
        assertThat(ApiKeysTabController.expiryBadge("EXPIRED", at(-1), NOW)).extracting(tone, label).containsExactly("bad", "만료됨")
        assertThat(ApiKeysTabController.expiryBadge("ACTIVE", at(-1), NOW)).extracting(tone, label).containsExactly("bad", "만료됨")
        assertThat(ApiKeysTabController.expiryBadge("REVOKED", at(10), NOW)).extracting(tone, label).containsExactly("neutral", "해당 없음")
        assertThat(ApiKeysTabController.expiryBadge("ACTIVE", OffsetDateTime.parse("2028-10-10T16:00:00Z"), NOW).sub).isEqualTo("2028-10-11") // KST 날짜

        // rotationOverdueCount: ACTIVE 이고 생성 후 90일 초과만(정확히 90일은 아님), 회수·만료 제외
        val keys = listOf(
            key(KA, "a", null, null, at(-91)), key(KA, "b", null, null, at(-89)), key(KA, "c", null, null, at(-90)),
            key(KA, "d", null, at(-1), at(-200)), key(KA, "e", at(-1), null, at(-200)),
        )
        assertThat(ApiKeysTabController.rotationOverdueCount(keys, NOW)).isEqualTo(1)
        // previewExpiry: 말일 보정(1/31 + 1개월 → 2/28), KST 날짜
        assertThat(ApiKeysTabController.previewExpiry(1, Instant.parse("2026-01-31T03:00:00Z"))).isEqualTo("2026-02-28")
        assertThat(ApiKeysTabController.previewExpiry(24, NOW)).isEqualTo("2028-10-11")

        whenever(apiKeys.list(T1.toString())).thenReturn(
            ApiResponse.ok(
                listOf(
                    key(KA, "ok", at(100), null, at(-1)),
                    key(KR, "soon", at(10), null, at(-1)),
                    key(KE, "forever", null, null, at(-1)),
                    key(KNEW, "gone", at(-1), null, at(-40)),
                ),
            ),
        )
        val d = tab(platform())
        assertThat(badge(d, "정상").hasClass("badge--success")).isTrue()
        assertThat(badge(d, "만료 임박 · 10일 남음").hasClass("badge--warning")).isTrue()
        assertThat(badge(d, "무기한").hasClass("badge")).isTrue()
        assertThat(badge(d, "만료됨").hasClass("badge--danger")).isTrue()
        assertThat(d.text()).contains("만료일 없음")
        assertThat(badge(d, "정상").hasClass("badge--dot")).isFalse()
    }

    @Test
    @DisplayName("빈 상태: 첫 API 키 발급(+ RP 가이드 링크는 설정이 비면 없음)")
    fun emptyState() {
        whenever(apiKeys.list(T1.toString())).thenReturn(ApiResponse.ok(listOf()))
        val d = tab(platform())
        assertThat(d.select(".empty__title").text()).isEqualTo("아직 발급한 API 키가 없습니다")
        assertThat(d.select(".empty__sub").text()).isEqualTo("RP 서버가 패스키 등록·인증 API를 호출하려면 키가 필요합니다.")
        assertThat(d.select(".empty__art").attr("src")).endsWith("/admin/img/api-key-issued.webp")
        val first = d.select(".empty button").first { e -> e.text() == "첫 API 키 발급" }
        assertThat(first.attr("x-on:click")).contains("ak-new")
        assertThat(d.select("a:contains(RP 연동 가이드)")).isEmpty()
        assertThat(d.select("table")).isEmpty()
    }

    @Test
    @DisplayName("로드 실패: '키 없음'이 아니라 LoadError + 다시 시도(탭 영역 같은 sync 그룹), KPI 는 —")
    fun loadErrorNotEmpty() {
        whenever(apiKeys.list(T1.toString())).thenThrow(IllegalStateException("db down secret-sql"))
        val r = mvc.perform(htmx(get(URI.create("$BASE/tab/apikeys")), platform())).andReturn()
        assertThat(r.response.status).isEqualTo(200)
        val html = body(r)
        val d = Jsoup.parse(html)
        assertThat(d.select("[role=alert] .empty__title").text()).isEqualTo("API 키 목록을 불러오지 못했습니다")
        assertThat(html).doesNotContain("아직 발급한 API 키가 없습니다").doesNotContain("secret-sql")
        val retry = d.select("button").first { e -> e.text() == "다시 시도" }
        assertThat(retry.attr("hx-get")).isEqualTo("$BASE/tab/apikeys")
        assertThat(retry.attr("hx-sync")).isEqualTo(TenantTabs.SYNC)
        assertThat(d.select(".kpi__value").eachText()).containsExactly("—", "—", "90일")
        assertThat(d.select(".kpi__hint")).isEmpty()
    }

    // ── 권한 경계 ───────────────────────────────────────────

    @Test
    @DisplayName("RP_ADMIN 이 다른 tenant 의 탭·발급·회전·회수 → 403, REST 목록·변경 호출 없음")
    fun rpAdminOtherTenantDenied() {
        whenever(tenants.get(OTHER.toString())).thenThrow(BusinessException(ErrorCode.ACCESS_DENIED, "RP_ADMIN cannot access tenant $OTHER"))
        val base = "/admin/_/tenants/$OTHER"
        val rp = rp(T1)
        assertThat(mvc.perform(htmx(get(URI.create("$base/tab/apikeys")), rp)).andReturn().response.status).isEqualTo(403)
        assertThat(mvc.perform(htmx(post(URI.create("$base/api-keys")), rp).param("name", "x").param("scopes", "registration")).andReturn().response.status).isEqualTo(403)
        assertThat(mvc.perform(confirm(post(URI.create("/admin/_/api-keys/$KA/rotate?tenantId=$OTHER")), rp)).andReturn().response.status).isEqualTo(403)
        assertThat(mvc.perform(confirm(delete(URI.create("/admin/_/api-keys/$KA?tenantId=$OTHER")), rp)).andReturn().response.status).isEqualTo(403)
        verify(apiKeys, never()).list(anyString())
        verify(apiKeys, never()).issue(anyOrNull(), anyOrNull())
        verify(apiKeys, never()).rotate(anyOrNull(), anyOrNull())
        verify(apiKeys, never()).revoke(anyOrNull(), anyOrNull())
    }

    @Test
    @DisplayName("자기 tenant id 를 붙여도 다른 tenant 의 키면 회전·회수하지 않는다(404, REST 변경 호출 없음)")
    fun keyOfAnotherTenantViaOwnTenantIdNotMutated() {
        val foreign = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001")
        val rp = rp(T1)
        val rot = mvc.perform(confirm(post(URI.create("/admin/_/api-keys/$foreign/rotate?tenantId=$T1")), rp)).andReturn()
        val rev = mvc.perform(confirm(delete(URI.create("/admin/_/api-keys/$foreign?tenantId=$T1")), rp)).andReturn()
        assertThat(rot.response.status).isEqualTo(404)
        assertThat(rev.response.status).isEqualTo(404)
        // 목록이 (잘못) 다른 tenant 의 키를 섞어 줘도 tenantId 가 다르면 거부한다
        whenever(apiKeys.list(T1.toString())).thenReturn(ApiResponse.ok(listOf(key(foreign, OTHER, "x", setOf("registration"), null, null, at(-1)))))
        assertThat(mvc.perform(confirm(post(URI.create("/admin/_/api-keys/$foreign/rotate?tenantId=$T1")), rp)).andReturn().response.status).isEqualTo(404)
        verify(apiKeys, never()).rotate(anyOrNull(), anyOrNull())
        verify(apiKeys, never()).revoke(anyOrNull(), anyOrNull())
    }

    @Test
    @DisplayName("RP_ADMIN 자기 tenant 는 탭이 보인다")
    fun rpAdminOwnTenantAllowed() {
        val d = tab(rp(T1))
        assertThat(d.select("button[aria-label=prod 키 액션]")).hasSize(1)
    }

    // ── 발급 ────────────────────────────────────────────────

    @Test
    @DisplayName("발급: REST issue(tenant, 이름, 권한 범위, 개월) 인자, 발급 완료 다이얼로그 + 목록·배지 OOB, 토스트 없음, no-store")
    fun issueCallsRestAndRendersIssuedDialog() {
        stubIssue()
        whenever(apiKeys.list(T1.toString())).thenReturn(
            ApiResponse.ok(
                listOf(
                    key(KA, "prod", at(10), null, at(-10)),
                    key(KNEW, T1, "staging", setOf("registration", "authentication"), at(730), null, at(0)),
                ),
            ),
        )
        val r = mvc.perform(issueReq("staging", "24", "registration", "authentication")).andReturn()
        assertThat(r.response.status).isEqualTo(200)
        val cap = argumentCaptor<ApiKeyAdminDto.ApiKeyCreateRequest>()
        verify(apiKeys).issue(cap.capture(), any<Authentication>())
        assertThat(cap.lastValue.tenantId).isEqualTo(T1)
        assertThat(cap.lastValue.name).isEqualTo("staging")
        assertThat(cap.lastValue.scopes).containsExactly("registration", "authentication")
        assertThat(cap.lastValue.expiresInMonths).isEqualTo(24)

        assertThat(r.response.getHeader("Cache-Control")).contains("no-store")
        assertThat(r.response.getHeader("HX-Trigger")).isNull()
        assertThat(r.response.getHeader("HX-Redirect")).isNull()
        val d = doc(r)
        val dlg = d.selectFirst("[role=alertdialog][aria-labelledby=ak-issued-title]")
        assertThat(dlg).isNotNull()
        assertThat(dlg!!.closest("[hx-history=false]")).isNotNull()
        assertThat(dlg.select("#ak-issued-title").text()).contains("새 API key가 발급되었습니다 — 지금만 표시됩니다")
        assertThat(dlg.select(".secret__value").text()).isEqualTo(SECRET)
        assertThat(dlg.select("dd").eachText()).contains("staging 발급 완료", KNEW.toString(), "2028-10-11")
        assertThat(dlg.select("dt").eachText()).containsExactly("이름", "prefix", "만료", "id")
        val close = dlg.select(".dialog__foot button").last()!!
        assertThat(close.hasAttr("disabled")).isTrue()
        assertThat(close.text()).isEqualTo("닫기 (영구 소실) · 체크 필요")
        assertThat(close.attr("x-bind:disabled")).isEqualTo("!checked")
        assertThat(dlg.select("label.ak-ack").text()).isEqualTo("안전한 장소에 복사했습니다.")
        assertThat(dlg.select(".dialog__foot-start").text()).isEqualTo("server는 plaintext의 해시만 저장합니다.")
        assertThat(dlg.select(".banner")).isEmpty() // 발급은 이전 키 안내 없음
        assertThat(d.selectFirst("[x-data^=dialog]")!!.attr("x-init")).contains("dialog:close").contains("ak-new")

        val list = d.selectFirst("#ak-list")!!
        assertThat(list.attr("hx-swap-oob")).isEqualTo("true")
        assertThat(list.select("button[aria-label=staging 키 액션]")).hasSize(1)
        val badge = d.selectFirst("#tenant-tab-apikeys-badge")!!
        assertThat(badge.attr("hx-swap-oob")).isEqualTo("true")
        assertThat(badge.text()).isEqualTo("확인 필요 1")
        assertThat(badge.selectFirst(".badge")!!.attr("title")).isEqualTo("30일 안에 만료되는 키 1개")
    }

    @Test
    @DisplayName("평문 키는 응답 본문에만: 세션 속성·플래시·어떤 응답 헤더에도 없다(발급·회전)")
    fun issuedSecretOnlyInResponse() {
        stubIssue()
        val r = mvc.perform(issueReq("staging", "", "registration")).andReturn()
        assertSecretOnlyInBody(r)

        whenever(apiKeys.rotate(eq(KA), anyOrNull())).thenReturn(
            ApiResponse.ok(ApiKeyAdminDto.ApiKeyRotateResponse(KNEW, SECRET, "pk_new", setOf("registration"), at(1))),
        )
        val rot = mvc.perform(confirm(post(URI.create("/admin/_/api-keys/$KA/rotate?tenantId=$T1")), platform())).andReturn()
        assertThat(rot.response.status).isEqualTo(200)
        assertSecretOnlyInBody(rot)
    }

    @Test
    @DisplayName("발급 실패: 다이얼로그 안 경고(422, OOB), 입력 폼은 응답에 없어 그대로, 토스트 없음, 마침표 겹침 없음")
    fun issueFailureIsInlineAndKeepsInput() {
        whenever(apiKeys.issue(anyOrNull(), anyOrNull())).thenThrow(BusinessException(ErrorCode.TENANT_DUPLICATE, "키 개수 한도 초과."))
        val r = mvc.perform(issueReq("staging", "12", "registration")).andReturn()
        assertThat(r.response.status).isEqualTo(422)
        assertThat(r.response.getHeader("HX-Trigger")).isNull()
        val d = doc(r)
        val err = d.selectFirst("#ak-new-error")!!
        assertThat(err.attr("hx-swap-oob")).isEqualTo("true")
        val alert = err.selectFirst("[role=alert]")!!
        assertThat(alert.select(".banner__title").text()).isEqualTo("API 키를 발급하지 못했습니다.")
        assertThat(alert.text()).contains("요청을 처리하지 못했습니다. 키 개수 한도 초과. 입력한 내용은 그대로 있습니다.")
        assertThat(alert.text()).doesNotContain("..")
        assertThat(d.select("#ak-new-form, input")).isEmpty()

        assertThat(ApiKeysTabController.issueErrorText("요청을 처리하지 못했습니다", "")).isEqualTo("요청을 처리하지 못했습니다. 입력한 내용은 그대로 있습니다.")
        assertThat(ApiKeysTabController.issueErrorText("요청을 처리하지 못했습니다", "  한도 초과。 ")).isEqualTo("요청을 처리하지 못했습니다. 한도 초과. 입력한 내용은 그대로 있습니다.")
    }

    @Test
    @DisplayName("발급 검증: 이름 없음·64자 초과·권한 범위 없음·모르는 범위·잘못된 개월 → 422 인라인, REST 호출 없음")
    fun issueValidationRejectsWithoutCallingRest() {
        val cases = arrayOf(
            arrayOf("", "24", "registration"),
            arrayOf("   ", "24", "registration"),
            arrayOf("x".repeat(65), "24", "registration"),
            arrayOf("staging", "24"),
            arrayOf("staging", "24", "admin"),
            arrayOf("staging", "abc", "registration"),
            arrayOf("staging", "37", "registration"),
            arrayOf("staging", "0", "registration"),
        )
        for (c in cases) {
            val scopes = c.copyOfRange(2, c.size)
            val r = mvc.perform(issueReq(c[0], c[1], *scopes)).andReturn()
            assertThat(r.response.status).`as`(c.joinToString("|")).isEqualTo(422)
            assertThat(doc(r).select("#ak-new-error [role=alert]")).`as`(c.joinToString("|")).hasSize(1)
            assertThat(r.response.getHeader("HX-Trigger")).isNull()
        }
        verify(apiKeys, never()).issue(anyOrNull(), anyOrNull())
    }

    @Test
    @DisplayName("무기한(개월 비움)은 expiresInMonths=null 로 보낸다")
    fun issueWithoutExpirySendsNull() {
        stubIssue()
        mvc.perform(issueReq("staging", "", "authentication")).andReturn()
        val cap = argumentCaptor<ApiKeyAdminDto.ApiKeyCreateRequest>()
        verify(apiKeys).issue(cap.capture(), anyOrNull())
        assertThat(cap.lastValue.expiresInMonths).isNull()
        assertThat(cap.lastValue.scopes).containsExactly("authentication")
    }

    @Test
    @DisplayName("새 키 발급 다이얼로그: 이름 64자·권한 범위 2개(기본 둘 다)·만료 프리셋 5개(기본 24개월)·미리보기, 같은 sync 그룹")
    fun newKeyDialogMarkup() {
        val d = tab(platform())
        val form = d.selectFirst("#ak-new-form")!!
        assertThat(form.attr("hx-post")).isEqualTo("$BASE/api-keys")
        assertThat(form.attr("hx-target")).isEqualTo("#ak-issued-slot")
        assertThat(form.attr("hx-sync")).isEqualTo("#ak-root:drop")
        assertThat(d.select("#ak-new-title").text()).isEqualTo("새 API 키 발급")
        assertThat(d.select("label[for=apikey-name]").text()).isEqualTo("용도 (이름)")
        assertThat(d.selectFirst("#apikey-name")!!.attr("maxlength")).isEqualTo("64")
        assertThat(form.select("input[name=scopes]").eachAttr("value")).containsExactly("registration", "authentication")
        assertThat(form.select("input[name=scopes][checked]")).hasSize(2)
        assertThat(form.select(".fchip").eachText()).containsExactly("6개월", "12개월", "24개월", "36개월", "무기한")
        assertThat(form.select(".fchip.fchip--on").text()).isEqualTo("24개월")
        assertThat(form.selectFirst("input[name=expiresInMonths]")!!.`val`()).isEqualTo("24")
        assertThat(form.select("p.hint").text()).isEqualTo("만료일 2028-10-11 (KST) · 권장 회전 주기 90일")
        assertThat(d.selectFirst("[x-data^=apiKeyForm]")!!.attr("x-data")).contains("\"6\":\"2027-04-11\"").contains("\"36\":\"2029-10-11\"")
        val submit = d.selectFirst("button[form=ak-new-form]")!!
        assertThat(submit.attr("type")).isEqualTo("submit")
        assertThat(submit.attr("x-bind:disabled")).isEqualTo("busy || !canSubmit")
        assertThat(submit.text()).contains("발급").contains("발급 중…")
    }

    // ── 회전 ────────────────────────────────────────────────

    @Test
    @DisplayName("회전: REST rotate(키), 새 키 id·이전 키 24시간 후 만료 안내, 만료는 다시 읽은 목록의 새 키 값")
    fun rotateShowsNewKeyAndOldKeyExpiry() {
        whenever(apiKeys.list(T1.toString())).thenReturn(
            ApiResponse.ok(listOf(key(KA, "prod", at(5), null, at(-10)))),
            ApiResponse.ok(listOf(key(KA, "prod", at(1), null, at(-10)), key(KNEW, "prod", OffsetDateTime.parse("2028-10-11T00:00:00Z"), null, at(0)))),
        )
        whenever(apiKeys.rotate(eq(KA), anyOrNull())).thenReturn(
            ApiResponse.ok(ApiKeyAdminDto.ApiKeyRotateResponse(KNEW, SECRET, "pk_newprefx", setOf("registration"), at(1))),
        )
        val r = mvc.perform(confirm(post(URI.create("/admin/_/api-keys/$KA/rotate?tenantId=$T1")), platform())).andReturn()
        assertThat(r.response.status).isEqualTo(200)
        verify(apiKeys).rotate(eq(KA), any<Authentication>())
        val d = doc(r)
        val dlg = d.selectFirst("[role=alertdialog][aria-labelledby=ak-issued-title]")!!
        assertThat(dlg.select(".banner").text()).isEqualTo("이전 키는 24시간 후(2026-10-12 09:00) 만료됩니다. 그 전에 RP 서버를 새 키로 교체하세요.")
        assertThat(dlg.select("dd").eachText()).contains(KNEW.toString(), "pk_newprefx", "2028-10-11").doesNotContain(KA.toString())
        assertThat(dlg.select("dd").first()!!.text()).startsWith("prod")
        assertThat(d.selectFirst("#ak-list")!!.attr("hx-swap-oob")).isEqualTo("true")
        assertThat(d.selectFirst("#tenant-tab-apikeys-badge")!!.text()).isEqualTo("확인 필요 1")
        assertThat(r.response.getHeader("HX-Trigger")).isNull()
    }

    @Test
    @DisplayName("회전 뒤 목록에서 새 키를 못 찾으면 만료 줄을 숨긴다(이전 키 만료를 보이지 않음)")
    fun rotateHidesExpiryWhenNewKeyMissing() {
        whenever(apiKeys.rotate(eq(KA), anyOrNull())).thenReturn(
            ApiResponse.ok(ApiKeyAdminDto.ApiKeyRotateResponse(KNEW, SECRET, "pk_newprefx", setOf("registration"), at(1))),
        )
        val d = doc(mvc.perform(confirm(post(URI.create("/admin/_/api-keys/$KA/rotate?tenantId=$T1")), platform())).andReturn())
        assertThat(d.select("[aria-labelledby=ak-issued-title] dt").eachText()).containsExactly("이름", "prefix", "id")
    }

    @Test
    @DisplayName("회전 실패는 확인 다이얼로그 안 인라인(confirmError), 토스트 없음")
    fun rotateFailureIsInline() {
        whenever(apiKeys.rotate(eq(KA), anyOrNull())).thenThrow(BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, "boom"))
        val r = mvc.perform(confirm(post(URI.create("/admin/_/api-keys/$KA/rotate?tenantId=$T1")), platform())).andReturn()
        assertThat(r.response.status).isEqualTo(500)
        val t = trigger(r)!!
        assertThat(t.has("confirmError")).isTrue()
        assertThat(t.has("toast")).isFalse()
        assertThat(body(r)).isEmpty()
    }

    @Test
    @DisplayName("회전·회수는 확인 다이얼로그(X-Confirm)로만 — 헤더가 없으면 400, REST 호출 없음")
    fun mutationsRequireConfirmHeader() {
        val rot = mvc.perform(htmx(post(URI.create("/admin/_/api-keys/$KA/rotate?tenantId=$T1")), platform())).andReturn()
        val rev = mvc.perform(htmx(delete(URI.create("/admin/_/api-keys/$KA?tenantId=$T1")), platform())).andReturn()
        assertThat(rot.response.status).isEqualTo(400)
        assertThat(rev.response.status).isEqualTo(400)
        verify(apiKeys, never()).rotate(anyOrNull(), anyOrNull())
        verify(apiKeys, never()).revoke(anyOrNull(), anyOrNull())
    }

    @Test
    @DisplayName("회전 확인: L2 영향 요약(24시간 함께 유효·유예 안내), primary 버튼, 회전 POST 는 #ak-issued-slot 로")
    fun rotateDialogIsL2Primary() {
        val d = tab(platform())
        val dlg = d.selectFirst("[aria-labelledby=ak-rotate-$KA-title]")!!
        assertThat(dlg.select(".dialog__title").text()).isEqualTo("API 키를 회전할까요?")
        assertThat(dlg.text()).contains("24시간 함께 유효").contains("즉시 발급 · 1회 표시").contains("유예 기간 안에 RP 서버 설정을 새 키로 바꾸세요.")
        val ok = dlg.selectFirst("button[data-confirm]")!!
        assertThat(ok.hasClass("btn--primary")).isTrue()
        assertThat(ok.attr("hx-post")).isEqualTo("/admin/_/api-keys/$KA/rotate?tenantId=$T1")
        assertThat(ok.attr("hx-target")).isEqualTo("#ak-issued-slot")
        assertThat(ok.attr("hx-sync")).isEqualTo("#ak-root:drop")
        assertThat(dlg.select("input")).isEmpty()
        assertThat(dlg.selectFirst("button[autofocus]")!!.text()).isEqualTo("취소")
        val item = d.select("[role=menuitem]").first { e -> e.text() == "회전" }
        assertThat(item.attr("data-dialog")).isEqualTo("ak-rotate-$KA")
        // 만료·회수 키는 확인 다이얼로그도 없다
        assertThat(d.select("[aria-labelledby=ak-rotate-$KR-title]")).isEmpty()
        assertThat(d.select("[aria-labelledby=ak-revoke-$KE-title]")).isEmpty()
    }

    // ── 회수 ────────────────────────────────────────────────

    @Test
    @DisplayName("회수 확인: 원본과 같은 L2 영향 요약 — 입력 확인(ConfirmByTyping) 없음, danger 버튼, 기본 포커스 취소")
    fun revokeIsL2WithoutTypedConfirm() {
        val d = tab(platform())
        val dlg = d.selectFirst("[aria-labelledby=ak-revoke-$KA-title]")!!
        assertThat(dlg.attr("role")).isEqualTo("alertdialog")
        assertThat(dlg.select(".dialog__title").text()).isEqualTo("API 키를 회수할까요?")
        assertThat(dlg.text()).contains("즉시 거부됩니다(401)").contains("사용 안 함").contains("pk_live_ab")
        assertThat(dlg.select("input")).`as`("ApiKeysTab.test: 회수는 입력 확인 없음").isEmpty()
        assertThat(dlg.select("[x-data^=confirmByTyping]")).isEmpty()
        val ok = dlg.selectFirst("button[data-confirm]")!!
        assertThat(ok.hasClass("btn--danger-solid")).isTrue()
        assertThat(ok.attr("hx-headers")).contains("X-Confirm")
        assertThat(ok.attr("hx-delete")).isEqualTo("/admin/_/api-keys/$KA?tenantId=$T1")
        assertThat(ok.attr("hx-target")).isEqualTo("#ak-list")
        assertThat(ok.attr("hx-swap")).isEqualTo("outerHTML")
        assertThat(dlg.selectFirst("button[autofocus]")!!.text()).isEqualTo("취소")
        assertThat(ok.select("span").eachText()).containsExactly("회수", "회수 중…")
        assertThat(dlg.closest("[x-data^=dialog]")!!.attr("x-data")).contains("\"level\":\"L2\"")
    }

    @Test
    @DisplayName("회수: REST revoke(키), 성공 토스트 “prod”을 회수했습니다 + 접두사, 목록(본 대상)·배지 OOB")
    fun revokeSucceedsWithToastAndRefresh() {
        whenever(apiKeys.list(T1.toString())).thenReturn(
            ApiResponse.ok(listOf(key(KA, "prod", null, null, at(-10)))),
            ApiResponse.ok(listOf(key(KA, "prod", null, at(0), at(-10)))),
        )
        val r = mvc.perform(confirm(delete(URI.create("/admin/_/api-keys/$KA?tenantId=$T1")), platform())).andReturn()
        assertThat(r.response.status).isEqualTo(200)
        verify(apiKeys).revoke(eq(KA), any<Authentication>())
        val toast = trigger(r)!!.get("toast")
        assertThat(toast.get("kind").asString()).isEqualTo("success")
        assertThat(toast.get("title").asString()).isEqualTo("API 키 “prod”을 회수했습니다.")
        assertThat(toast.get("message").asString()).isEqualTo("pk_live_ab")
        val d = doc(r)
        val list = d.selectFirst("#ak-list")!!
        assertThat(list.hasAttr("hx-swap-oob")).isFalse()
        assertThat(list.select("button[aria-label=prod 키 액션]")).isEmpty()
        assertThat(list.text()).contains("활성 0 · 만료 0 · 회수 1")
        val badge = d.selectFirst("#tenant-tab-apikeys-badge")!!
        assertThat(badge.attr("hx-swap-oob")).isEqualTo("true")
        assertThat(badge.select(".badge")).isEmpty()
    }

    @Test
    @DisplayName("회수 실패는 확인 다이얼로그 안 인라인(confirmError)")
    fun revokeFailureIsInline() {
        doThrow(BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, "boom")).whenever(apiKeys).revoke(eq(KA), anyOrNull())
        val r = mvc.perform(confirm(delete(URI.create("/admin/_/api-keys/$KA?tenantId=$T1")), platform())).andReturn()
        assertThat(r.response.status).isEqualTo(500)
        assertThat(trigger(r)!!.has("confirmError")).isTrue()
        assertThat(trigger(r)!!.has("toast")).isFalse()
    }

    @Test
    @DisplayName("접두사 복사 메뉴: data-prefix + 상수 JS(adminCopy → 토스트 '접두사를 복사했습니다.' / '복사 실패')")
    fun copyPrefixMenuItem() {
        val d = tab(platform())
        val item = d.select("[role=menuitem]").first { e -> e.text() == "접두사 복사" }
        assertThat(item.attr("data-prefix")).isEqualTo("pk_live_ab")
        assertThat(item.attr("x-on:click.capture")).isEqualTo(ApiKeysTabController.COPY_PREFIX)
            .contains("접두사를 복사했습니다.").contains("복사 실패").doesNotContain("pk_live_ab")
    }

    @Test
    @DisplayName("발급·회전·회수 응답은 tenant 를 다시 읽어 정지 확인창의 키 수(#tenant-apikey-count)를 OOB 로 갱신한다")
    fun mutationsRefreshSuspendKeyCount() {
        stubIssue()
        whenever(tenants.get(T1.toString())).thenReturn(ApiResponse.ok(tenant(3)), ApiResponse.ok(tenant(4)))
        val issued = doc(mvc.perform(issueReq("staging", "24", "registration")).andReturn())
        assertThat(keyCount(issued)!!.attr("hx-swap-oob")).isEqualTo("true")
        assertThat(keyCount(issued)!!.attr("data-count")).isEqualTo("4")
        assertThat(keyCount(issued)!!.hasAttr("hidden")).isTrue()

        whenever(tenants.get(T1.toString())).thenReturn(ApiResponse.ok(tenant(3)), ApiResponse.ok(tenant(5)))
        whenever(apiKeys.rotate(eq(KA), anyOrNull())).thenReturn(ApiResponse.ok(ApiKeyAdminDto.ApiKeyRotateResponse(KNEW, SECRET, "pk_new", setOf("registration"), at(1))))
        val rotated = doc(mvc.perform(confirm(post(URI.create("/admin/_/api-keys/$KA/rotate?tenantId=$T1")), platform())).andReturn())
        assertThat(keyCount(rotated)!!.attr("data-count")).isEqualTo("5")

        whenever(tenants.get(T1.toString())).thenReturn(ApiResponse.ok(tenant(3)), ApiResponse.ok(tenant(2)))
        val revoked = doc(mvc.perform(confirm(delete(URI.create("/admin/_/api-keys/$KA?tenantId=$T1")), platform())).andReturn())
        assertThat(keyCount(revoked)!!.attr("hx-swap-oob")).isEqualTo("true")
        assertThat(keyCount(revoked)!!.attr("data-count")).isEqualTo("2")
    }

    @Test
    @DisplayName("변경 뒤 tenant 다시 읽기가 실패해도 변경 응답은 그대로(키 수 OOB 만 빠진다)")
    fun keyCountRefreshFailureIsSilent() {
        stubIssue()
        whenever(tenants.get(T1.toString())).thenReturn(ApiResponse.ok(tenant(3))).thenThrow(IllegalStateException("down"))
        val r = mvc.perform(issueReq("staging", "24", "registration")).andReturn()
        assertThat(r.response.status).isEqualTo(200)
        assertThat(body(r)).contains(SECRET)
        assertThat(keyCount(doc(r))).isNull()
    }

    @Test
    @DisplayName("발급 다이얼로그: 발급 중에는 바깥 dialog 의 busy 로 Escape·스크림·취소가 막힌다, 폼 상태는 x-if 밖(취소 후 다시 열어도 유지)")
    fun issueDialogBlocksDismissWhileIssuingAndKeepsState() {
        val d = tab(platform())
        val formScope = d.selectFirst("[x-data^=apiKeyForm]")!!
        val dialogScope = d.selectFirst("[x-data*='\"id\":\"ak-new\"']")
        assertThat(dialogScope).isNotNull()
        assertThat(formScope.select("[x-data*='\"id\":\"ak-new\"']")).`as`("폼 상태가 다이얼로그(x-if) 바깥 조상").hasSize(1)
        assertThat(formScope.parents().`is`("template")).isFalse()
        assertThat(formScope.attr("x-on:ak-form-reset.window")).isEqualTo("reset()")
        val form = d.selectFirst("#ak-new-form")!!
        assertThat(form.attr("x-on:htmx:before-request")).contains("busy = true")
        assertThat(form.attr("x-on:htmx:after-request")).contains("busy = false")
        assertThat(d.selectFirst("button[form=ak-new-form]")!!.attr("x-bind:disabled")).contains("busy")
    }

    @Test
    @DisplayName("발급 성공 응답만 폼 초기화를 보낸다(회전은 발급 폼을 건드리지 않음)")
    fun onlyIssueResetsForm() {
        stubIssue()
        val issued = doc(mvc.perform(issueReq("staging", "24", "registration")).andReturn())
        assertThat(issued.selectFirst("[x-data^=dialog]")!!.attr("data-reset-form")).isEqualTo("true")
        assertThat(issued.selectFirst("[x-data^=dialog]")!!.attr("x-init")).contains("ak-form-reset")
        whenever(apiKeys.rotate(eq(KA), anyOrNull())).thenReturn(ApiResponse.ok(ApiKeyAdminDto.ApiKeyRotateResponse(KNEW, SECRET, "pk_new", setOf("registration"), at(1))))
        val rotated = doc(mvc.perform(confirm(post(URI.create("/admin/_/api-keys/$KA/rotate?tenantId=$T1")), platform())).andReturn())
        assertThat(rotated.selectFirst("[x-data^=dialog]")!!.hasAttr("data-reset-form")).isFalse()
    }

    @Test
    @DisplayName("발급의 네트워크 오류·권한 거부는 전역 토스트가 아니라 다이얼로그 안: 폼 data-quiet + X-Confirm(→ confirmError), 인라인 netErr 자리")
    fun issueNetworkAndDeniedErrorsAreInline() {
        val d = tab(platform())
        val form = d.selectFirst("#ak-new-form")!!
        assertThat(form.hasAttr("data-quiet")).isTrue()
        assertThat(form.attr("hx-headers")).contains("X-Confirm")
        assertThat(form.attr("x-on:htmx:send-error")).isEqualTo("netFail(\$event)")
        assertThat(form.attr("x-on:htmx:timeout")).isEqualTo("netFail(\$event)")
        val inline = d.selectFirst("[x-show=netErr]")
        assertThat(inline).isNotNull()
        assertThat(inline!!.select(".banner__title").text()).isEqualTo("API 키를 발급하지 못했습니다.")
        assertThat(inline.selectFirst("[x-text=netErr]")).isNotNull()

        whenever(apiKeys.issue(anyOrNull(), anyOrNull())).thenThrow(BusinessException(ErrorCode.ACCESS_DENIED, "denied"))
        val r = mvc.perform(issueReq("staging", "24", "registration").header("X-Confirm", "1")).andReturn()
        assertThat(r.response.status).isEqualTo(403)
        assertThat(trigger(r)!!.has("confirmError")).isTrue()
        assertThat(trigger(r)!!.has("toast")).isFalse()
    }

    @Test
    @DisplayName("키 이름의 따옴표·꺾쇠는 목록·발급 완료·확인창에서 이스케이프된다")
    fun keyNameIsEscaped() {
        val evil = "a\"b'<img src=x onerror=alert(1)>"
        whenever(apiKeys.list(T1.toString())).thenReturn(ApiResponse.ok(listOf(key(KA, evil, null, null, at(-1)))))
        val tab = mvc.perform(htmx(get(URI.create("$BASE/tab/apikeys")), platform())).andReturn()
        val html = body(tab)
        assertThat(html).doesNotContain("<img src=x").doesNotContain("onerror=alert(1)>")
        val d = Jsoup.parse(html)
        assertThat(d.select("img[src=x]")).isEmpty()
        assertThat(d.select("td strong").text()).isEqualTo(evil)
        assertThat(d.select("button[aria-label]").any { e -> e.attr("aria-label") == "$evil 키 액션" }).isTrue()
        assertThat(d.select("[aria-labelledby=ak-revoke-$KA-title] dd").first()!!.text()).isEqualTo(evil)

        stubIssue()
        val r = mvc.perform(issueReq(evil, "24", "registration")).andReturn()
        val ih = body(r)
        assertThat(ih).doesNotContain("<img src=x")
        val id = Jsoup.parse(ih)
        assertThat(id.select("img[src=x]")).isEmpty()
        assertThat(id.select("[aria-labelledby=ak-issued-title] dd").first()!!.text()).startsWith(evil)
    }
}
