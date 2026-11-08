package com.crosscert.passkey.admin.contract;

import com.crosscert.passkey.admin.activity.ActivityDetailView;
import com.crosscert.passkey.admin.activity.ActivityView;
import com.crosscert.passkey.admin.audit.AuditChainMonitorController;
import com.crosscert.passkey.admin.audit.AuditChainOverview;
import com.crosscert.passkey.admin.audit.AuditChainTenantOverview;
import com.crosscert.passkey.admin.audit.IncidentDto;
import com.crosscert.passkey.admin.insights.PlatformInsightsDto;
import com.crosscert.passkey.admin.license.LicenseView;
import com.crosscert.passkey.admin.tenant.TenantAdminDto;
import com.crosscert.passkey.core.api.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 렌더 HTML 스냅샷 — PLATFORM 전용 화면(Activity·감사 체인·Insights·라이선스) 컨트롤러 4개 (Kotlin 전환 안전망, 동결).
 * 커버리지 표는 {@link RenderedHtmlContractCoverage}. RP_ADMIN 은 전체 페이지에서 홈으로 리다이렉트, 조각은 403.
 */
@HtmlContractSupport.HtmlContractSlice
class RenderedHtmlPlatformContractTest extends HtmlContractSupport {

    static final UUID EV1 = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    static final UUID EV2 = UUID.fromString("00000000-0000-0000-0000-0000000000e2");
    static final UUID EV3 = UUID.fromString("00000000-0000-0000-0000-0000000000e3");
    static final UUID INC_OPEN = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    static final UUID INC_DONE = UUID.fromString("00000000-0000-0000-0000-0000000000f2");

    static ActivityView activityView() {
        return new ActivityView(new ActivityView.Kpi(120L, 7L, 3L, 85L),
                List.of(new ActivityView.TopTenant(TID, "alpha", 80L), new ActivityView.TopTenant(TID2, "beta", 40L)),
                List.of(new ActivityView.Event(EV1, "TENANT_UPDATED", OP_EMAIL, "TENANT", TID.toString(), TID, "alpha", T0, "ops"),
                        new ActivityView.Event(EV2, "ADMIN_LOGIN_FAILED", "x@example.com", "ADMIN_USER", null, null, null, T0.minusHours(5), "security"),
                        new ActivityView.Event(EV3, "API_KEY_ISSUED", RP_EMAIL, "API_KEY", "k1", TID2, "beta", T0.minusDays(1), "ops")));
    }

    static AuditChainOverview overview(boolean tampered) {
        return new AuditChainOverview(NOW, 24, 60, new AuditChainOverview.Totals(tampered ? 1 : 2, 2, tampered ? 1 : 0, 10L, 5L),
                List.of(new AuditChainTenantOverview(TID, "Alpha Corp", true, 7L, List.of(1L, 2L, 0L), null),
                        new AuditChainTenantOverview(TID2, "베타", !tampered, 3L, List.of(0L, 1L, 0L), tampered ? EV1 : null)));
    }

    static List<IncidentDto.IncidentView> incidents() {
        return List.of(
                new IncidentDto.IncidentView(INC_OPEN.toString(), TID2.toString(), "베타", EV1.toString(), "CHAIN_TAMPERED", "HIGH", "OPEN",
                        "hash mismatch", "2026-10-11T03:34:56Z", OP_EMAIL, null, null, null),
                new IncidentDto.IncidentView(INC_DONE.toString(), TID.toString(), "Alpha Corp", EV2.toString(), "CHAIN_TAMPERED", "HIGH", "RESOLVED",
                        null, "2026-10-01T00:00:00Z", OP_EMAIL, "2026-10-02T00:00:00Z", OP_EMAIL, "백업에서 복구"));
    }

    @BeforeEach
    void platformStubs() {
        when(tenants.list()).thenReturn(ApiResponse.ok(List.of(
                Stub.of(TenantAdminDto.TenantView.class, "id", TID, "slug", "alpha", "displayName", "Alpha Corp", "status", "ACTIVE"),
                Stub.of(TenantAdminDto.TenantView.class, "id", TID2, "slug", "beta", "displayName", "베타", "status", "SUSPENDED"))));
        when(activity.activity(any(), any(), any(), any(), any())).thenReturn(ApiResponse.ok(activityView()));
        when(activity.detail(any())).thenReturn(ApiResponse.ok(new ActivityDetailView(EV1, "TENANT_UPDATED", OP_ID, OP_EMAIL,
                "TENANT", TID.toString(), TID, "alpha", T0, "ops",
                "{\"before\":{\"rpName\":\"Alpha\",\"origins\":[\"https://a.com\"]},\"after\":{\"rpName\":\"Alpha 2\",\"origins\":[\"https://a.com\",\"https://b.com\"]}}")));
        when(chain.overview(anyInt())).thenReturn(overview(true));
        when(chain.listIncidents()).thenReturn(incidents());
        when(license.get()).thenReturn(ApiResponse.ok(new LicenseView("onprem", "VALID", "acme", "lic_1",
                NOW.plusSeconds(86400L * 20), 20L, List.of("mds", "insights"), NOW.minusSeconds(3600), null, NOW.plusSeconds(3600))));
        when(insights.get(anyInt())).thenReturn(Stub.of(PlatformInsightsDto.View.class, "windowDays", 7,
                "tenants", List.of(
                        Stub.of(PlatformInsightsDto.TenantRow.class, "tenantId", TID, "name", "Alpha Corp", "slug", "alpha", "status", "ACTIVE",
                                "activeUsers", 50L, "authAttempts", 100L, "authSuccess", 95L, "risk", 0L),
                        Stub.of(PlatformInsightsDto.TenantRow.class, "tenantId", TID2, "name", "베타", "slug", "beta", "status", "SUSPENDED",
                                "activeUsers", 5L, "authAttempts", 40L, "authSuccess", 10L, "risk", 12L, "recentRisk", 9L))));
    }

