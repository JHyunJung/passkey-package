import { ReactNode } from 'react';
import { cn } from '@/lib/cn';

type Props = { label: ReactNode; value: ReactNode; hint?: ReactNode; hintTone?: 'default' | 'warn'; selected?: boolean; onClick?: () => void };

export function KpiCard({ label, value, hint, hintTone = 'default', selected, onClick }: Props) {
  const body = (
    <>
      <span className="kpi__label">{label}</span>
      <span className="kpi__value">{value}</span>
      {hint != null && <span className={cn('kpi__hint', hintTone === 'warn' && 'kpi__hint--warn')}>{hint}</span>}
    </>
  );
  if (onClick) {
    return <button type="button" className={cn('kpi', 'kpi--clickable', selected && 'kpi--selected')} aria-pressed={!!selected} onClick={onClick}>{body}</button>;
  }
  return <div className="kpi">{body}</div>;
}
