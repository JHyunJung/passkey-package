import { useState, useEffect, useRef, useMemo } from 'react';
import { Icons } from '@/icons/Icons';
import { searchAll, type SearchResult } from '@/lib/search';
import type { AppRoute } from '@/appRoute';
import { EmptyState } from '@/ui';
import { illustrations } from '@/assets/illustrations';
import { GO_SHORTCUTS } from './useGoShortcuts';

const GO_BY_LABEL: Record<string, keyof typeof GO_SHORTCUTS> = {
  'Tenants 목록': 'tenants', 'Activity': 'activity', 'Audit Chain Monitor': 'audit-chain', '설정': 'settings',
};

export type CommandPaletteProps = {
  open: boolean;
  onClose: () => void;
  me: { role: string; tenantIds?: string[]; email: string; displayName?: string };
  onNavigate: (route: AppRoute) => void;
  onAction: (kind: string) => void;
};

export function CommandPalette({ open, onClose, me, onNavigate, onAction }: CommandPaletteProps) {
  const [q, setQ] = useState("");
  const [searchResults, setSearchResults] = useState<SearchResult[]>([]);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (open) { setQ(""); setSearchResults([]); setTimeout(() => inputRef.current?.focus(), 50); }
  }, [open]);

  // navigate adapter: path like "/tenants/:id?tab=overview" → onNavigate route object
  function navigateFromPath(path: string) {
    const tenantMatch = path.match(/^\/tenants\/([^?]+)/);
    if (tenantMatch) {
      const tabMatch = path.match(/[?&]tab=([^&]+)/);
      // AppRoute.tab is required; mirror urlToRoute's 'overview' default when absent.
      onNavigate({ name: 'tenant', tenantId: tenantMatch[1], tab: tabMatch?.[1] ?? 'overview' });
    }
  }

  useEffect(() => {
    let cancelled = false;
    if (!q.trim()) { setSearchResults([]); return; }
    searchAll(q, navigateFromPath).then((r) => { if (!cancelled) setSearchResults(r); });
    return () => { cancelled = true; };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [q]);

  const isPlatform = me?.role === "PLATFORM_OPERATOR";
  const allCommands = useMemo(() => {
    const items: {
      group: string;
      icon: string;
      label: string;
      hint?: string;
      path?: string;
      action: () => void;
      disabled?: boolean;
    }[] = [];

    // Pages — context-dependent
    if (isPlatform) {
      items.push({ group: "이동", icon: "Building", label: "Tenants 목록", hint: "전체 RP 회사", path: "플랫폼", action: () => onNavigate({ name: "tenants" }) });
      items.push({ group: "이동", icon: "Activity", label: "Activity", hint: "cross-tenant 활동 피드", path: "플랫폼", action: () => onNavigate({ name: "activity" }) });
      items.push({ group: "이동", icon: "Hash", label: "Audit Chain Monitor", hint: "전 tenant 무결성 보드", path: "플랫폼", action: () => onNavigate({ name: "audit-chain" }) });
      items.push({ group: "이동", icon: "Cog", label: "설정", hint: "Admin · MDS · 시스템", path: "관리", action: () => onNavigate({ name: "settings" }) });
    }

    // Tenants — only platform sees all (populated from searchResults below)
    if (!isPlatform && me?.tenantIds && me.tenantIds.length > 0) {
      // RP_ADMIN sees only their tenant tabs (first/active tenant)
      const activeTenantId = me.tenantIds[0];
      const tabs = [
        { id: "overview", label: "개요", icon: "Activity" },
        { id: "webauthn", label: "WebAuthn", icon: "Globe" },
        { id: "aaguid", label: "AAGUID 정책", icon: "Shield" },
        { id: "apikeys", label: "API Keys", icon: "Key" },
        { id: "credentials", label: "Credentials", icon: "Fingerprint" },
        { id: "audit", label: "Audit Logs", icon: "Receipt" },
        { id: "funnel", label: "Funnel", icon: "Activity" },
      ];
      tabs.forEach((tb) => items.push({ group: "현재 Tenant", icon: tb.icon, label: tb.label, hint: `내 tenant > ${tb.label}`, action: () => onNavigate({ name: "tenant", tenantId: activeTenantId, tab: tb.id }) }));
    }

    // Quick actions
    items.push({ group: "액션", icon: "Plus", label: "신규 tenant 생성", hint: "PLATFORM_OPERATOR만", action: () => onAction("new-tenant"), disabled: !isPlatform });
    items.push({ group: "액션", icon: "LogOut", label: "로그아웃", action: () => onAction("logout") });

    return items;
  }, [isPlatform, me, onNavigate, onAction]);

  const filtered = useMemo(() => {
    // Base: filter static commands
    const baseList = (() => {
      if (!q) return allCommands;
      const qq = q.toLowerCase();
      return allCommands.filter((c) => c.label.toLowerCase().includes(qq) || (c.hint || "").toLowerCase().includes(qq) || c.group.toLowerCase().includes(qq));
    })();

    // Merge platform tenant search results (only when query is active)
    if (isPlatform && q.trim() && searchResults.length > 0) {
      const tenantItems = searchResults
        .filter((r) => r.type === 'tenant')
        .map((r) => ({
          group: "Tenant",
          icon: "Building",
          label: r.label,
          hint: r.sub ? `${r.sub} · ${r.id.slice(-8)}` : r.id.slice(-8),
          action: () => r.onSelect(),
          disabled: false,
        }));
      return [...baseList, ...tenantItems];
    }
    return baseList;
  }, [q, allCommands, isPlatform, searchResults]);

  const [selIdx, setSelIdx] = useState(0);
  useEffect(() => { setSelIdx(0); }, [q]);
  useEffect(() => {
    if (!open) return;
    function k(e: KeyboardEvent) {
      if (e.key === "Escape") { onClose(); return; }
      if (e.key === "ArrowDown") { e.preventDefault(); setSelIdx((i) => Math.min(filtered.length - 1, i + 1)); }
      if (e.key === "ArrowUp") { e.preventDefault(); setSelIdx((i) => Math.max(0, i - 1)); }
      if (e.key === "Enter") {
        e.preventDefault();
        const it = filtered[selIdx];
        if (it && !it.disabled) { it.action(); onClose(); }
      }
    }
    window.addEventListener("keydown", k);
    return () => window.removeEventListener("keydown", k);
  }, [open, filtered, selIdx, onClose]);

  if (!open) return null;

  type IndexedCommand = (typeof allCommands)[number] & { _i: number };
  // Group items
  const groups: Record<string, IndexedCommand[]> = {};
  filtered.forEach((c, i) => {
    groups[c.group] = groups[c.group] || [];
    groups[c.group].push({ ...c, _i: i });
  });

  return (
    <div className="scrim" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="dialog" role="dialog" aria-modal="true" aria-label="Command Palette" style={{ maxWidth: 640, marginTop: "10vh", maxHeight: "70vh", display: "flex", flexDirection: "column", overflow: "hidden" }}>
        <label className="row" style={{ flexWrap: "nowrap", gap: 10, padding: "14px 16px", borderBottom: "1px solid var(--line-soft)" }}>
          <Icons.Search size={18} />
          <span className="sr-only">검색</span>
          <input
            ref={inputRef}
            aria-label="검색"
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder="tenant, credential, audit ID 검색…"
            style={{ flex: 1, border: 0, font: "inherit", fontSize: 15, background: "transparent" }}
          />
          <kbd>esc</kbd>
        </label>
        <div style={{ flex: 1, overflow: "auto", padding: "0 0 6px" }}>
          {Object.keys(groups).length === 0 && (
            <EmptyState compact illustration={illustrations.noResults} title="일치하는 결과가 없습니다." />
          )}
          {Object.entries(groups).map(([g, items]) => (
            <div key={g}>
              <span className="t-over" style={{ display: "block", padding: "10px 16px 4px" }}>{g}</span>
              {items.map((it) => {
                const active = it._i === selIdx;
                const Ic = (Icons as Record<string, React.ComponentType<{ size?: number }>>)[it.icon] || Icons.ChevronRight;
                const goKey = it.group === "이동" ? GO_BY_LABEL[it.label] : undefined;
                return (
                  <button
                    key={it._i}
                    className={`menu__item${it.label === "로그아웃" ? " menu__item--danger" : ""}`}
                    aria-current={active || undefined}
                    disabled={it.disabled}
                    onClick={() => { it.action(); onClose(); }}
                    onMouseEnter={() => setSelIdx(it._i)}
                    style={{ minHeight: 40, padding: "0 16px", ...(active ? { background: "var(--brand-100)", boxShadow: "inset 3px 0 0 var(--brand-700)" } : {}) }}
                  >
                    <Ic size={16} />
                    <span>{it.label}</span>
                    {(it.path ?? it.hint) && <span className="hint" style={{ margin: "0 0 0 auto" }}>{it.path ?? it.hint}</span>}
                    {goKey && <kbd>{GO_SHORTCUTS[goKey]}</kbd>}
                  </button>
                );
              })}
            </div>
          ))}
        </div>
        <div className="row" style={{ gap: 14, padding: "10px 16px", borderTop: "1px solid var(--line-soft)", background: "var(--canvas)", fontSize: 12, color: "var(--mute)" }}>
          <span><kbd>↑</kbd><kbd>↓</kbd> 이동</span>
          <span><kbd>↵</kbd> 실행</span>
          <span style={{ marginLeft: "auto" }}>{filtered.length} 결과</span>
        </div>
      </div>
    </div>
  );
}
