import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within, act } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ToastHost } from '@/ui';
import { ApiError } from '@/api/types';
import type { AuditEvent, Tenant } from '@/api/designTypes';

const api = vi.hoisted(() => ({ list: vi.fn(), verify: vi.fn() }));
vi.mock('@/api/audit', () => ({ auditApi: api }));

import AuditTab from './AuditTab';

const tenant: Tenant = {
  id: 't1', name: 'Acme', slug: 'acme', rpId: 'acme.com', status: 'ACTIVE', credentials: 0, apiKeys: 0,
  lastEventAt: null, createdAt: '2026-01-01T00:00:00Z', userVerification: 'PREFERRED', attestationConveyance: 'NONE', webauthnTimeoutMs: 60000,
};
const ev = (id: string, p: Partial<AuditEvent> = {}): AuditEvent => ({
  id, ts: new Date(Date.now() - 60_000).toISOString(), eventType: 'WEBAUTHN_CONFIG_UPDATED', actorType: 'ADMIN',
  actorId: 'admin@x.com', subjectType: 'TENANT', subjectId: 't1', payload: null, ...p,
});
const resp = (items: AuditEvent[], extra: { total?: number } = {}) => ({ items, page: 0, size: 50, ...extra });
const renderTab = (platform = false) =>
  render(<MemoryRouter><ToastHost><AuditTab tenant={tenant} isPlatformOperator={platform} /></ToastHost></MemoryRouter>);
const HOUR = 3_600_000;

