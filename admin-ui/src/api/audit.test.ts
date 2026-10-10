import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { auditApi } from './audit';

function envelope(data: unknown) {
  return new Response(JSON.stringify({ success: true, data }), { status: 200, headers: { 'Content-Type': 'application/json' } });
}
const row = (p: Record<string, unknown> = {}) => ({
  id: 1, actorId: 1, actorEmail: 'a@x.com', tenantId: 't1', action: 'WEBAUTHN_CONFIG_UPDATED',
  targetType: 'TENANT', targetId: 't1', payload: '{"timeoutMs":{"from":60000,"to":120000}}', createdAt: '2026-10-10T05:00:00Z', ...p,
});

describe('auditApi.list', () => {
  beforeEach(() => vi.stubGlobal('fetch', vi.fn()));
  afterEach(() => vi.unstubAllGlobals());
  const lastParams = () => new URL((fetch as ReturnType<typeof vi.fn>).mock.calls.at(-1)![0], 'http://x').searchParams;

  it('기간을 from/to(ISO date-time) 로 서버에 보낸다', async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(envelope([row()]));
    await auditApi.list('t1', 0, 50, { from: '2026-10-10T00:00:00.000Z', to: '2026-10-11T00:00:00.000Z' });
    const p = lastParams();
    expect(p.get('tenantId')).toBe('t1');
    expect(p.get('page')).toBe('0');
    expect(p.get('size')).toBe('50');
    expect(p.get('from')).toBe('2026-10-10T00:00:00.000Z');
    expect(p.get('to')).toBe('2026-10-11T00:00:00.000Z');
  });

  it('range 가 없으면 from/to 를 보내지 않는다', async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(envelope([]));
    await auditApi.list('t1');
    expect(lastParams().has('from')).toBe(false);
    expect(lastParams().has('to')).toBe(false);
  });

  it('배열 응답이면 total 키가 없다(§6 total? 미배포)', async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(envelope([row(), row({ id: 2 })]));
    const res = await auditApi.list('t1', 0, 50);
    expect(res.items).toHaveLength(2);
    expect('total' in res).toBe(false);
  });

  it('{ items, total } 응답이면 total 을 전달한다', async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(envelope({ items: [row()], total: 1234 }));
    const res = await auditApi.list('t1', 0, 50);
    expect(res.items).toHaveLength(1);
    expect(res.total).toBe(1234);
  });

  it('result 는 서버가 줄 때만 AuditEvent.result 로 전달된다', async () => {
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(envelope([row({ result: 'FAILURE' }), row({ id: 2 })]));
    const res = await auditApi.list('t1');
    expect(res.items[0].result).toBe('FAILURE');
    expect('result' in res.items[1]).toBe(false);
    expect(res.items[0].payload).toEqual({ timeoutMs: { from: 60000, to: 120000 } });
  });
});
