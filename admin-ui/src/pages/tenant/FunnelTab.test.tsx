// admin-ui/src/pages/tenant/FunnelTab.test.tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import type { FunnelData, FunnelSeries } from '@/api/funnel';
import type { Tenant } from '@/api/designTypes';

const api = vi.hoisted(() => ({ get: vi.fn() }));
vi.mock('@/api/funnel', () => ({ funnelApi: api }));
const csv = vi.hoisted(() => ({ downloadCsv: vi.fn() }));
vi.mock('@/lib/csvExport', () => csv);

import FunnelTab from './FunnelTab';

const tenant: Tenant = {
  id: 't1', name: 'Acme', slug: 'acme', rpId: 'acme.com', status: 'ACTIVE', credentials: 0, apiKeys: 0,
  lastEventAt: null, createdAt: '2026-01-01T00:00:00Z', userVerification: 'PREFERRED', attestationConveyance: 'NONE', webauthnTimeoutMs: 60000,
};
const days = (n: number, success: (i: number) => number): FunnelSeries[] =>
  Array.from({ length: n }, (_, i) => ({ day: `10-${String(i + 1).padStart(2, '0')}`, attempts: 10, success: success(i) }));
const data = (w: number, series: FunnelSeries[]): FunnelData => ({
  windowDays: w,
  registration: { attempts: 100, success: 80, ratio: 0.8 },
  authentication: { attempts: 70, success: 63, ratio: 0.9 },
  conversion: 0.63, series, byEventType: [{ type: 'AUTH_SUCCESS', n: 63 }, { type: 'AUTH_FAIL', n: 7 }],
});
const callsWith = (w: number) => api.get.mock.calls.filter((c) => c[1] === w).length;

describe('FunnelTab V2', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.get.mockImplementation((_t: string, w: 1 | 7 | 30) =>
      Promise.resolve(w === 30 ? data(30, days(30, (i) => (i >= 23 ? 8 : 5))) : data(w, days(w, () => 9))));
  });

  it('7d: 요약 KPI 먼저 — 전체 전환율, 직전 7일 대비(30일 series), 최대 이탈 단계', async () => {
    render(<FunnelTab tenant={tenant} />);
    expect(await screen.findByText('63.0%')).toBeInTheDocument();
    expect(api.get).toHaveBeenCalledWith('t1', 7);
    expect(api.get).toHaveBeenCalledWith('t1', 30);
    expect(await screen.findByText('직전 7일 대비 +30.0%p (인증 성공률)')).toBeInTheDocument();
    expect(screen.getByText('등록 시도 → 등록 성공')).toBeInTheDocument();
    expect(screen.getByText('이탈 20건 · 20.0%')).toBeInTheDocument();
    expect(screen.getByText('최근 7일')).toBeInTheDocument();
    // 단계 사이 전환율 칩
    for (const t of ['80.0%', '87.5%', '90.0%']) expect(screen.getByText(t)).toHaveClass('tag');
  });

  it('이벤트 타입 범례는 본문 글꼴(아트보드 — mono 아님)', async () => {
    render(<FunnelTab tenant={tenant} />);
    const legend = (await screen.findAllByText('AUTH_SUCCESS')).find((el) => el.classList.contains('truncate'));
    expect(legend).toBeDefined();
    expect(legend).not.toHaveClass('mono');
  });

  it('24h·30d 에서는 직전 기간 대비를 계산·표시하지 않는다', async () => {
    render(<FunnelTab tenant={tenant} />);
    await screen.findByText('직전 7일 대비 +30.0%p (인증 성공률)');
    expect(callsWith(30)).toBe(1);

    fireEvent.click(within(screen.getByRole('group', { name: '기간' })).getByRole('button', { name: '24h' }));
    await waitFor(() => expect(api.get).toHaveBeenCalledWith('t1', 1));
    await screen.findByText('최근 24시간');
    expect(screen.queryByText(/직전 7일 대비/)).toBeNull();
    expect(callsWith(30)).toBe(1); // 24h 는 30일 비교 조회를 하지 않는다

    fireEvent.click(within(screen.getByRole('group', { name: '기간' })).getByRole('button', { name: '30d' }));
    await screen.findByText('최근 30일');
    expect(callsWith(30)).toBe(2);   // 본 조회 1회만(비교 조회 없음)
    expect(screen.queryByText(/직전 7일 대비/)).toBeNull();
  });

  it('기간 전환 중에는 이전 기간 숫자를 새 라벨 아래 보이지 않는다', async () => {
    render(<FunnelTab tenant={tenant} />);
    await screen.findByText('63.0%');
    api.get.mockImplementation(() => new Promise(() => {}));
    fireEvent.click(within(screen.getByRole('group', { name: '기간' })).getByRole('button', { name: '30d' }));
    await waitFor(() => expect(api.get).toHaveBeenCalledWith('t1', 30));
    expect(screen.queryByText('63.0%')).toBeNull();
    expect(screen.queryByText('최근 30일')).toBeNull();
    expect(screen.getByText('불러오는 중…')).toBeInTheDocument();
  });

  it('표로 보기: 같은 데이터를 표로, CSV 는 일별 시리즈', async () => {
    render(<FunnelTab tenant={tenant} />);
    await screen.findByText('63.0%');
    const [seriesToggle] = screen.getAllByRole('button', { name: '표로 보기' });
    expect(screen.queryByRole('table')).toBeNull();
    fireEvent.click(seriesToggle);
    expect(seriesToggle).toHaveAttribute('aria-pressed', 'true');
    const table = screen.getByRole('table');
    expect(within(table).getAllByRole('row')).toHaveLength(1 + 7);
    expect(within(table).getByText('10-01')).toBeInTheDocument();

    fireEvent.click(screen.getAllByRole('button', { name: 'CSV' })[0]);
    expect(csv.downloadCsv).toHaveBeenCalledWith(expect.stringMatching(/^funnel-series-acme-/), ['day', 'attempts', 'success'], expect.arrayContaining([['10-01', 10, 9]]));
  });

  it('조회 실패 시 오류 배너', async () => {
    api.get.mockRejectedValue(new Error('boom'));
    render(<FunnelTab tenant={tenant} />);
    expect(await screen.findByRole('alert')).toHaveTextContent('퍼널 데이터를 불러오지 못했습니다.');
  });
});
