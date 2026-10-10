import { ReactNode } from 'react';

export function EmptyState({ illustration, title, description, action, compact }: { illustration?: string; title: ReactNode; description?: ReactNode; action?: ReactNode; compact?: boolean }) {
  return (
    <div className="empty" style={compact ? { padding: '20px 16px' } : undefined}>
      {illustration && <img className="empty__art" src={illustration} alt="" style={compact ? { width: 104, height: 104 } : undefined} />}
      <p className="empty__title">{title}</p>
      {description && <p className="empty__sub">{description}</p>}
      {action}
    </div>
  );
}
