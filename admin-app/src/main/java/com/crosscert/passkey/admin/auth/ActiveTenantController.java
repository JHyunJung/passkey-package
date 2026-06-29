package com.crosscert.passkey.admin.auth;

import com.crosscert.passkey.core.entity.Tenant;
import com.crosscert.passkey.core.repository.TenantRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 로그인 운영자 본인의 "현재 활성 RP" 조회/변경(스위처).
 * 본인 세션 한정 — 별도 role 게이트 없이 allowedTenantIds 범위로만 제한된다.
 */
@RestController
@RequestMapping("/admin/api/active-tenant")
public class ActiveTenantController {

    private final ActiveTenantResolver resolver;
    private final TenantRepository tenants;

    public ActiveTenantController(ActiveTenantResolver resolver, TenantRepository tenants) {
        this.resolver = resolver;
        this.tenants = tenants;
    }

    public record SwitchRequest(UUID tenantId) {}

    /** 스위처가 보여줄 RP 한 항목 — id + 표시 이름(없으면 slug). */
    public record TenantRef(UUID id, String name) {}

    /** allowedTenants 는 스위처 라벨용(id+name). allowedTenantIds 는 하위호환 유지. */
    public record ActiveTenantView(UUID activeTenantId,
                                   List<UUID> allowedTenantIds,
                                   List<TenantRef> allowedTenants) {}

    @GetMapping
    public ActiveTenantView current() {
        return view(principal());
    }

    @PostMapping
    public ActiveTenantView switchTenant(@RequestBody SwitchRequest req) {
        AdminUserDetails me = principal();
        resolver.setActive(me, req.tenantId());   // 허용범위 밖이면 ACCESS_DENIED
        return view(me);
    }

    /**
     * 활성 RP + 허용 RP 목록(id/name)을 구성한다. name 은 allowedTenantIds 범위의
     * tenant 만 조회하므로 cross-tenant 노출이 없다. RP_ADMIN 의 일반 tenant 목록 조회는
     * @Filter 로 활성 RP 하나만 보이지만, 스위처 라벨은 본인 허용 RP 전체 이름이 필요하므로
     * 여기서 직접 채운다. UUID 자연 순서로 정렬해 활성 RP(TreeSet.first)·라우팅과 일관되게 한다.
     */
    private ActiveTenantView view(AdminUserDetails me) {
        List<UUID> allowedIds = me.getAllowedTenantIds().stream().sorted().toList();
        List<TenantRef> refs = tenants.findAllById(allowedIds).stream()
                .map(t -> new TenantRef(t.getId(),
                        t.getDisplayName() != null ? t.getDisplayName() : t.getSlug()))
                .sorted(Comparator.comparing(TenantRef::id))
                .toList();
        return new ActiveTenantView(resolver.resolve(me), allowedIds, refs);
    }

    private AdminUserDetails principal() {
        return (AdminUserDetails) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
    }
}
