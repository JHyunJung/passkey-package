// ActivityPage (pages-4.jsx) 이 기대하는 형태.
// 디자인 data.js AUDIT_EVENTS + TENANTS 구조 그대로.

export type ActivityEvent = {
  id: string;
  ts: string;
  tenantId: string;
  tenantName: string;
  tenantSlug: string;
  type: string;           // CREDENTIAL_AUTHENTICATED, API_KEY_ISSUED, …
  actorType: string;      // RP_SERVICE | ADMIN
  actorId: string | null;
  subjectType: string;
  subjectId: string;
  payload: Record<string, unknown>;
};

export type ActivityKpi = {
  events24h: number;
  ops24h: number;
  security24h: number;
  p95Ms: number;
};

export type TopTenant = {
  tenantId: string;
  tenantName: string;
  tenantSlug: string;
  count: number;
};

// Raw events — mirrors data.js AUDIT_EVENTS, spread across tenants
export const activityEvents: ActivityEvent[] = [
  {
    id: 'ev_001',
    ts: '2026-05-28T07:31:55Z',
    tenantId: '7f00dead-0000-0000-0000-00000ace0001',
    tenantName: 'Acme Corp',
    tenantSlug: 'acme-corp',
    type: 'CREDENTIAL_AUTHENTICATED',
    actorType: 'RP_SERVICE',
    actorId: 'ak_aB3xY7Q9',
    subjectType: 'CREDENTIAL',
    subjectId: 'Cr3d_8Tg2hPq7vN3jL',
    payload: { externalUserId: 'u_889201', origin: 'https://acme.example.com', uvFlag: true, ip: '203.0.113.42' },
  },
  {
    id: 'ev_002',
    ts: '2026-05-28T07:30:22Z',
    tenantId: '7f00dead-0000-0000-0000-0000f00c0001',
    tenantName: 'Foo Corp',
    tenantSlug: 'foo-corp',
    type: 'CREDENTIAL_AUTHENTICATED',
    actorType: 'RP_SERVICE',
    actorId: 'ak_fB4yZ8R1',
    subjectType: 'CREDENTIAL',
    subjectId: 'Cr3d_Q1aS33XdRtY4z',
    payload: { externalUserId: 'u_412903', origin: 'https://foo.example.com', uvFlag: true, ip: '198.51.100.18' },
  },
  {
    id: 'ev_003',
    ts: '2026-05-28T07:28:01Z',
    tenantId: '7f00dead-0000-0000-0000-00000ace0001',
    tenantName: 'Acme Corp',
    tenantSlug: 'acme-corp',
    type: 'CREDENTIAL_REGISTERED',
    actorType: 'RP_SERVICE',
    actorId: 'ak_zZ91qq2P',
    subjectType: 'CREDENTIAL',
    subjectId: 'Cr3d_W2eRtY55Hh99Pp',
    payload: { externalUserId: 'u_771203', aaguid: 'ea9b8d66-4d01-1d21-3ce4-b6b48cb575d4', transports: ['internal', 'hybrid'] },
  },
  {
    id: 'ev_004',
    ts: '2026-05-28T06:51:18Z',
    tenantId: '7f00dead-0000-0000-0000-0000ba1c0001',
    tenantName: 'Bar Corp',
    tenantSlug: 'bar-corp',
    type: 'SIGNATURE_COUNTER_REGRESSION',
    actorType: 'RP_SERVICE',
    actorId: 'ak_bC5wA2S3',
    subjectType: 'CREDENTIAL',
    subjectId: 'Cr3d_M2nB9pTzC55Vh',
    payload: { previous: 91, received: 89, decision: 'REJECTED', policy: 'STRICT' },
  },
  {
    id: 'ev_005',
    ts: '2026-05-28T05:18:44Z',
    tenantId: '7f00dead-0000-0000-0000-00000ace0001',
    tenantName: 'Acme Corp',
    tenantSlug: 'acme-corp',
    type: 'API_KEY_ISSUED',
    actorType: 'ADMIN',
    actorId: 'adm_jhyun_01',
    subjectType: 'API_KEY',
    subjectId: 'ak_aB3xY7Q9',
    payload: { name: 'production', issuedBy: 'jhyun@crosscert.com' },
  },
  {
    id: 'ev_006',
    ts: '2026-05-28T05:00:01Z',
    tenantId: '7f00dead-0000-0000-0000-0000f00c0001',
    tenantName: 'Foo Corp',
    tenantSlug: 'foo-corp',
    type: 'ATTESTATION_TRUST_FAILED',
    actorType: 'RP_SERVICE',
    actorId: 'ak_zZ91qq2P',
    subjectType: 'CREDENTIAL',
    subjectId: '—',
    payload: { aaguid: '00000000-0000-0000-0000-000000000000', reason: 'AAGUID not in allowlist', policy: 'ALLOWLIST' },
  },
  {
    id: 'ev_007',
    ts: '2026-05-27T22:14:00Z',
    tenantId: '7f00dead-0000-0000-0000-00000ace0001',
    tenantName: 'Acme Corp',
    tenantSlug: 'acme-corp',
    type: 'WEBAUTHN_CONFIG_UPDATED',
    actorType: 'ADMIN',
    actorId: 'adm_jhyun_01',
    subjectType: 'TENANT',
    subjectId: '7f00dead-0000-0000-0000-00000ace0001',
    payload: { changes: { origins: { added: ['https://staging.acme.example.com'] } } },
  },
  {
    id: 'ev_008',
    ts: '2026-05-27T20:18:09Z',
    tenantId: '7f00dead-0000-0000-0000-0000ba1c0001',
    tenantName: 'Bar Corp',
    tenantSlug: 'bar-corp',
    type: 'CREDENTIAL_REVOKED',
    actorType: 'ADMIN',
    actorId: 'adm_kim_iam',
    subjectType: 'CREDENTIAL',
    subjectId: 'Cr3d_R4dT88vWqAa11',
    payload: { reason: 'user reported lost device' },
  },
  {
    id: 'ev_009',
    ts: '2026-05-27T15:08:22Z',
    tenantId: '7f00dead-0000-0000-0000-00000ace0001',
    tenantName: 'Acme Corp',
    tenantSlug: 'acme-corp',
    type: 'API_KEY_REVOKED',
    actorType: 'ADMIN',
    actorId: 'adm_kim_iam',
    subjectType: 'API_KEY',
    subjectId: 'ak_oldKeyAA',
    payload: { name: 'old-rotated', reason: 'scheduled rotation' },
  },
  {
    id: 'ev_010',
    ts: '2026-05-27T11:11:11Z',
    tenantId: '7f00dead-0000-0000-0000-00000ace0001',
    tenantName: 'Acme Corp',
    tenantSlug: 'acme-corp',
    type: 'ATTESTATION_POLICY_UPDATED',
    actorType: 'ADMIN',
    actorId: 'adm_jhyun_01',
    subjectType: 'TENANT',
    subjectId: '7f00dead-0000-0000-0000-00000ace0001',
    payload: { mode: 'ALLOWLIST', added: 2 },
  },
  {
    id: 'ev_011',
    ts: '2026-05-27T09:05:33Z',
    tenantId: '00000000-0000-0000-0000-00000000c0de',
    tenantName: 'Demo RP',
    tenantSlug: 'demo-rp',
    type: 'CREDENTIAL_REGISTERED',
    actorType: 'RP_SERVICE',
    actorId: 'ak_demo01',
    subjectType: 'CREDENTIAL',
    subjectId: 'Cr3d_Demo001xXxX',
    payload: { externalUserId: 'u_demo01', aaguid: 'ea9b8d66-4d01-1d21-3ce4-b6b48cb575d4', transports: ['internal'] },
  },
  {
    id: 'ev_012',
    ts: '2026-05-27T08:44:21Z',
    tenantId: '7f00dead-0000-0000-0000-0000f00c0001',
    tenantName: 'Foo Corp',
    tenantSlug: 'foo-corp',
    type: 'CREDENTIAL_AUTHENTICATED',
    actorType: 'RP_SERVICE',
    actorId: 'ak_fB4yZ8R1',
    subjectType: 'CREDENTIAL',
    subjectId: 'Cr3d_K9wP21nLm8Xs2',
    payload: { externalUserId: 'u_889201', origin: 'https://foo.example.com', uvFlag: true, ip: '203.0.113.11' },
  },
  {
    id: 'ev_013',
    ts: '2026-05-27T04:22:10Z',
    tenantId: '7f00dead-0000-0000-0000-0000ba1c0001',
    tenantName: 'Bar Corp',
    tenantSlug: 'bar-corp',
    type: 'API_KEY_ISSUED',
    actorType: 'ADMIN',
    actorId: 'adm_park_op',
    subjectType: 'API_KEY',
    subjectId: 'ak_bar_prod',
    payload: { name: 'bar-production', issuedBy: 'park.ops@crosscert.com' },
  },
  {
    id: 'ev_014',
    ts: '2026-05-26T23:55:02Z',
    tenantId: '7f00dead-0000-0000-0000-00000ace0001',
    tenantName: 'Acme Corp',
    tenantSlug: 'acme-corp',
    type: 'CREDENTIAL_AUTHENTICATED',
    actorType: 'RP_SERVICE',
    actorId: 'ak_aB3xY7Q9',
    subjectType: 'CREDENTIAL',
    subjectId: 'Cr3d_Z8mLp1xRsTu2Hh',
    payload: { externalUserId: 'u_771203', origin: 'https://acme.example.com', uvFlag: false, ip: '10.0.0.5' },
  },
  {
    id: 'ev_015',
    ts: '2026-05-26T19:30:47Z',
    tenantId: '7f00dead-0000-0000-0000-0000f00c0001',
    tenantName: 'Foo Corp',
    tenantSlug: 'foo-corp',
    type: 'SIGNATURE_COUNTER_REGRESSION',
    actorType: 'RP_SERVICE',
    actorId: 'ak_fB4yZ8R1',
    subjectType: 'CREDENTIAL',
    subjectId: 'Cr3d_J7uY12pNqXzZ4',
    payload: { previous: 45, received: 43, decision: 'REJECTED', policy: 'STRICT' },
  },
];

// KPI values — pages-4 computes these dynamically; we pre-compute for convenience
export const activityKpi: ActivityKpi = {
  events24h: 4823,
  ops24h:    312,
  security24h: 7,
  p95Ms:     42,
};

export const topTenants: TopTenant[] = [
  { tenantId: '7f00dead-0000-0000-0000-00000ace0001', tenantName: 'Acme Corp',  tenantSlug: 'acme-corp',  count: 1868 },
  { tenantId: '7f00dead-0000-0000-0000-0000f00c0001', tenantName: 'Foo Corp',   tenantSlug: 'foo-corp',   count: 1203 },
  { tenantId: '7f00dead-0000-0000-0000-0000ba1c0001', tenantName: 'Bar Corp',   tenantSlug: 'bar-corp',   count:  894 },
  { tenantId: '00000000-0000-0000-0000-00000000c0de', tenantName: 'Demo RP',    tenantSlug: 'demo-rp',    count:   58 },
];

export const activityFixture = {
  events:     activityEvents,
  kpi:        activityKpi,
  topTenants,
};
