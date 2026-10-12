import { ReactNode } from 'react';
import { cn } from '@/lib/cn';
import { Icons } from '@/icons/Icons';

export function FilterChip({ active, onClick, mono, disabled, children }: { active: boolean; onClick: () => void; mono?: boolean; disabled?: boolean; children: ReactNode }) {
  return <button type="button" className={cn('fchip', active && 'fchip--on', mono && 'fchip--mono')} aria-pressed={active} disabled={disabled} onClick={onClick}>{children}</button>;
}

export function RemovableChip({ children, onRemove, removeLabel, tone = 'default', mono }: { children: ReactNode; onRemove: () => void; removeLabel: string; tone?: 'default' | 'added'; mono?: boolean }) {
  return (
    <span className={cn('chip', tone === 'added' && 'chip--added', mono && 'mono')}>
      <span className="truncate">{children}</span>
      <button type="button" className="chip__x" aria-label={removeLabel} onClick={onRemove}><Icons.X size={12} /></button>
    </span>
  );
}
