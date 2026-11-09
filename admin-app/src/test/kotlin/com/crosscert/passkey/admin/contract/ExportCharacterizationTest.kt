package com.crosscert.passkey.admin.contract

import com.crosscert.passkey.admin.activity.ActivityController
import com.crosscert.passkey.admin.activity.ActivityView
import com.crosscert.passkey.admin.apikey.ApiKeyAdminController
import com.crosscert.passkey.admin.audit.AuditChainMonitorController
import com.crosscert.passkey.admin.audit.AuditChainOverview
import com.crosscert.passkey.admin.audit.AuditChainTenantOverview
import com.crosscert.passkey.admin.audit.AuditLogController
import com.crosscert.passkey.admin.audit.MonthlyReportController
import com.crosscert.passkey.admin.audit.MonthlyReportService
import com.crosscert.passkey.admin.auth.ActiveTenantController
import com.crosscert.passkey.admin.auth.AdminUserDetails
import com.crosscert.passkey.admin.credential.CredentialAdminController
import com.crosscert.passkey.admin.credential.CredentialAdminDto.CredentialView
import com.crosscert.passkey.admin.funnel.AdoptionController
import com.crosscert.passkey.admin.funnel.AdoptionDto
import com.crosscert.passkey.admin.funnel.FunnelController
import com.crosscert.passkey.admin.funnel.FunnelDto
import com.crosscert.passkey.admin.insights.PlatformInsightsController
import com.crosscert.passkey.admin.insights.PlatformInsightsDto
import com.crosscert.passkey.admin.license.LicenseController
import com.crosscert.passkey.admin.license.LicenseView
import com.crosscert.passkey.admin.policy.AaguidPolicyController
import com.crosscert.passkey.admin.tenant.TenantAdminController
import com.crosscert.passkey.admin.tenant.TenantAdminDto.TenantView
import com.crosscert.passkey.admin.web.AdminWebSliceTest
import com.crosscert.passkey.admin.web.activity.ActivityPageController
import com.crosscert.passkey.admin.web.insights.InsightsPageController
import com.crosscert.passkey.admin.web.tenant.AuditTabController
import com.crosscert.passkey.admin.web.tenant.CredentialsTabController
import com.crosscert.passkey.admin.web.tenant.FunnelTabController
import com.crosscert.passkey.admin.web.tenant.TenantsPageController
import com.crosscert.passkey.core.api.ApiResponse
import com.crosscert.passkey.core.api.PageView
import com.crosscert.passkey.core.ceremony.CredentialAuthFailureReason
import com.crosscert.passkey.core.config.CoreJacksonConfig
import com.openhtmltopdf.util.XRRuntimeException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.ThrowingConsumer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.xml.sax.SAXParseException
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.TreeMap
import java.util.UUID
import java.util.regex.Pattern

/**
 * 고객 데이터 반출물(CSV·PDF) 특성 테스트 (Kotlin 전환 안전망, Review Focus 5, 동결).
 *
 * CSV 6곳([TenantsPageController], [CredentialsTabController], [AuditTabController],
 * [InsightsPageController](4종), [ActivityPageController], [FunnelTabController](6종))의 다운로드 응답을
 * 상태줄·`Content-Type`·`Content-Disposition` 헤더와 본문 전체 바이트(BOM 포함)로 고정한다
 * (`contract/export/` 아래 `.http`). 데이터에는 쉼표·따옴표·줄바꿈(LF·CRLF)·한글·수식 접두(`= + - @ TAB`)를 넣는다.
 * 월간 감사 체인 PDF([MonthlyReportService] 실제 구현 + [MonthlyReportController])는 base 에서 **항상 500** 이다
 * (buildHtml 의 닫히지 않은 `<meta>` → openhtmltopdf `SAXParseException`, 알려진 결함 — 수정은 백로그). 그래서
 * PDF 텍스트 대신 (1) 500 응답 형태, (2) 원인 예외가 그 결함(`XRRuntimeException` ← `SAXParseException` "meta")임,
 * (3) 렌더러에 들어가는 보고서 HTML 전체(`Generated at` 시각만 치환, `contract/export/monthly-report.html`)를 고정한다.
 * 결함이 고쳐지면 (1)(2) 가 실패하므로 그때 PDF 텍스트 스냅샷을 새로 추가한다.
 *
 * 기록: `./gradlew :admin-app:test --tests '*ExportCharacterizationTest' -Pcontract.record=true`
 */
