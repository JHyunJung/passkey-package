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

/**
 * MDS Status 스트립에 그릴 최근 실행 수. 서버 MdsHistoryService.recent(limit) 는 1–50 으로 제한하고
 * started_at DESC(최신 먼저)로 돌려준다 — 서버 변경 없이 ?limit=30 으로 요청한다.
 */
export const MDS_HISTORY_STRIP_LIMIT = 30;

export const mdsStatusApi = {
  get: (): Promise<MdsStatus> => api.get<MdsStatus>('/admin/api/mds/status'),
  sync: (): Promise<MdsSyncResult> => api.post<MdsSyncResult>('/admin/api/mds/sync', {}),
  history: (limit = 5): Promise<MdsHistoryRow[]> =>
    api.get<MdsHistoryRow[]>(`/admin/api/mds/history?limit=${limit}`),
};
