package com.crosscert.passkey.admin.web.shell;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 화면별 브레드크럼(← App.tsx buildBreadcrumb). 화면 컨트롤러가 모델에 담아
 * {@code breadcrumb :: breadcrumb(${breadcrumbs})} 로 그린다.
 */
public final class Crumbs {

    public static final String TENANTS_URL = "/admin/tenants";

    /** App.tsx 의 tabName. 모르는 탭은 키 그대로. */
    private static final Map<String, String> TAB_NAMES = Map.of(
            "overview", "개요",
            "webauthn", "WebAuthn",
            "aaguid", "AAGUID 정책",
            "apikeys", "API 키",
            "credentials", "Credentials",
            "audit", "감사 로그",
            "funnel", "인증 현황");

    private Crumbs() {}

    /** Tenants 목록: 현재 위치만. */
    public static List<Crumb> tenants() {
        return List.of(Crumb.of("Tenants"));
    }

    /**
     * Tenant 상세: PLATFORM 은 Tenants › {이름} › {탭}, RP_ADMIN 은 {이름} › {탭}.
     * 이름 조회에 실패했으면({@code tenantName == null}) PLATFORM 은 Tenants 만, RP_ADMIN 은 비어 있다(useCrumbTenant).
     */
    public static List<Crumb> tenant(boolean platform, String tenantId, String tenantName, String tab) {
        List<Crumb> items = new ArrayList<>();
        if (platform) items.add(Crumb.link("Tenants", TENANTS_URL));
        if (tenantName != null) {
            items.add(Crumb.link(tenantName, TENANTS_URL + "/" + tenantId + "?tab=overview"));
            items.add(Crumb.of(TAB_NAMES.getOrDefault(tab, tab)));
        }
        return items;
    }

    public static List<Crumb> insights() {
        return List.of(Crumb.of("현황"));
    }

    public static List<Crumb> activity() {
        return List.of(Crumb.of("활동"));
    }

    public static List<Crumb> auditChain() {
        return List.of(Crumb.of("감사 체인 모니터"));
    }

    public static List<Crumb> settings() {
        return List.of(Crumb.of("설정"));
    }

    public static List<Crumb> license() {
        return List.of(Crumb.of("라이선스"));
    }
}
