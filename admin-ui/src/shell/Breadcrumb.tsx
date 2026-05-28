import { Fragment, ReactNode } from 'react';
import { Icons } from '@/icons/Icons';

export function Breadcrumb({ items }: { items: { label: ReactNode; onClick?: () => void }[] }) {
  if (!items || items.length === 0) return null;
  return (
    <nav style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 13, color: 'var(--text-soft)', minWidth: 0 }}>
      {items.map((it, i) => (
        <Fragment key={i}>
          {i > 0 && <Icons.ChevronRight size={13} />}
          {it.onClick ? (
            <button onClick={it.onClick} className="btn btn--ghost btn--xs" style={{ padding: '2px 6px', color: i === items.length - 1 ? 'var(--text)' : 'var(--text-mute)', fontWeight: i === items.length - 1 ? 600 : 500 }}>{it.label}</button>
          ) : (
            <span style={{ color: i === items.length - 1 ? 'var(--text)' : 'var(--text-mute)', fontWeight: i === items.length - 1 ? 600 : 500 }}>{it.label}</span>
          )}
        </Fragment>
      ))}
    </nav>
  );
}
