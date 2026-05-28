import { api } from './client';
import type {
  AdminUserView,
  InviteRequest,
  InviteResponse,
  InvitationCheck,
  InvitationInfo,
} from './types';

export const adminUserApi = {
  list: () => api.get<AdminUserView[]>('/admin/api/admin-users'),
  invite: (body: InviteRequest) => api.post<InviteResponse>('/admin/api/admin-users', body),
  suspend: (id: string) => api.post<void>(`/admin/api/admin-users/${id}/suspend`, {}),
  activate: (id: string) => api.post<void>(`/admin/api/admin-users/${id}/activate`, {}),
  resend: (id: string, email: string) =>
    api.post<InvitationInfo>(
      `/admin/api/admin-users/${id}/invitation/resend?email=${encodeURIComponent(email)}`,
      {},
    ),
};

export const invitationApi = {
  check: (token: string) =>
    api.get<InvitationCheck>(`/admin/api/invitations/${encodeURIComponent(token)}`),
  accept: (token: string, password: string) =>
    api.post<void>(`/admin/api/invitations/${encodeURIComponent(token)}/accept`, { password }),
};
