package com.crosscert.passkey.admin.web.shell

import com.crosscert.passkey.admin.auth.ActiveTenantController
import com.crosscert.passkey.admin.tenant.TenantAdminController
import com.crosscert.passkey.admin.tenant.TenantAdminDto
import com.crosscert.passkey.admin.web.format.Search
import com.crosscert.passkey.admin.web.format.javaIsBlank
import com.crosscert.passkey.admin.web.format.javaTrim
import com.crosscert.passkey.admin.web.support.CurrentAdmin
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import java.util.Locale
import java.util.function.Function

private const val VIEW = "admin/fragments/shell/command-palette :: results"

/** TENANT_TABS (CommandPalette.tsx). icon 은 icons 조각 이름. */
private val TENANT_TABS: List<Array<String>> = java.util.List.of(
    arrayOf("overview", "개요", "activity"),
    arrayOf("webauthn", "WebAuthn", "globe"),
    arrayOf("aaguid", "AAGUID 정책", "shield"),
    arrayOf("apikeys", "API 키", "key"),
    arrayOf("credentials", "Credentials", "fingerprint"),
    arrayOf("audit", "감사 로그", "receipt"),
    arrayOf("funnel", "인증 현황", "activity"),
)

/** GO_BY_LABEL + GO_SHORTCUTS (CommandPalette.tsx, useGoShortcuts.ts). */
private val GO_BY_LABEL: Map<String, String> = java.util.Map.of(
    "Tenants 목록", "G T", "Activity", "G A", "감사 체인 모니터", "G C", "설정", "G S",
)

/**
 * 명령 팔레트 결과 조각(← extras/CommandPalette.tsx). `GET /admin/_/palette?q=` 는 검색어에 맞는 항목을 그룹별로 그려 돌려준다.
 *
 * 범위는 서버가 정한다: 역할은 인증된 `me`(ViewModelAdvice)에서, RP 관리자의 현재 tenant 는 `ActiveTenantController` 에서 읽고
 * 클라이언트 입력(q)은 검색어로만 쓴다. tenant 검색은 PLATFORM 운영자에게만 열려 있다(TSX 와 같다). RP 관리자는 자신의 현재 tenant 탭만 본다.
 * 기존 REST 컨트롤러 빈을 메서드로 호출한다(HTTP 아님).
 */
