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
vi.mock('@/api/audit', () => ({ auditApi: { list: vi.fn() } }));

import { auditChainApi } from '@/api/auditChain';
import { auditChainMonitorApi } from '@/api/auditChainMonitor';
import { funnelApi } from '@/api/funnel';
import { activityApi } from '@/api/activity';
import { auditApi } from '@/api/audit';
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

function renderOverview(props: { tenant?: Tenant; isPlatformOperator?: boolean; expiringKeyCount?: number; chainTimeoutMs?: number } = {}) {
  return renderWithDataRouter(
    <ToastHost><TenantOverview tenant={props.tenant ?? T} isPlatformOperator={props.isPlatformOperator} expiringKeyCount={props.expiringKeyCount} chainTimeoutMs={props.chainTimeoutMs} /></ToastHost>,
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
  (auditApi.list as ReturnType<typeof vi.fn>).mockResolvedValue({
    page: 0, size: 5,
    items: [{ id: 'a1', ts: new Date().toISOString(), eventType: 'WEBAUTHN_CONFIG_UPDATED', actorType: 'ADMIN', actorId: 'rp@x.com', subjectType: 'TENANT', subjectId: 'tenant-0123456789abcdef', payload: null }],
  });
});
afterEach(cleanup);

/** 마운트 직후 시작된 비동기 로드가 act 밖에서 끝나지 않도록 settle + commit 을 flush 한다. */
const settle = () => act(async () => { await new Promise((r) => setTimeout(r, 0)); });

