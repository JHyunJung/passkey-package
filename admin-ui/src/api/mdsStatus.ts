import { api } from './client';

export type MdsSuccessRate = {
  ok: number;
  total: number;
};

export type MdsStatus = {
  version: number;
  nextUpdate: string | null;
  fetchedAt: string | null;
  trustAnchorCount: number;
  trustMode: string;
  successRate30d: MdsSuccessRate;
};

export type MdsSyncResult = {
  status: 'SYNCED' | 'SKIPPED' | 'FAILED';
  version: number | null;
  error: string | null;
};

export type MdsHistoryRow = {
  id: number;
  startedAt: string;
  finishedAt: string | null;
  version: number | null;
  status: 'SYNCED' | 'SKIPPED' | 'FAILED';
  changeSummary: string | null;
  durationMs: number | null;
  errorMessage: string | null;
};

export const mdsStatusApi = {
  get: (): Promise<MdsStatus> => api.get<MdsStatus>('/admin/api/mds/status'),
  sync: (): Promise<MdsSyncResult> => api.post<MdsSyncResult>('/admin/api/mds/sync', {}),
  history: (limit = 5): Promise<MdsHistoryRow[]> =>
    api.get<MdsHistoryRow[]>(`/admin/api/mds/history?limit=${limit}`),
};
