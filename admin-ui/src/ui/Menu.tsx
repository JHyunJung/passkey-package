import { useEffect, useRef, useState, ReactNode, KeyboardEvent, CSSProperties } from 'react';
import { Icons } from '@/icons/Icons';
import { cn } from '@/lib/cn';
import { IconButton } from './IconButton';

export type MenuItem = { label: ReactNode; onSelect: () => void; icon?: ReactNode; danger?: boolean };

// 메뉴 높이 추정(열기 전이라 측정 불가): 패딩 12 + 항목 36 + 위험 구획(구분선 13 + 제목 22).
const ITEM_H = 36;
const GAP = 6;
function estimateHeight(n: number, hasDanger: boolean) { return 12 + n * ITEM_H + (hasDanger ? 35 : 0); }

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
  const all = [...items, ...dangerItems];

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
    if (r) {
      const right = window.innerWidth - r.right;
      const fitsBelow = r.bottom + GAP + estimateHeight(all.length, dangerItems.length > 0) <= window.innerHeight;
      setPos(fitsBelow
        ? { position: 'fixed', top: r.bottom + GAP, right }
        : { position: 'fixed', top: 'auto', bottom: window.innerHeight - r.top + GAP, right });
    }
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
    else if ((e.key === 'Enter' || e.key === ' ') && i >= 0) { e.preventDefault(); all[i].onSelect(); close(true); }
  }
  const renderItem = (it: MenuItem, k: number) => (
    <button key={k} type="button" role="menuitem" tabIndex={-1} className={cn('menu__item', it.danger && 'menu__item--danger')} onClick={(e) => { e.stopPropagation(); it.onSelect(); close(true); }}>
      {it.icon}{it.label}
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
        <div className="menu" role="menu" tabIndex={-1} aria-label={label} style={pos} onClick={(e) => e.stopPropagation()}>
          {items.map(renderItem)}
          {dangerItems.length > 0 && <><div className="menu__sep" /><span className="menu__section">{dangerTitle}</span>{dangerItems.map((it, k) => renderItem(it, items.length + k))}</>}
        </div>
      )}
    </div>
  );
}
