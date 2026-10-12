// admin-ui/src/pages/AuditChainPage.backfillGuard.test.tsx
// runBackfill 의 위변조 가드 회귀 테스트: 위변조가 처음 나타난 렌더에서는 다이얼로그가 UI 로 닿지 않으므로
// ConfirmDialog 를 가로채 onConfirm 을 직접 호출한다.
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import { renderWithDataRouter } from '@/test/renderWithDataRouter';
import type { ChainOverview } from '@/api/auditChainMonitor';

const captured: { onConfirm?: () => Promise<void> | void } = {};

vi.mock('@/ui', async () => {
  const actual = await vi.importActual<typeof import('@/ui')>('@/ui');
  return {
    ...actual,
    ConfirmDialog: (props: { onConfirm: () => Promise<void> | void }) => {
      captured.onConfirm = props.onConfirm;
      return null;
    },
  };
});
vi.mock('@/api/auditChainMonitor', () => ({
  auditChainMonitorApi: {
    overview: vi.fn(), backfill: vi.fn(), listIncidents: vi.fn(), createIncident: vi.fn(), resolveIncident: vi.fn(),
  },
}));
vi.mock('@/api/auditChain', () => ({ auditChainApi: { verifyTenant: vi.fn() } }));
vi.mock('@/api/monthlyReport', () => ({ monthlyReportApi: { download: vi.fn() } }));

import { auditChainMonitorApi } from '@/api/auditChainMonitor';
import { ToastHost } from '@/ui';
import AuditChainPage from './AuditChainPage';

const tamperedOverview: ChainOverview = {
  verifiedAt: new Date().toISOString(), windowHours: 24, bucketSizeMinutes: 60,
  totals: { tenantsIntact: 1, tenantsTotal: 2, tenantsTampered: 1, verifiedRows: 2400, verificationMs: 42 },
  tenants: [
    { tenantId: 't1', tenantName: 'Acme', intact: true, verifiedRows: 1200, buckets: [], tamperedEntryId: null },
    { tenantId: 't2', tenantName: 'Globex', intact: false, verifiedRows: 1200, buckets: [], tamperedEntryId: 'entry-0000-abcdef12' },
  ],
} as ChainOverview;

beforeEach(() => {
  captured.onConfirm = undefined;
  vi.mocked(auditChainMonitorApi.backfill).mockReset();
  vi.mocked(auditChainMonitorApi.overview).mockReset().mockResolvedValue(tamperedOverview);
  vi.mocked(auditChainMonitorApi.listIncidents).mockReset().mockResolvedValue([]);
});

describe('AuditChainPage 백필 위변조 가드', () => {
  it('위변조가 있는 상태에서 onConfirm 이 호출돼도 backfill 은 실행되지 않는다', async () => {
    vi.mocked(auditChainMonitorApi.backfill).mockResolvedValue({ tenantsProcessed: 1, rowsUpdated: 1, rowsSkipped: 0 } as never);
    renderWithDataRouter(<ToastHost><AuditChainPage /></ToastHost>, { initialPath: '/audit-chain' });
    await screen.findByRole('alert');
    await waitFor(() => expect(captured.onConfirm).toBeDefined());
    await captured.onConfirm!();
    expect(auditChainMonitorApi.backfill).not.toHaveBeenCalled();
  });
});
