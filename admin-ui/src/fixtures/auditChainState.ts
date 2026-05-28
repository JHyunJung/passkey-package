// AuditChainPage (pages-5.jsx) 이 기대하는 형태.
// pages-5 에서 useMemo 로 tenants → chainState 를 합성하는 구조를 사전 계산.
//
// IMPORTANT field-name note:
//   pages-5 reads c.id, c.name, c.slug directly (inherited from TENANTS shape).
//   The type carries both the pages-5-compatible short names AND semantic aliases.

export type TenantChainState = {
  // pages-5 reads these fields directly (matches TENANTS object shape)
  id: string;       // tenant UUID — used in onOpenTenant(c.id) and table keys
  name: string;     // display name — used in labels and monthly report rows
  slug: string;     // slug — pages-5 uses slug === "pied-piper" for tamper flag
  // semantic aliases (identical values — for downstream TypeScript consumers)
  tenantId: string;
  tenantName: string;
  tenantSlug: string;
  intact: boolean;
  // pages-5: c.rows
  rows: number;
  lastVerifiedAt: string;
  avgInterval: number;   // seconds
  // tampered entry IDs (pages-5: c.tampered)
  tampered: string[];
  // 24-bucket sparkline heights (px) for ChainSparkline — range 4..18
  sparkline: number[];
};

// Deterministic sparkline: mirrors pages-5 ChainSparkline logic
//   h = 6 + (i * 7919) % 12  — same formula, pre-computed for 24 buckets
function makeSparkline(intact: boolean): number[] {
  return Array.from({ length: 24 }, (_, i) => {
    const h = 6 + (i * 7919) % 12;
    // tampered buckets 14 & 15 get height 4 (visual "broken" indicator)
    return !intact && (i === 14 || i === 15) ? 4 : h;
  });
}

// pages-5 computes verifiedRows = floor((credentials * (4 + rowsK)) / 10)
// where rowsK = 1 + ((idx * 173) % 9)
function computeRows(credentials: number, idx: number): number {
  const rowsK = 1 + ((idx * 173) % 9);
  return Math.floor((credentials * (4 + rowsK)) / 10);
}

// Base date for lastVerifiedAt offsets (pages-5 uses Date.now() − offset)
const BASE_TS = '2026-05-28T03:00:00Z';
function verifiedAt(idx: number): string {
  // offset = (60 * (i+1) + 30) seconds — mirrors pages-5
  const offsetMs = (60 * (idx + 1) + 30) * 1000;
  return new Date(new Date(BASE_TS).getTime() - offsetMs).toISOString();
}

// Helper to build a tenant entry with both short and long field names
function tenant(
  id: string, name: string, slug: string,
  credentials: number, idx: number,
  intact: boolean, tampered: string[] = [],
): TenantChainState {
  return {
    id, name, slug,
    tenantId: id, tenantName: name, tenantSlug: slug,
    intact,
    rows: computeRows(credentials, idx),
    lastVerifiedAt: verifiedAt(idx),
    avgInterval: 60,
    tampered,
    sparkline: makeSparkline(intact),
  };
}

export const chainStateFixture = {
  tenants: [
    // Real seed tenants (idx 0–3)
    tenant('7f00dead-0000-0000-0000-00000ace0001', 'Acme Corp',  'acme-corp',  14823,  0, true),
    tenant('7f00dead-0000-0000-0000-0000f00c0001', 'Foo Corp',   'foo-corp',   92471,  1, true),
    tenant('7f00dead-0000-0000-0000-0000ba1c0001', 'Bar Corp',   'bar-corp',   4108,   2, true),
    tenant('00000000-0000-0000-0000-00000000c0de', 'Demo RP',    'demo-rp',    1,      3, true),
    // Design-only tenants (idx 4–8) — display-only, navigation shows not-found state
    tenant('tnt_01HZ8VYQ1P', 'Initech Health',  'initech-health',  3094,   4, true),
    tenant('tnt_01HXC74WPM', 'Hooli Pay',       'hooli-pay',       51208,  5, true),
    tenant('tnt_01HV9P22N4', 'Stark Industries','stark-industries', 188204, 6, true),
    tenant('tnt_01HM4DKSXV', 'Wonka Telecom',   'wonka-telecom',   22481,  7, true),
    // TAMPERED — pages-5 triggers alert when slug === "pied-piper"
    tenant('tnt_01HQA77JKL', 'Pied Piper',      'pied-piper',      412,    8, false, ['en_01H9KX2..', 'en_01H9KX9..']),
  ] as TenantChainState[],

  totals: {
    tenantsTotal:    9,
    tenantsIntact:   8,
    tenantsTampered: 1,
    // sum of rows across all tenants
    verifiedRows:
      computeRows(14823, 0) + computeRows(92471, 1) + computeRows(4108,  2) +
      computeRows(1,     3) + computeRows(3094,  4) + computeRows(51208, 5) +
      computeRows(188204,6) + computeRows(22481, 7) + computeRows(412,   8),
    verificationMs: 284,
  },

  lastVerifiedAt: BASE_TS,
  bucketSizeMinutes: 60,
};
