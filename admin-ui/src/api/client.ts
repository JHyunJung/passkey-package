import type {
  ApiEnvelope,
  Me,
  TenantUpdateRequest,
  TenantView,
  CredentialView,
  PageView,
  ActivityView,
  ActivityCategory,
  AuditLogView,
} from './types';
import { ApiError } from './types';

function getCookie(name: string): string | null {
  const m = document.cookie.match(new RegExp('(?:^|; )' + name + '=([^;]*)'));
  return m ? decodeURIComponent(m[1]) : null;
}

// 401 redirect 를 건너뛰어야 하는 화면인지. 로그인 화면(SPA basename '/admin'
// 의 root, 전체 경로 '/admin' 또는 '/admin/')과 비밀번호 재설정 공개 경로에서는
// /admin 으로 다시 보내면 무한 reload 가 되므로 redirect 하지 않는다.
function isOnLoginScreen(): boolean {
  if (typeof window === 'undefined') return false;
  const p = window.location.pathname;
  return p === '/admin'
    || p === '/admin/'
    || p.startsWith('/admin/forgot-password')
    || p.startsWith('/admin/reset-password');
}

// Used for endpoints that return raw JSON (no ApiResponse envelope),
// e.g. POST /admin/api/tenants/{id}/webauthn-config/diff
async function rawRequest<T>(method: string, path: string, body?: unknown): Promise<T> {
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
    // 미인증 401 → 로그인 화면(/admin)으로 리다이렉트. 로그인 화면은 SPA basename
    // '/admin' 의 root('/') 라 전체 경로는 '/admin'(또는 '/admin/'). 이미 그 위에
    // 있으면 무한 reload 방지로 redirect 하지 않고, App.tsx 의 /me catch 가 me=null 로 처리.
    if (typeof window !== 'undefined' && !isOnLoginScreen()) {
      window.location.href = '/admin';
    }
    throw new ApiError(401, 'A001', 'Authentication required');
  }
  if (!res.ok) {
    // 비-2xx (403 등) — 본문이 에러 envelope({success:false,code,message})일 수 있으나
    // raw 호출자도 실패를 throw 로 받도록 통일. envelope code/message 있으면 보존.
    // res.json() 은 한 번만 읽을 수 있으므로 여기서 throw → 아래 정상 return 엔 도달 안 함.
    let code = 'C999', message = `HTTP ${res.status}`;
    try {
      const body = await res.json() as { code?: string; message?: string; error?: string };
      code = body.code ?? body.error ?? code;
      message = body.message ?? body.error ?? message;
    } catch { /* 비-JSON 본문 */ }
    throw new ApiError(res.status, code, message);
  }
  try {
    return (await res.json()) as T;
  } catch {
    throw new ApiError(res.status, 'C999', `Non-JSON response (status ${res.status})`);
  }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
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
    // 미인증 401 → 로그인 화면(/admin)으로 리다이렉트. 로그인 화면은 SPA basename
    // '/admin' 의 root('/') 라 전체 경로는 '/admin'(또는 '/admin/'). 이미 그 위에
    // 있으면 무한 reload 방지로 redirect 하지 않고, App.tsx 의 /me catch 가 me=null 로 처리.
    if (typeof window !== 'undefined' && !isOnLoginScreen()) {
      window.location.href = '/admin';
    }
    throw new ApiError(401, 'A001', 'Authentication required');
  }
  // 204 historically meant "void success"; Phase 4 admin DELETE returns 200
  // with an empty-data envelope, but keep the 204 short-circuit so any
  // non-envelope responses (legacy or static) still work.
  if (res.status === 204) return undefined as T;

  let envelope: ApiEnvelope<T>;
  try {
    envelope = (await res.json()) as ApiEnvelope<T>;
  } catch {
    throw new ApiError(res.status, 'C999', `Non-JSON response (status ${res.status})`);
  }

  if (!envelope.success) {
    throw new ApiError(
      res.status,
      envelope.code ?? 'C999',
      envelope.message ?? 'Unknown error',
      envelope.error?.fieldErrors,
      envelope.traceId
    );
  }
  return envelope.data as T;
}

export const api = {
  get:     <T>(path: string)               => request<T>('GET',    path),
  getRaw:  <T>(path: string)               => rawRequest<T>('GET',  path),
  post:    <T>(path: string, body: unknown) => request<T>('POST',   path, body),
  postRaw: <T>(path: string, body: unknown) => rawRequest<T>('POST', path, body),
  put:     <T>(path: string, body: unknown) => request<T>('PUT',    path, body),
  putRaw:  <T>(path: string, body: unknown) => rawRequest<T>('PUT',  path, body),
  delete:  <T>(path: string)               => request<T>('DELETE', path),
  loginForm: async (email: string, password: string) => {
    const csrf = getCookie('XSRF-TOKEN');
    const form = new URLSearchParams();
    form.set('email', email);
    form.set('password', password);
    const res = await fetch('/admin/login', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded',
        ...(csrf ? { 'X-XSRF-TOKEN': csrf } : {}),
      },
      credentials: 'include',
      body: form.toString(),
    });
    return res.ok;
  },
};

export const getMe = () => api.get<Me>('/admin/api/me');

export const updateTenant = (id: string, req: TenantUpdateRequest) =>
  api.put<TenantView>(`/admin/api/tenants/${id}`, req);

export const listCredentials = (
  tenantId: string,
  params: { page: number; size: number; q?: string },
): Promise<PageView<CredentialView>> => {
  const qs = new URLSearchParams({
    page: String(params.page),
    size: String(params.size),
    ...(params.q ? { q: params.q } : {}),
  });
  return api.get<PageView<CredentialView>>(
    `/admin/api/tenants/${tenantId}/credentials?${qs}`,
  );
};

export const revokeCredential = (tenantId: string, credentialId: string) =>
  api.delete<void>(
    `/admin/api/tenants/${tenantId}/credentials/${encodeURIComponent(credentialId)}`,
  );

export const getActivity = (params: { sinceId?: string; category?: ActivityCategory }) => {
  const qs = new URLSearchParams();
  if (params.sinceId) qs.set('sinceId', params.sinceId);
  if (params.category && params.category !== 'all') qs.set('category', params.category);
  const q = qs.toString();
  return api.get<ActivityView>(`/admin/api/activity${q ? '?' + q : ''}`);
};

export const getAuditLog = (params: {
  action?: string;
  actorId?: string;
  tenantId?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}) => {
  const qs = new URLSearchParams();
  if (params.action)   qs.set('action', params.action);
  if (params.actorId)  qs.set('actorId', params.actorId);
  if (params.tenantId) qs.set('tenantId', params.tenantId);
  if (params.from)     qs.set('from', params.from);
  if (params.to)       qs.set('to', params.to);
  if (params.page !== undefined) qs.set('page', String(params.page));
  if (params.size !== undefined) qs.set('size', String(params.size));
  return api.get<AuditLogView[]>(`/admin/api/audit?${qs}`);
};
