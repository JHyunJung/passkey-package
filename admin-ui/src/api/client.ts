function getCookie(name: string): string | null {
  const m = document.cookie.match(new RegExp('(?:^|; )' + name + '=([^;]*)'));
  return m ? decodeURIComponent(m[1]) : null;
}

interface ApiResponse<T> {
  success: boolean;
  code: string;
  message: string;
  data?: T;
  error?: { errorCode: string; fieldErrors?: unknown[] };
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
    window.location.href = '/admin/login';
    throw new Error('unauthorized');
  }
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`HTTP ${res.status}: ${text}`);
  }
  if (res.status === 204) return undefined as T;
  const envelope: ApiResponse<T> = await res.json();
  // All admin API responses are wrapped in ApiResponse. Unwrap .data so callers
  // receive the typed payload directly and don't need to know the envelope shape.
  return envelope.data as T;
}

export const api = {
  get:    <T>(path: string)               => request<T>('GET',    path),
  post:   <T>(path: string, body: unknown) => request<T>('POST',   path, body),
  put:    <T>(path: string, body: unknown) => request<T>('PUT',    path, body),
  delete: <T>(path: string)               => request<T>('DELETE', path),
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
