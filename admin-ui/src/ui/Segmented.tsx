import { ReactNode } from 'react';
import { cn } from '@/lib/cn';

export function Segmented<T extends string>({ options, value, onChange, ariaLabel }: { options: { value: T; label: ReactNode }[]; value: T; onChange: (v: T) => void; ariaLabel: string }) {
  return (
    <div className="seg" role="group" aria-label={ariaLabel}>
      {options.map((o) => (
        <button key={o.value} type="button" className={cn('seg__btn', o.value === value && 'seg__btn--on')} aria-pressed={o.value === value} onClick={() => onChange(o.value)}>{o.label}</button>
      ))}
    </div>
  );
}
