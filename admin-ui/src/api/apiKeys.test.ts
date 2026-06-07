import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { apiKeysApi } from './apiKeys';

function envelope(data: unknown) {
  return new Response(JSON.stringify({ success: true, data }), {
    status: 200, headers: { 'Content-Type': 'application/json' },
  });
}

describe('apiKeysApi', () => {
  beforeEach(() => vi.stubGlobal('fetch', vi.fn()));
  afterEach(() => vi.unstubAllGlobals());

  it('create sends provided scopes (not hardcoded ceremony)', async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      envelope({ id: 'k1', prefix: 'pk_x', plainText: 'pk_x.secret', scopes: ['registration'] }),
    );
    await apiKeysApi.create('t1', 'prod', ['registration', 'authentication'], 24);
    const [url, init] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe('/admin/api/api-keys');
    const body = JSON.parse(init.body);
    expect(body.scopes).toEqual(['registration', 'authentication']);
    expect(body.scopes).not.toContain('ceremony');
    expect(body.expiresInMonths).toBe(24);
  });

  it('create with null expiresInMonths sends null (무기한)', async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      envelope({ id: 'k1', prefix: 'pk_x', plainText: 'pk_x.secret', scopes: ['registration'], expiresAt: null }),
    );
    await apiKeysApi.create('t1', 'prod', ['registration'], null);
    const [, init] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    const body = JSON.parse(init.body);
    expect(body.expiresInMonths).toBeNull();
  });

  it('rotate posts to /{id}/rotate and returns rotate response', async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      envelope({ id: 'k1', plaintextKey: 'pk_x.new', prefix: 'pk_x', scopes: ['registration'], oldKeyExpiresAt: '2026-06-01T05:32:00Z' }),
    );
    const res = await apiKeysApi.rotate('k1');
    const [url, init] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe('/admin/api/api-keys/k1/rotate');
    expect(init.method).toBe('POST');
    expect(res.plaintextKey).toBe('pk_x.new');
    expect(res.oldKeyExpiresAt).toBe('2026-06-01T05:32:00Z');
  });
});
