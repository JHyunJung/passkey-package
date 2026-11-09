package com.crosscert.passkey.admin.web.tenant

import com.crosscert.passkey.admin.tenant.TenantAdminController
import com.crosscert.passkey.admin.tenant.TenantAdminDto

/**
 * Tenant 상세의 탭 조각 컨트롤러(Task 10–13)가 공통으로 쓰는 도우미.
 *
 * 탭 조각은 `GET /admin/_/tenants/{id}/tab/<리터럴 탭 키>` 에 매핑하고(일반 `{tab}` 매핑보다 우선),
 * 데이터를 읽기 전에 반드시 [load] 로 접근 경계를 확인한다 — 일반 매핑의 보호는 리터럴 경로에 적용되지 않는다.
 */
object TenantTabs {

    /** 탭 내용 영역의 DOM id. 탭 링크·조각 안의 다시 시도 등 이 영역을 바꾸는 모든 요청의 hx-target. */
    const val TARGET_ID = "tenant-tab"

    /** 이 영역을 바꾸는 모든 요청이 쓰는 hx-sync 값(같은 그룹이라 늦은 응답이 새 탭을 덮지 않는다). */
    const val SYNC = "#$TARGET_ID:replace"

    /**
     * 테넌트를 읽는다. `TenantAdminController.get` → 서비스의 TenantBoundary 가 RP_ADMIN 의 다른 테넌트를 거부한다
     * (BusinessException ACCESS_DENIED → WebExceptionAdvice 403, 404 는 TENANT_NOT_FOUND). 탭 컨트롤러는 이 호출 뒤에만 데이터를 읽는다.
     * 반환 id 는 canonical UUID 라 이후 REST 호출·링크에 쓴다.
     */
    fun load(tenants: TenantAdminController, idOrSlug: String): TenantAdminDto.TenantView = tenants.get(idOrSlug).data()

    /**
     * 탭 영역을 교체하는 요청(다시 시도 등)의 공통 속성: hx-get·hx-target·hx-swap·hx-sync·hx-push-url=false.
     * 요소는 반드시 이 속성을 쓰거나 최소한 [SYNC] 를 가져야 한다.
     */
    fun requesterAttrs(getUrl: String): MutableMap<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["hx-get"] = getUrl
        m["hx-target"] = "#$TARGET_ID"
        m["hx-swap"] = "innerHTML"
        m["hx-sync"] = SYNC
        m["hx-push-url"] = "false"
        return m
    }
}
