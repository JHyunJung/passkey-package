// ActiveTenantController returns raw JSON (no ApiResponse envelope).
// Uses the same adminFetch helper as adminUsers.ts (credentials:include + X-XSRF-TOKEN).

import { adminFetch } from './adminUsers';

export interface TenantRef {
  id: string;
  name: string;
}

export interface ActiveTenantView {
  activeTenantId: string | null;
  allowedTenantIds: string[];
  /** 스위처 라벨용 id+name. RP_ADMIN 이 일반 목록으로 못 보는 비활성 RP 이름도 포함. */
  allowedTenants: TenantRef[];
}

export const activeTenantApi = {
  get: (): Promise<ActiveTenantView> =>
    adminFetch<ActiveTenantView>('GET', '/admin/api/active-tenant'),

  switch: (tenantId: string): Promise<ActiveTenantView> =>
    adminFetch<ActiveTenantView>('POST', '/admin/api/active-tenant', { tenantId }),
};
