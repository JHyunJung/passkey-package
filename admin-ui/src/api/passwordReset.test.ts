import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { passwordResetApi } from './passwordReset';

describe('passwordResetApi', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async () =>
      new Response(JSON.stringify({ ok: true }), { status: 200, headers: { 'Content-Type': 'application/json' } }),
    ));
  });
  afterEach(() => vi.unstubAllGlobals());

  it('request posts email to /password-reset/request', async () => {
    const res = await passwordResetApi.request('a@b.com');
    expect(res).toEqual({ ok: true });
    const [url, init] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe('/admin/api/password-reset/request');
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body)).toEqual({ email: 'a@b.com' });
  });

  it('confirm posts token+newPassword to /password-reset/confirm', async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({ reset: true }), { status: 200, headers: { 'Content-Type': 'application/json' } }),
    );
    const res = await passwordResetApi.confirm('tok123', 'NewPass!23');
    expect(res).toEqual({ reset: true });
    const [url, init] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe('/admin/api/password-reset/confirm');
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body)).toEqual({ token: 'tok123', newPassword: 'NewPass!23' });
  });

  it('confirm surfaces 400 as ApiError', async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      new Response(JSON.stringify({ error: 'invalid_token', message: '토큰이 만료되었습니다' }), { status: 400, headers: { 'Content-Type': 'application/json' } }),
    );
    await expect(passwordResetApi.confirm('bad', 'x')).rejects.toMatchObject({ status: 400 });
  });
});