describe('AuditTab V2', () => {
  beforeEach(() => vi.clearAllMocks());

  it('기간 세그먼트를 서버 from/to 로 보낸다 (24시간 → 7일)', async () => {
    api.list.mockResolvedValue(resp([]));
    renderTab();
    await waitFor(() => expect(api.list).toHaveBeenCalledTimes(1));
    const [tid, page, size, r1] = api.list.mock.calls[0];
    expect([tid, page, size]).toEqual(['t1', 0, 50]);
    expect(Date.parse(r1.to) - Date.parse(r1.from)).toBe(24 * HOUR);
    expect(Math.abs(Date.parse(r1.to) - Date.now())).toBeLessThan(5_000);

    fireEvent.click(within(screen.getByRole('group', { name: '기간' })).getByRole('button', { name: '7일' }));
    await waitFor(() => expect(api.list).toHaveBeenCalledTimes(2));
    const r2 = api.list.mock.calls[1][3];
    expect(Date.parse(r2.to) - Date.parse(r2.from)).toBe(168 * HOUR);
  });

  it('실패만: result 가 있으면 우선, 없으면 실패 액션 목록·이름 규칙', async () => {
    api.list.mockResolvedValue(resp([
      ev('1', { eventType: 'ATTESTATION_TRUST_FAILED' }),
      ev('2', { eventType: 'WEBAUTHN_CONFIG_UPDATED' }),
      ev('3', { eventType: 'CUSTOM_CHECK', result: 'FAILURE' }),
      ev('4', { eventType: 'RETRY_FAILED', result: 'SUCCESS' }),
    ]));
    renderTab();
    await screen.findByText('WEBAUTHN_CONFIG_UPDATED', { selector: 'tbody .badge' });
    const shown = () => Array.from(document.querySelectorAll('tbody .badge')).map((b) => b.textContent);
    expect(shown()).toHaveLength(4);
    fireEvent.click(screen.getByRole('switch', { name: '실패만' }));
    expect(shown()).toEqual(['ATTESTATION_TRUST_FAILED', 'CUSTOM_CHECK']);
    expect(screen.getByText('이 페이지 4건 중 2건')).toBeInTheDocument();
  });

  it('이벤트 종류 그룹 칩(원시 코드는 툴팁)으로 현재 페이지를 거르고, 범위·CSV 라벨 표시, 필터 지우기', async () => {
    api.list.mockResolvedValue(resp([ev('1', { eventType: 'AUTH_SUCCESS' }), ev('2', { eventType: 'API_KEY_ROTATED' })]));
    renderTab();
    await screen.findByText('AUTH_SUCCESS', { selector: 'tbody .badge' });
    const group = screen.getByRole('group', { name: '이벤트 종류' });
    expect(within(group).getAllByRole('button').map((b) => b.textContent)).toEqual(['인증', '등록', '설정 변경', '키 관리', '기타']);
    const keys = within(group).getByRole('button', { name: '키 관리' });
    expect(keys).toHaveAttribute('title', 'API_KEY_* · CREDENTIAL_REVOKE*');
    expect(screen.queryByRole('button', { name: 'API_KEY_ROTATED' })).toBeNull();
    fireEvent.click(keys);
    expect(keys).toHaveAttribute('aria-pressed', 'true');
    expect(screen.queryByText('AUTH_SUCCESS', { selector: 'tbody .badge' })).toBeNull();
    expect(screen.getByText('API_KEY_ROTATED', { selector: 'tbody .badge' })).toBeInTheDocument();
    expect(screen.getByText('이 페이지 2건 중 1건')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'CSV 내보내기 (현재 페이지 1건)' })).toBeEnabled();
    fireEvent.click(screen.getByRole('button', { name: '필터 지우기' }));
    expect(screen.getByText('AUTH_SUCCESS', { selector: 'tbody .badge' })).toBeInTheDocument();
    expect(screen.getByText('최근 2건')).toBeInTheDocument();
  });

  it('어느 그룹에도 없는 이벤트는 "기타" 칩으로 거를 수 있다', async () => {
    api.list.mockResolvedValue(resp([ev('1', { eventType: 'AUTH_SUCCESS' }), ev('2', { eventType: 'MDS_BLOB_SYNC' })]));
    renderTab();
    await screen.findByText('AUTH_SUCCESS', { selector: 'tbody .badge' });
    fireEvent.click(within(screen.getByRole('group', { name: '이벤트 종류' })).getByRole('button', { name: '기타' }));
    expect(screen.queryByText('AUTH_SUCCESS', { selector: 'tbody .badge' })).toBeNull();
    expect(screen.getByText('MDS_BLOB_SYNC', { selector: 'tbody .badge' })).toBeInTheDocument();
  });

  it('날짜 그룹 행 + 행 펼침(diff·payload·JSON 복사·상세 다이얼로그)', async () => {
    api.list.mockResolvedValue(resp([ev('1', { payload: { timeoutMs: { from: 60000, to: 120000 } } })]));
    const { container } = renderTab();
    const badge = await screen.findByText('WEBAUTHN_CONFIG_UPDATED', { selector: 'tbody .badge' });
    expect(container.querySelector('tr.table__group')).toHaveTextContent('오늘');
    expect(screen.queryByRole('button', { name: 'JSON 복사' })).toBeNull();

    fireEvent.click(badge);
    expect(container.querySelectorAll('tr.table__expanded')).toHaveLength(1);
    expect(screen.getByText('60000')).toBeInTheDocument();
    expect(screen.getByText('120000')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'JSON 복사' })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '상세 다이얼로그' }));
    const dlg = screen.getByRole('dialog', { name: /Audit Event/ });
    expect(within(dlg).getByText('누가 (actor)')).toBeInTheDocument();
    expect(within(dlg).getByText(/KST$/)).toBeInTheDocument();

    fireEvent.click(within(dlg).getByRole('button', { name: '닫기' }));
    fireEvent.click(badge);
    expect(container.querySelectorAll('tr.table__expanded')).toHaveLength(0);
  });

  it('건수: total 이 없으면 "최근 N건", 있으면 기간 · 총 건수', async () => {
    api.list.mockResolvedValueOnce(resp([ev('1'), ev('2')]));
    const { unmount } = renderTab();
    expect(await screen.findByText('최근 2건')).toBeInTheDocument();
    unmount();
    api.list.mockResolvedValueOnce(resp([ev('1')], { total: 1234 }));
    renderTab();
    expect(await screen.findByText('최근 24시간 · 1,234건')).toBeInTheDocument();
  });

  it('더 보기: 마지막 응답이 50건이면 같은 기간으로 다음 페이지를 붙인다', async () => {
    const first = Array.from({ length: 50 }, (_, i) => ev(`a${i}`));
    api.list.mockResolvedValueOnce(resp(first)).mockResolvedValueOnce(resp([ev('b0', { eventType: 'AUTH_SUCCESS' })]));
    renderTab();
    fireEvent.click(await screen.findByRole('button', { name: '더 보기' }));
    await screen.findByText('AUTH_SUCCESS', { selector: 'tbody .badge' });
    const [r1, r2] = [api.list.mock.calls[0], api.list.mock.calls[1]];
    expect(r2[1]).toBe(1);
    expect(r2[3]).toEqual(r1[3]);
    expect(screen.queryByRole('button', { name: '더 보기' })).toBeNull();
    expect(screen.getByText('최근 51건')).toBeInTheDocument();
  });

  it('체인 검증 카드는 PLATFORM 전용, 검증 실행 → 결과 배지', async () => {
    api.list.mockResolvedValue(resp([]));
    const { unmount } = renderTab(false);
    await waitFor(() => expect(api.list).toHaveBeenCalled());
    expect(screen.queryByText('감사 로그 위변조 검증')).toBeNull();
    unmount();

    api.verify.mockResolvedValue({ intact: true, verifiedRows: 0, tamperedEntryIds: [], verifiedAt: new Date().toISOString() });
    renderTab(true);
    expect(await screen.findByText('감사 로그 위변조 검증')).toBeInTheDocument();
    expect(screen.getByText('PLATFORM 전용')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '검증 실행' }));
    const dlg = screen.getByRole('dialog', { name: '해시 체인 검증' });
    // 서버 /chain/verify 는 기간을 받지 않고 항상 전체 체인을 검증한다 — 기간 입력을 보이지 않는다.
    expect(within(dlg).queryByLabelText('from')).toBeNull();
    expect(within(dlg).queryByLabelText('to')).toBeNull();
    expect(dlg).toHaveTextContent('전체 체인 검증');
    fireEvent.click(within(dlg).getByRole('button', { name: '검증 실행' }));
    await waitFor(() => expect(api.verify).toHaveBeenCalledWith('t1'));
    expect(await screen.findByText('위변조 0건')).toBeInTheDocument();
    expect(screen.queryByRole('dialog', { name: '해시 체인 검증' })).toBeNull();
  });

  it('더 보기 진행 중 기간을 바꾸면 이전 기간의 응답은 버려진다', async () => {
    const first = Array.from({ length: 50 }, (_, i) => ev(`a${i}`));
    let resolveStale: (v: unknown) => void = () => {};
    const stale = new Promise((r) => { resolveStale = r; });
    api.list
      .mockResolvedValueOnce(resp(first))
      .mockReturnValueOnce(stale)
      .mockResolvedValueOnce(resp([ev('n0', { eventType: 'AUTH_SUCCESS' })]));
    renderTab();
    fireEvent.click(await screen.findByRole('button', { name: '더 보기' }));
    fireEvent.click(within(screen.getByRole('group', { name: '기간' })).getByRole('button', { name: '7일' }));
    await screen.findByText('최근 1건');
    resolveStale(resp([ev('stale0', { eventType: 'STALE_EVENT' })]));
    await new Promise((r) => setTimeout(r, 20));
    expect(screen.queryByText('STALE_EVENT', { selector: 'tbody .badge' })).toBeNull();
    expect(screen.getByText('최근 1건')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '더 보기' })).toBeNull();
  });

  it('체인 검증 중: 가짜 퍼센트 없이 "검증 중…" + 경과 시간, 닫아도 계속 실행되고 결과는 카드에', async () => {
    api.list.mockResolvedValue(resp([]));
    let resolve: (v: unknown) => void = () => {};
    api.verify.mockReturnValue(new Promise((r) => { resolve = r; }));
    renderTab(true);
    fireEvent.click(await screen.findByRole('button', { name: '검증 실행' }));
    const dlg = screen.getByRole('dialog', { name: '해시 체인 검증' });
    const run = within(dlg).getByRole('button', { name: '검증 실행' });
    fireEvent.click(run);
    fireEvent.click(run); // 연타해도 한 번만
    expect(within(dlg).getByRole('button', { name: '검증 중…' })).toBeDisabled();
    const bar = within(dlg).getByRole('progressbar', { name: '검증 진행 중' });
    expect(bar).not.toHaveAttribute('aria-valuenow');
    expect(dlg.textContent).not.toMatch(/\d+%/);
    expect(dlg).toHaveTextContent(/경과 \d+초/);
    // 초 단위 경과 시간은 스크린리더에 계속 읽히지 않게 aria-live 밖에 둔다.
    expect(dlg.querySelector('[aria-live] .hint')).toBeNull();
    fireEvent.click(within(dlg).getByRole('button', { name: '닫기 (계속 실행)' }));
    expect(screen.queryByRole('dialog', { name: '해시 체인 검증' })).toBeNull();
    expect(screen.getByRole('button', { name: '검증 중…' })).toBeDisabled(); // 카드 버튼
    await act(async () => { resolve({ intact: true, verifiedRows: 0, tamperedEntryIds: [], verifiedAt: new Date().toISOString() }); });
    expect(await screen.findByText('위변조 0건')).toBeInTheDocument();
    expect(api.verify).toHaveBeenCalledTimes(1);
  });

  it('체인 검증 실패: 토스트로 알리고 버튼이 다시 활성화된다', async () => {
    api.list.mockResolvedValue(resp([]));
    api.verify.mockRejectedValueOnce(new ApiError(503, 'C999', 'HTTP 503'));
    renderTab(true);
    fireEvent.click(await screen.findByRole('button', { name: '검증 실행' }));
    fireEvent.click(within(screen.getByRole('dialog', { name: '해시 체인 검증' })).getByRole('button', { name: '검증 실행' }));
    expect(await screen.findByText('체인 검증에 실패했습니다')).toBeInTheDocument();
    await waitFor(() => expect(within(screen.getByRole('dialog', { name: '해시 체인 검증' })).getByRole('button', { name: '검증 실행' })).toBeEnabled());
  });

  it('로드 실패: "이 기간에 이벤트 없음"이 아니라 LoadError, 건수 "—", CSV 비활성, 다시 시도', async () => {
    api.list.mockRejectedValueOnce(new ApiError(503, 'C999', 'HTTP 503')).mockResolvedValueOnce(resp([ev('1')]));
    renderTab();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('감사 로그를 불러오지 못했습니다');
    expect(screen.queryByText('이 기간에 감사 이벤트가 없습니다.')).toBeNull();
    expect(screen.getByText('—')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /CSV 내보내기/ })).toBeDisabled();
    expect(screen.queryByText('audit 로드 실패')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(await screen.findByText('WEBAUTHN_CONFIG_UPDATED', { selector: 'tbody .badge' })).toBeInTheDocument();
    expect(api.list).toHaveBeenCalledTimes(2);
  });

  it('기간을 바꾼 뒤 로드가 실패하면 이전 기간의 행을 남기지 않고 LoadError', async () => {
    api.list.mockResolvedValueOnce(resp([ev('1')])).mockRejectedValueOnce(new ApiError(503, 'C999', 'HTTP 503'));
    renderTab();
    await screen.findByText('WEBAUTHN_CONFIG_UPDATED', { selector: 'tbody .badge' });
    fireEvent.click(within(screen.getByRole('group', { name: '기간' })).getByRole('button', { name: '7일' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('감사 로그를 불러오지 못했습니다');
    expect(screen.queryByText('WEBAUTHN_CONFIG_UPDATED', { selector: 'tbody .badge' })).toBeNull();
  });

  it('로딩 중에는 Skeleton', async () => {
    let resolve: (v: unknown) => void = () => {};
    api.list.mockReturnValue(new Promise((r) => { resolve = r; }));
    renderTab();
    expect(screen.getByRole('status', { name: '감사 로그 불러오는 중' })).toBeInTheDocument();
    expect(screen.queryByText('불러오는 중…')).toBeNull();
    await act(async () => { resolve(resp([])); });
  });

  it('체인 검증 중 테넌트가 바뀌면 이전 테넌트의 결과는 버려지고 검증 버튼이 풀린다', async () => {
    api.list.mockResolvedValue(resp([]));
    let resolve: (v: unknown) => void = () => {};
    api.verify.mockReturnValue(new Promise((r) => { resolve = r; }));
    const ui = (t: Tenant) => <MemoryRouter><ToastHost><AuditTab tenant={t} isPlatformOperator /></ToastHost></MemoryRouter>;
    const { rerender } = render(ui(tenant));
    fireEvent.click(await screen.findByRole('button', { name: '검증 실행' }));
    fireEvent.click(within(screen.getByRole('dialog', { name: '해시 체인 검증' })).getByRole('button', { name: '검증 실행' }));
    rerender(ui({ ...tenant, id: 't2', name: 'Beta' }));
    expect(await screen.findByRole('button', { name: '검증 실행' })).toBeEnabled();
    await act(async () => { resolve({ intact: true, verifiedRows: 0, tamperedEntryIds: [], verifiedAt: new Date().toISOString() }); });
    expect(screen.queryByText('위변조 0건')).toBeNull();
    expect(screen.getByRole('button', { name: '검증 실행' })).toBeEnabled();
  });

  it('체인 검증 중 경과 시간이 실제로 늘어난다 (0초에 멈추지 않음)', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] });
    try {
      api.list.mockResolvedValue(resp([]));
      let resolve: (v: unknown) => void = () => {};
      api.verify.mockReturnValue(new Promise((r) => { resolve = r; }));
      renderTab(true);
      fireEvent.click(await screen.findByRole('button', { name: '검증 실행' }));
      const dlg = screen.getByRole('dialog', { name: '해시 체인 검증' });
      fireEvent.click(within(dlg).getByRole('button', { name: '검증 실행' }));
      expect(dlg).toHaveTextContent('경과 0초');
      act(() => { vi.advanceTimersByTime(2000); });
      expect(dlg).toHaveTextContent('경과 2초');
      act(() => { vi.advanceTimersByTime(3000); });
      expect(dlg).toHaveTextContent('경과 5초');
      await act(async () => { resolve({ intact: true, verifiedRows: 0, tamperedEntryIds: [], verifiedAt: new Date().toISOString() }); });
      expect(screen.getByText('위변조 0건')).toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });
});
