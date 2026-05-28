import { api } from './client';

// Server: MdsAdminController returns ApiResponse<MdsStatusView> envelope
// MdsStatusView record: { version: long, nextUpdate: String, fetchedAt: String }
export type MdsStatus = {
  version: number | null;
  nextUpdate: string | null;
  fetchedAt: string | null;
};

// Server: POST /admin/api/mds/sync returns ApiResponse<SyncResult>
// SyncResult record: { status, version, error }
export type MdsSyncResult = {
  status: 'SYNCED' | 'SKIPPED' | 'FAILED';
  version: number | null;
  error: string | null;
};

export const mdsStatusApi = {
  get: (): Promise<MdsStatus> =>
    api.get<MdsStatus>('/admin/api/mds/status'),

  sync: (): Promise<MdsSyncResult> =>
    api.post<MdsSyncResult>('/admin/api/mds/sync', {}),
};
