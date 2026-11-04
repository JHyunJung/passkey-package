package com.crosscert.passkey.admin.web.format;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * 전역 검색의 매칭·정렬 규칙(← admin-ui lib/search.ts). 원본은 테넌트 목록을 30초 캐시한 뒤
 * name·slug·id 에 대한 대소문자 무시 부분 일치로 최대 8건을 돌려준다. 서버 렌더에서는 캐시가 필요 없고
 * 목록 조회는 화면 컨트롤러가 맡으므로, 여기서는 필터와 링크 규칙만 옮긴다.
 */
public final class Search {

    public static final int MAX_TENANT_RESULTS = 8;

    private Search() {}

    public record Result(String type, String id, String label, String sub, String href) {}

    public record TenantRef(String name, String slug, String id) {}

    /** 빈·공백 질의는 빈 목록. 결과는 입력 순서를 유지한다. */
    public static List<Result> tenants(String query, List<TenantRef> tenants) {
        if (query == null || query.isBlank()) return List.of();
        String q = query.toLowerCase(Locale.ROOT);
        return tenants.stream()
                .filter(t -> contains(t.name(), q) || contains(t.slug(), q) || contains(t.id(), q))
                .limit(MAX_TENANT_RESULTS)
                .map(t -> new Result("tenant", t.id(), t.name(), t.slug(), "/admin/tenants/" + t.id() + "?tab=overview"))
                .toList();
    }

    public static <T> List<Result> tenants(String query, List<T> tenants, Function<T, TenantRef> ref) {
        return tenants(query, tenants.stream().map(ref).toList());
    }

    private static boolean contains(String s, String q) {
        return s != null && s.toLowerCase(Locale.ROOT).contains(q);
    }
}
