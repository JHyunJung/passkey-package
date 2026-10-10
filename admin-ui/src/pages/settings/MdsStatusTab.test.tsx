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
});
