import { api } from './client';

export type FunnelStage = {
  attempts: number;
  success: number;
  ratio: number;
};

export type FunnelSeries = {
  day: string;
  attempts: number;
  success: number;
};

export type FunnelByEventType = {
  type: string;
  n: number;
};

export type FunnelData = {
  windowDays: number;
  registration: FunnelStage;
  authentication: FunnelStage;
  conversion: number;
  series: FunnelSeries[];
  byEventType: FunnelByEventType[];
};

export const funnelApi = {
  // FunnelController returns raw DTO (no ApiResponse envelope — same as
  // AaguidPolicyController / SecurityPolicyController), so use getRaw.
  get: (tenantId: string, windowDays: 1 | 7 | 30 = 7): Promise<FunnelData> =>
    api.getRaw<FunnelData>(`/admin/api/tenants/${tenantId}/funnel?windowDays=${windowDays}`),
};
