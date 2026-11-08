package com.crosscert.passkey.admin.web.shell;

import com.crosscert.passkey.admin.auth.ActiveTenantController;
import com.crosscert.passkey.admin.tenant.TenantAdminController;
import com.crosscert.passkey.admin.tenant.TenantAdminDto;
import com.crosscert.passkey.admin.web.format.Search;
import com.crosscert.passkey.admin.web.support.CurrentAdmin;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 명령 팔레트 결과 조각(← extras/CommandPalette.tsx). {@code GET /admin/_/palette?q=} 는 검색어에 맞는 항목을 그룹별로 그려 돌려준다.
 *
 * <p>범위는 서버가 정한다: 역할은 인증된 {@code me}(ViewModelAdvice)에서, RP 관리자의 현재 tenant 는 {@code ActiveTenantController} 에서 읽고
 * 클라이언트 입력(q)은 검색어로만 쓴다. tenant 검색은 PLATFORM 운영자에게만 열려 있다(TSX 와 같다). RP 관리자는 자신의 현재 tenant 탭만 본다.
 * 기존 REST 컨트롤러 빈을 Java 메서드로 호출한다(HTTP 아님).
 */
@Controller
public class CommandPaletteController {

    static final String VIEW = "admin/fragments/shell/command-palette :: results";

    /** TENANT_TABS (CommandPalette.tsx). icon 은 icons 조각 이름. */
    static final List<String[]> TENANT_TABS = List.of(
            new String[] {"overview", "개요", "activity"},
            new String[] {"webauthn", "WebAuthn", "globe"},
            new String[] {"aaguid", "AAGUID 정책", "shield"},
            new String[] {"apikeys", "API 키", "key"},
            new String[] {"credentials", "Credentials", "fingerprint"},
            new String[] {"audit", "감사 로그", "receipt"},
            new String[] {"funnel", "인증 현황", "activity"});

    /** GO_BY_LABEL + GO_SHORTCUTS (CommandPalette.tsx, useGoShortcuts.ts). */
    static final Map<String, String> GO_BY_LABEL = Map.of(
            "Tenants 목록", "G T", "Activity", "G A", "감사 체인 모니터", "G C", "설정", "G S");

    /**
     * 한 항목. href 가 있으면 이동, action 이 있으면 동작(logout · new-tenant · shortcuts · recheck).
     * keepOpen 은 실행 뒤 팔레트를 닫지 않는다(현재 tenant 다시 확인). index 는 필터 뒤 전체 목록에서의 위치(키보드 선택용).
     */
    public record Item(int index, String group, String icon, String label, String hint, String path,
                       String href, String action, boolean disabled, boolean keepOpen, String goKey) {

        /** 오른쪽에 보이는 문구: path ?? hint (비면 숨김). */
        public String shown() {
            String s = path != null ? path : hint;
            return s == null || s.isEmpty() ? null : s;
        }

        public boolean danger() {
            return "로그아웃".equals(label);
        }
    }

    public record Group(String label, List<Item> items) {}

    private final TenantAdminController tenants;
    private final ActiveTenantController activeTenants;

    public CommandPaletteController(TenantAdminController tenants, ActiveTenantController activeTenants) {
        this.tenants = tenants;
        this.activeTenants = activeTenants;
    }

    @GetMapping("/admin/_/palette")
    public String palette(@RequestParam(name = "q", defaultValue = "") String q, Model model) {
        CurrentAdmin me = (CurrentAdmin) model.getAttribute("me");
        List<Item> items = filtered(q, me);
        Map<String, List<Item>> groups = new LinkedHashMap<>();
        items.forEach(it -> groups.computeIfAbsent(it.group(), k -> new ArrayList<>()).add(it));
        String term = q.trim();
        model.addAttribute("q", q); // 결과가 어떤 검색어에 대한 것인지(data-q) — 클라이언트가 오래된 결과 실행을 막는 데 쓴다
        model.addAttribute("groups", groups.entrySet().stream().map(e -> new Group(e.getKey(), e.getValue())).toList());
        model.addAttribute("total", items.size());
        model.addAttribute("emptyTitle", term.isEmpty() ? "실행할 수 있는 항목이 없습니다." : "“" + term + "”와 일치하는 tenant · 화면이 없습니다.");
        return VIEW;
    }

    // ── 항목 구성 ───────────────────────────────────────────

