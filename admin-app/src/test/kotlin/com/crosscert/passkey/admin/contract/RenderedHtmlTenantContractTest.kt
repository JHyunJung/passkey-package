package com.crosscert.passkey.admin.contract

import com.crosscert.passkey.admin.apikey.ApiKeyAdminDto
import com.crosscert.passkey.admin.audit.AuditChainMonitorController
import com.crosscert.passkey.admin.audit.AuditChainOverview
import com.crosscert.passkey.admin.audit.AuditChainTenantOverview
import com.crosscert.passkey.admin.credential.CredentialAdminDto
import com.crosscert.passkey.admin.funnel.AdoptionDto
import com.crosscert.passkey.admin.policy.AaguidPolicyDto
import com.crosscert.passkey.admin.tenant.TenantAdminDto
import com.crosscert.passkey.admin.tenant.WebauthnConfigDiff
import com.crosscert.passkey.core.api.ApiResponse
import com.crosscert.passkey.core.api.BusinessException
import com.crosscert.passkey.core.api.ErrorCode
import com.crosscert.passkey.core.api.PageView
import com.crosscert.passkey.core.entity.TenantAaguidPolicy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.whenever
import org.springframework.http.ResponseEntity
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import java.time.ZoneOffset
import java.util.UUID

/**
 * 렌더 HTML 스냅샷 — Tenant 목록·상세와 상세 탭 컨트롤러 8개 (Kotlin 전환 안전망, 동결). 커버리지 표는
 * [RenderedHtmlContractCoverage].
 *
 * 테넌트 2개: `alpha`(ACTIVE, UV 필수 true, MDS 필수 false), `beta`(SUSPENDED, UV false, MDS true).
 * `tenants.get(idOrSlug)` 는 beta 의 ID·slug 면 beta, 그 밖은 alpha.
 */
@HtmlContractSupport.HtmlContractSlice
class RenderedHtmlTenantContractTest : HtmlContractSupport() {

    companion object {
        val T = "/admin/_/tenants/$TID"
        val KEY_ACTIVE: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
        val KEY_REVOKED: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000c2")

        fun alpha(): TenantAdminDto.TenantView = Stub.of(
            TenantAdminDto.TenantView::class.java,
            "id", TID, "slug", "alpha", "displayName", "Alpha Corp", "status", "ACTIVE",
            "rpId", "passkey.alpha.com", "rpName", "Alpha",
            "allowedOrigins", listOf("https://passkey.alpha.com", "android:apk-key-hash:AbCdEf0123456789AbCdEf0123456789AbCdEf01234"),
            "acceptedFormats", setOf("packed"), "requireUserVerification", true, "mdsRequired", false,
            "attestationConveyance", "NONE", "webauthnTimeoutMs", 60000, "credentials", 1234L, "apiKeys", 2L,
            "lastEventAt", T0, "createdAt", T0.minusDays(30), "updatedAt", T0,
        )

        fun beta(): TenantAdminDto.TenantView = Stub.of(
            TenantAdminDto.TenantView::class.java,
            "id", TID2, "slug", "beta", "displayName", "베타", "status", "SUSPENDED",
            "rpId", "beta.example.org", "rpName", "Beta", "allowedOrigins", listOf<String>(),
            "acceptedFormats", setOf("none"), "requireUserVerification", false, "mdsRequired", true,
            "attestationConveyance", "DIRECT", "webauthnTimeoutMs", 120000, "credentials", 0L, "apiKeys", 0L,
            "lastEventAt", null, "createdAt", T0.minusDays(3), "updatedAt", null,
        )
    }

