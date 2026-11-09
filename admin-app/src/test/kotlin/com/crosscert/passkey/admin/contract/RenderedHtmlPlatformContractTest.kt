package com.crosscert.passkey.admin.contract

import com.crosscert.passkey.admin.activity.ActivityDetailView
import com.crosscert.passkey.admin.activity.ActivityView
import com.crosscert.passkey.admin.audit.AuditChainMonitorController
import com.crosscert.passkey.admin.audit.AuditChainOverview
import com.crosscert.passkey.admin.audit.AuditChainTenantOverview
import com.crosscert.passkey.admin.audit.IncidentDto
import com.crosscert.passkey.admin.insights.PlatformInsightsDto
import com.crosscert.passkey.admin.license.LicenseView
import com.crosscert.passkey.admin.tenant.TenantAdminDto
import com.crosscert.passkey.core.api.ApiResponse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.whenever
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.util.UUID

/**
 * 렌더 HTML 스냅샷 — PLATFORM 전용 화면(Activity·감사 체인·Insights·라이선스) 컨트롤러 4개 (Kotlin 전환 안전망, 동결).
 * 커버리지 표는 [RenderedHtmlContractCoverage]. RP_ADMIN 은 전체 페이지에서 홈으로 리다이렉트, 조각은 403.
 */
@HtmlContractSupport.HtmlContractSlice
class RenderedHtmlPlatformContractTest : HtmlContractSupport() {

    companion object {
        val EV1: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000e1")
        val EV2: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000e2")
        val EV3: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000e3")
        val INC_OPEN: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000f1")
        val INC_DONE: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000f2")

        fun activityView(): ActivityView = ActivityView(
            ActivityView.Kpi(120L, 7L, 3L, 85L),
            listOf(ActivityView.TopTenant(TID, "alpha", 80L), ActivityView.TopTenant(TID2, "beta", 40L)),
            listOf(
                ActivityView.Event(EV1, "TENANT_UPDATED", OP_EMAIL, "TENANT", TID.toString(), TID, "alpha", T0, "ops"),
                ActivityView.Event(
                    EV2, "ADMIN_LOGIN_FAILED", "x@example.com", "ADMIN_USER", null, null, null,
                    T0.minusHours(5), "security",
                ),
                ActivityView.Event(EV3, "API_KEY_ISSUED", RP_EMAIL, "API_KEY", "k1", TID2, "beta", T0.minusDays(1), "ops"),
            ),
        )

        fun overview(tampered: Boolean): AuditChainOverview = AuditChainOverview(
            NOW, 24, 60,
            AuditChainOverview.Totals(if (tampered) 1 else 2, 2, if (tampered) 1 else 0, 10L, 5L),
            listOf(
                AuditChainTenantOverview(TID, "Alpha Corp", true, 7L, listOf(1L, 2L, 0L), null),
                AuditChainTenantOverview(TID2, "베타", !tampered, 3L, listOf(0L, 1L, 0L), if (tampered) EV1 else null),
            ),
        )

        fun incidents(): List<IncidentDto.IncidentView> = listOf(
            IncidentDto.IncidentView(
                INC_OPEN.toString(), TID2.toString(), "베타", EV1.toString(), "CHAIN_TAMPERED", "HIGH", "OPEN",
                "hash mismatch", "2026-10-11T03:34:56Z", OP_EMAIL, null, null, null,
            ),
            IncidentDto.IncidentView(
                INC_DONE.toString(), TID.toString(), "Alpha Corp", EV2.toString(), "CHAIN_TAMPERED", "HIGH", "RESOLVED",
                null, "2026-10-01T00:00:00Z", OP_EMAIL, "2026-10-02T00:00:00Z", OP_EMAIL, "백업에서 복구",
            ),
        )
    }

