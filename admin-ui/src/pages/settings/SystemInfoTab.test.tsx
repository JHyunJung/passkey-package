import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { render, screen, waitFor, cleanup, within, fireEvent } from '@testing-library/react';

vi.mock('@/api/systemInfo', () => ({
  systemInfoApi: { get: vi.fn() },
}));

import { systemInfoApi } from '@/api/systemInfo';
import type { SystemInfoData } from '@/api/systemInfo';
import SystemInfoTab from './SystemInfoTab';

const sample: SystemInfoData = {
  serverVersion: '1.2.3',
  deployedAt: '2026-05-31T09:00+09:00',
  uptimeDays: 4,
  host: { environment: 'prod,oracle' },
  components: [
    { name: 'admin-app', version: '1.2.3', status: 'OK', note: 'Spring Boot' },
    { name: 'MDS sync scheduler', version: '1.2.3', status: 'OK', note: '매 6시간' },
  ],
};

const mockGet = () => systemInfoApi.get as ReturnType<typeof vi.fn>;
const list = () => screen.getByRole('list', { name: '백엔드 컴포넌트' });

beforeEach(() => {
  vi.clearAllMocks();
  mockGet().mockResolvedValue(sample);
});
afterEach(cleanup);

describe('SystemInfoTab', () => {
  it('renders version, KST deploy date, environment and component notes', async () => {
    render(<SystemInfoTab />);

    // 버전은 메트릭 카드와 컴포넌트 행 양쪽에 나온다.
    await waitFor(() => expect(screen.getAllByText('1.2.3').length).toBeGreaterThan(0));
    // KST 문자열이어도 앞 10자가 날짜다.
    expect(screen.getByText('2026-05-31 배포')).toBeInTheDocument();
    expect(screen.getByText('prod,oracle')).toBeInTheDocument();
    expect(screen.getByText('매 6시간')).toBeInTheDocument();
    expect(screen.getByText('4d')).toBeInTheDocument();
  });

  it('긴 버전 문자열은 말줄임(title) — 컴포넌트 이름 열을 밀어내지 않는다, 배포 시각은 KST', async () => {
    const longV = 'Oracle Database 21c Express Edition Release 21.0.0.0.0 - Production';
    mockGet().mockResolvedValue({ ...sample, components: [{ name: 'Oracle DB', version: longV, status: 'OK', note: 'primary' }] });
    const { container } = render(<SystemInfoTab />);
    const v = await screen.findByText(longV);
    expect(v).toHaveClass('truncate');
    expect(v).toHaveAttribute('title', longV);
    expect(screen.getByText('Oracle DB')).toBeInTheDocument();
    expect(container.querySelector('dl')).toHaveClass('kv');
    expect(screen.getByText('2026-05-31 09:00 KST')).toBeInTheDocument();
  });

  it('shows a placeholder subtitle when deployedAt is null', async () => {
    mockGet().mockResolvedValue({ ...sample, deployedAt: null });
    render(<SystemInfoTab />);

    await waitFor(() => expect(screen.getByText('배포 시각 정보 없음')).toBeInTheDocument());
  });

  it('shows an error message instead of loading forever when the fetch fails', async () => {
    mockGet().mockRejectedValue(new Error('boom'));
    render(<SystemInfoTab />);

    await waitFor(() =>
      expect(screen.getByText('System Info 를 불러오지 못했습니다.')).toBeInTheDocument());
    expect(screen.queryByText('System Info 로딩 중…')).not.toBeInTheDocument();
  });

  it('maps each component status to its badge class', async () => {
    mockGet().mockResolvedValue({
      ...sample,
      components: [
        { name: 'admin-app', version: '1.2.3', status: 'OK', note: 'Spring Boot' },
        { name: 'Oracle DB', version: 'Oracle (unknown)', status: 'DEGRADED', note: 'primary' },
        { name: 'MDS sync scheduler', version: '1.2.3', status: 'DOWN', note: '매 6시간' },
      ],
    });
    render(<SystemInfoTab />);

    await waitFor(() => expect(list()).toBeInTheDocument());
    expect(within(list()).getByText('정상').className).toContain('badge--success');
    expect(within(list()).getByText('저하').className).toContain('badge--warning');
    expect(within(list()).getByText('중단').className).toContain('badge--danger');
  });

  it('전체 상태는 컴포넌트 중 가장 나쁜 상태', async () => {
    mockGet().mockResolvedValue({
      ...sample,
      components: [
        { name: 'admin-app', version: '1.2.3', status: 'OK', note: null },
        { name: 'Redis', version: '7', status: 'DEGRADED', note: null },
      ],
    });
    render(<SystemInfoTab />);
    await waitFor(() => expect(list()).toBeInTheDocument());
    const kpi = screen.getByText('전체 상태').closest('.kpi')!;
    expect(kpi).toHaveTextContent('저하');
    expect(kpi).toHaveTextContent('컴포넌트 1 / 2');
  });

  it('latency hidden when undefined (§6 ⑤), shown when defined', async () => {
    mockGet().mockResolvedValue({
      ...sample,
      components: [
        { name: 'admin-app', version: '1.2.3', status: 'OK', note: null },
        { name: 'Redis', version: '7', status: 'OK', note: null, latencyMs: 12 },
      ],
    });
    render(<SystemInfoTab />);
    await waitFor(() => expect(list()).toBeInTheDocument());
    const items = within(list()).getAllByRole('listitem');
    expect(items[0].textContent).not.toMatch(/\d+ms/);
    expect(within(items[1]).getByText('12ms')).toBeInTheDocument();
  });

  it('불러오기 실패 → 일러스트 빈 상태, 다시 시도로 재요청', async () => {
    mockGet().mockRejectedValueOnce(new Error('boom'));
    const { container } = render(<SystemInfoTab />);
    await waitFor(() => expect(screen.getByText('System Info 를 불러오지 못했습니다.')).toBeInTheDocument());
    expect(container.querySelector('img')).toHaveAttribute('alt', '');
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    await waitFor(() => expect(screen.getByText('2026-05-31 배포')).toBeInTheDocument());
    expect(systemInfoApi.get).toHaveBeenCalledTimes(2);
  });
});
