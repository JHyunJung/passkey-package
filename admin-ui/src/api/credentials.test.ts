import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { credentialsApi } from './credentials';

function envelope(data: unknown) {
  return new Response(JSON.stringify({ success: true, data }), {
    status: 200, headers: { 'Content-Type': 'application/json' },
  });
}

describe('credentialsApi.authEvents', () => {
  beforeEach(() => vi.stubGlobal('fetch', vi.fn()));
  afterEach(() => vi.unstubAllGlobals());

  it('calls the per-credential auth-events endpoint with page=0 and given size', async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      envelope({ content: [
        { result: 'SUCCESS', failureReason: null, signCount: 3, createdAt: '2026-06-07T05:00:00Z' },
        { result: 'FAILED', failureReason: 'SIGN_COUNT_REPLAY', signCount: 3, createdAt: '2026-06-06T05:00:00Z' },
      ], page: 0, size: 20, totalElements: 2, hasNext: false }),
    );
    const events = await credentialsApi.authEvents('t1', 'cred+slash/id', 20);
    const [url] = (fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toContain('/admin/api/tenants/t1/credentials/');
    expect(url).toContain('/auth-events?');
    expect(url).toContain('page=0');
    expect(url).toContain('size=20');
    // credentialId 가 URL-encode 되어야 함 (slash/plus 안전)
    expect(url).toContain(encodeURIComponent('cred+slash/id'));
    expect(events).toHaveLength(2);
    expect(events[0].result).toBe('SUCCESS');
    expect(events[1].failureReason).toBe('SIGN_COUNT_REPLAY');
  });
});

describe('credentialsApi.list adapter', () => {
  beforeEach(() => vi.stubGlobal('fetch', vi.fn()));
  afterEach(() => vi.unstubAllGlobals());

  it('maps attestationFormat from server CredentialView', async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(
      envelope({ content: [{
        credentialId: 'c1', userHandle: 'u1', label: null, aaguidHex: null,
        authenticatorName: null, attestationFormat: 'packed', transports: '',
        signCount: 0, lastUsedAt: null, createdAt: '2026-06-07T05:00:00Z',
      }], page: 0, size: 50, totalElements: 1, hasNext: false }),
    );
    const res = await credentialsApi.list('t1', 0, 50);
    expect(res.items[0].attestationFormat).toBe('packed');
  });
});
