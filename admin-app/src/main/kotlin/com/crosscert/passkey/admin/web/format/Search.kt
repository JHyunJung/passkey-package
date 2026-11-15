package com.crosscert.passkey.admin.web.format

import java.util.function.Function

/**
 * 전역 검색의 매칭·정렬 규칙: name·slug·id 에 대한 대소문자 무시 부분 일치로 최대 8건을 돌려준다.
 * 목록 조회는 화면 컨트롤러(CommandPaletteController)가 맡고, 여기서는 필터와 링크 규칙만 둔다.
 */
object Search {

    const val MAX_TENANT_RESULTS = 8

    data class Result(val type: String?, val id: String?, val label: String?, val sub: String?, val href: String?)

    data class TenantRef(val name: String?, val slug: String?, val id: String?)

    /** 빈·공백 질의는 빈 목록. 결과는 입력 순서를 유지한다. */
    fun tenants(query: String?, tenants: List<TenantRef>): List<Result> {
        if (query == null || query.javaIsBlank()) return java.util.List.of()
        val q = query.lowercase()
        return tenants.stream()
            .filter { t -> contains(t.name, q) || contains(t.slug, q) || contains(t.id, q) }
            .limit(MAX_TENANT_RESULTS.toLong())
            .map { t -> Result("tenant", t.id, t.name, t.slug, "/admin/tenants/" + t.id + "?tab=overview") }
            .toList()
    }

    fun <T> tenants(query: String?, tenants: List<T>, ref: Function<T, TenantRef>): List<Result> =
        tenants(query, tenants.stream().map(ref).toList())

    private fun contains(s: String?, q: String): Boolean = s != null && s.lowercase().contains(q)
}
