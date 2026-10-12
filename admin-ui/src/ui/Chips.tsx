import { ReactNode } from 'react';
import { cn } from '@/lib/cn';
import { Icons } from '@/icons/Icons';

export function FilterChip({ active, onClick, mono, disabled, title, children }: { active: boolean; onClick: () => void; mono?: boolean; disabled?: boolean; title?: string; children: ReactNode }) {
  return <button type="button" className={cn('fchip', active && 'fchip--on', mono && 'fchip--mono')} aria-pressed={active} disabled={disabled} title={title} onClick={onClick}>{children}</button>;
}

export function RemovableChip({ children, onRemove, removeLabel, tone = 'default', mono, disabled }: { children: ReactNode; onRemove: () => void; removeLabel: string; tone?: 'default' | 'added'; mono?: boolean; disabled?: boolean }) {
  return (
    <span className={cn('chip', tone === 'added' && 'chip--added', mono && 'mono')}>
      <span className="truncate">{children}</span>
      <button type="button" className="chip__x" aria-label={removeLabel} onClick={onRemove} disabled={disabled}><Icons.X size={12} /></button>
    </span>
  );
}
