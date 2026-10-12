import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import type { LicenseView } from '@/api/license';

vi.mock('@/api/license', () => ({ licenseApi: { get: vi.fn() } }));

import { licenseApi } from '@/api/license';
import { LicenseBanner } from './LicenseBanner';

const base: LicenseView = {
  deploymentMode: 'onprem', state: 'WARNING', sub: 'acme', jti: 'lic_1', expiresAt: '2026-11-10T00:00:00+09:00',
  daysUntilExpiry: 30, features: [], lastVerifiedAt: null, graceRemainingHours: null, nextHeartbeatAt: null,
};

function renderBanner(props: { showLink?: boolean } = {}, path = '/tenants') {
  return render(<MemoryRouter initialEntries={[path]}><LicenseBanner {...props} /></MemoryRouter>);
}

// 호출 관측만으로는 fetch 가 settle 되고 React 가 커밋되기 전이다 — 비어 있음을 단언하기 전에 반드시 flush.
async function settle() {
  await act(async () => { await new Promise((r) => setTimeout(r, 0)); });
}

beforeEach(() => { vi.mocked(licenseApi.get).mockReset(); });

describe('LicenseBanner', () => {
  it('WARNING: 남은 일수 + "라이선스 보기" 링크(/license)', async () => {
    vi.mocked(licenseApi.get).mockResolvedValue(base);
    renderBanner({ showLink: true });
    expect(await screen.findByText('라이선스가 30일 후 만료됩니다. 영업 담당자에게 갱신을 요청하세요.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '라이선스 보기' })).toHaveAttribute('href', '/license');
  });

  it('NETWORK_GRACE: 남은 시간 + "조치 방법 보기"', async () => {
    vi.mocked(licenseApi.get).mockResolvedValue({ ...base, state: 'NETWORK_GRACE', graceRemainingHours: 12 });
    renderBanner({ showLink: true });
    expect(await screen.findByText('라이선스 서버에 연결할 수 없습니다. 12시간 안에 연결되지 않으면 서비스가 중단됩니다.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '조치 방법 보기' })).toHaveAttribute('href', '/license');
  });

  it('DEAD: role=alert + 쉬운 영향 문구 + "갱신 방법 보기"', async () => {
    vi.mocked(licenseApi.get).mockResolvedValue({ ...base, state: 'DEAD' });
    renderBanner({ showLink: true });
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('라이선스가 만료되어 패스키 등록·인증이 중단되었습니다. 관리 콘솔은 계속 쓸 수 있습니다.');
    expect(screen.getByRole('link', { name: '갱신 방법 보기' })).toBeInTheDocument();
  });

  it('showLink 가 없으면(RP 관리자) 링크를 그리지 않는다', async () => {
    vi.mocked(licenseApi.get).mockResolvedValue(base);
    renderBanner();
    await screen.findByText(/30일 후 만료됩니다/);
    expect(screen.queryByRole('link')).toBeNull();
  });

  it('이미 /license 에 있으면 링크를 숨긴다', async () => {
    vi.mocked(licenseApi.get).mockResolvedValue(base);
    renderBanner({ showLink: true }, '/license');
    await screen.findByText(/30일 후 만료됩니다/);
    expect(screen.queryByRole('link')).toBeNull();
  });

  it('VALID·SaaS 는 아무것도 그리지 않는다', async () => {
    vi.mocked(licenseApi.get).mockResolvedValue({ ...base, state: 'VALID' });
    const { container } = renderBanner({ showLink: true });
    await vi.waitFor(() => expect(licenseApi.get).toHaveBeenCalled());
    await settle();
    expect(container).toBeEmptyDOMElement();
  });

  it('SaaS 는 WARNING·DEAD 상태여도 아무것도 그리지 않는다', async () => {
    for (const state of ['WARNING', 'DEAD'] as const) {
      vi.mocked(licenseApi.get).mockReset();
      vi.mocked(licenseApi.get).mockResolvedValue({ ...base, deploymentMode: 'saas', state });
      const { container, unmount } = renderBanner({ showLink: true });
      await vi.waitFor(() => expect(licenseApi.get).toHaveBeenCalled());
      await settle();
      expect(container).toBeEmptyDOMElement();
      unmount();
    }
  });

  it('조회가 실패하면 아무것도 그리지 않고 깨지지 않는다', async () => {
    vi.mocked(licenseApi.get).mockRejectedValue(new Error('boom'));
    const { container } = renderBanner({ showLink: true });
    await vi.waitFor(() => expect(licenseApi.get).toHaveBeenCalled());
    await settle();
    expect(container).toBeEmptyDOMElement();
  });

  it('이후 조회가 실패하면 마지막으로 알던 배너를 유지한다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      vi.mocked(licenseApi.get).mockResolvedValueOnce(base).mockRejectedValue(new Error('boom'));
      renderBanner({ showLink: true });
      expect(await screen.findByText(/30일 후 만료됩니다/)).toBeInTheDocument();
      await vi.advanceTimersByTimeAsync(5 * 60 * 1000 + 100);
      expect(licenseApi.get).toHaveBeenCalledTimes(2);
      expect(screen.getByText(/30일 후 만료됩니다/)).toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });
});
