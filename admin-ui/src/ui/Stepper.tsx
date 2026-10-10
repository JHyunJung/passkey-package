import { ReactNode } from 'react';
import { cn } from '@/lib/cn';

export type StepState = 'done' | 'now' | 'todo';

export function Stepper({ title, steps }: { title?: string; steps: { label: ReactNode; description?: ReactNode; state: StepState }[] }) {
  return (
    <nav aria-label={title ?? '진행 단계'}>
      {title && <span className="t-over" style={{ display: 'block', padding: '0 6px 8px' }}>{title}</span>}
      <ol className="stepper" style={{ listStyle: 'none', margin: 0, padding: 0 }}>
        {steps.map((s, i) => (
          <li key={i} className={cn('stepper__item', `stepper__item--${s.state}`)} aria-current={s.state === 'now' ? 'step' : undefined}>
            <span className="stepper__mark" aria-hidden="true">{s.state === 'done' ? '✓' : i + 1}</span>
            <span className="stack-1" style={{ gap: 0 }}>
              <span className="stepper__label">{s.label}</span>
              {s.description && <span className="stepper__desc">{s.description}</span>}
            </span>
          </li>
        ))}
      </ol>
    </nav>
  );
}
