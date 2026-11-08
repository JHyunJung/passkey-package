package com.crosscert.passkey.admin.contract;

import com.crosscert.passkey.admin.activity.ActivityController;
import com.crosscert.passkey.admin.activity.ActivityView;
import com.crosscert.passkey.admin.apikey.ApiKeyAdminController;
import com.crosscert.passkey.admin.audit.AuditChainMonitorController;
import com.crosscert.passkey.admin.audit.AuditChainOverview;
import com.crosscert.passkey.admin.audit.AuditChainTenantOverview;
import com.crosscert.passkey.admin.audit.AuditLogController;
import com.crosscert.passkey.admin.audit.MonthlyReportController;
import com.crosscert.passkey.admin.audit.MonthlyReportService;
import com.crosscert.passkey.admin.auth.ActiveTenantController;
import com.crosscert.passkey.admin.auth.AdminUserDetails;
import com.crosscert.passkey.admin.credential.CredentialAdminController;
import com.crosscert.passkey.admin.credential.CredentialAdminDto.CredentialView;
import com.crosscert.passkey.admin.funnel.AdoptionController;
import com.crosscert.passkey.admin.funnel.AdoptionDto;
import com.crosscert.passkey.admin.funnel.FunnelController;
import com.crosscert.passkey.admin.funnel.FunnelDto;
import com.crosscert.passkey.admin.insights.PlatformInsightsController;
import com.crosscert.passkey.admin.insights.PlatformInsightsDto;
import com.crosscert.passkey.admin.license.LicenseController;
import com.crosscert.passkey.admin.license.LicenseView;
import com.crosscert.passkey.admin.policy.AaguidPolicyController;
import com.crosscert.passkey.admin.tenant.TenantAdminController;
import com.crosscert.passkey.admin.tenant.TenantAdminDto.TenantView;
import com.crosscert.passkey.admin.web.AdminWebSliceTest;
import com.crosscert.passkey.admin.web.activity.ActivityPageController;
import com.crosscert.passkey.admin.web.insights.InsightsPageController;
import com.crosscert.passkey.admin.web.tenant.AuditTabController;
import com.crosscert.passkey.admin.web.tenant.CredentialsTabController;
import com.crosscert.passkey.admin.web.tenant.FunnelTabController;
import com.crosscert.passkey.admin.web.tenant.TenantsPageController;
import com.crosscert.passkey.core.api.ApiResponse;
import com.crosscert.passkey.core.api.PageView;
import com.crosscert.passkey.core.ceremony.CredentialAuthFailureReason;
import com.crosscert.passkey.core.config.CoreJacksonConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import org.xml.sax.SAXParseException;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 고객 데이터 반출물(CSV·PDF) 특성 테스트 (Kotlin 전환 안전망, Review Focus 5, 동결).
 *
 * <p>CSV 6곳({@link TenantsPageController}, {@link CredentialsTabController}, {@link AuditTabController},
 * {@link InsightsPageController}(4종), {@link ActivityPageController}, {@link FunnelTabController}(6종))의 다운로드 응답을
 * 상태줄·{@code Content-Type}·{@code Content-Disposition} 헤더와 본문 전체 바이트(BOM 포함)로 고정한다
 * ({@code contract/export/*.http}). 데이터에는 쉼표·따옴표·줄바꿈(LF·CRLF)·한글·수식 접두({@code = + - @ TAB})를 넣는다.
 * 월간 감사 체인 PDF({@link MonthlyReportService} 실제 구현 + {@link MonthlyReportController})는 base 에서 <b>항상 500</b> 이다
 * (buildHtml 의 닫히지 않은 {@code <meta>} → openhtmltopdf {@code SAXParseException}, 알려진 결함 — 수정은 백로그). 그래서
 * PDF 텍스트 대신 (1) 500 응답 형태, (2) 원인 예외가 그 결함({@code XRRuntimeException} ← {@code SAXParseException} "meta")임,
 * (3) 렌더러에 들어가는 보고서 HTML 전체({@code Generated at} 시각만 치환, {@code contract/export/monthly-report.html})를 고정한다.
 * 결함이 고쳐지면 (1)(2) 가 실패하므로 그때 PDF 텍스트 스냅샷을 새로 추가한다.
 *
 * <p>기록: {@code ./gradlew :admin-app:test --tests '*ExportCharacterizationTest' -Pcontract.record=true}
 */
@AdminWebSliceTest(controllers = {
        TenantsPageController.class, CredentialsTabController.class, AuditTabController.class,
        InsightsPageController.class, ActivityPageController.class, FunnelTabController.class,
        MonthlyReportController.class
})
@Import({CoreJacksonConfig.class, MonthlyReportService.class})
class ExportCharacterizationTest {

    static final Instant NOW = Instant.parse("2026-10-11T03:00:00Z"); // KST 2026-10-11 12:00
    static final UUID TID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final UUID TID2 = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    static final OffsetDateTime T1 = OffsetDateTime.parse("2026-10-10T09:15:30.123456+09:00");
    static final OffsetDateTime T2 = OffsetDateTime.parse("2026-10-01T00:00:00+09:00");

    /** 까다로운 문자열: 쉼표·따옴표·LF·CRLF·한글·수식 접두. */
    static final String COMMA = "Acme, Inc.";
    static final String QUOTE = "Say \"hi\"";
    static final String NEWLINE = "line1\nline2";
    static final String CRLF = "a\r\nb";
    static final String KOREAN = "가나다 한글 테넌트";
    static final String FORMULA = "=SUM(A1:A2)";

    @Autowired MockMvc mvc;
    @Autowired Clock clock;
    @Autowired MonthlyReportService reportService;
    @MockitoBean TenantAdminController tenants;
    @MockitoBean ApiKeyAdminController apiKeys;
    @MockitoBean CredentialAdminController credentials;
    @MockitoBean AuditLogController audit;
    @MockitoBean AuditChainMonitorController chain;
    @MockitoBean AaguidPolicyController aaguid;
    @MockitoBean PlatformInsightsController insights;
    @MockitoBean ActivityController activity;
    @MockitoBean FunnelController funnel;
    @MockitoBean AdoptionController adoption;
    @MockitoBean LicenseController license;
    @MockitoBean ActiveTenantController activeTenants;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(license.get()).thenReturn(ApiResponse.ok(new LicenseView("saas", "VALID", "acme", "lic_1",
                NOW.plusSeconds(86400L * 30), 30, List.of(), null, null, null)));
        when(apiKeys.list(null)).thenReturn(ApiResponse.ok(List.of()));
        when(tenants.get(anyString())).thenReturn(ApiResponse.ok(tenant(TID, KOREAN, "acme")));
    }

    static Authentication platform() {
        AdminUserDetails p = new AdminUserDetails(UUID.fromString("00000000-0000-0000-0000-0000000000e1"),
                "alice@example.com", "x", "PLATFORM_OPERATOR", Set.of(), true, null, Clock.systemUTC());
        return new UsernamePasswordAuthenticationToken(p, "x", p.getAuthorities());
    }

    static TenantView tenant(UUID id, String name, String slug) {
        return new TenantView(id, slug, name, "ACTIVE", "rp." + slug + ".example", name,
                List.of("https://" + slug + ".example"), Set.of("none"), true, false, "NONE", 60000,
                10, 2, null, T2, T2);
    }

    /** 응답 전체(상태줄·내보내기 헤더 2개·빈 줄·본문 바이트)를 하나의 바이트열로. */
    private byte[] download(MockHttpServletRequestBuilder req) throws Exception {
        MockHttpServletResponse res = mvc.perform(req.with(authentication(platform()))).andReturn().getResponse();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String head = "HTTP " + res.getStatus() + "\n"
                + "Content-Type: " + res.getContentType() + "\n"
                + "Content-Disposition: " + res.getHeader(HttpHeaders.CONTENT_DISPOSITION) + "\n"
                + "\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(res.getContentAsByteArray());
        return out.toByteArray();
    }

    private void csv(String name, MockHttpServletRequestBuilder req) throws Exception {
        byte[] all = download(req);
        String text = new String(all, StandardCharsets.UTF_8);
        assertThat(text).as(name).startsWith("HTTP 200\n");
        ContractSnapshot.assertBytesMatch("export/" + name + ".http", all);
    }

    // ── CSV ────────────────────────────────────────────────

    @Test
    void tenantsCsv() throws Exception {
        when(tenants.list()).thenReturn(ApiResponse.ok(List.of(
                tenant(TID, COMMA, "acme"),
                tenant(TID2, QUOTE + " " + NEWLINE, "beta"),
                tenant(UUID.fromString("00000000-0000-0000-0000-0000000000a3"), FORMULA, "gamma"),
                tenant(UUID.fromString("00000000-0000-0000-0000-0000000000a4"), KOREAN, "delta"),
                tenant(UUID.fromString("00000000-0000-0000-0000-0000000000a5"), CRLF, "epsilon"))));
        csv("tenants", get("/admin/_/tenants/export"));
    }

    @Test
    void credentialsCsv() throws Exception {
        List<CredentialView> rows = List.of(
                new CredentialView("AAAAAAAAAAAAAAAAAAAAAA", "dXNlci0x", COMMA, "ea9b8d664d011d21ac3f8d5fe7e8b77a",
                        KOREAN + " " + QUOTE, "packed", "internal,hybrid", 42, T1, T2),
                new CredentialView("BBBBBBBBBBBBBBBBBBBBBB", "-dXNlci0y", NEWLINE, null,
                        null, "none", "usb, nfc,unknown-x", 0, null, T2),
                new CredentialView("CCCCCCCCCCCCCCCCCCCCCC", "@user", "+82 10", "00000000000000000000000000000000",
                        "\tTabbed", "none", null, 7, T2, T1));
        when(credentials.list(eq(TID), anyInt(), anyInt(), any()))
                .thenReturn(ApiResponse.ok(new PageView<>(rows, 0, 50, rows.size(), false)));
        csv("credentials", get("/admin/_/tenants/acme/credentials.csv"));
    }

    @Test
    void auditCsv() throws Exception {
        List<AuditLogController.AuditLogView> rows = List.of(
                new AuditLogController.AuditLogView(UUID.fromString("00000000-0000-0000-0000-0000000000d1"),
                        UUID.fromString("00000000-0000-0000-0000-0000000000e1"), "alice@example.com", "API_KEY_ISSUED",
                        "api_key", COMMA, TID, "{\"name\":\"" + KOREAN + "\",\"scopes\":[\"a\",\"b\"],\"n\":1.50}", T1),
                new AuditLogController.AuditLogView(UUID.fromString("00000000-0000-0000-0000-0000000000d2"),
                        null, null, "CREDENTIAL_REVOKED", "credential", QUOTE, TID, "not json, \"raw\"\nline", T2),
                new AuditLogController.AuditLogView(UUID.fromString("00000000-0000-0000-0000-0000000000d3"),
                        null, "=cmd@example.com", "AUTH_FAILED", null, null, TID, null, T2));
        when(audit.list(isNull(), isNull(), eq(TID), any(), any(), anyInt(), anyInt())).thenReturn(ApiResponse.ok(rows));
        csv("audit", get("/admin/tenants/acme/audit.csv"));
    }

    @Test
    void insightsCsv() throws Exception {
        PlatformInsightsDto.View v = new PlatformInsightsDto.View(7,
                new PlatformInsightsDto.Totals(2, 1, 30, 100, 90, 20, 15, 3, 40, 25, 5),
                List.of(new PlatformInsightsDto.TenantRow(TID, COMMA + " " + KOREAN, "acme", "ACTIVE",
                                30, 100, 90, 20, 15, 3, 40, 25, 5, 50, 45, 50, 49, 1),
                        new PlatformInsightsDto.TenantRow(TID2, null, "beta", null,
                                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)),
                List.of(),
                List.of(new FunnelDto.DailyPoint("2026-10-10", 3, 1), new FunnelDto.DailyPoint("2026-10-11", 0, 0)),
                List.of(new AdoptionDto.Month("2026-09", false, 10, 4, 2), new AdoptionDto.Month("2026-10", true, 5, 3, 1)),
                List.of(new AdoptionDto.Device(QUOTE + " " + KOREAN, 3, 9), new AdoptionDto.Device(NEWLINE, 0, 0)),
                new PlatformInsightsDto.Thresholds(10.0, 20, 1, 0.5, 10));
        when(insights.get(anyInt())).thenReturn(v);
        for (String kind : List.of("tenants", "daily", "monthly", "devices")) {
            csv("insights-" + kind, get("/admin/insights.csv").param("kind", kind));
        }
    }

    @Test
    void activityCsv() throws Exception {
        ActivityView view = new ActivityView(new ActivityView.Kpi(3, 1, 1, null), List.of(), List.of(
                new ActivityView.Event(UUID.fromString("00000000-0000-0000-0000-0000000000f1"), "TENANT_CREATED",
                        "alice@example.com", "tenant", COMMA, TID, "acme", T1, "ops"),
                new ActivityView.Event(UUID.fromString("00000000-0000-0000-0000-0000000000f2"), "ADMIN_LOGIN_FAILED",
                        "-bob@example.com", null, null, null, null, T2, "security"),
                new ActivityView.Event(UUID.fromString("00000000-0000-0000-0000-0000000000f3"), "MDS_SYNC",
                        null, "mds", KOREAN + "\n" + QUOTE, TID2, null, T2, "system")));
        when(activity.activity(isNull(), any(), isNull(), isNull(), any())).thenReturn(ApiResponse.ok(view));
        csv("activity", get("/admin/_/activity/export"));
    }

    @Test
    void funnelCsv() throws Exception {
        FunnelDto.View f = new FunnelDto.View(7,
                new FunnelDto.Stage(10, 7, 0.7), new FunnelDto.Stage(3, 0, 0.0), 0.5,
                List.of(new FunnelDto.DailyPoint("2026-10-10", 13, 7)),
                List.of(new FunnelDto.EventCount("REG_START", 10)),
                List.of(new FunnelDto.DailyPoint("2026-10-10", 10, 7), new FunnelDto.DailyPoint("2026-10-11", 0, 0)),
                List.of(new FunnelDto.DailyPoint("2026-10-10", 3, 1), new FunnelDto.DailyPoint("2026-10-11", 2, 2)),
                List.of(new FunnelDto.FailureCount(CredentialAuthFailureReason.SIGNATURE_INVALID, 2),
                        new FunnelDto.FailureCount(null, 1),
                        new FunnelDto.FailureCount("CUSTOM, \"odd\"", 4)));
        when(funnel.get(eq(TID), anyInt())).thenReturn(f);
        AdoptionDto.View a = new AdoptionDto.View(7, 12, 3, 4, 5, 6, 2,
                List.of(new AdoptionDto.Month("2026-09", false, 10, 4, 2), new AdoptionDto.Month("2026-10", true, 5, 3, 1)),
                List.of(new AdoptionDto.Device(KOREAN + ", " + QUOTE, 3, 9), new AdoptionDto.Device("@Unknown", 1, 0)));
        when(adoption.get(eq(TID), anyInt())).thenReturn(a);
        for (String kind : List.of("summary", "auth", "reg", "failures", "monthly", "devices")) {
            csv("funnel-" + kind, get("/admin/tenants/acme/funnel.csv").param("kind", kind).param("win", "30"));
        }
    }

    // ── PDF ────────────────────────────────────────────────

    @Test
    void monthlyReportPdf() throws Exception {
        AuditChainOverview overview = new AuditChainOverview(Instant.parse("2026-10-11T02:59:59Z"), 24, 60,
                new AuditChainOverview.Totals(1, 3, 1, 1234, 56),
                List.of(new AuditChainTenantOverview(null, "[platform]", true, 10, List.of(0L, 1L), null),
                        new AuditChainTenantOverview(TID, "Acme <&> \"quoted\" 'single'", true, 1200, List.of(1L), null),
                        new AuditChainTenantOverview(TID2, KOREAN, false, 24, List.of(),
                                UUID.fromString("00000000-0000-0000-0000-0000000000bb"))));
        when(chain.overview(24)).thenReturn(overview);

        MockHttpServletResponse res = mvc.perform(get("/admin/api/audit/chain/monthly-report")
                        .param("from", "2026-10-01").param("to", "2026-10-31")
                        .with(authentication(platform())))
                .andReturn().getResponse();
        // 현재(Java base) 동작: MonthlyReportService.buildHtml 이 닫히지 않은 <meta charset='utf-8'> 를 써서
        // openhtmltopdf 의 XML 파서가 SAXParseException 을 내고, GlobalExceptionHandler 가 500 봉투로 바꾼다
        // (데이터와 무관하게 항상). 전환은 이 동작을 바꾸지 않아야 하므로 그대로 고정한다 — 고치는 것은 별도 작업(백로그).
        assertThat(res.getStatus()).as("월간 PDF 는 base 에서 항상 500 (알려진 결함)").isEqualTo(500);
        java.util.TreeMap<String, String> meta = new java.util.TreeMap<>();
        meta.put("@status", String.valueOf(res.getStatus()));
        meta.put("@contentType", String.valueOf(res.getContentType()));
        meta.put("@contentDisposition", String.valueOf(res.getHeader(HttpHeaders.CONTENT_DISPOSITION)));
        ContractSnapshot.assertMatches("export/monthly-report.response.shape.json",
                AdminJsonShape.of(res.getContentAsString(StandardCharsets.UTF_8), true, meta));

        // (a) 500 의 원인이 바로 그 알려진 결함인지 — 다른 예외로 바뀌면 여기서 드러난다.
        assertThatThrownBy(() -> reportService.generate(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31")))
                .isInstanceOf(com.openhtmltopdf.util.XRRuntimeException.class)
                .satisfies(e -> {
                    Throwable root = e;
                    while (root.getCause() != null && root.getCause() != root) root = root.getCause();
                    assertThat(root).isInstanceOf(SAXParseException.class);
                    assertThat(root.getMessage()).as("닫히지 않은 meta 요소").contains("meta");
                });

        // (b) PDF 렌더러로 들어가는 보고서 본문(HTML) 고정 — 이스케이프(<&>"')·한글·null tenantId·위변조 행.
        // buildHtml 은 private 이라 reflection 으로 부른다(main 무수정). 생성 시각(Instant.now())만 치환.
        Method build = MonthlyReportService.class.getDeclaredMethod("buildHtml",
                LocalDate.class, LocalDate.class, AuditChainOverview.class);
        build.setAccessible(true);
        String html = (String) build.invoke(reportService, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"), overview);
        String normalized = html.replaceAll("Generated at: [^<]+", "Generated at: <now>");
        assertThat(normalized).contains("Generated at: <now>");
        ContractSnapshot.assertMatches("export/monthly-report.html", normalized + "\n");
    }
}