describe('TenantOverview', () => {
  it('KPI 4개와 요약 카드 3개', async () => {
    renderOverview({ expiringKeyCount: 2, isPlatformOperator: true });
    expect(screen.getByText('등록 Credential')).toBeInTheDocument();
    expect(screen.getByText('1,234')).toBeInTheDocument();
    expect(screen.getByText('만료 임박 2')).toHaveClass('kpi__hint--warn');
    expect(await screen.findByText('90.0%')).toBeInTheDocument();
    expect(screen.getByText('9 / 10 시도')).toBeInTheDocument();
    expect(screen.getByText('95.0%')).toBeInTheDocument();
    for (const h of ['WebAuthn 요약', '최근 활동', '감사 체인']) expect(screen.getByRole('heading', { name: h })).toBeInTheDocument();
    expect(screen.getByText('60000 ms')).toBeInTheDocument();
  });
  it('WebAuthn 요약 origins 는 tenant 의 실제 allowedOrigins, 없으면 행을 그리지 않는다', async () => {
    const { unmount } = renderOverview({ tenant: { ...T, allowedOrigins: ['http://localhost:9090', 'android:apk-key-hash:abc'] } });
    expect(screen.getByText('http://localhost:9090')).toHaveClass('tag');
    expect(screen.getByText('android:apk-key-hash:abc')).toHaveClass('tag');
    expect(screen.queryByText(`https://${T.rpId}`)).toBeNull();
    unmount();
    renderOverview({ tenant: { ...T, allowedOrigins: undefined } });
    expect(screen.queryByText('origins')).toBeNull();
    await settle();
  });

  it('credentialsDelta7d 는 있을 때만 보조 줄', async () => {
    const { unmount } = renderOverview({ tenant: { ...T, credentialsDelta7d: 4 } });
    expect(screen.getByText('7일 +4')).toBeInTheDocument();
    unmount();
    renderOverview();
    expect(screen.queryByText(/^7일 /)).toBeNull();
    await settle();
  });
  it('expiringKeyCount 가 없으면 만료 임박 줄이 없다', async () => {
    renderOverview();
    expect(screen.queryByText(/만료 임박/)).toBeNull();
    await settle();
  });
  it('PLATFORM: 최근 활동은 활동 피드에서 이 tenant 이벤트만', async () => {
    renderOverview({ isPlatformOperator: true });
    expect(await screen.findByText('CREDENTIAL_AUTHENTICATED')).toBeInTheDocument();
    expect(screen.queryByText('API_KEY_ISSUED')).toBeNull();
  });
  it('감사 체인 무결 상태', async () => {
    renderOverview({ isPlatformOperator: true });
    expect(await screen.findByText('무결 · 위변조 0건')).toBeInTheDocument();
    expect(screen.getByText('마지막 검증 방금 전')).toBeInTheDocument();
  });
  it('위변조 상태는 bad 배너', async () => {
    (auditChainApi.verifyTenant as ReturnType<typeof vi.fn>).mockResolvedValue({ tenantId: 't-1', intact: false, tamperedEntryId: 'entry-1234567890abcdef', verifiedAt: new Date().toISOString() });
    renderOverview({ isPlatformOperator: true });
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('위변조 감지');
    expect(alert).toHaveTextContent('…90abcdef');
  });
  it('RP_ADMIN: PLATFORM 전용 API(verifyTenant·활동 피드·overview)를 부르지 않고, 감사 체인 카드가 없다', async () => {
    const { router } = renderOverview({ isPlatformOperator: false });
    expect(await screen.findByText('WEBAUTHN_CONFIG_UPDATED')).toBeInTheDocument();
    expect(auditApi.list).toHaveBeenCalledWith('t-1', 0, 5);
    expect(auditChainApi.verifyTenant).not.toHaveBeenCalled();
    expect(activityApi.fetch).not.toHaveBeenCalled();
    expect(auditChainMonitorApi.overview).not.toHaveBeenCalled();
    expect(screen.queryByRole('heading', { name: '감사 체인' })).toBeNull();
    expect(screen.queryByRole('button', { name: '수동 검증' })).toBeNull();
    expect(screen.queryByRole('button', { name: '월간 보고서' })).toBeNull();
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: /전체 보기/ })); });
    expect(router.state.location.pathname).toBe('/tenants/t-1');
    expect(router.state.location.search).toBe('?tab=audit');
  });
  it('PLATFORM: 전체 보기는 활동 화면(tenant 필터)', async () => {
    const { router } = renderOverview({ isPlatformOperator: true });
    await screen.findByText('CREDENTIAL_AUTHENTICATED');
    expect(auditApi.list).not.toHaveBeenCalled();
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: /전체 보기/ })); });
    expect(router.state.location.pathname).toBe('/activity');
  });
  it('PLATFORM: 전 tenant 체인 검증(overview)을 개요에서 부르지 않는다 — recentRuns 가 서버에 생길 때까지 스트립 없음', async () => {
    renderOverview({ isPlatformOperator: true });
    await screen.findByText('무결 · 위변조 0건');
    expect(auditChainMonitorApi.overview).not.toHaveBeenCalled();
    expect(screen.queryByRole('img', { name: /최근 \d+회 검증/ })).toBeNull();
  });
  it('수동 검증은 verifyTenant 재호출, 월간 보고서는 감사 체인 화면, 자세히는 감사 로그 탭', async () => {
    const { router } = renderOverview({ isPlatformOperator: true });
    await screen.findByText('무결 · 위변조 0건');
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '수동 검증' })); });
    expect(auditChainApi.verifyTenant).toHaveBeenCalledTimes(2);
    expect(await screen.findByText('수동 검증 완료')).toBeInTheDocument();
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: /자세히/ })); });
    expect(router.state.location.search).toBe('?tab=audit');
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '월간 보고서' })); });
    expect(router.state.location.pathname).toBe('/audit-chain');
  });

  it('퍼널 실패: 성공률 KPI 는 "—" + "불러오지 못했습니다", "등록 시도 없음"으로 보이지 않고 다시 시도', async () => {
    (funnelApi.get as ReturnType<typeof vi.fn>)
      .mockRejectedValueOnce(new Error('boom'))
      .mockResolvedValueOnce({
        windowDays: 7, conversion: 0, series: [], byEventType: [],
        registration: { attempts: 10, success: 9, ratio: 0.9 },
        authentication: { attempts: 200, success: 190, ratio: 0.95 },
      });
    renderOverview({ isPlatformOperator: false });
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('퍼널 지표를 불러오지 못했습니다');
    expect(screen.getAllByText('불러오지 못했습니다')).toHaveLength(2);
    expect(screen.queryByText('최근 7일 등록 시도 없음')).toBeNull();
    expect(screen.queryByText('최근 7일 인증 시도 없음')).toBeNull();
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '다시 시도' })); });
    expect(await screen.findByText('90.0%')).toBeInTheDocument();
    expect(funnelApi.get).toHaveBeenCalledTimes(2);
  });

  it('퍼널 로딩 중에는 "등록 시도 없음"을 보이지 않는다', async () => {
    (funnelApi.get as ReturnType<typeof vi.fn>).mockReturnValue(new Promise(() => {}));
    renderOverview();
    await act(async () => { await new Promise((r) => setTimeout(r, 0)); });
    expect(screen.queryByText('최근 7일 등록 시도 없음')).toBeNull();
  });

  it('체인 검증 실패: "확인 중"에 멈추지 않고 확인 불가 + 다시 시도', async () => {
    (auditChainApi.verifyTenant as ReturnType<typeof vi.fn>)
      .mockRejectedValueOnce(new Error('boom'))
      .mockResolvedValueOnce({ tenantId: 't-1', intact: true, tamperedEntryId: null, verifiedAt: new Date().toISOString() });
    renderOverview({ isPlatformOperator: true });
    expect(await screen.findByText('검증 결과를 받지 못했습니다')).toBeInTheDocument();
    expect(screen.getByText('확인 불가')).toBeInTheDocument();
    expect(screen.queryByText('확인 중')).toBeNull();
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '다시 시도' })); });
    expect(await screen.findByText('무결 · 위변조 0건')).toBeInTheDocument();
    expect(auditChainApi.verifyTenant).toHaveBeenCalledTimes(2);
  });

  it('체인 검증 응답이 제한 시간 안에 없으면 확인 불가, "감사 로그로"는 감사 로그 탭', async () => {
    (auditChainApi.verifyTenant as ReturnType<typeof vi.fn>).mockReturnValue(new Promise(() => {}));
    const { router } = renderOverview({ isPlatformOperator: true, chainTimeoutMs: 20 });
    expect(screen.getByText('확인 중')).toBeInTheDocument();
    expect(await screen.findByText('검증 결과를 받지 못했습니다')).toBeInTheDocument();
    expect(screen.getByText(/응답이 없었습니다/)).toBeInTheDocument();
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '감사 로그로' })); });
    expect(router.state.location.search).toBe('?tab=audit');
  });

  it('수동 검증 중에는 "검증 중…" 비활성, 연타해도 한 번만 호출', async () => {
    renderOverview({ isPlatformOperator: true });
    await screen.findByText('무결 · 위변조 0건');
    (auditChainApi.verifyTenant as ReturnType<typeof vi.fn>).mockReturnValue(new Promise(() => {}));
    const btn = screen.getByRole('button', { name: '수동 검증' });
    fireEvent.click(btn);
    const busy = screen.getByRole('button', { name: '검증 중…' });
    expect(busy).toBeDisabled();
    fireEvent.click(busy);
    expect(auditChainApi.verifyTenant).toHaveBeenCalledTimes(2); // 처음 로드 1 + 수동 1
    expect(screen.getByRole('button', { name: '월간 보고서' })).toBeDisabled();
    await waitFor(() => expect(screen.getByText('검증 중')).toBeInTheDocument());
  });

  it('수동 검증이 제한 시간 안에 응답하지 않으면 버튼을 풀고 이전 결과를 유지, 늦은 응답은 무시', async () => {
    let late!: (v: unknown) => void;
    renderOverview({ isPlatformOperator: true, chainTimeoutMs: 20 });
    await screen.findByText('무결 · 위변조 0건');
    (auditChainApi.verifyTenant as ReturnType<typeof vi.fn>).mockReturnValue(new Promise((r) => { late = r; }));
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '수동 검증' })); });
    expect(screen.getByRole('button', { name: '검증 중…' })).toBeDisabled();
    expect(await screen.findByText('수동 검증 실패')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '수동 검증' })).toBeEnabled();
    expect(screen.getByText('무결 · 위변조 0건')).toBeInTheDocument();
    await act(async () => { late({ tenantId: 't-1', intact: false, tamperedEntryId: 'x', verifiedAt: new Date().toISOString() }); await new Promise((r) => setTimeout(r, 0)); });
    expect(screen.queryByText('위변조 감지')).toBeNull();
    expect(screen.getByText('무결 · 위변조 0건')).toBeInTheDocument();
  });

  it('수동 검증 실패도 버튼을 풀고 이전 결과를 유지', async () => {
    renderOverview({ isPlatformOperator: true });
    await screen.findByText('무결 · 위변조 0건');
    (auditChainApi.verifyTenant as ReturnType<typeof vi.fn>).mockRejectedValueOnce(new Error('boom'));
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '수동 검증' })); });
    expect(await screen.findByText('수동 검증 실패')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '수동 검증' })).toBeEnabled();
    expect(screen.getByText('무결 · 위변조 0건')).toBeInTheDocument();
  });
});
