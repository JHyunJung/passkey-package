import { ApiError } from './types';

function getCookie(name: string): string | null {
  const m = document.cookie.match(new RegExp('(?:^|; )' + name + '=([^;]*)'));
  return m ? decodeURIComponent(m[1]) : null;
}

// MFA-only POST — 401 (invalid_code) must surface as ApiError WITHOUT the
// shared client's login redirect, so the challenge/account screens can show
// a retry toast instead of bouncing to /admin/login.
async function mfaPost<T>(path: string, body: unknown): Promise<T> {
  const csrf = getCookie('XSRF-TOKEN');
  const res = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...(csrf ? { 'X-XSRF-TOKEN': csrf } : {}) },
    credentials: 'include',
    body: JSON.stringify(body),
  });
  let json: unknown = null;
  try { json = await res.json(); } catch { /* empty body ok */ }
  if (!res.ok) {
    const err = (json as { error?: string })?.error ?? 'error';
    throw new ApiError(res.status, err, err);
  }
  return json as T;
}

export interface EnrollResponse {
  secret: string;
  otpauthUri: string;
}

export const mfaApi = {
  enroll: () => mfaPost<EnrollResponse>('/admin/api/mfa/enroll', {}),
  confirm: (code: string) => mfaPost<{ confirmed: boolean }>('/admin/api/mfa/confirm', { code }),
  verify: (code: string) => mfaPost<{ verified: boolean }>('/admin/api/mfa/verify', { code }),
  disable: (code: string) => mfaPost<{ disabled: boolean }>('/admin/api/mfa/disable', { code }),
};