    // ── ActivityPageController ─────────────────────────────

    @Test
    void activityPage() throws Exception {
        snap("platform/activity.page.platform.html", page("/admin/activity", operator()));
        snap("platform/activity.page.filtered.html", page("/admin/activity?category=security&tenantId=" + TID, operator()));
        snap("platform/activity.page.rp-admin.html", page("/admin/activity", rpAdmin()));
    }

    @Test
    void activityFragments() throws Exception {
        snap("platform/activity.rows.frag.html", frag("/admin/_/activity/rows?category=ops&reset=1", operator()));
        snap("platform/activity.poll.frag.html", frag("/admin/_/activity/poll?loaded=1&topSig=x", operator()));
        snap("platform/activity.more.frag.html", frag("/admin/_/activity/more?before=2026-10-11T03:34:56Z&beforeId=" + EV3
                + "&lastDay=2026-10-10", operator()));
        snap("platform/activity.detail.frag.html", frag("/admin/_/activity/" + EV1, operator()));
        snap("platform/activity.detail.frag.bad-id.html", frag("/admin/_/activity/not-a-uuid", operator()));
        snap("platform/activity.rows.frag.rp-admin-denied.html", frag("/admin/_/activity/rows", rpAdmin()));
    }

    // ── AuditChainPageController ───────────────────────────

    @Test
    void auditChainPage() throws Exception {
        snap("platform/audit-chain.page.platform.html", page("/admin/audit-chain", operator()));
        snap("platform/audit-chain.page.rp-admin.html", page("/admin/audit-chain", rpAdmin()));
    }

    @Test
    void auditChainFragments() throws Exception {
        snap("platform/audit-chain.overview.frag.tampered.html", frag("/admin/_/audit-chain/overview?first=1", operator()));
        when(chain.overview(anyInt())).thenReturn(overview(false));
        when(chain.listIncidents()).thenReturn(List.of());
        snap("platform/audit-chain.overview.frag.intact.html", frag("/admin/_/audit-chain/overview?view=tampered", operator()));
        when(chain.verifyTenant(anyString())).thenReturn(new AuditChainMonitorController.TenantVerifyResponse(TID, true, null, NOW));
        snap("platform/audit-chain.verify.frag.html", frag("/admin/_/audit-chain/verify?tenantId=" + TID, operator()));
        snap("platform/audit-chain.verify.frag.bad-id.html", frag("/admin/_/audit-chain/verify?tenantId=zz", operator()));
        when(chain.listIncidents()).thenReturn(incidents());
        snap("platform/audit-chain.incidents.frag.html", frag("/admin/_/audit-chain/incidents", operator()));
    }

    @Test
    void auditChainWrites() throws Exception {
        when(chain.backfill()).thenReturn(new AuditChainMonitorController.BackfillResponse(2, 10, 1));
        when(chain.overview(anyInt())).thenReturn(overview(false));
        snap("platform/audit-chain.backfill.html", write(post("/admin/_/audit-chain/backfill").header("X-Confirm", "1"), operator()));
        when(chain.overview(anyInt())).thenReturn(overview(true));
        snap("platform/audit-chain.backfill.blocked.html", write(post("/admin/_/audit-chain/backfill").header("X-Confirm", "1"), operator()));
        when(chain.createIncident(any(), any())).thenReturn(incidents().get(0));
        snap("platform/audit-chain.incident.create.html", write(post("/admin/_/audit-chain/incidents").param("tenantId", TID2.toString()), operator()));
        snap("platform/audit-chain.incident.resolve.form-error.html", write(post("/admin/_/audit-chain/incidents/resolve")
                .param("id", INC_OPEN.toString()).param("note", ""), operator()));
        when(chain.resolveIncident(anyString(), any(), any())).thenReturn(incidents().get(1));
        snap("platform/audit-chain.incident.resolve.ok.html", write(post("/admin/_/audit-chain/incidents/resolve")
                .param("id", INC_OPEN.toString()).param("note", "백업에서 복구"), operator()));
    }

    // ── InsightsPageController ─────────────────────────────

    @Test
    void insightsPage() throws Exception {
        snap("platform/insights.page.platform.html", page("/admin/insights", operator()));
        snap("platform/insights.page.rp-admin.html", page("/admin/insights", rpAdmin()));
        snap("platform/insights.root.frag.html", frag("/admin/_/insights?win=30&sort=risk", operator()));
        snap("platform/insights.root.frag.rp-admin-denied.html", frag("/admin/_/insights", rpAdmin()));
    }

    // ── LicensePageController ──────────────────────────────

    @Test
    void licensePage() throws Exception {
        snap("platform/license.page.onprem-valid.html", page("/admin/license", operator()));
        snap("platform/license.page.rp-admin.html", page("/admin/license", rpAdmin()));
        when(license.get()).thenReturn(ApiResponse.ok(new LicenseView("saas", "NETWORK_GRACE", "acme", "lic_1",
                NOW.plusSeconds(86400L * 3), 3L, List.of(), NOW.minusSeconds(86400L * 2), 30L, null)));
        snap("platform/license.page.saas-grace.html", page("/admin/license", operator()));
        snap("platform/license.recheck.frag.html", frag("/admin/_/license/recheck", operator()));
        when(license.get()).thenThrow(new IllegalStateException("license server down"));
        snap("platform/license.recheck.frag.failed.html", frag("/admin/_/license/recheck", operator()));
    }
}
