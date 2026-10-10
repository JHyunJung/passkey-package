import { ReactNode } from 'react';
import { cn } from '@/lib/cn';

export function ChoiceCard({ checked, onSelect, title, description }: { checked: boolean; onSelect: () => void; title: ReactNode; description?: ReactNode }) {
  return (
    <button type="button" role="radio" aria-checked={checked} className={cn('choice', checked && 'choice--on')} onClick={onSelect}>
      <span className="choice__radio" aria-hidden="true" />
      <span className="stack-1" style={{ gap: 2 }}>
        <span className="choice__title">{title}</span>
        {description && <span className="choice__desc">{description}</span>}
      </span>
    </button>
  );
}
