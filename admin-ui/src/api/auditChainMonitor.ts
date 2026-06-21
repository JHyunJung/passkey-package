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

// SecurityIncident view — controller toView() 와 1:1 (전부 string | null).
export type IncidentView = {
  id: string;
  tenantId: string;
  tenantName: string;
  tamperedEntryId: string | null;
  type: string;
  severity: string;
  status: 'OPEN' | 'RESOLVED';
  detail: string | null;
  createdAt: string;
  createdByEmail: string;
  resolvedAt: string | null;
  resolvedByEmail: string | null;
  resolutionNote: string | null;
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
  listIncidents: async (): Promise<IncidentView[]> => {
    return api.getRaw<IncidentView[]>('/admin/api/audit/chain/incidents');
  },
  // tamperedEntryId 는 보내지 않는다 — 서버가 위변조 재검증 결과에서 도출한다.
  createIncident: async (tenantId: string): Promise<IncidentView> => {
    return api.postRaw<IncidentView>('/admin/api/audit/chain/incidents', { tenantId });
  },
  resolveIncident: async (id: string, note: string): Promise<IncidentView> => {
    return api.postRaw<IncidentView>(
      `/admin/api/audit/chain/incidents/${id}/resolve`,
      { note },
    );
  },
};
