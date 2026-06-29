// ActiveTenantController returns raw JSON (no ApiResponse envelope).
// Uses the same adminFetch helper as adminUsers.ts (credentials:include + X-XSRF-TOKEN).

import { adminFetch } from './adminUsers';

export interface ActiveTenantView {
  activeTenantId: string | null;
  allowedTenantIds: string[];
}

export const activeTenantApi = {
  get: (): Promise<ActiveTenantView> =>
    adminFetch<ActiveTenantView>('GET', '/admin/api/active-tenant'),

  switch: (tenantId: string): Promise<ActiveTenantView> =>
    adminFetch<ActiveTenantView>('POST', '/admin/api/active-tenant', { tenantId }),
};
