package com.crosscert.passkey.admin.auth

import com.crosscert.passkey.core.repository.TenantRepository
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.Comparator
import java.util.UUID

/**
 * 로그인 운영자 본인의 "현재 활성 RP" 조회/변경(스위처).
 * 본인 세션 한정 — 별도 role 게이트 없이 allowedTenantIds 범위로만 제한된다.
 */
@RestController
@RequestMapping("/admin/api/active-tenant")
class ActiveTenantController(
    private val resolver: ActiveTenantResolver,
    private val tenants: TenantRepository,
) {

    data class SwitchRequest(val tenantId: UUID?)

    /** 스위처가 보여줄 RP 한 항목 — id + 표시 이름(없으면 slug). */
    data class TenantRef(val id: UUID?, val name: String?)

    /** allowedTenants 는 스위처 라벨용(id+name). allowedTenantIds 는 하위호환 유지. */
    data class ActiveTenantView(
        val activeTenantId: UUID?,
        val allowedTenantIds: List<UUID>?,
        val allowedTenants: List<TenantRef>?,
    )

    @GetMapping
    fun current(): ActiveTenantView = view(principal())

    @PostMapping
    fun switchTenant(@RequestBody req: SwitchRequest): ActiveTenantView {
        val me = principal()
        resolver.setActive(me, req.tenantId) // 허용범위 밖이면 ACCESS_DENIED
        return view(me)
    }

    /**
     * 활성 RP + 허용 RP 목록(id/name)을 구성한다. name 은 allowedTenantIds 범위의
     * tenant 만 조회하므로 cross-tenant 노출이 없다. RP_ADMIN 의 일반 tenant 목록 조회는
     * @Filter 로 활성 RP 하나만 보이지만, 스위처 라벨은 본인 허용 RP 전체 이름이 필요하므로
     * 여기서 직접 채운다. UUID 자연 순서로 정렬해 활성 RP(TreeSet.first)·라우팅과 일관되게 한다.
     */
    private fun view(me: AdminUserDetails): ActiveTenantView {
        val allowedIds: List<UUID> = me.allowedTenantIds.stream().sorted().toList()
        val refs: List<TenantRef> = tenants.findAllById(allowedIds).stream()
            .map { t -> TenantRef(t.id, if (t.displayName != null) t.displayName else t.slug) }
            // 원 Java Comparator.comparing(TenantRef::id): null 키는 비교 시점에 NPE — `!!` 가 같은 지점에서 같은 예외.
            .sorted(Comparator.comparing { r: TenantRef -> r.id!! })
            .toList()
        return ActiveTenantView(resolver.resolve(me), allowedIds, refs)
    }

    private fun principal(): AdminUserDetails =
        // 원 Java 의 (AdminUserDetails) 캐스트 — 인증 없음은 NPE, 다른 principal 은 ClassCastException.
        SecurityContextHolder.getContext().authentication!!.principal as AdminUserDetails
}
