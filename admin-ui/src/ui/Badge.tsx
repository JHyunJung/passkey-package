import { ReactNode } from 'react';
import { cn } from '@/lib/cn';

export type BadgeTone = 'ok' | 'warn' | 'bad' | 'info' | 'plat' | 'neutral';
const TONE: Record<BadgeTone, string | null> = { ok: 'badge--success', warn: 'badge--warning', bad: 'badge--danger', info: 'badge--info', plat: 'badge--violet', neutral: null };

export function Badge({ tone = 'neutral', dot = true, mono, title, children }: { tone?: BadgeTone; dot?: boolean; mono?: boolean; title?: string; children: ReactNode }) {
  return <span className={cn('badge', TONE[tone], dot && 'badge--dot', mono && 'mono')} title={title}>{children}</span>;
}
