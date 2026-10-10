// admin-ui/src/pages/tenant/TenantOverview.test.tsx
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { screen, fireEvent, cleanup, act, waitFor } from '@testing-library/react';
import { ToastHost } from '@/ui';
import { renderWithDataRouter } from '@/test/renderWithDataRouter';
import type { Tenant } from '@/api/designTypes';

vi.mock('@/api/auditChain', () => ({ auditChainApi: { verifyTenant: vi.fn() } }));
vi.mock('@/api/auditChainMonitor', () => ({ auditChainMonitorApi: { overview: vi.fn() } }));
vi.mock('@/api/funnel', () => ({ funnelApi: { get: vi.fn() } }));
vi.mock('@/api/activity', () => ({ activityApi: { fetch: vi.fn() } }));

import { auditChainApi } from '@/api/auditChain';
import { auditChainMonitorApi } from '@/api/auditChainMonitor';
import { funnelApi } from '@/api/funnel';
import { activityApi } from '@/api/activity';
import TenantOverview from './TenantOverview';

const T: Tenant = {
  id: 't-1', name: 'Acme', slug: 'acme', rpId: 'passkey.acme.com', status: 'ACTIVE', credentials: 1234, apiKeys: 3,
  lastEventAt: null, createdAt: '2026-03-01T00:00:00Z', userVerification: 'PREFERRED', attestationConveyance: 'NONE', webauthnTimeoutMs: 60000,
};
const OVERVIEW = {
  verifiedAt: new Date().toISOString(), windowHours: 24, bucketSizeMinutes: 60,
  totals: { tenantsIntact: 1, tenantsTotal: 1, tenantsTampered: 0, verifiedRows: 10, verificationMs: 3 },
  tenants: [{ tenantId: 't-1', tenantName: 'Acme', intact: true, verifiedRows: 10, buckets: [], tamperedEntryId: null }],
};

