import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within, cleanup } from '@testing-library/react';
import { ToastHost } from '@/ui';
import type { MdsHistoryRow, MdsStatus } from '@/api/mdsStatus';

vi.mock('@/api/mdsStatus', () => ({
  MDS_HISTORY_STRIP_LIMIT: 30,
  mdsStatusApi: { get: vi.fn(), sync: vi.fn(), history: vi.fn() },
}));

import { mdsStatusApi } from '@/api/mdsStatus';
import MdsStatusTab from './MdsStatusTab';

const mock = (fn: unknown) => fn as ReturnType<typeof vi.fn>;
const status: MdsStatus = {
  version: 87, nextUpdate: '2026-10-12', fetchedAt: new Date(Date.now() - 2 * 3600_000).toISOString(),
  trustAnchorCount: 142, trustMode: 'MDS_STRICT_OPTIONAL', successRate30d: { ok: 29, total: 30 },
};
// 서버는 최신순(DESC). index 3 = 실패, index 5 = 건너뜀
const history: MdsHistoryRow[] = Array.from({ length: 12 }, (_, i) => ({
  id: 100 - i,
  startedAt: new Date(Date.now() - i * 86400_000).toISOString(),
  finishedAt: null,
  version: 87 - i,
  status: i === 3 ? 'FAILED' : i === 5 ? 'SKIPPED' : 'SYNCED',
  changeSummary: i === 0 ? '+3 항목' : null,
  durationMs: 400 + i,
  errorMessage: null,
}));

beforeEach(() => {
  vi.clearAllMocks();
  mock(mdsStatusApi.get).mockResolvedValue(status);
  mock(mdsStatusApi.history).mockResolvedValue(history);
  mock(mdsStatusApi.sync).mockResolvedValue({ status: 'SYNCED', version: 88, error: null });
});
afterEach(cleanup);

const renderTab = () => render(<ToastHost><MdsStatusTab /></ToastHost>);

