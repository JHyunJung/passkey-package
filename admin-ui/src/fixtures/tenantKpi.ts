// 서버에 없는 KPI 필드 더미값.
// 알려진 tenant 들 (acme/foo/bar/demo-rp) 에 의미있는 값.
// 기타 tenant 는 default 0/null.

export type TenantKpi = {
  credentials: number;
  apiKeys: number;
  lastEventAt: string | null;
};

export const tenantKpiFixture: Record<string, TenantKpi> = {
  // 'acme-corp' — R__dev_seed 의 결정적 UUID 7F00DEAD00000000000000000ACE0001
  '7f00dead-0000-0000-0000-00000ace0001': {
    credentials: 14823, apiKeys: 3,
    lastEventAt: '2026-05-28T03:42:12Z',
  },
  // 'foo-corp' — 7F00DEAD0000000000000000F00C0001
  '7f00dead-0000-0000-0000-0000f00c0001': {
    credentials: 92471, apiKeys: 5,
    lastEventAt: '2026-05-28T03:51:00Z',
  },
  // 'bar-corp' — 7F00DEAD0000000000000000BA1C0001
  '7f00dead-0000-0000-0000-0000ba1c0001': {
    credentials: 4108, apiKeys: 2,
    lastEventAt: '2026-05-28T03:30:00Z',
  },
  // 'demo-rp' — V11 의 결정적 UUID
  '00000000-0000-0000-0000-00000000c0de': {
    credentials: 1, apiKeys: 1,
    lastEventAt: '2026-05-27T15:00:00Z',
  },
};

export function getTenantKpi(id: string): TenantKpi {
  return tenantKpiFixture[id] ?? { credentials: 0, apiKeys: 0, lastEventAt: null };
}