    @BeforeEach
    fun tenantStubs() {
        whenever(tenants.list()).thenReturn(ApiResponse.ok(listOf(alpha(), beta())))
        whenever(tenants.get(anyString())).thenAnswer { inv ->
            val k = inv.getArgument<String>(0)
            ApiResponse.ok(if (k == TID2.toString() || k == "beta") beta() else alpha())
        }
        whenever(apiKeys.list(anyOrNull())).thenReturn(
            ApiResponse.ok(
                listOf(
                    Stub.of(
                        ApiKeyAdminDto.ApiKeyView::class.java, "id", KEY_ACTIVE, "tenantId", TID, "name", "운영 서버",
                        "keyPrefix", "pk_live_ab12", "scopes", linkedSetOf("registration", "authentication"),
                        "createdAt", T0.minusDays(10),
                        "expiresAt", T0.plusDays(5), "revokedAt", null, "lastUsedAt", T0,
                    ),
                    Stub.of(
                        ApiKeyAdminDto.ApiKeyView::class.java, "id", KEY_REVOKED, "tenantId", TID, "name", "old",
                        "keyPrefix", "pk_live_zz99", "scopes", setOf("registration"), "createdAt", T0.minusDays(100),
                        "expiresAt", null, "revokedAt", T0.minusDays(1), "lastUsedAt", null,
                    ),
                ),
            ),
        )
        whenever(chain.overview(anyInt())).thenReturn(
            AuditChainOverview(
                NOW, 24, 60,
                AuditChainOverview.Totals(1, 2, 1, 10L, 5L),
                listOf(
                    AuditChainTenantOverview(TID, "Alpha Corp", true, 7L, listOf(1L, 2L, 0L), null),
                    AuditChainTenantOverview(
                        TID2, "베타", false, 3L, listOf(0L, 1L, 0L),
                        UUID.fromString("00000000-0000-0000-0000-0000000000e1"),
                    ),
                ),
            ),
        )
    }

    // ── TenantsPageController ──────────────────────────────

    @Test
    fun tenantsPage() {
        snap("tenant/tenants.page.platform.html", page("/admin/tenants", operator()))
        snap("tenant/tenants.page.new-dialog.html", page("/admin/tenants?new=1&q=al&status=ACTIVE", operator()))
        snap("tenant/tenants.page.rp-admin.html", page("/admin/tenants", rpAdmin()))
    }

    @Test
    fun tenantsListFragment() {
        snap("tenant/tenants.list.frag.html", frag("/admin/_/tenants/list?status=SUSPENDED", operator()))
        snap("tenant/tenants.list.frag.reset.html", frag("/admin/_/tenants/list?q=zz&reset=q", operator()))
    }

    @Test
    fun tenantsCreateForm() {
        snap(
            "tenant/tenants.create.form-error.html",
            write(
                post("/admin/_/tenants")
                    .param("name", "").param("slug", "-Bad").param("rpId", "https://example.com"),
                operator(),
            ),
        )
        whenever(tenants.create(anyOrNull(), anyOrNull())).thenReturn(ApiResponse.ok(alpha()))
        snap(
            "tenant/tenants.create.ok.html",
            write(
                post("/admin/_/tenants")
                    .param("name", "Alpha Corp").param("slug", "alpha").param("rpId", "passkey.alpha.com"),
                operator(),
            ),
        )
    }

    // ── TenantDetailPageController ─────────────────────────

    @Test
    fun detailPage() {
        snap("tenant/detail.page.overview.platform.html", page("/admin/tenants/$TID", operator()))
        snap("tenant/detail.page.overview.rp-admin.html", page("/admin/tenants/$TID", rpAdmin()))
        snap("tenant/detail.page.suspended.platform.html", page("/admin/tenants/beta?tab=apikeys", operator()))
        snap("tenant/detail.page.unknown-tab.html", page("/admin/tenants/alpha?tab=nope", operator()))
        // 각 탭을 주소로 연 전체 페이지(탭 컨트롤러의 "주 GET" — 탭 본문은 이 페이지가 조각으로 불러온다)
        for (tab in listOf("webauthn", "aaguid", "credentials", "audit", "funnel")) {
            snap("tenant/detail.page.tab-$tab.platform.html", page("/admin/tenants/$TID?tab=$tab", operator()))
        }
        snap("tenant/detail.page.tab-audit.rp-admin.html", page("/admin/tenants/$TID?tab=audit", rpAdmin()))
    }

    @Test
    fun detailFragments() {
        snap("tenant/detail.tab-overview.frag.html", frag("$T/tab/overview", operator()))
        snap("tenant/detail.chain.frag.intact.html", frag("$T/overview/chain?manual=1", operator()))
        snap("tenant/detail.chain.frag.tampered.html", frag("/admin/_/tenants/$TID2/overview/chain", operator()))
        snap(
            "tenant/detail.suspend.html",
            write(
                post("$T/suspend").header("HX-Current-URL", "http://localhost/admin/tenants/$TID?tab=apikeys"),
                operator(),
            ),
        )
        snap("tenant/detail.activate.html", write(post("/admin/_/tenants/$TID2/activate"), operator()))
    }

