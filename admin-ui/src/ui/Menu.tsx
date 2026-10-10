import { useEffect, useRef, useState, ReactNode, KeyboardEvent } from 'react';
import { Icons } from '@/icons/Icons';
import { cn } from '@/lib/cn';
import { IconButton } from './IconButton';

export type MenuItem = { label: ReactNode; onSelect: () => void; icon?: ReactNode; danger?: boolean };

export function Menu({ label, items, dangerItems = [], dangerTitle = '위험' }: { label: string; items: MenuItem[]; dangerItems?: MenuItem[]; dangerTitle?: string }) {
  const [open, setOpen] = useState(false);
  const wrapRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const all = [...items, ...dangerItems];

  useEffect(() => {
    if (!open) return;
    wrapRef.current?.querySelector<HTMLElement>('[role="menuitem"]')?.focus();
    const outside = (e: MouseEvent) => { if (!wrapRef.current?.contains(e.target as Node)) setOpen(false); };
    document.addEventListener('mousedown', outside);
    return () => document.removeEventListener('mousedown', outside);
  }, [open]);

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
      <IconButton ref={triggerRef} label={label} icon={<Icons.Dots size={18} />} aria-haspopup="menu" aria-expanded={open} onClick={(e) => { e.stopPropagation(); setOpen((v) => !v); }} />
      {open && (
        // 메뉴 클릭이 상위 행 핸들러로 올라가지 않도록 막는다. 키보드는 .menu-wrap 에서 처리.
        // eslint-disable-next-line jsx-a11y/click-events-have-key-events
        <div className="menu" role="menu" tabIndex={-1} aria-label={label} onClick={(e) => e.stopPropagation()}>
          {items.map(renderItem)}
          {dangerItems.length > 0 && <><div className="menu__sep" /><span className="menu__section">{dangerTitle}</span>{dangerItems.map((it, k) => renderItem(it, items.length + k))}</>}
        </div>
      )}
    </div>
  );
}
