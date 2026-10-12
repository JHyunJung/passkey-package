import { useEffect, useLayoutEffect, useRef, useState, ReactNode, KeyboardEvent, CSSProperties } from 'react';
import { Icons } from '@/icons/Icons';
import { cn } from '@/lib/cn';
import { IconButton } from './IconButton';

/** disabled: 보이되 실행되지 않는 항목(aria-disabled, 포커스 순서 유지). disabledReason 은 라벨 아래와 툴팁에 보인다. */
export type MenuItem = { label: ReactNode; onSelect: () => void; icon?: ReactNode; danger?: boolean; disabled?: boolean; disabledReason?: string };

// 메뉴 높이 추정(열기 전이라 측정 불가): 패딩 12 + 항목 36 + 위험 구획(구분선 13 + 제목 22).
// 사유가 붙은 비활성 항목은 라벨 아래 줄(hint 16 + gap 2 + 패딩 12)이 더해져 한 줄보다 높다. 여유를 둬서 잡는다.
const ITEM_H = 36;
const REASON_ITEM_H = 52;
const GAP = 6;
function estimateHeight(items: MenuItem[], hasDanger: boolean) {
  const body = items.reduce((h, it) => h + (it.disabled && it.disabledReason ? REASON_ITEM_H : ITEM_H), 0);
  return 12 + body + (hasDanger ? 35 : 0);
}
// 메뉴 높이 h 가 아래에 들어가면 아래, 위에 들어가면 위. 어디에도 다 안 들어가면 공간이 더 큰 쪽으로 열고
// 남은 높이만큼 max-height 로 자른 뒤 스크롤한다(화면 밖으로 나가는 항목이 없게). 열기 직전 추정과 실측에 같이 쓴다.
function placeMenu(t: Pick<DOMRect, 'top' | 'bottom' | 'right'>, h: number): CSSProperties {
  const right = window.innerWidth - t.right;
  const below = window.innerHeight - t.bottom - GAP;
  const above = t.top - GAP;
  if (h <= below) return { position: 'fixed', top: t.bottom + GAP, right };
  if (h <= above) return { position: 'fixed', top: 'auto', bottom: window.innerHeight - t.top + GAP, right };
  if (below >= above) return { position: 'fixed', top: t.bottom + GAP, right, maxHeight: below, overflowY: 'auto' };
  return { position: 'fixed', top: 'auto', bottom: window.innerHeight - t.top + GAP, right, maxHeight: above, overflowY: 'auto' };
}

/**
 * ⋯ 메뉴. 열린 목록은 position:fixed 로 트리거 기준에 붙인다 — 표의 가로 스크롤 컨테이너(.tw)
 * 안 마지막 행에서도 잘리지 않게. 아래 공간이 모자라면 위로 연다. 스크롤·리사이즈 시 닫는다.
 * trigger="button" 은 테두리 있는 버튼(테넌트 헤더), 기본은 테두리 없는 아이콘 버튼(표 행).
 */
