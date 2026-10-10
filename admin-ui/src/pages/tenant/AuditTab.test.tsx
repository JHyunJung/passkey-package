import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ToastHost } from '@/ui';
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
  });

  it('유형 칩으로 거르고 전체 해제', async () => {
    api.list.mockResolvedValue(resp([ev('1', { eventType: 'AUTH_SUCCESS' }), ev('2', { eventType: 'API_KEY_ROTATED' })]));
    renderTab();
    await screen.findByText('AUTH_SUCCESS', { selector: 'tbody .badge' });
    fireEvent.click(screen.getByRole('button', { name: 'API_KEY_ROTATED' }));
    expect(screen.queryByText('AUTH_SUCCESS', { selector: 'tbody .badge' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '전체 해제' }));
    expect(screen.getByText('AUTH_SUCCESS', { selector: 'tbody .badge' })).toBeInTheDocument();
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
    expect(screen.queryByText('Audit Hash Chain 검증')).toBeNull();
    unmount();

    api.verify.mockResolvedValue({ intact: true, verifiedRows: 0, tamperedEntryIds: [], verifiedAt: new Date().toISOString() });
    renderTab(true);
    expect(await screen.findByText('Audit Hash Chain 검증')).toBeInTheDocument();
    expect(screen.getByText('PLATFORM 전용')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '검증 실행' }));
    const dlg = screen.getByRole('dialog', { name: 'Hash chain 검증' });
    fireEvent.click(within(dlg).getByRole('button', { name: '검증 실행' }));
    await waitFor(() => expect(api.verify).toHaveBeenCalledWith('t1', expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/), expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/)));
    expect(await screen.findByText('위변조 0건')).toBeInTheDocument();
    expect(screen.queryByRole('dialog', { name: 'Hash chain 검증' })).toBeNull();
  });
});