    List<Item> filtered(String q, CurrentAdmin me) {
        boolean platform = me != null && me.platform();
        List<Item> all = commands(me, platform);
        List<Item> base;
        if (q.isEmpty()) {
            base = all;
        } else {
            String qq = q.toLowerCase(Locale.ROOT);
            base = all.stream().filter(c -> lower(c.label()).contains(qq) || lower(c.hint()).contains(qq) || lower(c.group()).contains(qq)).toList();
        }
        List<Item> out = new ArrayList<>(base);
        if (platform && !q.isBlank()) {
            for (Search.Result r : searchTenants(q)) {
                String last8 = r.id().length() > 8 ? r.id().substring(r.id().length() - 8) : r.id();
                String hint = r.sub() != null && !r.sub().isEmpty() ? r.sub() + " · " + last8 : last8;
                out.add(new Item(0, "Tenant", "building", r.label(), hint, null, r.href(), null, false, false, null));
            }
        }
        List<Item> indexed = new ArrayList<>(out.size());
        for (int i = 0; i < out.size(); i++) indexed.add(withIndex(out.get(i), i));
        return indexed;
    }

    private List<Search.Result> searchTenants(String q) {
        try {
            List<TenantAdminDto.TenantView> list = tenants.list().data();
            return Search.tenants(q, list, t -> new Search.TenantRef(
                    t.displayName() != null ? t.displayName() : t.slug(), t.slug(), String.valueOf(t.id())));
        } catch (RuntimeException e) {
            return List.of(); // TSX 의 catch { /* ignore */ }
        }
    }

    private List<Item> commands(CurrentAdmin me, boolean platform) {
        List<Item> items = new ArrayList<>();
        if (platform) {
            items.add(nav("이동", "building", "Tenants 목록", "전체 RP 회사", "플랫폼", "/admin/tenants"));
            items.add(nav("이동", "sparkles", "현황", "전 테넌트 사용량 · 인증 품질 · 확인 필요", "플랫폼", "/admin/insights"));
            items.add(nav("이동", "activity", "Activity", "cross-tenant 활동 피드", "플랫폼", "/admin/activity"));
            items.add(nav("이동", "hash", "감사 체인 모니터", "전 tenant 무결성 보드", "플랫폼", "/admin/audit-chain"));
            items.add(nav("이동", "cog", "설정", "Admin · MDS · 시스템", "관리", "/admin/settings"));
        }
        boolean hasIds = me != null && me.tenantIds() != null && !me.tenantIds().isEmpty();
        if (!platform) {
            ActiveTenant at = activeTenant();
            // 활성 tenant 가 확인되면 hasTenants 와 무관하게 보인다(TSX: tenantIds.length > 0 || status === 'ok').
            if (hasIds || at.ok()) {
                if (at.ok()) {
                    String group = at.name() != null && !at.name().isEmpty() ? "현재 Tenant · " + at.name() : "현재 Tenant";
                    for (String[] tb : TENANT_TABS) {
                        items.add(new Item(0, group, tb[2], tb[1], null, null, "/admin/tenants/" + at.id() + "?tab=" + tb[0], null, false, false, null));
                    }
                } else {
                    String reason = "현재 tenant를 확인하지 못했습니다";
                    for (String[] tb : TENANT_TABS) {
                        items.add(new Item(0, "현재 Tenant", tb[2], tb[1], reason, null, null, null, true, false, null));
                    }
                    items.add(new Item(0, "현재 Tenant", "refresh", "현재 tenant 다시 확인", null, null, null, "recheck", false, true, null));
                }
            }
        }
        items.add(new Item(0, "도움말", "help", "키보드 단축키", "?", null, null, "shortcuts", false, false, null));
        items.add(new Item(0, "액션", "plus", "신규 tenant 생성", platform ? "다이얼로그 열기" : "권한 없음", null,
                platform ? "/admin/tenants?new=1" : null, null, !platform, false, null));
        items.add(new Item(0, "액션", "logOut", "로그아웃", null, null, null, "logout", false, false, null));
        return items;
    }

    private static Item nav(String group, String icon, String label, String hint, String path, String href) {
        return new Item(0, group, icon, label, hint, path, href, null, false, false, GO_BY_LABEL.get(label));
    }

    private static Item withIndex(Item i, int idx) {
        return new Item(idx, i.group(), i.icon(), i.label(), i.hint(), i.path(), i.href(), i.action(), i.disabled(), i.keepOpen(), i.goKey());
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    /** RP 관리자의 현재(활성) tenant. 확인되지 않으면(조회 실패·활성 없음) 첫 tenant 를 짐작하지 않는다. */
    private record ActiveTenant(boolean ok, String id, String name) {}

    private ActiveTenant activeTenant() {
        try {
            ActiveTenantController.ActiveTenantView v = activeTenants.current();
            if (v == null || v.getActiveTenantId() == null) return new ActiveTenant(false, null, null);
            String name = null;
            if (v.getAllowedTenants() != null) {
                name = v.getAllowedTenants().stream().filter(t -> v.getActiveTenantId().equals(t.getId()))
                        .map(ActiveTenantController.TenantRef::getName).findFirst().orElse(null);
            }
            return new ActiveTenant(true, v.getActiveTenantId().toString(), name);
        } catch (RuntimeException e) {
            return new ActiveTenant(false, null, null);
        }
    }
}
