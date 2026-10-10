import { api } from './client';

export type SystemInfoComponent = {
  name: string;
  version: string;
  status: 'OK' | 'DOWN' | 'DEGRADED';
  note: string | null;
  /** 스펙 §6 ⑤ — 헬스 체크 응답 시간(ms). 서버가 내려주기 전까지 undefined → 표시하지 않는다. */
  latencyMs?: number;
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
