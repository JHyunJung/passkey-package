import { useEffect, useRef, useState, ReactNode, KeyboardEvent } from 'react';
import { Icons } from '@/icons/Icons';
import { cn } from '@/lib/cn';
import { IconButton } from './IconButton';

export type MenuItem = { label: ReactNode; onSelect: () => void; icon?: ReactNode; danger?: boolean };

export function Menu({ label, items, dangerItems = [], dangerTitle = '위험', trigger }: { label: string; items: MenuItem[]; dangerItems?: MenuItem[]; dangerTitle?: string; trigger?: ReactNode }) {
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
  function onKeyDown(e: KeyboardEvent<HTMLDivElement>) {
    const els = Array.from(wrapRef.current?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? []);
    const i = els.indexOf(document.activeElement as HTMLElement);
    if (e.key === 'ArrowDown') { e.preventDefault(); els[(i + 1) % els.length]?.focus(); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); els[(i - 1 + els.length) % els.length]?.focus(); }
    else if (e.key === 'Escape') { e.preventDefault(); close(true); }
    else if ((e.key === 'Enter' || e.key === ' ') && i >= 0) { e.preventDefault(); all[i].onSelect(); close(true); }
  }
  const renderItem = (it: MenuItem, k: number) => (
    <button key={k} type="button" role="menuitem" tabIndex={-1} className={cn('menu__item', it.danger && 'menu__item--danger')} onClick={() => { it.onSelect(); close(true); }}>
      {it.icon}{it.label}
    </button>
  );
  return (
    <div className="menu-wrap" ref={wrapRef}>
      {trigger ?? <IconButton ref={triggerRef} label={label} icon={<Icons.Dots size={18} />} aria-haspopup="menu" aria-expanded={open} onClick={(e) => { e.stopPropagation(); setOpen((v) => !v); }} />}
      {open && (
        <div className="menu" role="menu" tabIndex={-1} aria-label={label} onKeyDown={onKeyDown} onClick={(e) => e.stopPropagation()}>
          {items.map(renderItem)}
          {dangerItems.length > 0 && <><div className="menu__sep" /><span className="menu__section">{dangerTitle}</span>{dangerItems.map((it, k) => renderItem(it, items.length + k))}</>}
        </div>
      )}
    </div>
  );
}
