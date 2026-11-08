package com.crosscert.passkey.admin.contract

import com.crosscert.passkey.admin.auth.MfaController
import com.crosscert.passkey.admin.mds.MdsHistoryView
import com.crosscert.passkey.admin.mds.MdsSchedulerService
import com.crosscert.passkey.admin.mds.MdsStatusView
import com.crosscert.passkey.admin.operator.AdminUserDto
import com.crosscert.passkey.admin.operator.SignupRequestDto
import com.crosscert.passkey.admin.policy.SecurityPolicyDto
import com.crosscert.passkey.admin.system.SystemInfoView
import com.crosscert.passkey.admin.tenant.TenantAdminDto
import com.crosscert.passkey.core.api.ApiResponse
import com.crosscert.passkey.core.entity.AdminUser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import java.util.Optional
import java.util.UUID

/**
 * 렌더 HTML 스냅샷 — 설정 화면과 탭 컨트롤러 6개 (Kotlin 전환 안전망, 동결). 커버리지 표는 [RenderedHtmlContractCoverage].
 */
@HtmlContractSupport.HtmlContractSlice
class RenderedHtmlSettingsContractTest : HtmlContractSupport() {

    companion object {
        val SIGNUP_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000d1")
        val USER_RP: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000d2")
        val USER_SUSP: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000d3")
    }

    @BeforeEach
    fun settingsStubs() {
        whenever(signups.list()).thenReturn(
            listOf(SignupRequestDto.View(SIGNUP_ID, "new@example.com", "RP 운영 담당입니다", T0)),
        )
        whenever(adminUsers.list()).thenReturn(
            listOf(
                Stub.of(
                    AdminUserDto.View::class.java, "id", OP_ID, "email", OP_EMAIL, "role", "PLATFORM_OPERATOR",
                    "status", "ACTIVE",
                    "tenantIds", listOf<UUID>(), "createdAt", T0.minusDays(90), "lastLoginAt", T0, "suspendedAt", null,
                    "createdBy", "system", "mfaEnabled", true,
                ),
                Stub.of(
                    AdminUserDto.View::class.java, "id", USER_RP, "email", RP_EMAIL, "role", "RP_ADMIN", "status", "ACTIVE",
                    "tenantIds", listOf(TID, TID2), "createdAt", T0.minusDays(10), "lastLoginAt", null, "suspendedAt", null,
                    "createdBy", OP_EMAIL, "mfaEnabled", false,
                ),
                Stub.of(
                    AdminUserDto.View::class.java, "id", USER_SUSP, "email", "gone@example.com", "role", "RP_ADMIN",
                    "status", "SUSPENDED",
                    "tenantIds", listOf(TID), "createdAt", T0.minusDays(40), "lastLoginAt", T0.minusDays(20),
                    "suspendedAt", T0.minusDays(5), "createdBy", OP_EMAIL, "mfaEnabled", false,
                ),
            ),
        )
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
        whenever(securityPolicy.get()).thenReturn(
            SecurityPolicyDto.View(30, false, listOf("https://console.example.com", "http://localhost"), T0, OP_EMAIL),
        )
        whenever(mds.status()).thenReturn(
            ApiResponse.ok(
                MdsStatusView(
                    42L, "2026-11-01", "2026-10-11T03:00:00+09:00", 3,
                    "PINNED", MdsStatusView.SuccessRate(28, 30),
                ),
            ),
        )
        whenever(mds.history(anyInt())).thenReturn(
            ApiResponse.ok(
                listOf(
                    MdsHistoryView(
                        3L, "2026-10-13T03:00:00+09:00", "2026-10-13T03:00:02+09:00", 42L, "SYNCED",
                        "+2 / -0 entries", 1834, null,
                    ),
                    MdsHistoryView(
                        2L, "2026-10-12T03:00:00+09:00", "2026-10-12T03:00:05+09:00", null, "FAILED", null, 5012,
                        "BLOB signature\n  verification failed",
                    ),
                    MdsHistoryView(1L, "2026-10-11T03:00:00+09:00", null, 41L, "SKIPPED", null, null, null),
                ),
            ),
        )
        whenever(systemInfo.get()).thenReturn(
            ApiResponse.ok(
                SystemInfoView(
                    "1.4.2", "2026-10-01T09:00:00+09:00", 12L,
                    SystemInfoView.Host("prod"),
                    listOf(
                        SystemInfoView.Component("Oracle", "19c", "UP", null),
                        SystemInfoView.Component("Redis", "7.2", "DOWN", "연결 거부"),
                    ),
                ),
            ),
        )
    }

