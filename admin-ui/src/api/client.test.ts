import { describe, it, expect, vi, afterEach } from 'vitest';
import { api } from './client';
import { RequestTimeoutError } from './types';
import { isTransientError } from '@/lib/transientError';
import { describeError } from '@/lib/describeError';

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

describe('요청 시간 제한', () => {
  afterEach(() => vi.useRealTimers());

  /** signal 이 abort 될 때까지 끝나지 않는 fetch — abort 되면 AbortError 로 reject. */
  function hangingFetch() {
    return vi.fn((_url: string, init?: RequestInit) => new Promise<Response>((_res, rej) => {
      init?.signal?.addEventListener('abort', () => rej(new DOMException('aborted', 'AbortError')));
    }));
  }

  it('기본 30초 안에 응답이 없으면 요청을 중단하고 일시 오류(시간 초과)로 reject', async () => {
    vi.useFakeTimers();
    const fetchMock = hangingFetch();
    vi.stubGlobal('fetch', fetchMock);
    const p = api.get('/admin/api/x');
    const settled = vi.fn();
    p.catch(settled);
    await vi.advanceTimersByTimeAsync(29_999);
    expect(settled).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1);
    const err = await p.catch((e: unknown) => e);
    expect(err).toBeInstanceOf(RequestTimeoutError);
    expect(isTransientError(err)).toBe(true);
    expect(describeError(err).title).toBe('서버가 잠시 응답하지 않습니다');
    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect(init.signal?.aborted).toBe(true);
  });

  it('raw 요청과 폼 로그인에도 시간 제한이 적용된다', async () => {
    vi.useFakeTimers();
    vi.stubGlobal('fetch', hangingFetch());
    const raw = api.getRaw('/admin/api/raw').catch((e: unknown) => e);
    const login = api.loginForm('a@x.com', 'pw').catch((e: unknown) => e);
    await vi.advanceTimersByTimeAsync(30_000);
    expect(await raw).toBeInstanceOf(RequestTimeoutError);
    expect(await login).toBeInstanceOf(RequestTimeoutError);
  });

  it('호출별로 더 긴 시간 제한을 줄 수 있다', async () => {
    vi.useFakeTimers();
    vi.stubGlobal('fetch', hangingFetch());
    const p = api.postRaw('/admin/api/long', {}, { timeoutMs: 120_000 }).catch((e: unknown) => e);
    const settled = vi.fn();
    void p.then(settled);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(settled).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(60_000);
    expect(await p).toBeInstanceOf(RequestTimeoutError);
  });

  it('제때 응답하면 타이머가 남지 않는다', async () => {
    vi.useFakeTimers();
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ success: true, data: 1 }), { status: 200 })));
    await expect(api.get('/admin/api/x')).resolves.toBe(1);
    expect(vi.getTimerCount()).toBe(0);
  });
});