@AdminWebSliceTest(
    controllers = [
        TenantsPageController::class, CredentialsTabController::class, AuditTabController::class,
        InsightsPageController::class, ActivityPageController::class, FunnelTabController::class,
        MonthlyReportController::class,
    ],
)
@Import(CoreJacksonConfig::class, MonthlyReportService::class)
class ExportCharacterizationTest {

    companion object {
        val NOW: Instant = Instant.parse("2026-10-11T03:00:00Z") // KST 2026-10-11 12:00
        val TID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
        val TID2: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a2")
        val T1: OffsetDateTime = OffsetDateTime.parse("2026-10-10T09:15:30.123456+09:00")
        val T2: OffsetDateTime = OffsetDateTime.parse("2026-10-01T00:00:00+09:00")

        /** 까다로운 문자열: 쉼표·따옴표·LF·CRLF·한글·수식 접두. */
        const val COMMA = "Acme, Inc."
        const val QUOTE = "Say \"hi\""
        const val NEWLINE = "line1\nline2"
        const val CRLF = "a\r\nb"
        const val KOREAN = "가나다 한글 테넌트"
        const val FORMULA = "=SUM(A1:A2)"

        fun platform(): Authentication {
            val p = AdminUserDetails(
                UUID.fromString("00000000-0000-0000-0000-0000000000e1"),
                "alice@example.com", "x", "PLATFORM_OPERATOR", emptySet(), true, null, Clock.systemUTC(),
            )
            return UsernamePasswordAuthenticationToken(p, "x", p.authorities)
        }

        fun tenant(id: UUID, name: String, slug: String): TenantView = TenantView(
            id, slug, name, "ACTIVE", "rp.$slug.example", name,
            listOf("https://$slug.example"), setOf("none"), true, false, "NONE", 60000,
            10, 2, null, T2, T2,
        )
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var clock: Clock
    @Autowired lateinit var reportService: MonthlyReportService
    @MockitoBean lateinit var tenants: TenantAdminController
    @MockitoBean lateinit var apiKeys: ApiKeyAdminController
    @MockitoBean lateinit var credentials: CredentialAdminController
    @MockitoBean lateinit var audit: AuditLogController
    @MockitoBean lateinit var chain: AuditChainMonitorController
    @MockitoBean lateinit var aaguid: AaguidPolicyController
    @MockitoBean lateinit var insights: PlatformInsightsController
    @MockitoBean lateinit var activity: ActivityController
    @MockitoBean lateinit var funnel: FunnelController
    @MockitoBean lateinit var adoption: AdoptionController
    @MockitoBean lateinit var license: LicenseController
    @MockitoBean lateinit var activeTenants: ActiveTenantController

    @BeforeEach
    fun setUp() {
        whenever(clock.instant()).thenReturn(NOW)
        whenever(clock.zone).thenReturn(ZoneOffset.UTC)
        whenever(license.get()).thenReturn(
            ApiResponse.ok(
                LicenseView(
                    "saas", "VALID", "acme", "lic_1",
                    NOW.plusSeconds(86400L * 30), 30, listOf(), null, null, null,
                ),
            ),
        )
        whenever(apiKeys.list(null)).thenReturn(ApiResponse.ok(listOf()))
        whenever(tenants.get(anyString())).thenReturn(ApiResponse.ok(tenant(TID, KOREAN, "acme")))
    }

    /** 응답 전체(상태줄·내보내기 헤더 2개·빈 줄·본문 바이트)를 하나의 바이트열로. */
    private fun download(req: MockHttpServletRequestBuilder): ByteArray {
        val res = mvc.perform(req.with(authentication(platform()))).andReturn().response
        val out = ByteArrayOutputStream()
        val head = "HTTP " + res.status + "\n" +
            "Content-Type: " + res.contentType + "\n" +
            "Content-Disposition: " + res.getHeader(HttpHeaders.CONTENT_DISPOSITION) + "\n" +
            "\n"
        out.write(head.toByteArray(StandardCharsets.UTF_8))
        out.write(res.contentAsByteArray)
        return out.toByteArray()
    }

    private fun csv(name: String, req: MockHttpServletRequestBuilder) {
        val all = download(req)
        val text = String(all, StandardCharsets.UTF_8)
        assertThat(text).`as`(name).startsWith("HTTP 200\n")
        ContractSnapshot.assertBytesMatch("export/$name.http", all)
    }

    // ── CSV ────────────────────────────────────────────────

    @Test
    fun tenantsCsv() {
        whenever(tenants.list()).thenReturn(
            ApiResponse.ok(
                listOf(
                    tenant(TID, COMMA, "acme"),
                    tenant(TID2, "$QUOTE $NEWLINE", "beta"),
                    tenant(UUID.fromString("00000000-0000-0000-0000-0000000000a3"), FORMULA, "gamma"),
                    tenant(UUID.fromString("00000000-0000-0000-0000-0000000000a4"), KOREAN, "delta"),
                    tenant(UUID.fromString("00000000-0000-0000-0000-0000000000a5"), CRLF, "epsilon"),
                ),
            ),
        )
        csv("tenants", get("/admin/_/tenants/export"))
    }

    @Test
    fun credentialsCsv() {
        val rows = listOf(
            CredentialView(
                "AAAAAAAAAAAAAAAAAAAAAA", "dXNlci0x", COMMA, "ea9b8d664d011d21ac3f8d5fe7e8b77a",
                "$KOREAN $QUOTE", "packed", "internal,hybrid", 42, T1, T2,
            ),
            CredentialView(
                "BBBBBBBBBBBBBBBBBBBBBB", "-dXNlci0y", NEWLINE, null,
                null, "none", "usb, nfc,unknown-x", 0, null, T2,
            ),
            CredentialView(
                "CCCCCCCCCCCCCCCCCCCCCC", "@user", "+82 10", "00000000000000000000000000000000",
                "\tTabbed", "none", null, 7, T2, T1,
            ),
        )
        whenever(credentials.list(eq(TID), anyInt(), anyInt(), anyOrNull()))
            .thenReturn(ApiResponse.ok(PageView(rows, 0, 50, rows.size.toLong(), false)))
        csv("credentials", get("/admin/_/tenants/acme/credentials.csv"))
    }

    @Test
    fun auditCsv() {
        val rows = listOf(
            AuditLogController.AuditLogView(
                UUID.fromString("00000000-0000-0000-0000-0000000000d1"),
                UUID.fromString("00000000-0000-0000-0000-0000000000e1"), "alice@example.com", "API_KEY_ISSUED",
                "api_key", COMMA, TID, "{\"name\":\"" + KOREAN + "\",\"scopes\":[\"a\",\"b\"],\"n\":1.50}", T1,
            ),
            AuditLogController.AuditLogView(
                UUID.fromString("00000000-0000-0000-0000-0000000000d2"),
                null, null, "CREDENTIAL_REVOKED", "credential", QUOTE, TID, "not json, \"raw\"\nline", T2,
            ),
            AuditLogController.AuditLogView(
                UUID.fromString("00000000-0000-0000-0000-0000000000d3"),
                null, "=cmd@example.com", "AUTH_FAILED", null, null, TID, null, T2,
            ),
        )
        whenever(audit.list(isNull(), isNull(), eq(TID), anyOrNull(), anyOrNull(), anyInt(), anyInt()))
            .thenReturn(ApiResponse.ok(rows))
        csv("audit", get("/admin/tenants/acme/audit.csv"))
    }

    @Test
    fun insightsCsv() {
        val v = PlatformInsightsDto.View(
            7,
            PlatformInsightsDto.Totals(2, 1, 30, 100, 90, 20, 15, 3, 40, 25, 5),
            listOf(
                PlatformInsightsDto.TenantRow(
                    TID, "$COMMA $KOREAN", "acme", "ACTIVE",
                    30, 100, 90, 20, 15, 3, 40, 25, 5, 50, 45, 50, 49, 1,
                ),
                PlatformInsightsDto.TenantRow(
                    TID2, null, "beta", null,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                ),
            ),
            listOf(),
            listOf(FunnelDto.DailyPoint("2026-10-10", 3, 1), FunnelDto.DailyPoint("2026-10-11", 0, 0)),
            listOf(AdoptionDto.Month("2026-09", false, 10, 4, 2), AdoptionDto.Month("2026-10", true, 5, 3, 1)),
            listOf(AdoptionDto.Device("$QUOTE $KOREAN", 3, 9), AdoptionDto.Device(NEWLINE, 0, 0)),
            PlatformInsightsDto.Thresholds(10.0, 20, 1, 0.5, 10),
        )
        whenever(insights.get(anyInt())).thenReturn(v)
        for (kind in listOf("tenants", "daily", "monthly", "devices")) {
            csv("insights-$kind", get("/admin/insights.csv").param("kind", kind))
        }
    }

    @Test
    fun activityCsv() {
        val view = ActivityView(
            ActivityView.Kpi(3, 1, 1, null),
            listOf(),
            listOf(
                ActivityView.Event(
                    UUID.fromString("00000000-0000-0000-0000-0000000000f1"), "TENANT_CREATED",
                    "alice@example.com", "tenant", COMMA, TID, "acme", T1, "ops",
                ),
                ActivityView.Event(
                    UUID.fromString("00000000-0000-0000-0000-0000000000f2"), "ADMIN_LOGIN_FAILED",
                    "-bob@example.com", null, null, null, null, T2, "security",
                ),
                ActivityView.Event(
                    UUID.fromString("00000000-0000-0000-0000-0000000000f3"), "MDS_SYNC",
                    null, "mds", KOREAN + "\n" + QUOTE, TID2, null, T2, "system",
                ),
            ),
        )
        whenever(activity.activity(isNull(), anyOrNull(), isNull(), isNull(), anyOrNull()))
            .thenReturn(ApiResponse.ok(view))
        csv("activity", get("/admin/_/activity/export"))
    }

    @Test
    fun funnelCsv() {
        val f = FunnelDto.View(
            7,
            FunnelDto.Stage(10, 7, 0.7), FunnelDto.Stage(3, 0, 0.0), 0.5,
            listOf(FunnelDto.DailyPoint("2026-10-10", 13, 7)),
            listOf(FunnelDto.EventCount("REG_START", 10)),
            listOf(FunnelDto.DailyPoint("2026-10-10", 10, 7), FunnelDto.DailyPoint("2026-10-11", 0, 0)),
            listOf(FunnelDto.DailyPoint("2026-10-10", 3, 1), FunnelDto.DailyPoint("2026-10-11", 2, 2)),
            listOf(
                FunnelDto.FailureCount(CredentialAuthFailureReason.SIGNATURE_INVALID, 2),
                FunnelDto.FailureCount(null, 1),
                FunnelDto.FailureCount("CUSTOM, \"odd\"", 4),
            ),
        )
        whenever(funnel.get(eq(TID), anyInt())).thenReturn(f)
        val a = AdoptionDto.View(
            7, 12, 3, 4, 5, 6, 2,
            listOf(AdoptionDto.Month("2026-09", false, 10, 4, 2), AdoptionDto.Month("2026-10", true, 5, 3, 1)),
            listOf(AdoptionDto.Device("$KOREAN, $QUOTE", 3, 9), AdoptionDto.Device("@Unknown", 1, 0)),
        )
        whenever(adoption.get(eq(TID), anyInt())).thenReturn(a)
        for (kind in listOf("summary", "auth", "reg", "failures", "monthly", "devices")) {
            csv("funnel-$kind", get("/admin/tenants/acme/funnel.csv").param("kind", kind).param("win", "30"))
        }
    }

    // ── PDF ────────────────────────────────────────────────

    @Test
    fun monthlyReportPdf() {
        val overview = AuditChainOverview(
            Instant.parse("2026-10-11T02:59:59Z"), 24, 60,
            AuditChainOverview.Totals(1, 3, 1, 1234, 56),
            listOf(
                AuditChainTenantOverview(null, "[platform]", true, 10, listOf(0L, 1L), null),
                AuditChainTenantOverview(TID, "Acme <&> \"quoted\" 'single'", true, 1200, listOf(1L), null),
                AuditChainTenantOverview(
                    TID2, KOREAN, false, 24, listOf(),
                    UUID.fromString("00000000-0000-0000-0000-0000000000bb"),
                ),
            ),
        )
        whenever(chain.overview(24)).thenReturn(overview)

        val res = mvc.perform(
            get("/admin/api/audit/chain/monthly-report")
                .param("from", "2026-10-01").param("to", "2026-10-31")
                .with(authentication(platform())),
        ).andReturn().response
        // 현재(Java base) 동작: MonthlyReportService.buildHtml 이 닫히지 않은 <meta charset='utf-8'> 를 써서
        // openhtmltopdf 의 XML 파서가 SAXParseException 을 내고, GlobalExceptionHandler 가 500 봉투로 바꾼다
        // (데이터와 무관하게 항상). 전환은 이 동작을 바꾸지 않아야 하므로 그대로 고정한다 — 고치는 것은 별도 작업(백로그).
        assertThat(res.status).`as`("월간 PDF 는 base 에서 항상 500 (알려진 결함)").isEqualTo(500)
        val meta = TreeMap<String, String>()
        meta["@status"] = res.status.toString()
        meta["@contentType"] = res.contentType.toString()
        meta["@contentDisposition"] = res.getHeader(HttpHeaders.CONTENT_DISPOSITION).toString()
        ContractSnapshot.assertMatches(
            "export/monthly-report.response.shape.json",
            AdminJsonShape.of(res.getContentAsString(StandardCharsets.UTF_8), true, meta),
        )

        // (a) 500 의 원인이 바로 그 알려진 결함인지 — 다른 예외로 바뀌면 여기서 드러난다.
        assertThatThrownBy { reportService.generate(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31")) }
            .isInstanceOf(XRRuntimeException::class.java)
            .satisfies(
                ThrowingConsumer { e ->
                    var root: Throwable = e
                    while (true) {
                        val c = root.cause
                        if (c == null || c === root) break
                        root = c
                    }
                    assertThat(root).isInstanceOf(SAXParseException::class.java)
                    assertThat(root.message).`as`("닫히지 않은 meta 요소").contains("meta")
                },
            )

        // (b) PDF 렌더러로 들어가는 보고서 본문(HTML) 고정 — 이스케이프(<&>"')·한글·null tenantId·위변조 행.
        // buildHtml 은 private 이라 reflection 으로 부른다(main 무수정). 생성 시각(Instant.now())만 치환.
        val build = MonthlyReportService::class.java.getDeclaredMethod(
            "buildHtml",
            LocalDate::class.java, LocalDate::class.java, AuditChainOverview::class.java,
        )
        build.isAccessible = true
        val html = build.invoke(reportService, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"), overview) as String
        // Java String.replaceAll 과 같은 Pattern.matcher(..).replaceAll.
        val normalized = Pattern.compile("Generated at: [^<]+").matcher(html).replaceAll("Generated at: <now>")
        assertThat(normalized).contains("Generated at: <now>")
        ContractSnapshot.assertMatches("export/monthly-report.html", normalized + "\n")
    }
}
