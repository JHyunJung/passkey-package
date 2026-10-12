import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, within, act } from '@testing-library/react';
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

/** 상세 dl 에서 항목 이름(dt)에 해당하는 값(dd). */
const dd = (term: string) => screen.getByText(term, { selector: 'dt' }).nextElementSibling as HTMLElement;

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
  vi.mocked(licenseApi.get).mockReset();
});
afterEach(() => vi.useRealTimers());

describe('LicensePage', () => {
  it('상태 헤드라인: 원시 VALID 대신 배지만(원문은 툴팁) + 마지막/다음 확인 상대 시간', async () => {
    vi.mocked(licenseApi.get).mockResolvedValue(base);
    const { container } = render(<LicensePage />);
    expect(await screen.findByRole('heading', { name: '라이선스' })).toBeInTheDocument();
    expect(container.firstElementChild).toHaveClass('page');
    expect(screen.queryByText('VALID')).toBeNull();
    expect(screen.getByText('정상')).toHaveClass('badge', 'badge--success');
    expect(screen.getByTitle('VALID')).toContainElement(screen.getByText('정상'));
    expect(screen.getByText('마지막 확인 5분 전 · 다음 확인 55분 후')).toBeInTheDocument();
  });

  it('만료까지 일수만 표시하고 진행 바는 그리지 않는다(전체 기간 데이터 없음)', async () => {
    vi.mocked(licenseApi.get).mockResolvedValue(base);
    render(<LicensePage />);
    expect(await screen.findByText('만료까지')).toBeInTheDocument();
    expect(screen.getByText('200일')).toBeInTheDocument();
    expect(screen.getByText(/^만료일 /)).toBeInTheDocument();
    expect(screen.queryByText(/^Expires at /)).toBeNull();
    expect(screen.queryByRole('progressbar')).toBeNull();
  });

  it('상세 카드: 한국어 항목, 라이선스 ID 복사 버튼, 기능 태그, 유예 없으면 숨김', async () => {
    vi.mocked(licenseApi.get).mockResolvedValue(base);
    const { container } = render(<LicensePage />);
    expect(await screen.findByRole('heading', { name: '상세' })).toBeInTheDocument();
    const terms = Array.from(container.querySelectorAll('dl dt')).map((d) => d.textContent);
    expect(terms).toEqual(['고객사', '라이선스 ID', '만료일', '남은 일수', '사용 가능한 기능', '마지막 확인', '다음 확인 예정']);
    expect(screen.getByText('acme-bank')).toBeInTheDocument();
    expect(screen.getByText('lic_01J9ZQ8V7K3M')).toHaveClass('mono');
    expect(screen.getByRole('button', { name: '라이선스 ID 복사' })).toBeInTheDocument();
    expect(screen.getByText('audit-export')).toHaveClass('tag');
    expect(screen.queryByText('유예 기간 남은 시간')).toBeNull();
  });

  it('V2 레이아웃: 카드 간격 20px(stack-5), 상세는 행 구분선 kv(kv--rows)', async () => {
    vi.mocked(licenseApi.get).mockResolvedValue(base);
    const { container } = render(<LicensePage />);
    await screen.findByRole('heading', { name: '상세' });
    expect(container.querySelector('.stack-5')).not.toBeNull();
    expect(container.querySelector('dl')).toHaveClass('kv', 'kv--rows');
  });

  it('NETWORK_GRACE: 경고 배지, 유예 기간 "N시간"(hour(s) 아님), 연결 복구 조치 카드', async () => {
    vi.mocked(licenseApi.get).mockResolvedValue({ ...base, state: 'NETWORK_GRACE', graceRemainingHours: 12 });
    render(<LicensePage />);
    expect(await screen.findByText('서버 연결 불가')).toHaveClass('badge--warning');
    expect(dd('유예 기간 남은 시간')).toHaveTextContent('12시간');
    expect(screen.queryByText(/hour\(s\)/)).toBeNull();
    const card = screen.getByRole('region', { name: '지금 해야 할 일 — 라이선스 서버 연결 복구' });
    expect(card).toHaveTextContent('12시간 안에 연결되지 않으면 서비스가 중단됩니다');
    expect(within(card).getAllByRole('listitem')).toHaveLength(3);
  });

  it('값이 없으면 "—", 상대 시간 줄과 복사 버튼은 숨김, 만료면 새 라이선스 적용 카드', async () => {
    vi.mocked(licenseApi.get).mockResolvedValue({ ...base, state: 'DEAD', jti: null, lastVerifiedAt: null, nextHeartbeatAt: null, features: [] });
    render(<LicensePage />);
    expect(await screen.findByText('만료')).toHaveClass('badge--danger');
    expect(screen.queryByText(/마지막 확인 .* 전/)).toBeNull();
    expect(screen.queryByRole('button', { name: '라이선스 ID 복사' })).toBeNull();
    expect(dd('마지막 확인')).toHaveTextContent('—');
    expect(dd('다음 확인 예정')).toHaveTextContent('—');
    expect(dd('라이선스 ID')).toHaveTextContent('—');
    expect(dd('사용 가능한 기능')).toHaveTextContent('—');
    expect(screen.queryByText('(never)')).toBeNull();
    expect(screen.queryByText('(none scheduled)')).toBeNull();
    expect(screen.getByRole('region', { name: '지금 해야 할 일 — 새 라이선스 적용' })).toHaveTextContent('관리 콘솔은 계속 쓸 수 있습니다');
  });

  it('WARNING: 갱신 조치 카드(3단계)', async () => {
    vi.mocked(licenseApi.get).mockResolvedValue({ ...base, state: 'WARNING', daysUntilExpiry: 30 });
    render(<LicensePage />);
    const card = await screen.findByRole('region', { name: '지금 해야 할 일 — 라이선스 갱신' });
    expect(card).toHaveTextContent('라이선스가 30일 후 만료됩니다');
    expect(within(card).getAllByRole('listitem')).toHaveLength(3);
  });

  it('VALID 는 조치 카드 없음', async () => {
    vi.mocked(licenseApi.get).mockResolvedValue(base);
    render(<LicensePage />);
    await screen.findByText('정상');
    expect(screen.queryByRole('region', { name: /지금 해야 할 일/ })).toBeNull();
  });

  it('SaaS 면 온프레미스 전용 안내(한국어)', async () => {
    vi.mocked(licenseApi.get).mockResolvedValue({ ...base, deploymentMode: 'saas', state: null });
    render(<LicensePage />);
    expect(await screen.findByText('라이선스 관리는 온프레미스 설치본에서만 사용합니다')).toBeInTheDocument();
    expect(screen.queryByText(/on-prem only/)).toBeNull();
  });

  it('조회 실패는 LoadError, 다시 시도하면 다시 불러온다', async () => {
    vi.mocked(licenseApi.get).mockRejectedValueOnce(new Error('boom')).mockResolvedValueOnce(base);
    render(<LicensePage />);
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('라이선스 정보를 불러오지 못했습니다');
    expect(alert).toHaveTextContent('boom');
    fireEvent.click(within(alert).getByRole('button', { name: '다시 시도' }));
    expect(await screen.findByText('정상')).toBeInTheDocument();
  });

  it('지금 다시 확인: 확인하는 동안 기존 정보를 유지하고 "확인 중…"(비활성), 중복 호출 없음', async () => {
    let finish!: (v: LicenseView) => void;
    vi.mocked(licenseApi.get).mockResolvedValueOnce(base).mockReturnValueOnce(new Promise<LicenseView>((r) => { finish = r; }));
    render(<LicensePage />);
    fireEvent.click(await screen.findByRole('button', { name: '지금 다시 확인' }));
    const busy = screen.getByRole('button', { name: '확인 중…' });
    expect(busy).toBeDisabled();
    fireEvent.click(busy);
    expect(licenseApi.get).toHaveBeenCalledTimes(2);
    expect(screen.getByText('정상')).toBeInTheDocument();
    expect(screen.queryByRole('status', { name: '불러오는 중' })).toBeNull();
    await act(async () => { finish(base); });
    expect(screen.getByRole('button', { name: '지금 다시 확인' })).toBeEnabled();
  });

  it('지금 다시 확인이 실패해도 기존 정보를 유지하고 인라인 경고만 보인다', async () => {
    vi.mocked(licenseApi.get).mockResolvedValueOnce(base).mockRejectedValueOnce(new Error('boom'));
    render(<LicensePage />);
    fireEvent.click(await screen.findByRole('button', { name: '지금 다시 확인' }));
    expect(await screen.findByText('다시 확인하지 못했습니다')).toBeInTheDocument();
    expect(screen.getByText('정상')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '상세' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '지금 다시 확인' })).toBeEnabled();
  });

  it('처음 불러오는 동안 Skeleton(Loading… 없음)', async () => {
    let finish!: (v: LicenseView) => void;
    vi.mocked(licenseApi.get).mockReturnValue(new Promise<LicenseView>((r) => { finish = r; }));
    render(<LicensePage />);
    expect(await screen.findByRole('status', { name: '불러오는 중' })).toBeInTheDocument();
    expect(screen.queryByText('Loading…')).toBeNull();
    await act(async () => { finish(base); });
    expect(screen.getByText('정상')).toBeInTheDocument();
  });
});
