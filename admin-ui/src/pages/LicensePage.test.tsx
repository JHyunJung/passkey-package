import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import type { LicenseView } from '@/api/license';

vi.mock('@/api/license', () => ({ licenseApi: { get: vi.fn() } }));

import { licenseApi } from '@/api/license';
import LicensePage from './LicensePage';

const NOW = new Date('2026-10-11T03:00:00Z');
const base: LicenseView = {
  deploymentMode: 'onprem',
  state: 'VALID',
  sub: 'acme-bank',
  jti: 'lic_01J9ZQ8V7K3M',
  expiresAt: '2027-04-29T00:00:00+09:00',
  daysUntilExpiry: 200,
  features: ['mds', 'audit-export'],
  lastVerifiedAt: new Date(NOW.getTime() - 5 * 60_000).toISOString(),
  graceRemainingHours: null,
  nextHeartbeatAt: new Date(NOW.getTime() + 55 * 60_000).toISOString(),
};

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
  vi.mocked(licenseApi.get).mockReset();
});
afterEach(() => vi.useRealTimers());

describe('LicensePage V2', () => {
  it('상태 헤드라인: State + 배지 + 마지막 검증/다음 heartbeat 상대 시간', async () => {
    vi.mocked(licenseApi.get).mockResolvedValue(base);
    const { container } = render(<LicensePage />);
    expect(await screen.findByRole('heading', { name: 'License' })).toBeInTheDocument();
    expect(container.firstElementChild).toHaveClass('page');
    expect(screen.getByText('VALID')).toBeInTheDocument();
    expect(screen.getByText('정상')).toHaveClass('badge', 'badge--success');
    expect(screen.getByText('Last verified 5분 전 · Next heartbeat 55분 후')).toBeInTheDocument();
  });

  it('만료까지 일수만 표시하고 진행 바는 그리지 않는다(전체 기간 데이터 없음)', async () => {
    vi.mocked(licenseApi.get).mockResolvedValue(base);
    render(<LicensePage />);
    expect(await screen.findByText('만료까지')).toBeInTheDocument();
    expect(screen.getByText('200일')).toBeInTheDocument();
    expect(screen.getByText(/^Expires at /)).toBeInTheDocument();
    expect(screen.queryByRole('progressbar')).toBeNull();
  });

  it('상세 카드: dl 항목, jti 복사 버튼, feature 태그, grace 없으면 숨김', async () => {
    vi.mocked(licenseApi.get).mockResolvedValue(base);
    const { container } = render(<LicensePage />);
    expect(await screen.findByRole('heading', { name: '상세' })).toBeInTheDocument();
    const terms = Array.from(container.querySelectorAll('dl dt')).map((d) => d.textContent);
    expect(terms).toEqual(['Customer', 'License ID (jti)', 'Expires at', 'Days until expiry', 'Features', 'Last verified at', 'Next heartbeat at']);
    expect(screen.getByText('acme-bank')).toBeInTheDocument();
    expect(screen.getByText('lic_01J9ZQ8V7K3M')).toHaveClass('mono');
    expect(screen.getByRole('button', { name: 'License ID 복사' })).toBeInTheDocument();
    expect(screen.getByText('audit-export')).toHaveClass('tag');
    expect(screen.queryByText('Grace remaining')).toBeNull();
  });

  it('NETWORK_GRACE: 경고 배지와 Grace remaining 행', async () => {
    vi.mocked(licenseApi.get).mockResolvedValue({ ...base, state: 'NETWORK_GRACE', graceRemainingHours: 12 });
    render(<LicensePage />);
    expect(await screen.findByText('서버 연결 불가')).toHaveClass('badge--warning');
    expect(screen.getByText('Grace remaining')).toBeInTheDocument();
    expect(screen.getByText('12 hour(s)')).toBeInTheDocument();
  });

  it('값이 없으면 기존 표기, 상대 시간 줄과 jti 복사 버튼은 숨김', async () => {
    vi.mocked(licenseApi.get).mockResolvedValue({ ...base, state: 'DEAD', jti: null, lastVerifiedAt: null, nextHeartbeatAt: null, features: [] });
    render(<LicensePage />);
    expect(await screen.findByText('만료')).toHaveClass('badge--danger');
    expect(screen.queryByText(/Last verified .* 전/)).toBeNull();
    expect(screen.queryByRole('button', { name: 'License ID 복사' })).toBeNull();
    expect(screen.getByText('(never)')).toBeInTheDocument();
    expect(screen.getByText('(none scheduled)')).toBeInTheDocument();
  });

  it('SaaS 면 온프렘 전용 안내', async () => {
    vi.mocked(licenseApi.get).mockResolvedValue({ ...base, deploymentMode: 'saas', state: null });
    render(<LicensePage />);
    expect(await screen.findByText('License management is on-prem only.')).toBeInTheDocument();
  });

  it('조회 실패는 오류 배너', async () => {
    vi.mocked(licenseApi.get).mockRejectedValue(new Error('boom'));
    render(<LicensePage />);
    expect(await screen.findByRole('alert')).toHaveTextContent('boom');
  });
});
