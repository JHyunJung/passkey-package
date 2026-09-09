import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { render, screen, waitFor, cleanup } from '@testing-library/react';

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
    { name: 'MDS sync scheduler', version: '1.2.3', status: 'OK', note: '매 PT6H' },
  ],
};

const mockGet = () => systemInfoApi.get as ReturnType<typeof vi.fn>;

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
    expect(screen.getByText('매 PT6H')).toBeInTheDocument();
    expect(screen.getByText('4d')).toBeInTheDocument();
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
});
