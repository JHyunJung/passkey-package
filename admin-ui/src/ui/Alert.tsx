import { ReactNode } from 'react';
import { Icons } from '@/icons/Icons';

export type AlertTone = 'ok' | 'warn' | 'bad' | 'info';
const CLS: Record<AlertTone, string> = { ok: 'banner--success', warn: 'banner--warning', bad: 'banner--danger', info: 'banner--info' };
const ICON: Record<AlertTone, ReactNode> = { ok: <Icons.Check size={18} />, warn: <Icons.Alert size={18} />, bad: <Icons.Alert size={18} />, info: <Icons.Info size={18} /> };

export function Alert({ tone, title, children, actions, icon }: { tone: AlertTone; title?: ReactNode; children?: ReactNode; actions?: ReactNode; icon?: ReactNode }) {
  return (
    // 제목+본문(두 줄 이상)이면 아이콘을 첫 줄에 맞춘다(V2 아트보드 align-items:flex-start).
    <div className={`banner ${CLS[tone]}${title && children ? ' banner--top' : ''}`} role={tone === 'bad' ? 'alert' : 'status'}>
      <span className="banner__icon">{icon ?? ICON[tone]}</span>
      <div className="banner__body">
        {title && <div className="banner__title">{title}</div>}
        {children && <div>{children}</div>}
      </div>
      {actions && <div className="banner__actions">{actions}</div>}
    </div>
  );
}
