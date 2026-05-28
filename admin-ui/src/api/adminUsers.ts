// AdminUserController returns raw JSON (no ApiResponse envelope).
// We use a local fetch helper that:
//   1. Returns undefined for 204 / empty-body 200 (suspend/activate → void)
//   2. Throws on non-2xx so the caller gets a proper error toast
//   3. Does NOT misinterpret a { success: false } error envelope as data

import { ApiError } from './types';

function getCookie(name: string): string | null {
  const m = document.cookie.match(new RegExp('(?:^|; )' + name + '=([^;]*)'));
  return m ? decodeURIComponent(m[1]) : null;
}

async function adminFetch<T>(
  method: string,
  path: string,
  body?: unknown,
): Promise<T> {
  const headers: Record<string, string> = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (method !== 'GET' && method !== 'HEAD') {
    const csrf = getCookie('XSRF-TOKEN');
    if (csrf) headers['X-XSRF-TOKEN'] = csrf;
  }
  const res = await fetch(path, {
    method,
    headers,
    credentials: 'include',
    body: body === undefined ? undefined : JSON.stringify(body),
  });

  if (res.status === 401) {
    window.location.href = '/admin/login';
    throw new ApiError(401, 'A001', 'Authentication required');
  }

  // 204 or empty body → void success
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  if (!text) return undefined as T;

  let data: unknown;
  try {
    data = JSON.parse(text);
  } catch {
    throw new ApiError(res.status, 'C999', `Non-JSON response (status ${res.status})`);
  }

  // Non-2xx or error envelope (GlobalExceptionHandler wraps errors in { success: false })
  if (!res.ok) {
    const env = data as { code?: string; message?: string };
    throw new ApiError(res.status, env.code ?? 'C999', env.message ?? `HTTP ${res.status}`);
  }

  // Success envelope check: if the server accidentally wraps in { success, data }
  // (shouldn't happen for AdminUserController, but guard anyway)
  const env = data as { success?: boolean; data?: unknown };
  if (typeof env === 'object' && env !== null && env.success === false) {
    const errEnv = data as { code?: string; message?: string };
    throw new ApiError(res.status, errEnv.code ?? 'C999', errEnv.message ?? 'Unknown error');
  }

  return data as T;
}

export type AdminUserView = {
  id: string;
  email: string;
  role: 'PLATFORM_OPERATOR' | 'RP_ADMIN';
  status: 'ACTIVE' | 'PENDING' | 'SUSPENDED';
  tenantId: string | null;
  createdAt: string;
  lastLoginAt: string | null;
  suspendedAt: string | null;
  createdBy: string | null;
  mfaEnabled: boolean;
};

export type InvitationInfo = {
  tokenPrefix: string;
  plaintextToken: string;
  acceptUrl: string;
  expiresAt: string;
};

export type InviteResponse = {
  user: AdminUserView;
  invitation: InvitationInfo;
};

export const adminUsersApi = {
  list: (): Promise<AdminUserView[]> =>
    adminFetch<AdminUserView[]>('GET', '/admin/api/admin-users'),

  invite: (body: { email: string; role: string; tenantId?: string }): Promise<InviteResponse> =>
    adminFetch<InviteResponse>('POST', '/admin/api/admin-users', body),

  // Spring @PostMapping("/{id}/suspend") returns 200 with no body
  suspend: (id: string): Promise<void> =>
    adminFetch<void>('POST', `/admin/api/admin-users/${id}/suspend`, {}),

  activate: (id: string): Promise<void> =>
    adminFetch<void>('POST', `/admin/api/admin-users/${id}/activate`, {}),

  resendInvitation: (id: string, email: string): Promise<InvitationInfo> =>
    adminFetch<InvitationInfo>(
      'POST',
      `/admin/api/admin-users/${id}/invitation/resend?email=${encodeURIComponent(email)}`,
      {},
    ),
};