    @BeforeEach
    fun platformStubs() {
        whenever(tenants.list()).thenReturn(
            ApiResponse.ok(
                listOf(
                    Stub.of(
                        TenantAdminDto.TenantView::class.java,
                        "id", TID, "slug", "alpha", "displayName", "Alpha Corp", "status", "ACTIVE",
                    ),
                    Stub.of(
                        TenantAdminDto.TenantView::class.java,
                        "id", TID2, "slug", "beta", "displayName", "베타", "status", "SUSPENDED",
                    ),
                ),
            ),
        )
        whenever(activity.activity(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(ApiResponse.ok(activityView()))
        whenever(activity.detail(anyOrNull())).thenReturn(
            ApiResponse.ok(
                ActivityDetailView(
                    EV1, "TENANT_UPDATED", OP_ID, OP_EMAIL,
                    "TENANT", TID.toString(), TID, "alpha", T0, "ops",
                    "{\"before\":{\"rpName\":\"Alpha\",\"origins\":[\"https://a.com\"]},\"after\":{\"rpName\":\"Alpha 2\",\"origins\":[\"https://a.com\",\"https://b.com\"]}}",
                ),
            ),
        )
        whenever(chain.overview(anyInt())).thenReturn(overview(true))
        whenever(chain.listIncidents()).thenReturn(incidents())
        whenever(license.get()).thenReturn(
            ApiResponse.ok(
                LicenseView(
                    "onprem", "VALID", "acme", "lic_1",
                    NOW.plusSeconds(86400L * 20), 20L, listOf("mds", "insights"), NOW.minusSeconds(3600), null,
                    NOW.plusSeconds(3600),
                ),
            ),
        )
        whenever(insights.get(anyInt())).thenReturn(
            Stub.of(
                PlatformInsightsDto.View::class.java, "windowDays", 7,
                "tenants",
                listOf(
                    Stub.of(
                        PlatformInsightsDto.TenantRow::class.java, "tenantId", TID, "name", "Alpha Corp", "slug", "alpha",
                        "status", "ACTIVE",
                        "activeUsers", 50L, "authAttempts", 100L, "authSuccess", 95L, "risk", 0L,
                    ),
                    Stub.of(
                        PlatformInsightsDto.TenantRow::class.java, "tenantId", TID2, "name", "베타", "slug", "beta",
                        "status", "SUSPENDED",
                        "activeUsers", 5L, "authAttempts", 40L, "authSuccess", 10L, "risk", 12L, "recentRisk", 9L,
                    ),
                ),
            ),
        )
    }

    // ── ActivityPageController ─────────────────────────────

    @Test
    fun activityPage() {
        snap("platform/activity.page.platform.html", page("/admin/activity", operator()))
        snap("platform/activity.page.filtered.html", page("/admin/activity?category=security&tenantId=$TID", operator()))
        snap("platform/activity.page.rp-admin.html", page("/admin/activity", rpAdmin()))
    }

    @Test
    fun activityFragments() {
        snap("platform/activity.rows.frag.html", frag("/admin/_/activity/rows?category=ops&reset=1", operator()))
        snap("platform/activity.poll.frag.html", frag("/admin/_/activity/poll?loaded=1&topSig=x", operator()))
        snap(
            "platform/activity.more.frag.html",
            frag("/admin/_/activity/more?before=2026-10-11T03:34:56Z&beforeId=$EV3&lastDay=2026-10-10", operator()),
        )
        snap("platform/activity.detail.frag.html", frag("/admin/_/activity/$EV1", operator()))
        snap("platform/activity.detail.frag.bad-id.html", frag("/admin/_/activity/not-a-uuid", operator()))
        snap("platform/activity.rows.frag.rp-admin-denied.html", frag("/admin/_/activity/rows", rpAdmin()))
    }

    // ── AuditChainPageController ───────────────────────────

    @Test
    fun auditChainPage() {
        snap("platform/audit-chain.page.platform.html", page("/admin/audit-chain", operator()))
        snap("platform/audit-chain.page.rp-admin.html", page("/admin/audit-chain", rpAdmin()))
    }

    @Test
    fun auditChainFragments() {
        snap("platform/audit-chain.overview.frag.tampered.html", frag("/admin/_/audit-chain/overview?first=1", operator()))
        whenever(chain.overview(anyInt())).thenReturn(overview(false))
        whenever(chain.listIncidents()).thenReturn(listOf())
        snap("platform/audit-chain.overview.frag.intact.html", frag("/admin/_/audit-chain/overview?view=tampered", operator()))
        whenever(chain.verifyTenant(anyString()))
            .thenReturn(AuditChainMonitorController.TenantVerifyResponse(TID, true, null, NOW))
        snap("platform/audit-chain.verify.frag.html", frag("/admin/_/audit-chain/verify?tenantId=$TID", operator()))
        snap("platform/audit-chain.verify.frag.bad-id.html", frag("/admin/_/audit-chain/verify?tenantId=zz", operator()))
        whenever(chain.listIncidents()).thenReturn(incidents())
        snap("platform/audit-chain.incidents.frag.html", frag("/admin/_/audit-chain/incidents", operator()))
    }

    @Test
    fun auditChainWrites() {
        whenever(chain.backfill()).thenReturn(AuditChainMonitorController.BackfillResponse(2, 10, 1))
        whenever(chain.overview(anyInt())).thenReturn(overview(false))
        snap(
            "platform/audit-chain.backfill.html",
            write(post("/admin/_/audit-chain/backfill").header("X-Confirm", "1"), operator()),
        )
        whenever(chain.overview(anyInt())).thenReturn(overview(true))
        snap(
            "platform/audit-chain.backfill.blocked.html",
            write(post("/admin/_/audit-chain/backfill").header("X-Confirm", "1"), operator()),
        )
        whenever(chain.createIncident(anyOrNull(), anyOrNull())).thenReturn(incidents()[0])
        snap(
            "platform/audit-chain.incident.create.html",
            write(post("/admin/_/audit-chain/incidents").param("tenantId", TID2.toString()), operator()),
        )
        snap(
            "platform/audit-chain.incident.resolve.form-error.html",
            write(
                post("/admin/_/audit-chain/incidents/resolve")
                    .param("id", INC_OPEN.toString()).param("note", ""),
                operator(),
            ),
        )
        whenever(chain.resolveIncident(anyString(), anyOrNull(), anyOrNull())).thenReturn(incidents()[1])
        snap(
            "platform/audit-chain.incident.resolve.ok.html",
            write(
                post("/admin/_/audit-chain/incidents/resolve")
                    .param("id", INC_OPEN.toString()).param("note", "백업에서 복구"),
                operator(),
            ),
        )
    }

    // ── InsightsPageController ─────────────────────────────

    @Test
    fun insightsPage() {
        snap("platform/insights.page.platform.html", page("/admin/insights", operator()))
        snap("platform/insights.page.rp-admin.html", page("/admin/insights", rpAdmin()))
        snap("platform/insights.root.frag.html", frag("/admin/_/insights?win=30&sort=risk", operator()))
        snap("platform/insights.root.frag.rp-admin-denied.html", frag("/admin/_/insights", rpAdmin()))
    }

    // ── LicensePageController ──────────────────────────────

    @Test
    fun licensePage() {
        snap("platform/license.page.onprem-valid.html", page("/admin/license", operator()))
        snap("platform/license.page.rp-admin.html", page("/admin/license", rpAdmin()))
        whenever(license.get()).thenReturn(
            ApiResponse.ok(
                LicenseView(
                    "saas", "NETWORK_GRACE", "acme", "lic_1",
                    NOW.plusSeconds(86400L * 3), 3L, listOf(), NOW.minusSeconds(86400L * 2), 30L, null,
                ),
            ),
        )
        snap("platform/license.page.saas-grace.html", page("/admin/license", operator()))
        snap("platform/license.recheck.frag.html", frag("/admin/_/license/recheck", operator()))
        whenever(license.get()).thenThrow(IllegalStateException("license server down"))
        snap("platform/license.recheck.frag.failed.html", frag("/admin/_/license/recheck", operator()))
    }
}