    private fun mfaOn() {
        val u = AdminUser(OP_EMAIL, "x", "PLATFORM_OPERATOR")
        u.setMfaEnabled(true)
        whenever(admins.findByEmail(anyString())).thenReturn(Optional.of(u))
    }

    // ── SettingsPageController ─────────────────────────────

    @Test
    fun settingsPage() {
        snap("settings/page.account.platform.html", page("/admin/settings", operator()))
        snap("settings/page.admins.platform.html", page("/admin/settings?tab=admins", operator()))
        snap("settings/page.account.rp-admin.html", page("/admin/settings?tab=admins", rpAdmin()))
        snap("settings/tab.unknown.frag.html", frag("/admin/_/settings/tab/nope", operator()))
        // 각 탭을 주소로 연 전체 페이지(선택된 탭 표시 + 지연 로드 속성 — 탭 본문은 페이지가 조각으로 불러온다)
        for (tab in listOf("mds", "security", "system")) {
            snap("settings/page.$tab.platform.html", page("/admin/settings?tab=$tab", operator()))
        }
    }

    // ── AccountTabController ───────────────────────────────

    @Test
    fun accountTab() {
        snap("settings/account.tab.frag.mfa-off.html", frag("/admin/_/settings/tab/account", operator()))
        mfaOn()
        snap("settings/account.tab.frag.mfa-on.rp-admin.html", frag("/admin/_/settings/tab/account", rpAdmin()))
    }