describe('MdsStatusTab V2', () => {
  it('이력은 limit=30 으로 요청하고, 스트립 막대 수 = 이력 수', async () => {
    const { container } = renderTab();
    const strip = await screen.findByRole('img', { name: '최근 12회 동기화 결과 · 실패 1회' });
    expect(mdsStatusApi.history).toHaveBeenCalledWith(30);
    expect(strip.querySelectorAll('.strip__bar')).toHaveLength(history.length);
    expect(container.querySelectorAll('.strip__bar--bad')).toHaveLength(1);
    expect(container.querySelectorAll('.strip__bar--none')).toHaveLength(1);
    // 왼쪽이 오래된 것: 최신(index 0) 이 마지막 막대
    const bars = strip.querySelectorAll('.strip__bar');
    expect(bars[bars.length - 1 - 3]).toHaveClass('strip__bar--bad');
  });

  it('헤드라인·KPI·설정', async () => {
    renderTab();
    expect(await screen.findByText('정상 · BLOB #87')).toBeInTheDocument();
    expect(screen.getByText(/다음 갱신 2026-10-12 03:00/)).toBeInTheDocument();
    expect(screen.getByText('MDS BLOB 버전')).toBeInTheDocument();
    expect(screen.getByText('142')).toBeInTheDocument();
    expect(screen.getByText('2시간 전')).toBeInTheDocument();
    expect(screen.getByText('https://mds3.fidoalliance.org/')).toBeInTheDocument();
    expect(screen.getByText('97%')).toBeInTheDocument();
    expect(screen.getByText('MDS_STRICT_OPTIONAL')).toHaveClass('badge--info');
  });

  it('이력 표는 최근 6행, 상태 라벨 한국어', async () => {
    renderTab();
    const table = await screen.findByRole('table');
    await waitFor(() => expect(within(table).getAllByRole('row')).toHaveLength(1 + 6));
    expect(within(table).getAllByText('동기화됨')[0]).toHaveClass('badge--success');
    expect(within(table).getByText('실패')).toHaveClass('badge--danger');
    expect(within(table).getByText('건너뜀')).not.toHaveClass('badge--danger');
    expect(within(table).getByText('+3 항목')).toBeInTheDocument();
    expect(within(table).getByText('400ms')).toBeInTheDocument();
  });

  it('최신 실행이 실패면 실패 헤드라인', async () => {
    mock(mdsStatusApi.history).mockResolvedValue([{ ...history[0], status: 'FAILED' }, ...history.slice(1)]);
    renderTab();
    expect(await screen.findByText('동기화 실패 · BLOB #87')).toBeInTheDocument();
  });

  it('즉시 갱신 → sync 후 상태와 이력을 다시 읽는다', async () => {
    renderTab();
    fireEvent.click(await screen.findByRole('button', { name: /즉시 갱신/ }));
    await waitFor(() => expect(mdsStatusApi.sync).toHaveBeenCalledTimes(1));
    expect(await screen.findByText('MDS 즉시 갱신 완료')).toBeInTheDocument();
    await waitFor(() => expect(mdsStatusApi.get).toHaveBeenCalledTimes(2));
    expect(mdsStatusApi.history).toHaveBeenCalledTimes(2);
  });

  it('한 번도 동기화되지 않은 설치(version 0, 1970 시드)는 중립 "동기화 이력 없음" — 정상·20000일 전을 보이지 않는다', async () => {
    mock(mdsStatusApi.get).mockResolvedValue({
      ...status, version: 0, fetchedAt: '1970-01-01T00:00:00Z', nextUpdate: '1970-01-01', trustAnchorCount: 0,
      successRate30d: { ok: 0, total: 0 },
    });
    mock(mdsStatusApi.history).mockResolvedValue([]);
    renderTab();
    expect(await screen.findByText('동기화 이력 없음')).toBeInTheDocument();
    expect(screen.queryByText(/정상/)).toBeNull();
    expect(screen.queryByText(/일 전/)).toBeNull();
    expect(screen.queryByText(/1970/)).toBeNull();
    expect(screen.getByText('마지막 동기화').closest('.kpi')).toHaveTextContent('—');
    expect(screen.getByText('알 수 없음')).toBeInTheDocument();
  });

  it('fetchedAt 이 2000년 이전이면 version 이 있어도 동기화 이력 없음', async () => {
    mock(mdsStatusApi.get).mockResolvedValue({ ...status, fetchedAt: '1999-12-31T00:00:00Z' });
    renderTab();
    expect(await screen.findByText('동기화 이력 없음')).toBeInTheDocument();
  });

  it('이력이 오기 전에는 헤드라인(정상)을 먼저 그리지 않는다', async () => {
    let resolveHistory: (v: MdsHistoryRow[]) => void = () => {};
    mock(mdsStatusApi.history).mockReturnValue(new Promise((r) => { resolveHistory = r; }));
    renderTab();
    await waitFor(() => expect(mdsStatusApi.get).toHaveBeenCalled());
    await new Promise((r) => setTimeout(r, 0));
    expect(screen.queryByText('정상 · BLOB #87')).toBeNull();
    resolveHistory([{ ...history[0], status: 'FAILED' }]);
    expect(await screen.findByText('동기화 실패 · BLOB #87')).toBeInTheDocument();
    expect(screen.queryByText('정상 · BLOB #87')).toBeNull();
  });

  it('상태 조회 실패는 "이력 없음"이 아니라 오류 상태', async () => {
    mock(mdsStatusApi.get).mockRejectedValue(new Error('HTTP 500'));
    renderTab();
    expect(await screen.findByText('MDS 상태를 불러오지 못했습니다')).toBeInTheDocument();
    expect(screen.queryByText('동기화 이력 없음')).toBeNull();
    expect(screen.queryByText(/정상/)).toBeNull();
    mock(mdsStatusApi.get).mockResolvedValue(status);
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(await screen.findByText('정상 · BLOB #87')).toBeInTheDocument();
  });
});