    @Test
    fun detailNotFound() {
        whenever(tenants.get(anyString())).thenThrow(BusinessException(ErrorCode.ENTITY_NOT_FOUND))
        snap("tenant/detail.page.not-found.html", page("/admin/tenants/missing", operator()))
        snap("tenant/detail.tab.frag.not-found.html", frag("/admin/_/tenants/missing/tab/overview", operator()))
    }

    // ── ApiKeysTabController ───────────────────────────────

    @Test
    fun apiKeysTab() {
        snap("tenant/apikeys.tab.frag.platform.html", frag("$T/tab/apikeys", operator()))
        snap("tenant/apikeys.tab.frag.rp-admin.html", frag("$T/tab/apikeys", rpAdmin()))
    }

    @Test
    fun apiKeysWrites() {
        snap("tenant/apikeys.issue.form-error.html", write(post("$T/api-keys").param("name", ""), operator()))
        whenever(apiKeys.issue(anyOrNull(), anyOrNull())).thenReturn(
            ApiResponse.ok(
                Stub.of(
                    ApiKeyAdminDto.ApiKeyCreateResponse::class.java,
                    "id", KEY_ACTIVE, "plainText", "pk_live_ab12.secret", "prefix", "pk_live_ab12",
                    "scopes", setOf("registration"), "expiresAt", T0.plusDays(90),
                ),
            ),
        )
        snap(
            "tenant/apikeys.issue.ok.html",
            write(
                post("$T/api-keys").param("name", "새 키")
                    .param("scopes", "registration").param("expiresInMonths", "3"),
                operator(),
            ),
        )
        whenever(apiKeys.rotate(anyOrNull(), anyOrNull())).thenReturn(
            ApiResponse.ok(
                Stub.of(
                    ApiKeyAdminDto.ApiKeyRotateResponse::class.java,
                    "id", KEY_ACTIVE, "plaintextKey", "pk_live_cd34.secret", "prefix", "pk_live_cd34",
                    "scopes", setOf("registration"), "oldKeyExpiresAt", NOW.atOffset(ZoneOffset.UTC).plusHours(24),
                ),
            ),
        )
        snap(
            "tenant/apikeys.rotate.html",
            write(
                post("/admin/_/api-keys/$KEY_ACTIVE/rotate")
                    .param("tenantId", TID.toString()).header("X-Confirm", "1"),
                operator(),
            ),
        )
        snap(
            "tenant/apikeys.revoke.html",
            write(
                delete("/admin/_/api-keys/$KEY_ACTIVE")
                    .param("tenantId", TID.toString()).header("X-Confirm", "1"),
                operator(),
            ),
        )
    }

    // ── AaguidTabController ────────────────────────────────

