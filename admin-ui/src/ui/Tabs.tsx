import { ReactNode } from 'react';
import { cn } from '@/lib/cn';

export type TabItem = { id: string; label: ReactNode; dot?: boolean; count?: number };

export function Tabs({ items, active, onChange, ariaLabel }: { items: TabItem[]; active: string; onChange: (id: string) => void; ariaLabel: string }) {
  return (
    <div className="tabs" role="tablist" aria-label={ariaLabel}>
      {items.map((t) => (
        <button key={t.id} type="button" role="tab" aria-selected={t.id === active} className={cn('tabs__btn', t.id === active && 'tabs__btn--active')} onClick={() => onChange(t.id)}>
          {t.label}
          {t.dot && <span className="tabs__dot" aria-label="확인 필요" role="img" />}
          {t.count != null && t.count > 0 && <span className="tabs__count">{t.count}</span>}
        </button>
      ))}
    </div>
  );
}
