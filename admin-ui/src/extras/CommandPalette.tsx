import { useState, useEffect, useRef, useMemo, type ComponentType } from 'react';
import { Icons } from '@/icons/Icons';
import { searchAll, type SearchResult } from '@/lib/search';
import type { AppRoute } from '@/appRoute';
import { activeTenantApi } from '@/api/activeTenant';
import { EmptyState, useFocusTrap } from '@/ui';
import { illustrations } from '@/assets/illustrations';
import { GO_SHORTCUTS } from './useGoShortcuts';

const GO_BY_LABEL: Record<string, keyof typeof GO_SHORTCUTS> = {
  'Tenants 목록': 'tenants', 'Activity': 'activity', '감사 체인 모니터': 'audit-chain', '설정': 'settings',
};

const TENANT_TABS = [
  { id: 'overview', label: '개요', icon: 'Activity' },
  { id: 'webauthn', label: 'WebAuthn', icon: 'Globe' },
  { id: 'aaguid', label: 'AAGUID 정책', icon: 'Shield' },
  { id: 'apikeys', label: 'API 키', icon: 'Key' },
  { id: 'credentials', label: 'Credentials', icon: 'Fingerprint' },
  { id: 'audit', label: '감사 로그', icon: 'Receipt' },
  { id: 'funnel', label: '퍼널', icon: 'Activity' },
];

type Command = { group: string; icon: string; label: string; hint?: string; path?: string; action: () => void; disabled?: boolean };

/** dir 방향으로 다음 실행 가능한 항목. 없으면 제자리 — 비활성 항목에 선택이 멈추지 않는다. */
function stepEnabled(list: Command[], from: number, dir: 1 | -1): number {
  for (let i = from + dir; i >= 0 && i < list.length; i += dir) if (!list[i].disabled) return i;
  return from;
}
function firstEnabled(list: Command[]): number {
  const i = list.findIndex((c) => !c.disabled);
  return i < 0 ? 0 : i;
}

export type CommandPaletteProps = {
  open: boolean;
  onClose: () => void;
  me: { role: string; tenantIds?: string[]; email: string; displayName?: string };
  onNavigate: (route: AppRoute) => void;
  onAction: (kind: string) => void;
};