function renderOverview(props: { tenant?: Tenant; isPlatformOperator?: boolean; expiringKeyCount?: number } = {}) {
  return renderWithDataRouter(
    <ToastHost><TenantOverview tenant={props.tenant ?? T} isPlatformOperator={props.isPlatformOperator} expiringKeyCount={props.expiringKeyCount} /></ToastHost>,
    { initialPath: '/tenants/t-1?tab=overview' },
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  (auditChainApi.verifyTenant as ReturnType<typeof vi.fn>).mockResolvedValue({ tenantId: 't-1', intact: true, tamperedEntryId: null, verifiedAt: new Date().toISOString() });
  (auditChainMonitorApi.overview as ReturnType<typeof vi.fn>).mockResolvedValue(OVERVIEW);
  (funnelApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({
    windowDays: 7, conversion: 0, series: [], byEventType: [],
    registration: { attempts: 10, success: 9, ratio: 0.9 },
    authentication: { attempts: 200, success: 190, ratio: 0.95 },
  });
  (activityApi.fetch as ReturnType<typeof vi.fn>).mockResolvedValue({
    kpi: {}, top5: [],
    feed: [
      { id: 'e1', action: 'CREDENTIAL_AUTHENTICATED', actorEmail: '', targetType: null, targetId: 'cred-0123456789abcdef', tenantId: 't-1', tenantSlug: 'acme', createdAt: new Date().toISOString(), category: 'ops' },
      { id: 'e2', action: 'API_KEY_ISSUED', actorEmail: 'op@x.com', targetType: null, targetId: 'k1', tenantId: 'other', tenantSlug: 'other', createdAt: new Date().toISOString(), category: 'ops' },
    ],
  });
});
afterEach(cleanup);

describe('TenantOverview', () => {
  it('KPI 4개와 요약 카드 3개', async () => {
    renderOverview({ expiringKeyCount: 2 });
    expect(screen.getByText('등록 Credential')).toBeInTheDocument();
    expect(screen.getByText('1,234')).toBeInTheDocument();
    expect(screen.getByText('만료 임박 2')).toHaveClass('kpi__hint--warn');
    expect(await screen.findByText('90.0%')).toBeInTheDocument();
    expect(screen.getByText('9 / 10 시도')).toBeInTheDocument();
    expect(screen.getByText('95.0%')).toBeInTheDocument();
    for (const h of ['WebAuthn 요약', '최근 활동', '감사 체인']) expect(screen.getByRole('heading', { name: h })).toBeInTheDocument();
    expect(screen.getByText('60000 ms')).toBeInTheDocument();
  });
  it('WebAuthn 요약 origins 는 tenant 의 실제 allowedOrigins, 없으면 행을 그리지 않는다', () => {
    const { unmount } = renderOverview({ tenant: { ...T, allowedOrigins: ['http://localhost:9090', 'android:apk-key-hash:abc'] } });
    expect(screen.getByText('http://localhost:9090')).toHaveClass('tag');
    expect(screen.getByText('android:apk-key-hash:abc')).toHaveClass('tag');
    expect(screen.queryByText(`https://${T.rpId}`)).toBeNull();
    unmount();
    renderOverview({ tenant: { ...T, allowedOrigins: undefined } });
    expect(screen.queryByText('origins')).toBeNull();
  });

  it('credentialsDelta7d 는 있을 때만 보조 줄', () => {
    const { unmount } = renderOverview({ tenant: { ...T, credentialsDelta7d: 4 } });
    expect(screen.getByText('7일 +4')).toBeInTheDocument();
    unmount();
    renderOverview();
    expect(screen.queryByText(/^7일 /)).toBeNull();
  });
  it('expiringKeyCount 가 없으면 만료 임박 줄이 없다', () => {
    renderOverview();
    expect(screen.queryByText(/만료 임박/)).toBeNull();
  });
  it('최근 활동은 이 tenant 이벤트만', async () => {
    renderOverview();
    expect(await screen.findByText('CREDENTIAL_AUTHENTICATED')).toBeInTheDocument();
    expect(screen.queryByText('API_KEY_ISSUED')).toBeNull();
  });
  it('감사 체인 무결 상태', async () => {
    renderOverview();
    expect(await screen.findByText('무결 · 위변조 0건')).toBeInTheDocument();
    expect(screen.getByText('마지막 검증 방금 전')).toBeInTheDocument();
  });
  it('위변조 상태는 bad 배너', async () => {
    (auditChainApi.verifyTenant as ReturnType<typeof vi.fn>).mockResolvedValue({ tenantId: 't-1', intact: false, tamperedEntryId: 'entry-1234567890abcdef', verifiedAt: new Date().toISOString() });
    renderOverview();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('위변조 감지');
    expect(alert).toHaveTextContent('…90abcdef');
  });
  it('RP_ADMIN: overview 를 부르지 않고 스트립이 없다', async () => {
    renderOverview({ isPlatformOperator: false });
    await screen.findByText('무결 · 위변조 0건');
    expect(auditChainMonitorApi.overview).not.toHaveBeenCalled();
    expect(screen.queryByRole('img', { name: /최근 \d+회 검증/ })).toBeNull();
  });
  it('PLATFORM 이지만 recentRuns 필드가 없으면 스트립이 없다', async () => {
    renderOverview({ isPlatformOperator: true });
    await waitFor(() => expect(auditChainMonitorApi.overview).toHaveBeenCalledWith(24));
    await screen.findByText('무결 · 위변조 0건');
    expect(screen.queryByRole('img', { name: /최근 \d+회 검증/ })).toBeNull();
  });
  it('PLATFORM + recentRuns 가 있으면 요약 라벨 스트립', async () => {
    (auditChainMonitorApi.overview as ReturnType<typeof vi.fn>).mockResolvedValue({
      ...OVERVIEW,
      tenants: [{ ...OVERVIEW.tenants[0], recentRuns: [{ at: 'a', ok: true }, { at: 'b', ok: false }, { at: 'c', ok: true }] }],
    });
    renderOverview({ isPlatformOperator: true });
    expect(await screen.findByRole('img', { name: '최근 3회 검증 중 1회 위변조' })).toBeInTheDocument();
  });
  it('수동 검증은 verifyTenant 재호출, 월간 보고서는 감사 체인 화면, 자세히는 감사 로그 탭', async () => {
    const { router } = renderOverview();
    await screen.findByText('무결 · 위변조 0건');
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '수동 검증' })); });
    expect(auditChainApi.verifyTenant).toHaveBeenCalledTimes(2);
    expect(await screen.findByText('수동 검증 완료')).toBeInTheDocument();
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: /자세히/ })); });
    expect(router.state.location.search).toBe('?tab=audit');
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '월간 보고서' })); });
    expect(router.state.location.pathname).toBe('/audit-chain');
  });
});
