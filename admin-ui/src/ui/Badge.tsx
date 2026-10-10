import { ReactNode } from 'react';
import { cn } from '@/lib/cn';

export type BadgeTone = 'ok' | 'warn' | 'bad' | 'info' | 'plat' | 'neutral';
const TONE: Record<BadgeTone, string | null> = { ok: 'badge--success', warn: 'badge--warning', bad: 'badge--danger', info: 'badge--info', plat: 'badge--violet', neutral: null };

export function Badge({ tone = 'neutral', dot = true, mono, children }: { tone?: BadgeTone; dot?: boolean; mono?: boolean; children: ReactNode }) {
  return <span className={cn('badge', TONE[tone], dot && 'badge--dot', mono && 'mono')}>{children}</span>;
}
