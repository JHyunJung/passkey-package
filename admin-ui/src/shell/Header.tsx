import { KeyboardEvent, ReactNode, useEffect, useRef, useState } from 'react';
import { Icons } from '@/icons/Icons';
import { Breadcrumb } from './Breadcrumb';
import { activeTenantApi, type ActiveTenantView } from '@/api/activeTenant';
import { Badge, useToast } from '@/ui';
import { HELP_LINKS } from '@/config/links';
import { paletteShortcutLabel } from '@/lib/platform';
import { describeError } from '@/lib/describeError';

function useOutsideClose(open: boolean, close: () => void) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const h = (e: MouseEvent) => { if (!ref.current?.contains(e.target as Node)) close(); };
    const k = (e: globalThis.KeyboardEvent) => { if (e.key === 'Escape') close(); };
    document.addEventListener('mousedown', h); document.addEventListener('keydown', k);
    return () => { document.removeEventListener('mousedown', h); document.removeEventListener('keydown', k); };
  }, [open, close]);
  return ref;
}

/** 메뉴 안 ↑/↓ 이동. */
function moveFocus(root: HTMLElement | null, e: KeyboardEvent<HTMLDivElement>) {
  if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return;
  const els = Array.from(root?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? []);
  if (els.length === 0) return;
  e.preventDefault();
  const i = els.indexOf(document.activeElement as HTMLElement);
  const next = e.key === 'ArrowDown' ? (i + 1) % els.length : (i <= 0 ? els.length - 1 : i - 1);
  els[next]?.focus();
}

function RpSwitcher({ onSwitch }: { onSwitch: () => void }) {
  const [active, setActive] = useState<ActiveTenantView | null>(null);
  const [open, setOpen] = useState(false);
  const [switching, setSwitching] = useState(false);
  const ref = useOutsideClose(open, () => setOpen(false));
  const toast = useToast();
  useEffect(() => { activeTenantApi.get().then(setActive).catch(() => {}); }, []);
  if (!active || active.allowedTenantIds.length < 2) return null;
  const names: Record<string, string> = {};
  active.allowedTenants.forEach((t) => { names[t.id] = t.name; });
  const label = active.activeTenantId ? (names[active.activeTenantId] ?? active.activeTenantId.slice(-8)) : '—';
  async function pick(id: string) {
    if (id === active?.activeTenantId) { setOpen(false); return; }
    setSwitching(true);
    try {
      await activeTenantApi.switch(id);
      setOpen(false);
      onSwitch();
    } catch (e) {
      // 실패를 삼키지 않는다(H9). 서버는 활성 RP 를 바꾸지 않았으므로 화면도 그대로다.
      const info = describeError(e);
      setOpen(false);
      toast({ kind: 'err', title: 'RP를 전환하지 못했습니다', message: `${info.title}. 지금 보고 있는 RP(${label})는 그대로입니다.`, traceId: info.traceId });
    } finally {
      setSwitching(false);
    }
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

/** 도움말 진입점(H10). 주소가 빈 항목은 숨긴다(config/links.ts). */
function HelpMenu({ onOpenShortcuts }: { onOpenShortcuts: () => void }) {
  const [open, setOpen] = useState(false);
  const ref = useOutsideClose(open, () => setOpen(false));
  useEffect(() => {
    if (open) ref.current?.querySelector<HTMLElement>('[role="menuitem"]')?.focus();
  }, [open, ref]);
  const external = (href: string, label: string) => (
    <a role="menuitem" className="menu__item" href={href} target="_blank" rel="noopener noreferrer" onClick={() => setOpen(false)}>
      {label}
      <span className="hint" style={{ margin: '0 0 0 auto', display: 'inline-flex', alignItems: 'center', gap: 4 }}>새 창 <Icons.ExternalLink size={13} /></span>
    </a>
  );
  return (
    <div className="menu-wrap" ref={ref} onKeyDown={(e) => { if (open) moveFocus(ref.current, e); }}>
      <button type="button" className="btn" aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen((v) => !v)}>
        <Icons.Help size={16} />도움말
      </button>
      {open && (
        <div className="menu" role="menu" aria-label="도움말" style={{ minWidth: 220 }}>
          {HELP_LINKS.apiDocs && external(HELP_LINKS.apiDocs, 'API 문서 열기')}
          {HELP_LINKS.rpGuide && external(HELP_LINKS.rpGuide, 'RP 연동 가이드')}
          <button type="button" role="menuitem" className="menu__item" aria-keyshortcuts="?" onClick={() => { setOpen(false); onOpenShortcuts(); }}>
            키보드 단축키<kbd aria-hidden="true" style={{ marginLeft: 'auto' }}>?</kbd>
          </button>
          {HELP_LINKS.support && (
            <>
              <div className="menu__sep" />
              <a role="menuitem" className="menu__item" href={HELP_LINKS.support} onClick={() => setOpen(false)}>운영자에게 문의</a>
            </>
          )}
        </div>
      )}
    </div>
  );
}

type HeaderProps = {
  me: { role: string; email: string; displayName?: string };
  onLogout: () => void;
  breadcrumb: { label: ReactNode; onClick?: () => void }[];
  onOpenPalette: () => void;
  onOpenShortcuts: () => void;
  onRpSwitch?: () => void;
};

export function Header({ me, onLogout, breadcrumb, onOpenPalette, onOpenShortcuts, onRpSwitch }: HeaderProps) {
  const [open, setOpen] = useState(false);
  const ref = useOutsideClose(open, () => setOpen(false));
  const name = me.displayName ?? me.email;
  const platform = me.role === 'PLATFORM_OPERATOR';
  const shortcut = paletteShortcutLabel();
  return (
    <header className="topbar">
      <Breadcrumb items={breadcrumb} />
      <div className="spacer" />
      {me.role === 'RP_ADMIN' && <RpSwitcher onSwitch={onRpSwitch ?? (() => window.location.reload())} />}
      {/* 문구는 실제 검색 범위(lib/search.ts: tenant 이름·slug)만 약속한다(H2). */}
      <button type="button" className="topbar__search" onClick={onOpenPalette} aria-label={`tenant 이름 · slug 검색 (${shortcut})`}>
        <Icons.Search size={15} /><span>tenant 이름 · slug 검색</span><kbd>{shortcut}</kbd>
      </button>
      <HelpMenu onOpenShortcuts={onOpenShortcuts} />
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
            <button type="button" role="menuitem" className="menu__item menu__item--danger" onClick={() => { setOpen(false); onLogout(); }}><Icons.LogOut size={14} />로그아웃</button>
          </div>
        )}
      </div>
    </header>
  );
}