@Controller
class CommandPaletteController(
    private val tenants: TenantAdminController,
    private val activeTenants: ActiveTenantController,
) {

    /**
     * 한 항목. href 가 있으면 이동, action 이 있으면 동작(logout · new-tenant · shortcuts · recheck).
     * keepOpen 은 실행 뒤 팔레트를 닫지 않는다(현재 tenant 다시 확인). index 는 필터 뒤 전체 목록에서의 위치(키보드 선택용).
     */
    data class Item(
        val index: Int,
        val group: String?,
        val icon: String?,
        val label: String?,
        val hint: String?,
        val path: String?,
        val href: String?,
        val action: String?,
        val disabled: Boolean,
        val keepOpen: Boolean,
        val goKey: String?,
    ) {
        /** 오른쪽에 보이는 문구: path ?? hint (비면 숨김). */
        val shown: String?
            get() {
                val s = path ?: hint
                return if (s == null || s.isEmpty()) null else s
            }

        val danger: Boolean
            get() = "로그아웃" == label
    }

    data class Group(val label: String?, val items: List<Item>?)

    @GetMapping("/admin/_/palette")
    fun palette(@RequestParam(name = "q", defaultValue = "") q: String, model: Model): String {
        val me = model.getAttribute("me") as CurrentAdmin?
        val items = filtered(q, me)
        val groups = LinkedHashMap<String?, MutableList<Item>>()
        items.forEach { it -> groups.computeIfAbsent(it.group) { ArrayList() }.add(it) }
        val term = q.javaTrim()
        model.addAttribute("q", q) // 결과가 어떤 검색어에 대한 것인지(data-q) — 클라이언트가 오래된 결과 실행을 막는 데 쓴다
        model.addAttribute("groups", groups.entries.stream().map { e -> Group(e.key, e.value) }.toList())
        model.addAttribute("total", items.size)
        model.addAttribute("emptyTitle", if (term.isEmpty()) "실행할 수 있는 항목이 없습니다." else "“$term”와 일치하는 tenant · 화면이 없습니다.")
        return VIEW
    }

    // ── 항목 구성 ───────────────────────────────────────────

    private fun filtered(q: String, me: CurrentAdmin?): List<Item> {
        val platform = me != null && me.platform
        val all = commands(me, platform)
        val base: List<Item> = if (q.isEmpty()) {
            all
        } else {
            val qq = q.lowercase(Locale.ROOT)
            all.stream().filter { c -> lower(c.label).contains(qq) || lower(c.hint).contains(qq) || lower(c.group).contains(qq) }.toList()
        }
        val out = ArrayList<Item>(base)
        if (platform && !q.javaIsBlank()) {
            for (r in searchTenants(q)) {
                // 원 Java r.getId().length() — id 가 null 이면 같은 지점에서 NPE(searchTenants 는 String.valueOf 로 채워 null 이 없다).
                val id = r.id!!
                val last8 = if (id.length > 8) id.substring(id.length - 8) else id
                val sub = r.sub
                val hint = if (sub != null && sub.isNotEmpty()) "$sub · $last8" else last8
                out.add(Item(0, "Tenant", "building", r.label, hint, null, r.href, null, false, false, null))
            }
        }
        val indexed = ArrayList<Item>(out.size)
        for (i in out.indices) indexed.add(withIndex(out[i], i))
        return indexed
    }

    private fun searchTenants(q: String): List<Search.Result> {
        return try {
            val list: List<TenantAdminDto.TenantView> = tenants.list().data()
            Search.tenants(
                q,
                list,
                Function { t: TenantAdminDto.TenantView ->
                    Search.TenantRef(if (t.displayName != null) t.displayName else t.slug, t.slug, t.id.toString())
                },
            )
        } catch (e: RuntimeException) {
            java.util.List.of() // TSX 의 catch { /* ignore */ }
        }
    }

    private fun commands(me: CurrentAdmin?, platform: Boolean): List<Item> {
        val items = ArrayList<Item>()
        if (platform) {
            items.add(nav("이동", "building", "Tenants 목록", "전체 RP 회사", "플랫폼", "/admin/tenants"))
            items.add(nav("이동", "sparkles", "현황", "전 테넌트 사용량 · 인증 품질 · 확인 필요", "플랫폼", "/admin/insights"))
            items.add(nav("이동", "activity", "Activity", "cross-tenant 활동 피드", "플랫폼", "/admin/activity"))
            items.add(nav("이동", "hash", "감사 체인 모니터", "전 tenant 무결성 보드", "플랫폼", "/admin/audit-chain"))
            items.add(nav("이동", "cog", "설정", "Admin · MDS · 시스템", "관리", "/admin/settings"))
        }
        val tenantIds = me?.tenantIds
        val hasIds = tenantIds != null && tenantIds.isNotEmpty()
        if (!platform) {
            val at = activeTenant()
            // 활성 tenant 가 확인되면 hasTenants 와 무관하게 보인다(TSX: tenantIds.length > 0 || status === 'ok').
            if (hasIds || at.ok) {
                if (at.ok) {
                    val group = if (at.name != null && at.name.isNotEmpty()) "현재 Tenant · " + at.name else "현재 Tenant"
                    for (tb in TENANT_TABS) {
                        items.add(Item(0, group, tb[2], tb[1], null, null, "/admin/tenants/" + at.id + "?tab=" + tb[0], null, false, false, null))
                    }
                } else {
                    val reason = "현재 tenant를 확인하지 못했습니다"
                    for (tb in TENANT_TABS) {
                        items.add(Item(0, "현재 Tenant", tb[2], tb[1], reason, null, null, null, true, false, null))
                    }
                    items.add(Item(0, "현재 Tenant", "refresh", "현재 tenant 다시 확인", null, null, null, "recheck", false, true, null))
                }
            }
        }
        items.add(Item(0, "도움말", "help", "키보드 단축키", "?", null, null, "shortcuts", false, false, null))
        items.add(
            Item(
                0, "액션", "plus", "신규 tenant 생성", if (platform) "다이얼로그 열기" else "권한 없음", null,
                if (platform) "/admin/tenants?new=1" else null, null, !platform, false, null,
            ),
        )
        items.add(Item(0, "액션", "logOut", "로그아웃", null, null, null, "logout", false, false, null))
        return items
    }

    /** RP 관리자의 현재(활성) tenant. 확인되지 않으면(조회 실패·활성 없음) 첫 tenant 를 짐작하지 않는다. */
    private data class ActiveTenant(val ok: Boolean, val id: String?, val name: String?)

    private fun activeTenant(): ActiveTenant {
        try {
            val v: ActiveTenantController.ActiveTenantView? = activeTenants.current()
            val activeId = v?.activeTenantId
            if (v == null || activeId == null) return ActiveTenant(false, null, null)
            var name: String? = null
            val allowed = v.allowedTenants
            if (allowed != null) {
                // 원 Java 스트림 그대로 — 일치 항목의 이름이 null 이면 findFirst 가 NPE(아래 catch 로 "확인 못 함").
                name = allowed.stream().filter { t -> activeId == t.id }
                    .map { t -> t.name }.findFirst().orElse(null)
            }
            return ActiveTenant(true, activeId.toString(), name)
        } catch (e: RuntimeException) {
            return ActiveTenant(false, null, null)
        }
    }

    private companion object {
        fun nav(group: String, icon: String, label: String, hint: String, path: String, href: String): Item =
            Item(0, group, icon, label, hint, path, href, null, false, false, GO_BY_LABEL[label])

        fun withIndex(i: Item, idx: Int): Item =
            Item(idx, i.group, i.icon, i.label, i.hint, i.path, i.href, i.action, i.disabled, i.keepOpen, i.goKey)

        fun lower(s: String?): String = s?.lowercase(Locale.ROOT) ?: ""
    }
}
