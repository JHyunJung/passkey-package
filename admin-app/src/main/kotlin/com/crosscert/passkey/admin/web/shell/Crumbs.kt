package com.crosscert.passkey.admin.web.shell

/**
 * 화면별 브레드크럼(← App.tsx buildBreadcrumb). 화면 컨트롤러가 모델에 담아
 * `breadcrumb :: breadcrumb(${breadcrumbs})` 로 그린다.
 */
object Crumbs {

    const val TENANTS_URL = "/admin/tenants"

    /** App.tsx 의 tabName. 모르는 탭은 키 그대로. */
    private val TAB_NAMES: Map<String, String> = java.util.Map.of(
        "overview", "개요",
        "webauthn", "WebAuthn",
        "aaguid", "AAGUID 정책",
        "apikeys", "API 키",
        "credentials", "Credentials",
        "audit", "감사 로그",
        "funnel", "인증 현황",
    )

    /** Tenants 목록: 현재 위치만. */
    fun tenants(): List<Crumb> = java.util.List.of(Crumb.of("Tenants"))

    /**
     * Tenant 상세: PLATFORM 은 Tenants › {이름} › {탭}, RP_ADMIN 은 {이름} › {탭}.
     * 이름 조회에 실패했으면(`tenantName == null`) PLATFORM 은 Tenants 만, RP_ADMIN 은 비어 있다(useCrumbTenant).
     */
    fun tenant(platform: Boolean, tenantId: String?, tenantName: String?, tab: String): List<Crumb> {
        val items = ArrayList<Crumb>()
        if (platform) items.add(Crumb.link("Tenants", TENANTS_URL))
        if (tenantName != null) {
            items.add(Crumb.link(tenantName, "$TENANTS_URL/$tenantId?tab=overview"))
            items.add(Crumb.of(TAB_NAMES.getOrDefault(tab, tab)))
        }
        return items
    }

    fun insights(): List<Crumb> = java.util.List.of(Crumb.of("현황"))

    fun activity(): List<Crumb> = java.util.List.of(Crumb.of("활동"))

    fun auditChain(): List<Crumb> = java.util.List.of(Crumb.of("감사 체인 모니터"))

    fun settings(): List<Crumb> = java.util.List.of(Crumb.of("설정"))

    fun license(): List<Crumb> = java.util.List.of(Crumb.of("라이선스"))
}
