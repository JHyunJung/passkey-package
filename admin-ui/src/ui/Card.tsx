import { ReactNode } from 'react';
import { cn } from '@/lib/cn';

export function Card({ children, className, as: Tag = 'section' }: { children: ReactNode; className?: string; as?: 'section' | 'div' }) {
  return <Tag className={cn('card', className)}>{children}</Tag>;
}
/** sub: 제목 아래 설명(t-sm). cap: 제목 옆 한 줄 캡션(t-cap) — V2 아트보드의 "제목 + 짧은 기준/범위" 헤더. */
export function CardHeader({ title, sub, cap, actions }: { title: ReactNode; sub?: ReactNode; cap?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="card__head">
      <div className="stack-1" style={{ gap: 2, minWidth: 0 }}>
        {cap ? (
          <div className="row" style={{ gap: '4px 8px' }}>
            <h2 className="card__title">{title}</h2>
            <span className="card__cap">{cap}</span>
          </div>
        ) : <h2 className="card__title">{title}</h2>}
        {sub && <p className="card__sub">{sub}</p>}
      </div>
      {actions && <><div className="spacer" />{actions}</>}
    </div>
  );
}
export function CardBody({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={cn('card__body', className)}>{children}</div>;
}
export function CardFooter({ children }: { children: ReactNode }) {
  return <div className="card__foot">{children}</div>;
}
