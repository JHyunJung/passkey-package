import { api } from './client';

export type SystemInfoComponent = {
  name: string;
  version: string;
  status: 'OK' | 'DOWN' | 'DEGRADED';
  instances: number;
  note: string | null;
};

export type SystemInfoHost = {
  apiHostname: string;
  adminConsole: string;
  region: string;
  environment: string;
  deployMethod: string;
};

export type SystemInfoData = {
  serverVersion: string;
  deployedAt: string;
  apiP95Ms: number | null;
  apiAvgMs: number | null;
  apiP99Ms: number | null;
  uptimePercent: number | null;
  uptimeDays: number;
  uptimeIncidentMinutes: number | null;
  host: SystemInfoHost;
  components: SystemInfoComponent[];
};

export const systemInfoApi = {
  get: (): Promise<SystemInfoData> =>
    api.get<SystemInfoData>('/admin/api/system/info'),
};
