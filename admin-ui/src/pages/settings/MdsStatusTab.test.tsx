import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within, cleanup, act } from '@testing-library/react';
import { ToastHost } from '@/ui';
import { ApiError } from '@/api/types';
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
    expect(screen.getByText('MDS 확인 권장')).toHaveClass('badge--info');
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
    expect(screen.getByText('다음 갱신 예정').nextElementSibling).toHaveTextContent('—');
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

  it('이력 시각은 브라우저 시간대가 아니라 KST', async () => {
    mock(mdsStatusApi.history).mockResolvedValue([{ ...history[0], startedAt: '2026-10-10T16:30:00Z' }]);
    renderTab();
    const table = await screen.findByRole('table');
    expect(within(table).getByText('2026-10-11 01:30')).toBeInTheDocument();
  });
});

describe('MdsStatusTab V3', () => {
  it('신뢰 모드는 사람이 읽는 라벨 + 설명, 원문 enum 은 툴팁에만', async () => {
    renderTab();
    const badge = await screen.findByText('MDS 확인 권장');
    expect(badge.closest('[title]')).toHaveAttribute('title', 'MDS_STRICT_OPTIONAL');
    expect(screen.getByText('MDS 에 없는 인증기도 기본은 허용합니다. 테넌트에서 ‘MDS 엄격 모드’ 를 켠 경우에만 MDS 미등록 인증기를 거부합니다.')).toBeInTheDocument();
    expect(screen.queryByText('MDS_STRICT_OPTIONAL')).toBeNull();
  });

  it('모르는 신뢰 모드는 원문 그대로(설명 없음), 툴팁에도 원문', async () => {
    mock(mdsStatusApi.get).mockResolvedValue({ ...status, trustMode: 'CUSTOM_MODE' });
    renderTab();
    const badge = await screen.findByText('CUSTOM_MODE');
    expect(badge).toHaveClass('badge--info');
    expect(badge.closest('[title]')).toHaveAttribute('title', 'CUSTOM_MODE');
  });

  it('서버가 값을 주지 않으면 "—" — 기본값을 지어내지 않는다', async () => {
    mock(mdsStatusApi.get).mockResolvedValue({ ...status, trustMode: null, nextUpdate: null, successRate30d: null, trustAnchorCount: null } as unknown as MdsStatus);
    renderTab();
    expect(await screen.findByText('설정되지 않음')).toBeInTheDocument();
    expect(screen.queryByText('MDS_STRICT_OPTIONAL')).toBeNull();
    expect(screen.getByText('다음 갱신 예정').nextElementSibling).toHaveTextContent('—');
    expect(screen.getByText('동기화 성공률 (30일)').nextElementSibling).toHaveTextContent('—');
    expect(screen.getByText('신뢰 앵커 (Trust anchors)').closest('.kpi')).toHaveTextContent('—');
    expect(screen.queryByText('0%')).toBeNull();
  });

  it('영어 잔재 "next 갱신 예정" 대신 "다음 갱신 예정 … KST"', async () => {
    renderTab();
    expect(await screen.findByText('다음 갱신 예정')).toBeInTheDocument();
    expect(screen.queryByText(/next/)).toBeNull();
    expect(screen.getByText('다음 갱신 예정').nextElementSibling).toHaveTextContent('2026-10-12 03:00 KST');
  });

  it('로드 실패는 LoadError(다시 시도 + 오류 정보 복사), 토스트 없음', async () => {
    mock(mdsStatusApi.get).mockRejectedValue(new ApiError(503, 'C999', 'HTTP 503'));
    renderTab();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('MDS 상태를 불러오지 못했습니다');
    expect(within(alert).getByRole('button', { name: '오류 정보 복사' })).toBeInTheDocument();
    expect(document.querySelector('.toast')).toBeNull();
  });

  it('로딩 중에는 Skeleton', async () => {
    let resolveGet: (v: MdsStatus) => void = () => {};
    mock(mdsStatusApi.get).mockReturnValue(new Promise<MdsStatus>((r) => { resolveGet = r; }));
    renderTab();
    expect(screen.getByRole('status', { name: 'MDS 상태 불러오는 중' })).toBeInTheDocument();
    expect(screen.queryByText('MDS 상태 로딩 중…')).toBeNull();
    resolveGet(status);
    expect(await screen.findByText('정상 · BLOB #87')).toBeInTheDocument();
  });

  it('즉시 갱신 후 재조회가 실패하면 데이터를 유지하고 경고 + 다시 시도', async () => {
    renderTab();
    fireEvent.click(await screen.findByRole('button', { name: /즉시 갱신/ }));
    mock(mdsStatusApi.get).mockRejectedValue(new ApiError(503, 'C999', 'HTTP 503'));
    // 위 mock 은 클릭 직후 재조회에 적용되도록 sync 완료 전에 교체했다
    expect(await screen.findByText('다시 확인하지 못했습니다')).toBeInTheDocument();
    expect(screen.getByText('정상 · BLOB #87')).toBeInTheDocument();
    mock(mdsStatusApi.get).mockResolvedValue(status);
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    await waitFor(() => expect(screen.queryByText('다시 확인하지 못했습니다')).toBeNull());
    expect(screen.getByText('정상 · BLOB #87')).toBeInTheDocument();
  });

  it('즉시 갱신은 진행 중 재클릭해도 한 번만 호출하고, 끝나면 다시 눌 수 있다', async () => {
    let resolveSync: (v: { status: 'SYNCED'; version: number; error: null }) => void = () => {};
    mock(mdsStatusApi.sync).mockReturnValue(new Promise((r) => { resolveSync = r; }));
    renderTab();
    const btn = await screen.findByRole('button', { name: /즉시 갱신/ });
    fireEvent.click(btn);
    fireEvent.click(btn);
    await waitFor(() => expect(screen.getByRole('button', { name: /갱신 중/ })).toBeDisabled());
    expect(mdsStatusApi.sync).toHaveBeenCalledTimes(1);
    resolveSync({ status: 'SYNCED', version: 88, error: null });
    await waitFor(() => expect(screen.getByRole('button', { name: /즉시 갱신/ })).toBeEnabled());
  });

  it('즉시 갱신 예외는 describeError 문구로 알리고(원문 노출 없음) 버튼을 되돌린다', async () => {
    mock(mdsStatusApi.sync).mockRejectedValue(new ApiError(500, 'C999', 'java.lang.NullPointerException at X'));
    renderTab();
    fireEvent.click(await screen.findByRole('button', { name: /즉시 갱신/ }));
    expect(await screen.findByText('MDS 갱신 실패')).toBeInTheDocument();
    const toast = screen.getByRole('alert');
    expect(toast.querySelector('.toast__sub')).toHaveTextContent(/^서버가 잠시 응답하지 않습니다/);
    // 원문은 본문이 아니라 접힌 "자세히" 안에만(스펙 §2 알림 규칙).
    expect(toast.querySelector('.toast__title')).not.toHaveTextContent(/NullPointerException/);
    expect(toast.querySelector('.toast__sub')).not.toHaveTextContent(/NullPointerException/);
    expect(toast.querySelector('details:not([open])')).toHaveTextContent(/NullPointerException/);
    expect(screen.getByRole('button', { name: /즉시 갱신/ })).toBeEnabled();
  });

  it('즉시 갱신 결과가 FAILED 여도 서버 원문 error 를 토스트에 싣지 않는다', async () => {
    mock(mdsStatusApi.sync).mockResolvedValue({ status: 'FAILED', version: null, error: 'SSLHandshakeException: boom' });
    renderTab();
    fireEvent.click(await screen.findByRole('button', { name: /즉시 갱신/ }));
    expect(await screen.findByText('MDS 갱신 실패')).toBeInTheDocument();
    expect(screen.queryByText(/SSLHandshake/)).toBeNull();
    expect(screen.getByRole('button', { name: /즉시 갱신/ })).toBeEnabled();
  });

  it('이력 조회만 실패하면 "이력 없음"이나 "정상" 헤드라인을 만들지 않고, 이력 영역에 다시 시도', async () => {
    mock(mdsStatusApi.history).mockRejectedValue(new ApiError(503, 'C999', 'HTTP 503'));
    renderTab();
    expect(await screen.findByText('동기화 이력을 불러오지 못했습니다')).toBeInTheDocument();
    expect(screen.queryByText('동기화 이력이 없습니다.')).toBeNull();
    expect(screen.queryByText(/^정상/)).toBeNull();
    expect(screen.getByText('MDS BLOB 버전').closest('.kpi')).toHaveTextContent('#87');
    mock(mdsStatusApi.history).mockResolvedValue(history);
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(await screen.findByText('정상 · BLOB #87')).toBeInTheDocument();
    expect(screen.queryByText('동기화 이력을 불러오지 못했습니다')).toBeNull();
  });

  it('재조회에서 이력만 실패하면 이전 이력을 유지하고 경고', async () => {
    renderTab();
    fireEvent.click(await screen.findByRole('button', { name: /즉시 갱신/ }));
    mock(mdsStatusApi.history).mockRejectedValue(new ApiError(503, 'C999', 'HTTP 503'));
    expect(await screen.findByText('다시 확인하지 못했습니다')).toBeInTheDocument();
    expect(screen.getByRole('table')).toBeInTheDocument();
    expect(screen.queryByText('동기화 이력이 없습니다.')).toBeNull();
    // 오래된 이력으로 "정상" 을 주장하지 않는다
    expect(screen.queryByText(/^정상/)).toBeNull();
    expect(screen.getByText('이력 확인 불가 · BLOB #87')).toBeInTheDocument();
  });

  it('겹친 재조회가 순서 뒤바뀌어 끝나도, 이력 실패 시 유지되는 이력은 채택된 최신 응답', async () => {
    const pend: Array<(v: MdsHistoryRow[]) => void> = [];
    const hold = () => new Promise<MdsHistoryRow[]>((r) => { pend.push(r); });
    renderTab();
    const btn = async () => screen.findByRole('button', { name: /즉시 갱신/ });
    await btn(); // 최초 로드 완료
    mock(mdsStatusApi.history).mockReturnValueOnce(hold()); // 재조회 A
    fireEvent.click(await btn());
    await waitFor(() => expect(pend).toHaveLength(1));
    await waitFor(() => expect(screen.getByRole('button', { name: /즉시 갱신/ })).toBeEnabled());
    mock(mdsStatusApi.history).mockReturnValueOnce(hold()); // 재조회 B (더 최신)
    fireEvent.click(await btn());
    await waitFor(() => expect(pend).toHaveLength(2));
    await act(async () => { pend[1]([{ ...history[0], changeSummary: '+9 항목' }]); });
    expect(await screen.findByText('+9 항목')).toBeInTheDocument();
    await act(async () => { pend[0]([{ ...history[0], changeSummary: '+3 항목' }]); }); // 낡은 응답은 버려진다
    expect(screen.queryByText('+3 항목')).toBeNull();
    // 이력만 실패하는 재조회 → 채택된 최신(+9) 이 유지돼야 한다
    mock(mdsStatusApi.history).mockRejectedValue(new ApiError(503, 'C999', 'HTTP 503'));
    await waitFor(() => expect(screen.getByRole('button', { name: /즉시 갱신/ })).toBeEnabled());
    fireEvent.click(await btn());
    expect(await screen.findByText('다시 확인하지 못했습니다')).toBeInTheDocument();
    expect(screen.getByText('+9 항목')).toBeInTheDocument();
    expect(screen.queryByText('+3 항목')).toBeNull();
  });

  it('Object.prototype 이름 같은 신뢰 모드도 원문 그대로 표시', async () => {
    mock(mdsStatusApi.get).mockResolvedValue({ ...status, trustMode: 'constructor' });
    renderTab();
    expect(await screen.findByText('constructor')).toHaveClass('badge--info');
  });
});
