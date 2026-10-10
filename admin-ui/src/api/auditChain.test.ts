import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { auditChainApi } from './auditChain';

describe('auditChainApi.verifyTenant', () => {
  beforeEach(() => vi.stubGlobal('fetch', vi.fn()));
  afterEach(() => vi.unstubAllGlobals());

  it('서버는 envelope 없는 raw JSON 을 돌려준다 — 그대로 읽는다(개요 감사 체인 카드가 "확인 중"에 머물던 문제)', async () => {
    const body = { tenantId: 't1', intact: true, verifiedAt: '2026-10-10T17:45:16Z' };
    (fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    await expect(auditChainApi.verifyTenant('t1')).resolves.toEqual(body);
    expect((fetch as ReturnType<typeof vi.fn>).mock.calls[0][0]).toBe('/admin/api/audit/chain/verify?tenantId=t1');
  });
});
