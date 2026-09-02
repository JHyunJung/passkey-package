import { api } from './client';
import { adminFetch, type AdminUserView } from './adminUsers';

export type SignupRequestView = {
  id: string;
  email: string;
  reason: string | null;
  requestedAt: string;
};

/**
 * 가입 요청·승인 API.
 * - request: 공개(permitAll) — 항상 202 {accepted:true}. envelope 없음 → postRaw.
 * - list/approve/reject: PLATFORM_OPERATOR 전용, raw JSON → adminFetch.
 */
export const signupRequestsApi = {
  request: (body: { email: string; password: string; reason?: string }) =>
    api.postRaw<{ accepted: boolean }>('/admin/api/signup-requests', body),

  list: (): Promise<SignupRequestView[]> =>
    adminFetch<SignupRequestView[]>('GET', '/admin/api/signup-requests'),

  approve: (id: string, body: { role: string; tenantIds: string[] }): Promise<AdminUserView> =>
    adminFetch<AdminUserView>('POST', `/admin/api/signup-requests/${id}/approve`, body),

  reject: (id: string): Promise<void> =>
    adminFetch<void>('POST', `/admin/api/signup-requests/${id}/reject`, {}),
};
