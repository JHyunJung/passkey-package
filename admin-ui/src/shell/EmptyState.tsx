import { ReactNode } from 'react';
import { Icons } from '@/icons/Icons';

export function EmptyState({ icon = 'Sparkles', title, description, action }: { icon?: string; title: ReactNode; description?: ReactNode; action?: ReactNode }) {
  const I = (Icons as Record<string, React.ComponentType<{ size?: number }>>)[icon] || Icons.Sparkles;
  return (
    <div style={{ padding: '48px 24px', textAlign: 'center' }}>
      <div style={{ width: 44, height: 44, borderRadius: 12, background: 'var(--accent-soft)', color: 'var(--accent)', display: 'grid', placeItems: 'center', margin: '0 auto 12px' }}>
        <I size={22} />
      </div>
      <div style={{ fontSize: 14, fontWeight: 600, color: 'var(--text)' }}>{title}</div>
      {description && <div style={{ fontSize: 13, color: 'var(--text-mute)', marginTop: 6, maxWidth: 380, marginLeft: 'auto', marginRight: 'auto' }}>{description}</div>}
      {action && <div style={{ marginTop: 14 }}>{action}</div>}
    </div>
  );
}
