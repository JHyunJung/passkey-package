// admin-ui/src/pages/AuditChainPage.test.tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, fireEvent, within, waitFor, act } from '@testing-library/react';
import { renderWithDataRouter } from '@/test/renderWithDataRouter';
import { ToastHost } from '@/ui';
import type { ChainOverview, IncidentView } from '@/api/auditChainMonitor';

vi.mock('@/api/auditChainMonitor', () => ({
  auditChainMonitorApi: {
    overview: vi.fn(), backfill: vi.fn(), listIncidents: vi.fn(), createIncident: vi.fn(), resolveIncident: vi.fn(),
  },
}));
vi.mock('@/api/auditChain', () => ({ auditChainApi: { verifyTenant: vi.fn() } }));
vi.mock('@/api/monthlyReport', () => ({ monthlyReportApi: { download: vi.fn() } }));

import { auditChainMonitorApi } from '@/api/auditChainMonitor';
import AuditChainPage from './AuditChainPage';

type Row = ChainOverview['tenants'][number];
const row = (tenantId: string, tenantName: string, intact: boolean, over: Partial<Row> = {}): Row => ({
  tenantId, tenantName, intact, verifiedRows: 1200, buckets: [], tamperedEntryId: intact ? null : 'entry-0000-abcdef12', ...over,
});
function overview(tenants: Row[], over: Partial<ChainOverview> = {}): ChainOverview {
  const tampered = tenants.filter((t) => !t.intact).length;
  return {
    verifiedAt: new Date().toISOString(), windowHours: 24, bucketSizeMinutes: 60,
    totals: { tenantsIntact: tenants.length - tampered, tenantsTotal: tenants.length, tenantsTampered: tampered, verifiedRows: 2400, verificationMs: 42 },
    tenants, ...over,
  };
}
const incident = (over: Partial<IncidentView> = {}): IncidentView => ({
  id: 'i1', tenantId: 't2', tenantName: 'Globex', tamperedEntryId: 'entry-0000-abcdef12', type: 'CHAIN_TAMPER',
  severity: 'HIGH', status: 'OPEN', detail: null, createdAt: '2026-10-11T02:00:00Z', createdByEmail: 'op@x.com',
  resolvedAt: null, resolvedByEmail: null, resolutionNote: null, ...over,
});
const runs = (n: number, badTail = 0) =>
  Array.from({ length: n }, (_, i) => ({ at: new Date(Date.now() - (n - i) * 60_000).toISOString(), ok: i < n - badTail }));

function renderPage() {
  return renderWithDataRouter(<ToastHost><AuditChainPage /></ToastHost>, { initialPath: '/audit-chain' });
}

beforeEach(() => {
  vi.mocked(auditChainMonitorApi.overview).mockReset().mockResolvedValue(overview([row('t1', 'Acme', true), row('t2', 'Globex', true)]));
  vi.mocked(auditChainMonitorApi.listIncidents).mockReset().mockResolvedValue([]);
  vi.mocked(auditChainMonitorApi.createIncident).mockReset();
  vi.mocked(auditChainMonitorApi.resolveIncident).mockReset();
});

