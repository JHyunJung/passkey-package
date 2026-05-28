import type { ActivityView } from '@/api/types';

export type RecentActivityEvent = {
  id: string;
  ts: string;
  tenantId: string | null;
  tenantName: string;
  tenantSlug: string | null;
  type: string;
  actorType: 'ADMIN' | 'RP_SERVICE' | string;
  actorId: string | null;
  subjectId: string;
  category: 'ops' | 'security' | 'system';
};

export function adaptFeedItems(view: ActivityView): RecentActivityEvent[] {
  return view.feed.map((e) => ({
    id: e.id,
    ts: e.createdAt,
    tenantId: e.tenantId,
    // server doesn't carry displayName; fall back to slug or tenantId
    tenantName: e.tenantSlug ?? e.tenantId ?? '—',
    tenantSlug: e.tenantSlug,
    type: e.action,
    actorType: e.actorEmail ? 'ADMIN' : 'RP_SERVICE',
    actorId: e.actorEmail ?? null,
    subjectId: e.targetId ?? '—',
    category: e.category,
  }));
}
