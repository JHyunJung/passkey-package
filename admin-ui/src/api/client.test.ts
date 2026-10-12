import { describe, it, expect, vi, afterEach } from 'vitest';
import { api } from './client';

afterEach(() => vi.unstubAllGlobals());

describe('api.loginForm', () => {
  it('성공이면 { ok: true, status: 200 }, 폼 인코딩으로 /admin/login 에 보낸다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);
    await expect(api.loginForm('a@x.com', 'pw')).resolves.toEqual({ ok: true, status: 200 });
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/admin/login');
    expect(init.method).toBe('POST');
    expect(init.body).toBe('email=a%40x.com&password=pw');
  });

  it('401·503 은 ok=false 와 상태 코드를 그대로', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 401 })));
    await expect(api.loginForm('a@x.com', 'x')).resolves.toEqual({ ok: false, status: 401 });
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 503 })));
    await expect(api.loginForm('a@x.com', 'x')).resolves.toEqual({ ok: false, status: 503 });
  });

  it('네트워크 오류는 TypeError 로 던진다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));
    await expect(api.loginForm('a@x.com', 'x')).rejects.toBeInstanceOf(TypeError);
  });
});