describe('AuditChainPage V2', () => {
  it('카드 헤더 캡션은 제목 옆 한 줄(card__cap), 플랫폼 행 아바타는 첫 글자(괄호 아님)', async () => {
    vi.mocked(auditChainMonitorApi.overview).mockResolvedValue(overview([row('t1', 'Acme', true), row(null as unknown as string, '[platform]', true)]));
    const { container } = renderPage();
    await screen.findByText('[platform]');
    expect(screen.getByText('위변조 감지 시 등록되는 대응 incident')).toHaveClass('card__cap');
    expect(screen.getByText(/검증 기준$/)).toHaveClass('card__cap');
    const avatars = Array.from(container.querySelectorAll('.avatar')).map((a) => a.textContent);
    expect(avatars).toContain('P');
    expect(avatars).not.toContain('[');
  });

  it('어떤 tenant 에도 recentRuns 가 없으면 스트립 열 전체를 숨긴다', async () => {
    renderPage();
    await screen.findByText('Acme');
    expect(screen.queryByRole('columnheader', { name: '최근 14회' })).toBeNull();
    expect(screen.queryByRole('img', { name: /^최근 \d+회/ })).toBeNull();
  });

  it('recentRuns 가 있으면 스트립 열을 보이고 마지막 14회를 요약한다', async () => {
    vi.mocked(auditChainMonitorApi.overview).mockResolvedValue(
      overview([row('t1', 'Acme', true, { recentRuns: runs(20, 2) }), row('t2', 'Globex', true)]),
    );
    renderPage();
    await screen.findByText('Acme');
    expect(screen.getByRole('columnheader', { name: '최근 14회' })).toBeInTheDocument();
    expect(screen.getByRole('img', { name: '최근 14회 중 위변조 감지 2회' })).toBeInTheDocument();
    expect(screen.getAllByRole('img', { name: /^최근 \d+회/ })).toHaveLength(1); // Globex 는 셀 비움
  });

  it('nextRunAt 이 없으면 "다음 검증" 을 그리지 않고, 있으면 그린다', async () => {
    const { unmount } = renderPage();
    await screen.findByText('Acme');
    expect(screen.queryByText(/다음 검증/)).toBeNull();
    unmount();

    vi.mocked(auditChainMonitorApi.overview).mockResolvedValue(
      overview([row('t1', 'Acme', true)], { nextRunAt: new Date(Date.now() + 5 * 60_000).toISOString() }),
    );
    renderPage();
    expect(await screen.findByText(/다음 검증 \d+분 후/)).toBeInTheDocument();
  });

  it('스케줄러가 없으므로 60초 자동 검증을 주장하지 않는다', async () => {
    renderPage();
    await screen.findByText('Acme');
    expect(screen.queryByText(/60초|60s|자동 검증/)).toBeNull();
    expect(screen.getByText(/화면을 열 때와/)).toBeInTheDocument();
    expect(screen.getByText('요청 시')).toBeInTheDocument();
  });

  it('incident 0건이면 all-clear 빈 상태', async () => {
    renderPage();
    expect(await screen.findByText('등록된 incident가 없습니다')).toBeInTheDocument();
    expect(screen.getByText('모든 chain 이 정상입니다.')).toBeInTheDocument();
  });

  it('incident 목록 실패는 빈 상태로 오인시키지 않는다', async () => {
    vi.mocked(auditChainMonitorApi.listIncidents).mockRejectedValue(new Error('500'));
    renderPage();
    expect(await screen.findByText('Incident 목록을 불러오지 못했습니다.')).toBeInTheDocument();
    expect(screen.queryByText('등록된 incident가 없습니다')).toBeNull();
  });

  it('overview 실패 시 오류 상태 + 다시 시도', async () => {
    vi.mocked(auditChainMonitorApi.overview).mockRejectedValue(new Error('overview down'));
    renderPage();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('감사 체인 상태를 불러오지 못했습니다');
    expect(alert).toHaveTextContent('overview down');
    expect(within(alert).getByRole('button', { name: '다시 시도' })).toBeInTheDocument();
    expect(screen.queryByText(/모든 tenant 정상/)).toBeNull();
  });

  it('위변조면 bad 배너 → Incident 생성 다이얼로그 → createIncident(tenantId)', async () => {
    vi.mocked(auditChainMonitorApi.overview).mockResolvedValue(overview([row('t1', 'Acme', true), row('t2', 'Globex', false)]));
    vi.mocked(auditChainMonitorApi.createIncident).mockResolvedValue(incident());
    renderPage();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('위변조 의심 — 1개 tenant 모두 조사가 필요합니다');
    expect(alert).toHaveTextContent('Globex chain 에서 hash 불일치가 감지되었습니다.');
    fireEvent.click(within(alert).getByRole('button', { name: 'Incident 생성' }));
    const dialog = screen.getByRole('dialog', { name: 'Incident 생성' });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Incident 생성' }));
    await waitFor(() => expect(auditChainMonitorApi.createIncident).toHaveBeenCalledWith('t2'));
  });

  it('Incident 해결은 메모가 필수', async () => {
    vi.mocked(auditChainMonitorApi.listIncidents).mockResolvedValue([incident()]);
    vi.mocked(auditChainMonitorApi.resolveIncident).mockResolvedValue(incident({ status: 'RESOLVED' }));
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: '해결' }));
    const dialog = screen.getByRole('dialog', { name: 'Incident 해결' });
    const submit = within(dialog).getByRole('button', { name: '해결로 표시' });
    expect(submit).toBeDisabled();
    fireEvent.change(within(dialog).getByLabelText(/해결 메모/), { target: { value: '   ' } });
    expect(submit).toBeDisabled();
    fireEvent.change(within(dialog).getByLabelText(/해결 메모/), { target: { value: 'DBA 확인, 복구 완료' } });
    expect(submit).toBeEnabled();
    fireEvent.click(submit);
    await waitFor(() => expect(auditChainMonitorApi.resolveIncident).toHaveBeenCalledWith('i1', 'DBA 확인, 복구 완료'));
  });

  it('위변조만 세그먼트', async () => {
    vi.mocked(auditChainMonitorApi.overview).mockResolvedValue(overview([row('t1', 'Acme', true), row('t2', 'Globex', false)]));
    renderPage();
    const table = await screen.findByRole('table');
    expect(within(table).getByText('Acme')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '위변조만' }));
    expect(within(screen.getAllByRole('table')[0]).queryByText('Acme')).toBeNull();
    expect(within(screen.getAllByRole('table')[0]).getByText('Globex')).toBeInTheDocument();
  });

  it('월간 보고서 다이얼로그는 tenant 체크박스 없이 포함 목록만 보인다', async () => {
    renderPage();
    await screen.findByText('Acme');
    fireEvent.click(screen.getByRole('button', { name: '월간 보고서' }));
    const dialog = screen.getByRole('dialog', { name: '월간 무결성 보고서 발급' });
    expect(within(dialog).getByText('포함 tenant · 2개')).toBeInTheDocument();
    expect(within(dialog).queryByRole('checkbox')).toBeNull();
    expect(within(dialog).getByLabelText('시작일')).toHaveAttribute('type', 'date');
    expect(within(dialog).getByRole('button', { name: 'PDF 생성 → 다운로드' })).toBeEnabled();
  });

  it('지금 검증은 읽기 전용 — overview 를 다시 읽고 backfill 을 부르지 않는다', async () => {
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: '지금 검증' }));
    await waitFor(() => expect(auditChainMonitorApi.overview).toHaveBeenCalledTimes(2));
    expect(auditChainMonitorApi.backfill).not.toHaveBeenCalled();
  });

  it('체인 백필은 별도 동작 — 위험을 설명하는 확인 다이얼로그(취소 기본 포커스) 후에만 실행', async () => {
    vi.mocked(auditChainMonitorApi.backfill).mockReset().mockResolvedValue({ tenantsProcessed: 2, rowsUpdated: 5 } as never);
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: '체인 백필(마이그레이션)…' }));
    const dlg = screen.getByRole('alertdialog', { name: '체인 백필을 실행하시겠습니까?' });
    expect(dlg).toHaveTextContent(/hash 를 다시 계산해 덮어씁니다/);
    expect(dlg).toHaveTextContent(/삭제/);
    expect(document.activeElement).toBe(within(dlg).getByRole('button', { name: '취소' }));
    expect(auditChainMonitorApi.backfill).not.toHaveBeenCalled();
    fireEvent.click(within(dlg).getByRole('button', { name: '취소' }));
    expect(screen.queryByRole('alertdialog')).toBeNull();
    expect(auditChainMonitorApi.backfill).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: '체인 백필(마이그레이션)…' }));
    fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: '백필 실행' }));
    await waitFor(() => expect(auditChainMonitorApi.backfill).toHaveBeenCalledTimes(1));
    expect(await screen.findByText('백필 완료')).toBeInTheDocument();
  });

  it('검증 버튼은 "지금 검증" 하나뿐(새로 검증·전체 즉시 검증 통합)', async () => {
    renderPage();
    await screen.findByText('Acme');
    expect(screen.getAllByRole('button', { name: '지금 검증' })).toHaveLength(1);
    expect(screen.queryByRole('button', { name: '새로 검증' })).toBeNull();
    expect(screen.queryByRole('button', { name: '전체 즉시 검증' })).toBeNull();
  });

  it('재검증 중에도 표를 유지하고 버튼은 "검증 중…"(비활성, 가짜 진행률 없음), 끝나면 새 결과', async () => {
    renderPage();
    await screen.findByText('Acme');
    let resolve!: (o: ChainOverview) => void;
    vi.mocked(auditChainMonitorApi.overview).mockImplementationOnce(() => new Promise((r) => { resolve = r; }));
    fireEvent.click(screen.getByRole('button', { name: '지금 검증' }));
    expect(screen.getByRole('button', { name: '검증 중…' })).toBeDisabled();
    expect(screen.getByText('Acme')).toBeInTheDocument();
    expect(screen.queryByRole('status', { name: '불러오는 중' })).toBeNull();
    expect(screen.queryByRole('progressbar')).toBeNull();
    await act(async () => { resolve(overview([row('t1', 'Acme', true), row('t2', 'Globex', false)])); });
    expect(screen.getByRole('button', { name: '지금 검증' })).toBeEnabled();
    expect(screen.getByRole('alert')).toHaveTextContent('Globex');
  });

  it('다시 검증이 실패해도 이전 결과를 유지하고 경고를 보인다', async () => {
    renderPage();
    await screen.findByText('Acme');
    vi.mocked(auditChainMonitorApi.overview).mockRejectedValueOnce(new Error('down'));
    fireEvent.click(screen.getByRole('button', { name: '지금 검증' }));
    expect(await screen.findByText('다시 검증하지 못했습니다')).toBeInTheDocument();
    expect(screen.getByText('Acme')).toBeInTheDocument();
  });

  it('위변조 tenant 가 여럿이면 배너에 모두 나열하고 행마다 Incident 생성', async () => {
    vi.mocked(auditChainMonitorApi.overview).mockResolvedValue(
      overview([row('t1', 'Acme', true), row('t2', 'Globex', false), row('t3', 'Initech', false)]),
    );
    vi.mocked(auditChainMonitorApi.createIncident).mockResolvedValue(incident({ tenantId: 't3', tenantName: 'Initech' }));
    renderPage();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('위변조 의심 — 2개 tenant 모두 조사가 필요합니다');
    const items = within(alert).getAllByRole('listitem');
    expect(items).toHaveLength(2);
    const initech = items.find((li) => li.textContent?.includes('Initech'));
    expect(initech).toBeDefined();
    fireEvent.click(within(initech!).getByRole('button', { name: 'Incident 생성' }));
    const dialog = screen.getByRole('dialog', { name: 'Incident 생성' });
    expect(dialog).toHaveTextContent('Initech');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Incident 생성' }));
    await waitFor(() => expect(auditChainMonitorApi.createIncident).toHaveBeenCalledWith('t3'));
  });

  it('OPEN incident 가 있는 tenant 는 생성 버튼 대신 "Incident 처리 중"', async () => {
    vi.mocked(auditChainMonitorApi.overview).mockResolvedValue(overview([row('t1', 'Acme', true), row('t2', 'Globex', false)]));
    vi.mocked(auditChainMonitorApi.listIncidents).mockResolvedValue([incident()]); // t2 OPEN
    renderPage();
    const alert = await screen.findByRole('alert');
    expect(await within(alert).findByText('Incident 처리 중')).toBeInTheDocument();
    expect(within(alert).queryByRole('button', { name: 'Incident 생성' })).toBeNull();
  });

  it('배너의 "감사 로그 열기"는 그 tenant 의 감사 로그 탭으로 이동', async () => {
    vi.mocked(auditChainMonitorApi.overview).mockResolvedValue(overview([row('t1', 'Acme', true), row('t2', 'Globex', false)]));
    const { router } = renderPage();
    const alert = await screen.findByRole('alert');
    fireEvent.click(within(alert).getByRole('button', { name: '감사 로그 열기' }));
    expect(router.state.location.pathname).toBe('/tenants/t2');
    expect(router.state.location.search).toBe('?tab=audit');
  });

  it('처음 불러오는 동안은 Skeleton', async () => {
    let resolve!: (o: ChainOverview) => void;
    vi.mocked(auditChainMonitorApi.overview).mockReturnValue(new Promise((r) => { resolve = r; }));
    renderPage();
    expect(await screen.findByRole('status', { name: '불러오는 중' })).toBeInTheDocument();
    expect(screen.queryByText('불러오는 중…')).toBeNull();
    await act(async () => { resolve(overview([row('t1', 'Acme', true)])); });
  });
});
