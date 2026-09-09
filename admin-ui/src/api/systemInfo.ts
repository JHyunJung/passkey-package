import { api } from './client';

export type SystemInfoComponent = {
  name: string;
  version: string;
  status: 'OK' | 'DOWN' | 'DEGRADED';
  note: string | null;
};

export type SystemInfoHost = {
  /** 활성 Spring 프로필을 쉼표로 이은 값 — 없으면 "default". */
  environment: string;
};

export type SystemInfoData = {
  serverVersion: string;
  /** 빌드 시각(KST) — build-info 가 없으면 null. */
  deployedAt: string | null;
  uptimeDays: number;
  host: SystemInfoHost;
  components: SystemInfoComponent[];
};

export const systemInfoApi = {
  get: (): Promise<SystemInfoData> =>
    api.get<SystemInfoData>('/admin/api/system/info'),
};
