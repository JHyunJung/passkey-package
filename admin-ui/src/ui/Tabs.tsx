import { KeyboardEvent, ReactNode, useRef } from 'react';
import { cn } from '@/lib/cn';

/** badge: 확인이 필요한 탭. text 는 보이는 라벨("확인 필요 N"), reason 은 사유 툴팁(title). 색만으로 전달하지 않는다. */
export type TabItem = { id: string; label: ReactNode; badge?: { text: string; reason: string }; count?: number };

export function Tabs({ items, active, onChange, ariaLabel }: { items: TabItem[]; active: string; onChange: (id: string) => void; ariaLabel: string }) {
  const refs = useRef<(HTMLButtonElement | null)[]>([]);
  const activeIdx = Math.max(0, items.findIndex((t) => t.id === active));

  function onKeyDown(e: KeyboardEvent<HTMLButtonElement>, i: number) {
    const last = items.length - 1;
    const next =
      e.key === 'ArrowRight' ? (i === last ? 0 : i + 1)
        : e.key === 'ArrowLeft' ? (i === 0 ? last : i - 1)
          : e.key === 'Home' ? 0
            : e.key === 'End' ? last
              : null;
    if (next === null) return;
    e.preventDefault();
    refs.current[next]?.focus();
    onChange(items[next].id);
  }

  return (
    <div className="tabs" role="tablist" aria-label={ariaLabel}>
      {items.map((t, i) => (
        <button
          key={t.id}
          ref={(el) => { refs.current[i] = el; }}
          type="button"
          role="tab"
          aria-selected={t.id === active}
          tabIndex={i === activeIdx ? 0 : -1}
          title={t.badge?.reason}
          className={cn('tabs__btn', t.id === active && 'tabs__btn--active')}
          onClick={() => onChange(t.id)}
          onKeyDown={(e) => onKeyDown(e, i)}
        >
          {t.label}
          {t.badge && <span className="badge badge--warning tabs__badge" title={t.badge.reason}>{t.badge.text}</span>}
          {t.count != null && t.count > 0 && <span className="tabs__count">{t.count}</span>}
        </button>
      ))}
    </div>
  );
}