    @Test
    fun accountMfaFlows() {
        val enroll = LinkedHashMap<String, Any>()
        enroll["secret"] = "JBSWY3DPEHPK3PXP"
        enroll["otpauthUri"] =
            "otpauth://totp/Passkey%20Admin:op%40example.com?secret=JBSWY3DPEHPK3PXP&issuer=Passkey%20Admin"
        doReturn(ResponseEntity.ok(enroll)).whenever(mfa).enroll(anyOrNull(), anyOrNull())
        snap("settings/account.enroll.html", write(post("/admin/_/settings/mfa/enroll"), operator()))

        doReturn(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "invalid_code")))
            .whenever(mfa).confirm(any<MfaController.VerifyRequest>(), anyOrNull(), anyOrNull())
        snap(
            "settings/account.confirm.form-error.html",
            write(post("/admin/_/settings/mfa/confirm").param("code", "000000"), operator()),
        )
        val confirmed = LinkedHashMap<String, Any>()
        confirmed["confirmed"] = true
        confirmed["recoveryCodes"] = listOf("aaaa-bbbb-cccc", "dddd-eeee-ffff")
        doReturn(ResponseEntity.ok(confirmed))
            .whenever(mfa).confirm(any<MfaController.VerifyRequest>(), anyOrNull(), anyOrNull())
        snap(
            "settings/account.confirm.ok.html",
            write(post("/admin/_/settings/mfa/confirm").param("code", "123456"), operator()),
        )

        mfaOn()
        doReturn(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "invalid_code")))
            .whenever(mfa).disable(any<MfaController.VerifyRequest>(), anyOrNull(), anyOrNull())
        snap(
            "settings/account.disable.form-error.html",
            write(post("/admin/_/settings/mfa/disable").param("code", "1"), operator()),
        )
        doReturn(ResponseEntity.ok(mapOf("disabled" to true)))
            .whenever(mfa).disable(any<MfaController.VerifyRequest>(), anyOrNull(), anyOrNull())
        snap(
            "settings/account.disable.ok.html",
            write(post("/admin/_/settings/mfa/disable").param("code", "123456"), operator()),
        )
    }

    // ── AdminUsersTabController ────────────────────────────

    @Test
    fun adminUsersTab() {
        snap("settings/admins.tab.frag.html", frag("/admin/_/settings/tab/admins", operator()))
        snap("settings/admins.tab.frag.rp-admin-denied.html", frag("/admin/_/settings/tab/admins", rpAdmin()))
        snap("settings/admins.approve-dialog.frag.html", frag("/admin/_/settings/signups/$SIGNUP_ID/approve", operator()))
    }

    @Test
    fun adminUsersWrites() {
        snap(
            "settings/admins.approve.form-error.role.html",
            write(post("/admin/_/settings/signups/$SIGNUP_ID/approve"), operator()),
        )
        snap(
            "settings/admins.approve.form-error.tenant.html",
            write(post("/admin/_/settings/signups/$SIGNUP_ID/approve").param("role", "RP_ADMIN"), operator()),
        )
        whenever(signups.approve(anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(
            Stub.of(
                AdminUserDto.View::class.java, "id", SIGNUP_ID,
                "email", "new@example.com", "role", "RP_ADMIN", "status", "ACTIVE", "tenantIds", listOf(TID),
                "mfaEnabled", false,
            ),
        )
        snap(
            "settings/admins.approve.ok.html",
            write(
                post("/admin/_/settings/signups/$SIGNUP_ID/approve")
                    .param("role", "RP_ADMIN").param("tenantId", TID.toString()),
                operator(),
            ),
        )
        doReturn(ResponseEntity.noContent().build<Any>()).whenever(signups).reject(anyOrNull(), anyOrNull())
        snap(
            "settings/admins.reject.html",
            write(post("/admin/_/settings/signups/$SIGNUP_ID/reject").header("X-Confirm", "1"), operator()),
        )
        snap(
            "settings/admins.suspend.html",
            write(post("/admin/_/settings/admins/$USER_RP/suspend").header("X-Confirm", "1"), operator()),
        )
        snap(
            "settings/admins.suspend.stale.html",
            write(post("/admin/_/settings/admins/$USER_SUSP/suspend").header("X-Confirm", "1"), operator()),
        )
        snap(
            "settings/admins.activate.undo.html",
            write(
                post("/admin/_/settings/admins/$USER_SUSP/activate")
                    .param("undo", "1").param("email", "gone@example.com"),
                operator(),
            ),
        )
    }

    // ── MdsTabController ───────────────────────────────────

    @Test
    fun mdsTab() {
        snap("settings/mds.tab.frag.html", frag("/admin/_/settings/tab/mds", operator()))
        snap("settings/mds.tab.frag.rp-admin-denied.html", frag("/admin/_/settings/tab/mds", rpAdmin()))
        whenever(mds.sync()).thenReturn(ApiResponse.ok(MdsSchedulerService.SyncResult("SYNCED", 43L, null)))
        snap("settings/mds.sync.ok.html", write(post("/admin/_/settings/mds/sync"), operator()))
        whenever(mds.sync()).thenReturn(ApiResponse.ok(MdsSchedulerService.SyncResult("FAILED", null, "timeout")))
        snap("settings/mds.sync.failed.html", write(post("/admin/_/settings/mds/sync"), operator()))
        whenever(mds.history(anyInt())).thenReturn(ApiResponse.ok(listOf()))
        whenever(mds.status()).thenReturn(ApiResponse.ok(MdsStatusView(0L, null, null, 0, "PINNED", null)))
        snap("settings/mds.tab.frag.never-synced.html", frag("/admin/_/settings/tab/mds", operator()))
    }

    // ── SecurityPolicyTabController ────────────────────────

    @Test
    fun securityTab() {
        snap("settings/security.tab.frag.html", frag("/admin/_/settings/tab/security", operator()))
        snap("settings/security.tab.frag.rp-admin-denied.html", frag("/admin/_/settings/tab/security", rpAdmin()))
        whenever(securityPolicy.get()).thenReturn(SecurityPolicyDto.View(15, true, listOf(), null, null))
        snap("settings/security.tab.frag.mfa-required.html", frag("/admin/_/settings/tab/security?revert=1", operator()))
        snap(
            "settings/security.origin-check.bad.html",
            frag("/admin/_/settings/security/origin-check?value=not-a-url", operator()),
        )
    }

    @Test
    fun securityWrites() {
        snap(
            "settings/security.save.form-error.html",
            write(
                put("/admin/_/settings/security")
                    .param("sessionIdleTimeoutMinutes", "abc").param("mfaRequired", "maybe")
                    .param("corsAllowlist", "ftp://x"),
                operator(),
            ),
        )
        snap(
            "settings/security.save.lockout-dialog.html",
            write(
                put("/admin/_/settings/security")
                    .param("sessionIdleTimeoutMinutes", "30").param("mfaRequired", "true")
                    .param("corsAllowlist", "https://other.example.com"),
                operator(),
            ),
        )
        whenever(securityPolicy.update(anyOrNull(), anyOrNull())).thenReturn(
            SecurityPolicyDto.View(45, true, listOf("https://console.example.com", "http://localhost"), T0, OP_EMAIL),
        )
        snap(
            "settings/security.save.ok.html",
            write(
                put("/admin/_/settings/security")
                    .param("sessionIdleTimeoutMinutes", "45").param("mfaRequired", "true")
                    .param("corsAllowlist", "https://console.example.com", "http://localhost"),
                operator(),
            ),
        )
    }

    // ── SystemInfoTabController ────────────────────────────

    @Test
    fun systemTab() {
        snap("settings/system.tab.frag.html", frag("/admin/_/settings/tab/system", operator()))
        snap("settings/system.tab.frag.rp-admin-denied.html", frag("/admin/_/settings/tab/system", rpAdmin()))
    }
}