export function Menu({ label, items, dangerItems = [], dangerTitle = '위험', trigger = 'icon' }: { label: string; items: MenuItem[]; dangerItems?: MenuItem[]; dangerTitle?: string; trigger?: 'icon' | 'button' }) {
  const [open, setOpen] = useState(false);
  const [pos, setPos] = useState<CSSProperties>({});
  const wrapRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const all = [...items, ...dangerItems];

  // 추정으로 먼저 연 뒤, 렌더된 메뉴 높이로 방향을 다시 정한다(사유가 여러 줄로 감기면 추정이 틀린다).
  useLayoutEffect(() => {
    if (!open) return;
    const menu = menuRef.current;
    const t = triggerRef.current?.getBoundingClientRect();
    // 이전 max-height 로 잘린 높이가 아니라 자연 높이를 재야 한다.
    if (menu) menu.style.maxHeight = '';
    const m = menu?.getBoundingClientRect();
    // 높이 0 은 측정 불가(레이아웃 없는 환경)로 보고 추정을 그대로 둔다.
    if (t && m && m.height > 0) setPos(placeMenu(t, m.height));
  }, [open]);

  useEffect(() => {
    if (!open) return;
    wrapRef.current?.querySelector<HTMLElement>('[role="menuitem"]')?.focus({ preventScroll: true });
    const outside = (e: MouseEvent) => { if (!wrapRef.current?.contains(e.target as Node)) setOpen(false); };
    const dismiss = () => setOpen(false);
    document.addEventListener('mousedown', outside);
    window.addEventListener('scroll', dismiss, true);
    window.addEventListener('resize', dismiss);
    return () => {
      document.removeEventListener('mousedown', outside);
      window.removeEventListener('scroll', dismiss, true);
      window.removeEventListener('resize', dismiss);
    };
  }, [open]);

  function toggle() {
    if (open) { setOpen(false); return; }
    const r = triggerRef.current?.getBoundingClientRect();
    if (r) setPos(placeMenu(r, estimateHeight(all, dangerItems.length > 0)));
    setOpen(true);
  }

  function close(refocus: boolean) { setOpen(false); if (refocus) triggerRef.current?.focus(); }
  // 키보드 핸들러는 래퍼에 둔다: 트리거에 포커스가 있을 때도 Escape 가 닫히도록.
  function onKeyDown(e: KeyboardEvent<HTMLDivElement>) {
    if (e.key === 'Escape') { if (open) { e.preventDefault(); e.stopPropagation(); close(true); } return; }
    if (!open) return;
    const els = Array.from(wrapRef.current?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? []);
    const i = els.indexOf(document.activeElement as HTMLElement);
    if (e.key === 'ArrowDown') { e.preventDefault(); els[(i + 1) % els.length]?.focus(); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); els[i < 0 ? els.length - 1 : (i - 1 + els.length) % els.length]?.focus(); }
    else if ((e.key === 'Enter' || e.key === ' ') && i >= 0) { e.preventDefault(); if (all[i].disabled) return; all[i].onSelect(); close(true); }
  }
  const renderItem = (it: MenuItem, k: number) => (
    <button
      key={k}
      type="button"
      role="menuitem"
      tabIndex={-1}
      aria-disabled={it.disabled || undefined}
      title={it.disabled ? it.disabledReason : undefined}
      className={cn('menu__item', it.danger && 'menu__item--danger')}
      onClick={(e) => { e.stopPropagation(); if (it.disabled) return; it.onSelect(); close(true); }}
    >
      {it.icon}
      {it.disabled && it.disabledReason ? (
        <span style={{ display: 'flex', flexDirection: 'column', gap: 2, alignItems: 'flex-start', padding: '6px 0' }}>
          <span>{it.label}</span>
          <span className="hint" style={{ margin: 0, whiteSpace: 'normal' }}>{it.disabledReason}</span>
        </span>
      ) : it.label}
    </button>
  );
  return (
    <div className="menu-wrap" ref={wrapRef} onKeyDown={onKeyDown}>
      {trigger === 'button' ? (
        <button ref={triggerRef} type="button" className="btn" style={{ padding: '0 10px' }} aria-label={label} title={label} aria-haspopup="menu" aria-expanded={open} onClick={(e) => { e.stopPropagation(); toggle(); }}>
          <Icons.Dots size={18} />
        </button>
      ) : (
        <IconButton ref={triggerRef} label={label} icon={<Icons.Dots size={18} />} aria-haspopup="menu" aria-expanded={open} onClick={(e) => { e.stopPropagation(); toggle(); }} />
      )}
      {open && (
        // 메뉴 클릭이 상위 행 핸들러로 올라가지 않도록 막는다. 키보드는 .menu-wrap 에서 처리.
        // eslint-disable-next-line jsx-a11y/click-events-have-key-events
        <div ref={menuRef} className="menu" role="menu" tabIndex={-1} aria-label={label} style={pos} onClick={(e) => e.stopPropagation()}>
          {items.map(renderItem)}
          {dangerItems.length > 0 && <><div className="menu__sep" /><span className="menu__section">{dangerTitle}</span>{dangerItems.map((it, k) => renderItem(it, items.length + k))}</>}
        </div>
      )}
    </div>
  );
}
