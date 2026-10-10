import { statusLabel } from '@/i18n/labels';
import { Badge, BadgeTone } from './Badge';

const MAP: Record<string, BadgeTone> = { ACTIVE: 'ok', REVOKED: 'bad', EXPIRED: 'neutral', SUSPENDED: 'bad', PENDING: 'warn' };

export function StatusBadge({ status }: { status: string }) {
  return <Badge tone={MAP[status] ?? 'neutral'}>{statusLabel(status)}</Badge>;
}
