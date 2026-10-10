import { cn } from '@/lib/cn';

export function StatusStrip({ results, label, fluid }: { results: (boolean | null)[]; label: string; fluid?: boolean }) {
  return (
    <div className={cn('strip', fluid && 'strip--fluid')} role="img" aria-label={label}>
      {results.map((r, i) => <span key={i} className={cn('strip__bar', r === false && 'strip__bar--bad', r === null && 'strip__bar--none')} />)}
    </div>
  );
}
