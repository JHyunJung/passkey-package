import { ReactNode, useEffect, useRef, useState } from 'react';
import { Icons } from '@/icons/Icons';
import { Breadcrumb } from './Breadcrumb';
import { activeTenantApi, type ActiveTenantView } from '@/api/activeTenant';
import { Badge } from '@/ui';

function useOutsideClose(open: boolean, close: () => void) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const h = (e: MouseEvent) => { if (!ref.current?.contains(e.target as Node)) close(); };
    const k = (e: KeyboardEvent) => { if (e.key === 'Escape') close(); };
    document.addEventListener('mousedown', h); document.addEventListener('keydown', k);
    return () => { document.removeEventListener('mousedown', h); document.removeEventListener('keydown', k); };
  }, [open, close]);
  return ref;
}

function RpSwitcher({ onSwitch }: { onSwitch: () => void }) {
  const [active, setActive] = useState<ActiveTenantView | null>(null);
  const [open, setOpen] = useState(false);
  const [switching, setSwitching] = useState(false);
  const ref = useOutsideClose(open, () => setOpen(false));
  useEffect(() => { activeTenantApi.get().then(setActive).catch(() => {}); }, []);
  if (!active || active.allowedTenantIds.length < 2) return null;
  const names: Record<string, string> = {};
  active.allowedTenants.forEach((t) => { names[t.id] = t.name; });
  const label = active.activeTenantId ? (names[active.activeTenantId] ?? active.activeTenantId.slice(-8)) : '—';
  async function pick(id: string) {
    if (id === active?.activeTenantId) { setOpen(false); return; }
    setSwitching(true);
    try { await activeTenantApi.switch(id); setOpen(false); onSwitch(); } catch { /* 무시 */ } finally { setSwitching(false); }
  }
  return (
    <div className="menu-wrap" ref={ref}>
      <button type="button" className="btn btn--ghost" aria-haspopup="menu" aria-expanded={open} disabled={switching} onClick={() => setOpen((v) => !v)}>
        <Icons.Building size={14} /><span className="truncate" style={{ maxWidth: 140 }}>{switching ? '전환 중…' : label}</span><Icons.ChevronDown size={12} />
      </button>
      {open && (
        <div className="menu" role="menu" aria-label="RP 전환" style={{ left: 0, right: 'auto', minWidth: 220 }}>
          <span className="t-over" style={{ display: 'block', padding: '4px 10px 6px' }}>RP 전환</span>
          {active.allowedTenantIds.map((id) => (
            <button key={id} type="button" role="menuitemradio" aria-checked={id === active.activeTenantId} className="menu__item" onClick={() => void pick(id)}>
              <span style={{ width: 14, display: 'inline-flex' }}>{id === active.activeTenantId && <Icons.Check size={14} />}</span>
              {names[id] ?? id.slice(-8)}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

type HeaderProps = {
  me: { role: string; email: string; displayName?: string };
  onLogout: () => void; onSwitchRole?: () => void;
  breadcrumb: { label: ReactNode; onClick?: () => void }[];
  onOpenPalette: () => void; onRpSwitch?: () => void;
};

export function Header({ me, onLogout, onSwitchRole, breadcrumb, onOpenPalette, onRpSwitch }: HeaderProps) {
  const [open, setOpen] = useState(false);
  const ref = useOutsideClose(open, () => setOpen(false));
  const name = me.displayName ?? me.email;
  const platform = me.role === 'PLATFORM_OPERATOR';
  return (
    <header className="topbar">
      <Breadcrumb items={breadcrumb} />
      <div className="spacer" />
      {me.role === 'RP_ADMIN' && <RpSwitcher onSwitch={onRpSwitch ?? (() => window.location.reload())} />}
      <button type="button" className="topbar__search" onClick={onOpenPalette} aria-label="검색 (⌘K)">
        <Icons.Search size={15} /><span>tenant, credential, audit ID 검색…</span><kbd>⌘K</kbd>
      </button>
      <button type="button" className="icon-btn" aria-label="도움말 · API 문서" title="도움말 · API 문서"><Icons.Help size={18} /></button>
      <div className="menu-wrap" ref={ref}>
        <button type="button" className="user-pill" aria-label="사용자 메뉴" aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen((v) => !v)}>
          <span className="avatar">{name.slice(0, 1).toUpperCase()}</span>
          <span className="stack-1" style={{ gap: 0, lineHeight: 1.2, textAlign: 'left' }}><span style={{ fontWeight: 600 }}>{name}</span><span style={{ fontSize: 11, color: 'var(--mute)' }}>{me.email}</span></span>
          <Badge tone={platform ? 'plat' : 'info'} dot={false}>{platform ? 'PLATFORM' : 'RP_ADMIN'}</Badge>
          <Icons.ChevronDown size={14} />
        </button>
        {open && (
          <div className="menu" role="menu" aria-label="사용자 메뉴" style={{ minWidth: 240 }}>
            <div className="stack-1" style={{ padding: '8px 10px 10px', borderBottom: '1px solid var(--line-soft)', marginBottom: 4, gap: 2 }}>
              <strong style={{ fontSize: 13 }}>{name}</strong><span style={{ fontSize: 12, color: 'var(--mute)' }}>{me.email}</span>
              <span style={{ marginTop: 4 }}><Badge tone={platform ? 'plat' : 'info'} dot={false}>{platform ? 'PLATFORM' : 'RP_ADMIN'}</Badge></span>
            </div>
            <button type="button" role="menuitem" className="menu__item" onClick={() => { setOpen(false); onSwitchRole?.(); }}><Icons.Refresh size={14} />role 전환 (데모)</button>
            <button type="button" role="menuitem" className="menu__item"><Icons.ExternalLink size={14} />API 문서 열기</button>
            <div className="menu__sep" />
            <button type="button" role="menuitem" className="menu__item menu__item--danger" onClick={() => { setOpen(false); onLogout(); }}><Icons.LogOut size={14} />로그아웃</button>
          </div>
        )}
      </div>
    </header>
  );
}
