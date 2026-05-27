import { api } from './client';
import type { AuditChainOverview, ChainVerifyResponse, BackfillResponse } from './types';

export const auditChainApi = {
  overview: (windowHours = 24) =>
    api.get<AuditChainOverview>(`/admin/api/audit/chain/overview?windowHours=${windowHours}`),
  verify: (tenantId?: string) =>
    api.get<ChainVerifyResponse>(
      `/admin/api/audit/chain/verify${tenantId ? `?tenantId=${encodeURIComponent(tenantId)}` : ''}`
    ),
  backfill: () => api.post<BackfillResponse>('/admin/api/audit/chain/backfill', {}),
};
