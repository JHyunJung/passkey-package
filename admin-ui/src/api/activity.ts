// ActivityPage 전용 API 모듈.
// 서버 응답은 ApiEnvelope<ActivityView> 로 감싸여 있으며
// client.ts의 api.get() 이 자동으로 envelope 을 벗겨 ActivityView 를 반환한다.
//
// ActivityView (types.ts):
//   kpi   : { events24h, ops24h, security24h, p95Ms }
//   top5  : [{ tenantId, slug, count }]
//   feed  : [{ id, action, actorEmail, targetType, targetId,
//              tenantId, tenantSlug, createdAt, category }]

import { api } from './client';
import type { ActivityView, ActivityCategory, ActivityDetailView } from './types';

export const activityApi = {
  fetch: (
    sinceId?: string | null,
    category?: ActivityCategory,
    before?: string,
    tenantId?: string,
    beforeId?: string,
  ): Promise<ActivityView> => {
    const q = new URLSearchParams();
    if (sinceId) q.set('sinceId', sinceId);
    if (category && category !== 'all') q.set('category', category);
    if (before) q.set('before', before);
    // F07: beforeId is the id of the oldest row from the previous page. Sent
    // alongside `before` so the server can use the (createdAt, id) tuple
    // cursor instead of a strict-instant comparison, which drops rows that
    // share the exact microsecond boundary timestamp.
    if (beforeId) q.set('beforeId', beforeId);
    if (tenantId) q.set('tenantId', tenantId);
    const qs = q.toString();
    return api.get<ActivityView>(`/admin/api/activity${qs ? `?${qs}` : ''}`);
  },
  fetchDetail: (id: string): Promise<ActivityDetailView> =>
    api.get<ActivityDetailView>(`/admin/api/activity/${id}`),
};