    @Test
    fun aaguidTab() {
        snap("tenant/aaguid.tab.frag.html", frag("$T/tab/aaguid", operator()))
        snap("tenant/aaguid.entry-check.bad.html", frag("$T/aaguid/entry-check?value=zz", operator()))
        snap("tenant/aaguid.preview.block-all.html", write(post("$T/aaguid/preview").param("mode", "ALLOWLIST"), operator()))
        snap(
            "tenant/aaguid.preview.form-error.html",
            write(
                post("$T/aaguid/preview").param("mode", "ALLOWLIST")
                    .param(
                        "aaguid", "not-a-uuid", "adce0002-35bc-c60a-648b-0b25f1f05503",
                        "adce0002-35bc-c60a-648b-0b25f1f05503",
                    ),
                operator(),
            ),
        )
        snap(
            "tenant/aaguid.preview.ok.html",
            write(
                post("$T/aaguid/preview").param("mode", "ALLOWLIST")
                    .param("aaguid", "adce0002-35bc-c60a-648b-0b25f1f05503").param("mdsStrict", "true"),
                operator(),
            ),
        )
        snap("tenant/aaguid.save.form-error.html", write(put("$T/aaguid").param("mode", "BOGUS"), operator()))
        val deny = AaguidPolicyDto.View(
            TID, TenantAaguidPolicy.Mode.DENYLIST, false,
            listOf(
                AaguidPolicyDto.Entry(UUID.fromString("adce0002-35bc-c60a-648b-0b25f1f05503"), "분실 장비", "Chrome on Mac"),
                AaguidPolicyDto.Entry(UUID.fromString("00000000-0000-0000-0000-0000000000aa"), null, null),
            ),
            T0, OP_EMAIL,
        )
        whenever(aaguid.get(anyOrNull())).thenReturn(deny)
        snap("tenant/aaguid.tab.frag.denylist.html", frag("$T/tab/aaguid", rpAdmin()))
        whenever(aaguid.update(anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(deny)
        snap(
            "tenant/aaguid.save.ok.html",
            write(
                put("$T/aaguid").param("mode", "DENYLIST")
                    .param("aaguid", "adce0002-35bc-c60a-648b-0b25f1f05503", "00000000-0000-0000-0000-0000000000aa"),
                operator(),
            ),
        )
    }

    // ── WebauthnTabController ──────────────────────────────

    @Test
    fun webauthnTab() {
        snap("tenant/webauthn.tab.frag.alpha.html", frag("$T/tab/webauthn", operator()))
        snap("tenant/webauthn.tab.frag.beta.html", frag("/admin/_/tenants/$TID2/tab/webauthn", operator()))
        snap("tenant/webauthn.origin-check.bad.html", frag("$T/webauthn/origin-check?value=ftp://x", operator()))
        snap(
            "tenant/webauthn.apk-key.ok.html",
            frag(
                "$T/webauthn/apk-key?value=" +
                    "AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89",
                operator(),
            ),
        )
        snap(
            "tenant/webauthn.diff.form-error.html",
            write(post("$T/webauthn/diff").param("rpName", "").param("timeoutMs", "abc"), operator()),
        )
        whenever(tenants.diff(anyString(), anyOrNull())).thenReturn(
            WebauthnConfigDiff(
                Stub.of(
                    WebauthnConfigDiff.Current::class.java, "rpId", "passkey.alpha.com", "rpName", "Alpha",
                    "origins", listOf("https://passkey.alpha.com"), "formats", listOf("packed"),
                    "requireUserVerification", true, "mdsRequired", false,
                ),
                Stub.of(
                    WebauthnConfigDiff.Proposed::class.java, "rpId", "passkey.alpha.com", "rpName", "Alpha 2",
                    "origins", listOf("https://passkey.alpha.com", "https://login.alpha.com"), "formats", listOf("packed"),
                    "requireUserVerification", false, "mdsRequired", false,
                ),
                listOf(
                    WebauthnConfigDiff.FieldChange("rpName", "Alpha", "Alpha 2", listOf(), listOf()),
                    WebauthnConfigDiff.FieldChange("origins", null, null, listOf("https://login.alpha.com"), listOf()),
                    WebauthnConfigDiff.FieldChange("requireUserVerification", true, false, listOf(), listOf()),
                ),
                listOf("ORIGIN_REMOVED"),
            ),
        )
        snap(
            "tenant/webauthn.diff.ok.html",
            write(
                post("$T/webauthn/diff").param("rpName", "Alpha 2").param("timeoutMs", "90000")
                    .param("origins", "https://passkey.alpha.com", "https://login.alpha.com")
                    .param("userVerification", "PREFERRED").param("attestationConveyance", "DIRECT"),
                operator(),
            ),
        )
        snap(
            "tenant/webauthn.diff.no-change.html",
            write(
                post("$T/webauthn/diff").param("rpName", "Alpha").param("timeoutMs", "60000")
                    .param("origins", "https://passkey.alpha.com", "android:apk-key-hash:AbCdEf0123456789AbCdEf0123456789AbCdEf01234")
                    .param("userVerification", "REQUIRED").param("attestationConveyance", "NONE"),
                operator(),
            ),
        )
        snap(
            "tenant/webauthn.save.form-error.html",
            write(put("$T/webauthn").param("rpName", "").param("timeoutMs", "10"), operator()),
        )
        whenever(tenants.update(anyString(), anyOrNull(), anyOrNull())).thenReturn(ApiResponse.ok(beta()))
        snap(
            "tenant/webauthn.save.ok.html",
            write(
                put("$T/webauthn").param("rpName", "Beta").param("timeoutMs", "120000")
                    .param("origins", "https://beta.example.org").param("userVerification", "PREFERRED")
                    .param("attestationConveyance", "DIRECT").param("changes", "3"),
                operator(),
            ),
        )
    }

    // ── AuditTabController ─────────────────────────────────

    @Test
    fun auditTab() {
        snap("tenant/audit.tab.frag.platform.html", frag("$T/tab/audit", operator()))
        snap("tenant/audit.tab.frag.rp-admin.html", frag("$T/tab/audit?win=30d", rpAdmin()))
        snap("tenant/audit.card.frag.html", frag("$T/tab/audit/card?win=7d", operator()))
        snap("tenant/audit.more.frag.html", frag("$T/tab/audit/more?page=2&lastDay=2026-10-11", operator()))
        whenever(chain.verifyTenant(anyString()))
            .thenReturn(AuditChainMonitorController.TenantVerifyResponse(TID, true, null, NOW))
        snap("tenant/audit.verify.intact.html", frag("$T/audit/verify", operator()))
        whenever(chain.verifyTenant(anyString())).thenReturn(
            AuditChainMonitorController.TenantVerifyResponse(
                TID, false,
                UUID.fromString("00000000-0000-0000-0000-0000000000e1"), NOW,
            ),
        )
        snap("tenant/audit.verify.tampered.html", frag("$T/audit/verify", operator()))
    }

    // ── CredentialsTabController ───────────────────────────

    @Test
    fun credentialsTab() {
        whenever(credentials.list(anyOrNull(), anyInt(), anyInt(), anyOrNull())).thenReturn(
            ApiResponse.ok(
                PageView(
                    listOf(
                        Stub.of(
                            CredentialAdminDto.CredentialView::class.java, "credentialId", "Y3JlZDE", "userHandle", "dXNlcjE",
                            "label", "내 맥북", "aaguidHex", "adce000235bcc60a648b0b25f1f05503", "authenticatorName", "YubiKey 5",
                            "attestationFormat", "packed", "transports", "usb,nfc", "signCount", 42L, "lastUsedAt", T0,
                            "createdAt", T0.minusDays(9),
                        ),
                        Stub.of(
                            CredentialAdminDto.CredentialView::class.java, "credentialId", "Y3JlZDI", "userHandle", "dXNlcjI",
                            "label", null, "aaguidHex", null, "authenticatorName", null,
                            "attestationFormat", "none", "transports", null, "signCount", 0L, "lastUsedAt", null,
                            "createdAt", T0.minusDays(1),
                        ),
                    ),
                    0, 50, 2L, false,
                ),
            ),
        )
        whenever(credentials.authEvents(anyOrNull(), anyString(), anyInt(), anyInt())).thenReturn(
            ApiResponse.ok(
                PageView(
                    listOf(
                        CredentialAdminDto.AuthEventView("SUCCESS", null, 42L, T0),
                        CredentialAdminDto.AuthEventView("FAILED", "SIGN_COUNT_REGRESSION", 41L, T0.minusHours(3)),
                    ),
                    0, 20, 2L, true,
                ),
            ),
        )
        whenever(credentials.revoke(anyOrNull(), anyString(), anyOrNull())).thenReturn(ResponseEntity.ok(ApiResponse.ok()))
        snap("tenant/credentials.revoke.no-confirm.html", write(delete("$T/credentials/Y3JlZDE"), operator()))
        snap("tenant/credentials.tab.frag.html", frag("$T/tab/credentials", operator()))
        snap("tenant/credentials.list.frag.html", frag("$T/tab/credentials/list?status=REVOKED&q=ab", operator()))
        snap("tenant/credentials.detail.frag.html", frag("$T/credentials/Y3JlZDE?list=1", operator()))
        snap("tenant/credentials.revoke-confirm.frag.html", frag("$T/credentials/Y3JlZDE/revoke-confirm", operator()))
        snap("tenant/credentials.revoke.html", write(delete("$T/credentials/Y3JlZDE").header("X-Confirm", "1"), operator()))
        snap("tenant/credentials.detail.frag.not-found.html", frag("$T/credentials/nope", operator()))
    }

    // ── FunnelTabController ────────────────────────────────

    @Test
    fun funnelTab() {
        snap("tenant/funnel.tab.frag.html", frag("$T/tab/funnel", operator()))
        snap("tenant/funnel.tab.frag.win30.rp-admin.html", frag("$T/tab/funnel?win=30", rpAdmin()))
        whenever(adoption.get(anyOrNull(), anyInt())).thenReturn(
            AdoptionDto.View(
                7, 120L, 10L, 80L, 60L, 100L, 40L,
                listOf(
                    AdoptionDto.Month("2026-08", false, 300L, 50L, 12L), AdoptionDto.Month("2026-09", false, 0L, 0L, 0L),
                    AdoptionDto.Month("2026-10", true, 120L, 60L, 10L),
                ),
                listOf(AdoptionDto.Device("YubiKey 5", 70L, 50L), AdoptionDto.Device("iCloud Keychain", 50L, 0L)),
            ),
        )
        snap("tenant/funnel.tab.frag.adoption.html", frag("$T/tab/funnel?win=1", operator()))
    }
}
