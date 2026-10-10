// admin-ui/src/pages/tenant/tenantSignals.test.ts
import { describe, it, expect } from 'vitest';
import type { AaguidPolicy, ApiKey } from '@/api/designTypes';
import type { ChainOverview } from '@/api/auditChainMonitor';
import { unverifiedAaguidCount, hasUnverifiedAaguid, countExpiringKeys, pickRecentRuns, recentRunsLabel } from './tenantSignals';

const DAY = 86_400_000;
const NOW = Date.parse('2026-10-11T00:00:00Z');
const key = (over: Partial<ApiKey>): ApiKey => ({ id: 'k', prefix: 'pk_x', name: 'n', status: 'ACTIVE', createdAt: '2026-01-01T00:00:00Z', lastUsedAt: null, scopes: [], expiresAt: null, ...over });
const policy = (over: Partial<AaguidPolicy>): AaguidPolicy => ({ mode: 'ALLOWLIST', mdsStrict: false, entries: [], ...over });

describe('unverifiedAaguidCount', () => {
  it('정책이 없으면 0', () => {
    expect(unverifiedAaguidCount(null)).toBe(0);
    expect(hasUnverifiedAaguid(undefined)).toBe(false);
  });
  it('mode=ANY 면 미확인 항목이 있어도 0', () => {
    expect(unverifiedAaguidCount(policy({ mode: 'ANY', entries: [{ aaguid: 'a', note: null, mdsName: null }] }))).toBe(0);
  });
  it('ALLOWLIST/DENYLIST 에서 mdsName == null 항목 수', () => {
    const entries = [
      { aaguid: 'a', note: null, mdsName: null },
      { aaguid: 'b', note: null, mdsName: 'YubiKey 5' },
      { aaguid: 'c', note: null, mdsName: undefined as unknown as null },
    ];
    expect(unverifiedAaguidCount(policy({ mode: 'ALLOWLIST', entries }))).toBe(2);
    expect(hasUnverifiedAaguid(policy({ mode: 'DENYLIST', entries }))).toBe(true);
  });
  it('aaguid 가 빈 항목은 세지 않는다(목록에 표시되지 않으므로)', () => {
    expect(unverifiedAaguidCount(policy({ entries: [{ aaguid: '', note: null, mdsName: null }] }))).toBe(0);
  });
});

describe('countExpiringKeys', () => {
  it('30일 이내 만료 ACTIVE 키만 센다(경계 30일 포함)', () => {
    const keys = [
      key({ id: '1', expiresAt: new Date(NOW + 10 * DAY).toISOString() }),
      key({ id: '2', expiresAt: new Date(NOW + 30 * DAY).toISOString() }),
      key({ id: '3', expiresAt: new Date(NOW + 31 * DAY).toISOString() }),
      key({ id: '4', expiresAt: null }),
      key({ id: '5', status: 'REVOKED', expiresAt: new Date(NOW + 5 * DAY).toISOString() }),
      key({ id: '6', status: 'EXPIRED', expiresAt: new Date(NOW - DAY).toISOString() }),
      key({ id: '7', expiresAt: new Date(NOW - 1000).toISOString() }),
    ];
    expect(countExpiringKeys(keys, NOW)).toBe(2);
  });
  it('기간을 바꿀 수 있다', () => {
    expect(countExpiringKeys([key({ expiresAt: new Date(NOW + 10 * DAY).toISOString() })], NOW, 7)).toBe(0);
  });
});

describe('pickRecentRuns / recentRunsLabel', () => {
  const base: ChainOverview = {
    verifiedAt: '2026-10-11T00:00:00Z', windowHours: 24, bucketSizeMinutes: 60,
    totals: { tenantsIntact: 1, tenantsTotal: 1, tenantsTampered: 0, verifiedRows: 10, verificationMs: 5 },
    tenants: [{ tenantId: 't-1', tenantName: 'Acme', intact: true, verifiedRows: 10, buckets: [], tamperedEntryId: null }],
  };
  it('서버가 필드를 주지 않으면 undefined', () => {
    expect(pickRecentRuns(base, 't-1')).toBeUndefined();
  });
  it('tenant 가 없으면 undefined', () => {
    expect(pickRecentRuns(base, 'nope')).toBeUndefined();
  });
  it('필드가 있으면 그대로 반환', () => {
    const runs = [{ at: 'a', ok: true }, { at: 'b', ok: false }];
    const o: ChainOverview = { ...base, tenants: [{ ...base.tenants[0], recentRuns: runs }] };
    expect(pickRecentRuns(o, 't-1')).toEqual(runs);
  });
  it('요약 라벨', () => {
    expect(recentRunsLabel([{ at: 'a', ok: true }, { at: 'b', ok: true }])).toBe('최근 2회 검증 모두 정상');
    expect(recentRunsLabel([{ at: 'a', ok: true }, { at: 'b', ok: false }, { at: 'c', ok: true }])).toBe('최근 3회 검증 중 1회 위변조');
  });
});
