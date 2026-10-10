import { Fragment, ReactNode } from 'react';
import { Icons } from '@/icons/Icons';

export function Breadcrumb({ items }: { items: { label: ReactNode; onClick?: () => void }[] }) {
  if (!items || items.length === 0) return null;
  return (
    <nav aria-label="현재 위치" className="crumbs">
      {items.map((it, i) => {
        const last = i === items.length - 1;
        return (
          <Fragment key={i}>
            {i > 0 && <Icons.ChevronRight size={14} />}
            {last ? <span className="crumbs__current" aria-current="page">{it.label}</span>
              : it.onClick ? <button type="button" className="crumbs__item" onClick={it.onClick}>{it.label}</button>
              : <span>{it.label}</span>}
          </Fragment>
        );
      })}
    </nav>
  );
}
