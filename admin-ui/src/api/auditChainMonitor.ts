import { api } from './client';

export type ChainOverview = {
  verifiedAt: string;
  windowHours: number;
  bucketSizeMinutes: number;
  totals: {
    tenantsIntact: number;
    tenantsTotal: number;
    tenantsTampered: number;
    verifiedRows: number;
    verificationMs: number;
  };
  tenants: {
    tenantId: string | null;
    tenantName: string;
    intact: boolean;
    verifiedRows: number;
    buckets: number[];
    tamperedEntryId: string | null;
  }[];
};

export type BackfillResult = {
  tenantsProcessed: number;
  rowsUpdated: number;
  rowsSkipped: number;
};

// NOTE: AuditChainMonitorController returns raw POJO (no ApiResponse envelope).
// Use getRaw / postRaw instead of the envelope-aware get / post.
export const auditChainMonitorApi = {
  overview: async (windowHours = 24): Promise<ChainOverview> => {
    return api.getRaw<ChainOverview>(
      `/admin/api/audit/chain/overview?windowHours=${windowHours}`,
    );
  },
  backfill: async (): Promise<BackfillResult> => {
    return api.postRaw<BackfillResult>('/admin/api/audit/chain/backfill', {});
  },
};