export function CommandPalette({ open, onClose, me, onNavigate, onAction }: CommandPaletteProps) {
  const [q, setQ] = useState('');
  const [searchResults, setSearchResults] = useState<SearchResult[]>([]);
  const [activeTenant, setActiveTenant] = useState<{ id: string; name?: string } | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const boxRef = useRef<HTMLDivElement>(null);
  // Dialog 와 같은 포커스 규칙: 열리면 검색칸, Tab 은 안에서 순환, 닫히면 원래 위치로.
  const trap = useFocusTrap(open, boxRef, inputRef);
  const isPlatform = me?.role === 'PLATFORM_OPERATOR';

  useEffect(() => {
    if (open) { setQ(''); setSearchResults([]); }
  }, [open]);

  // RP 관리자: 첫 tenant 가 아니라 지금 활성인 tenant 가 대상(상단 바 RP 전환과 같은 출처). 열 때마다 다시 읽어 전환을 반영한다.
  useEffect(() => {
    if (!open || isPlatform) return;
    let cancelled = false;
    activeTenantApi.get()
      .then((v) => {
        if (cancelled || !v.activeTenantId) return;
        const name = v.allowedTenants.find((t) => t.id === v.activeTenantId)?.name;
        setActiveTenant({ id: v.activeTenantId, name });
      })
      .catch(() => { /* 실패 시 me.tenantIds[0] 기준 + 라벨 "현재 Tenant" */ });
    return () => { cancelled = true; };
  }, [open, isPlatform]);

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

  const allCommands = useMemo(() => {
    const items: Command[] = [];

    if (isPlatform) {
      items.push({ group: '이동', icon: 'Building', label: 'Tenants 목록', hint: '전체 RP 회사', path: '플랫폼', action: () => onNavigate({ name: 'tenants' }) });
      items.push({ group: '이동', icon: 'Activity', label: 'Activity', hint: 'cross-tenant 활동 피드', path: '플랫폼', action: () => onNavigate({ name: 'activity' }) });
      items.push({ group: '이동', icon: 'Hash', label: '감사 체인 모니터', hint: '전 tenant 무결성 보드', path: '플랫폼', action: () => onNavigate({ name: 'audit-chain' }) });
      items.push({ group: '이동', icon: 'Cog', label: '설정', hint: 'Admin · MDS · 시스템', path: '관리', action: () => onNavigate({ name: 'settings' }) });
    }

    const fallbackId = !isPlatform && me?.tenantIds && me.tenantIds.length > 0 ? me.tenantIds[0] : null;
    const tenantId = activeTenant?.id ?? fallbackId;
    if (!isPlatform && tenantId) {
      const group = activeTenant?.name ? `현재 Tenant · ${activeTenant.name}` : '현재 Tenant';
      TENANT_TABS.forEach((tb) => items.push({
        group, icon: tb.icon, label: tb.label,
        action: () => onNavigate({ name: 'tenant', tenantId, tab: tb.id }),
      }));
    }

    items.push({ group: '도움말', icon: 'Help', label: '키보드 단축키', hint: '?', action: () => onAction('shortcuts') });
    items.push({ group: '액션', icon: 'Plus', label: '신규 tenant 생성', hint: isPlatform ? '다이얼로그 열기' : '권한 없음', action: () => onAction('new-tenant'), disabled: !isPlatform });
    items.push({ group: '액션', icon: 'LogOut', label: '로그아웃', action: () => onAction('logout') });

    return items;
  }, [isPlatform, me, activeTenant, onNavigate, onAction]);

  const filtered = useMemo(() => {
    const baseList = (() => {
      if (!q) return allCommands;
      const qq = q.toLowerCase();
      return allCommands.filter((c) => c.label.toLowerCase().includes(qq) || (c.hint || '').toLowerCase().includes(qq) || c.group.toLowerCase().includes(qq));
    })();

    // Merge platform tenant search results (only when query is active)
    if (isPlatform && q.trim() && searchResults.length > 0) {
      const tenantItems: Command[] = searchResults
        .filter((r) => r.type === 'tenant')
        .map((r) => ({
          group: 'Tenant',
          icon: 'Building',
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
  // 검색어가 바뀌거나 다시 열리면 첫 실행 가능한 항목을 선택한다.
  useEffect(() => { setSelIdx(firstEnabled(filtered)); }, [q, open]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    if (!open) return;
    function k(e: KeyboardEvent) {
      if (e.key === 'Escape') { onClose(); return; }
      if (e.key === 'ArrowDown') { e.preventDefault(); setSelIdx((i) => stepEnabled(filtered, i, 1)); }
      if (e.key === 'ArrowUp') { e.preventDefault(); setSelIdx((i) => stepEnabled(filtered, i, -1)); }
      if (e.key === 'Enter') {
        e.preventDefault();
        const it = filtered[selIdx];
        if (it && !it.disabled) { it.action(); onClose(); }
      }
    }
    window.addEventListener('keydown', k);
    return () => window.removeEventListener('keydown', k);
  }, [open, filtered, selIdx, onClose]);

  if (!open) return null;

  type IndexedCommand = Command & { _i: number };
  const groups: Record<string, IndexedCommand[]> = {};
  filtered.forEach((c, i) => {
    groups[c.group] = groups[c.group] || [];
    groups[c.group].push({ ...c, _i: i });
  });
  const term = q.trim();

  return (
    <div className="scrim" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      {/* 모달 dialog 의 Tab 순환용 핸들러(Dialog 와 동일) */}
      {/* eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions */}
      <div ref={boxRef} onKeyDown={trap} className="dialog" role="dialog" aria-modal="true" aria-label="명령 팔레트" style={{ maxWidth: 640, marginTop: '10vh', maxHeight: '70vh', display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
        <label className="row" style={{ flexWrap: 'nowrap', gap: 10, padding: '14px 16px', borderBottom: '1px solid var(--line-soft)' }}>
          <Icons.Search size={18} />
          <span className="sr-only">검색</span>
          <input
            ref={inputRef}
            aria-label="검색"
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder="tenant 이름 · slug, 화면, 동작 검색…"
            style={{ flex: 1, border: 0, font: 'inherit', fontSize: 15, background: 'transparent' }}
          />
          <kbd>esc</kbd>
        </label>
        <div style={{ flex: 1, overflow: 'auto', padding: '0 0 6px' }}>
          {Object.keys(groups).length === 0 && (
            <EmptyState
              compact
              illustration={illustrations.noResults}
              title={term ? `“${term}”와 일치하는 tenant · 화면이 없습니다.` : '실행할 수 있는 항목이 없습니다.'}
              description="credential · 감사 이벤트 ID는 해당 화면의 검색에서 찾으세요."
            />
          )}
          {Object.entries(groups).map(([g, items]) => (
            <div key={g}>
              <span className="t-over" style={{ display: 'block', padding: '10px 16px 4px' }}>{g}</span>
              {items.map((it) => {
                const active = it._i === selIdx;
                const Ic = (Icons as Record<string, ComponentType<{ size?: number }>>)[it.icon] || Icons.ChevronRight;
                const goKey = it.group === '이동' ? GO_BY_LABEL[it.label] : undefined;
                return (
                  <button
                    key={it._i}
                    type="button"
                    className={`menu__item${it.label === '로그아웃' ? ' menu__item--danger' : ''}`}
                    aria-current={active || undefined}
                    disabled={it.disabled}
                    onClick={() => { it.action(); onClose(); }}
                    onMouseEnter={() => { if (!it.disabled) setSelIdx(it._i); }}
                    style={{ minHeight: 40, padding: '0 16px', ...(active ? { background: 'var(--brand-100)', boxShadow: 'inset 3px 0 0 var(--brand-700)' } : {}) }}
                  >
                    <Ic size={16} />
                    <span>{it.label}</span>
                    {(it.path ?? it.hint) && <span className="hint" style={{ margin: '0 0 0 auto' }}>{it.path ?? it.hint}</span>}
                    {goKey && <kbd>{GO_SHORTCUTS[goKey]}</kbd>}
                  </button>
                );
              })}
            </div>
          ))}
        </div>
        <div className="row" style={{ gap: 14, padding: '10px 16px', borderTop: '1px solid var(--line-soft)', background: 'var(--canvas)', fontSize: 12, color: 'var(--mute)' }}>
          <span><kbd>↑</kbd><kbd>↓</kbd> 이동</span>
          <span><kbd>↵</kbd> 실행</span>
          <span><kbd>esc</kbd> 닫기</span>
          <span style={{ marginLeft: 'auto' }}>{filtered.length}개 결과</span>
        </div>
      </div>
    </div>
  );
}
