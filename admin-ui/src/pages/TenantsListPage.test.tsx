// admin-ui/src/pages/TenantsListPage.test.tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, fireEvent, within } from '@testing-library/react';
import { renderWithDataRouter } from '@/test/renderWithDataRouter';
import { ToastHost } from '@/ui';
import type { Tenant } from '@/api/designTypes';
import type { ApiKeyView } from '@/api/types';
import { ApiError } from '@/api/types';

vi.mock('@/api/tenants', () => ({ tenantsApi: { list: vi.fn(), create: vi.fn() } }));
vi.mock('@/api/apiKeys', () => ({ apiKeysApi: { listAll: vi.fn() } }));

import { tenantsApi } from '@/api/tenants';
import { apiKeysApi } from '@/api/apiKeys';
import TenantsListPage from './TenantsListPage';

function tenant(id: string, over: Partial<Tenant> = {}): Tenant {
  return {
    id, name: `Tenant ${id}`, slug: `slug-${id}`, rpId: `${id}.example.org`, status: 'ACTIVE',
    credentials: 10, apiKeys: 2, lastEventAt: null, createdAt: '2026-10-01T00:00:00Z',
    userVerification: 'REQUIRED', attestationConveyance: 'NONE', webauthnTimeoutMs: 60000, ...over,
  };
}
const key = (over: Partial<ApiKeyView>): ApiKeyView => ({
  id: 'k', keyPrefix: 'pk_x', name: 'n', tenantId: 'a1', scopes: [], createdAt: '2026-01-01T00:00:00Z', ...over,
});
const DAY = 86_400_000;
const fromNow = (ms: number) => new Date(Date.now() + ms).toISOString();

function renderPage() {
  return renderWithDataRouter(<ToastHost><TenantsListPage /></ToastHost>, { initialPath: '/tenants' });
}

beforeEach(() => {
  vi.mocked(tenantsApi.list).mockReset();
  vi.mocked(apiKeysApi.listAll).mockReset().mockResolvedValue([]);
});

describe('TenantsListPage V2', () => {
  const three = [tenant('a1'), tenant('a2'), tenant('s1', { status: 'SUSPENDED' })];

  it('활성 Tenant KPI 클릭 = 상태 필터 ACTIVE 토글', async () => {
    vi.mocked(tenantsApi.list).mockResolvedValue(three);
    renderPage();
    await screen.findByText('Tenant s1');
    const kpi = screen.getByRole('button', { name: /활성 Tenant/ });
    expect(kpi).toHaveAttribute('aria-pressed', 'false');

    fireEvent.click(kpi);
    expect(kpi).toHaveAttribute('aria-pressed', 'true');
    expect(screen.queryByText('Tenant s1')).toBeNull();
    expect(screen.getByText('Tenant a1')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '활성 2' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByText('상태: 활성')).toBeInTheDocument();

    fireEvent.click(kpi);
    expect(kpi).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByText('Tenant s1')).toBeInTheDocument();
  });

  it('세그먼트는 상태별 건수를 보이고 선택한 상태만 남긴다', async () => {
    vi.mocked(tenantsApi.list).mockResolvedValue(three);
    renderPage();
    await screen.findByText('Tenant a1');
    expect(screen.getByRole('button', { name: '전체 3' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: '활성 2' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '정지 1' }));
    expect(screen.queryByText('Tenant a1')).toBeNull();
    expect(screen.getByText('Tenant s1')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /활성 Tenant/ })).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByText('1 / 3건')).toBeInTheDocument();
  });

  it('적용 필터 칩 제거와 모두 지우기', async () => {
    vi.mocked(tenantsApi.list).mockResolvedValue(three);
    renderPage();
    await screen.findByText('Tenant a1');
    fireEvent.click(screen.getByRole('button', { name: '정지 1' }));
    fireEvent.change(screen.getByRole('textbox', { name: 'tenant 검색' }), { target: { value: 's1' } });
    expect(screen.getByText('검색: s1')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '상태 필터 제거' }));
    expect(screen.queryByText('상태: 정지')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '모두 지우기' }));
    expect(screen.queryByText('적용된 필터')).toBeNull();
    expect(screen.getByText('3 / 3건')).toBeInTheDocument();
  });

  it('tenant 0개: emptyTenants 빈 상태(검색 결과 없음과 구분)', async () => {
    vi.mocked(tenantsApi.list).mockResolvedValue([]);
    renderPage();
    expect(await screen.findByText('아직 tenant 가 없습니다')).toBeInTheDocument();
    expect(screen.queryByText('검색 결과가 없습니다')).toBeNull();
    expect(screen.queryByRole('table')).toBeNull();
  });

  it('검색 결과 없음: noResults 빈 상태', async () => {
    vi.mocked(tenantsApi.list).mockResolvedValue(three);
    renderPage();
    await screen.findByText('Tenant a1');
    fireEvent.change(screen.getByRole('textbox', { name: 'tenant 검색' }), { target: { value: 'zzz' } });
    expect(screen.getByText('검색 결과가 없습니다')).toBeInTheDocument();
    expect(screen.queryByText('아직 tenant 가 없습니다')).toBeNull();
  });

  it('로드 실패: 빈 목록 안내 대신 불러오기 실패 상태(KPI 는 "—", 오류 토스트 중복 없음)', async () => {
    vi.mocked(tenantsApi.list).mockRejectedValue(new Error('boom'));
    renderPage();
    expect(await screen.findByText('tenant 목록을 불러오지 못했습니다')).toBeInTheDocument();
    expect(screen.queryByText('아직 tenant 가 없습니다')).toBeNull();
    expect(screen.getAllByText('—')).toHaveLength(3);
    expect(screen.queryByRole('button', { name: /활성 Tenant/ })).toBeNull();
    expect(screen.queryByRole('button', { name: 'CSV' })).toBeNull();
    expect(screen.getAllByRole('alert')).toHaveLength(1);
  });

  it('만료 임박 N = ACTIVE 키 중 30일 이내 만료', async () => {
    vi.mocked(tenantsApi.list).mockResolvedValue(three);
    vi.mocked(apiKeysApi.listAll).mockResolvedValue([
      key({ id: 'k1', expiresAt: fromNow(10 * DAY) }),
      key({ id: 'k2', expiresAt: fromNow(40 * DAY) }),
      key({ id: 'k3', expiresAt: fromNow(5 * DAY), revokedAt: fromNow(-DAY) }),
      key({ id: 'k4', expiresAt: fromNow(-DAY) }),
      key({ id: 'k5' }),
    ]);
    renderPage();
    expect(await screen.findByText('만료 임박 1')).toBeInTheDocument();
  });

  it('listAll 실패 시 만료 임박 표시를 숨긴다', async () => {
    vi.mocked(tenantsApi.list).mockResolvedValue(three);
    vi.mocked(apiKeysApi.listAll).mockRejectedValue(new Error('403'));
    renderPage();
    await screen.findByText('Tenant a1');
    expect(screen.queryByText(/만료 임박/)).toBeNull();
  });

  it('credentialsDelta7d 없으면 7일 증감 미표시, 있으면 합계 표시', async () => {
    vi.mocked(tenantsApi.list).mockResolvedValue(three);
    const { unmount } = renderPage();
    await screen.findByText('Tenant a1');
    expect(screen.getByText('모든 tenant 합산')).toBeInTheDocument();
    expect(screen.queryByText(/7일/)).toBeNull();
    unmount();

    vi.mocked(tenantsApi.list).mockResolvedValue([tenant('a1', { credentialsDelta7d: 3 }), tenant('a2', { credentialsDelta7d: 2 })]);
    renderPage();
    expect(await screen.findByText('모든 tenant 합산 · 7일 +5')).toBeInTheDocument();
  });

  it('행 클릭 → tenant 개요로 이동', async () => {
    vi.mocked(tenantsApi.list).mockResolvedValue(three);
    const { router } = renderPage();
    fireEvent.click(await screen.findByText('Tenant a1'));
    expect(router.state.location.pathname).toBe('/tenants/a1');
    expect(router.state.location.search).toBe('?tab=overview');
  });

  it('클라이언트 페이지네이션 50건', async () => {
    vi.mocked(tenantsApi.list).mockResolvedValue(Array.from({ length: 60 }, (_, i) => tenant(`n${i + 1}`)));
    renderPage();
    await screen.findByText('Tenant n1');
    expect(screen.getByText('1–50 / 60건')).toBeInTheDocument();
    expect(screen.queryByText('Tenant n55')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '다음 페이지' }));
    expect(screen.getByText('51–60 / 60건')).toBeInTheDocument();
    expect(screen.getByText('Tenant n55')).toBeInTheDocument();
    const table = screen.getByRole('table');
    expect(within(table).queryByText('Tenant n1')).toBeNull();
  });

  it('V3: 불러오는 동안 Skeleton, KPI 는 0 이 아니라 "—"', async () => {
    vi.mocked(tenantsApi.list).mockReturnValue(new Promise(() => {}));
    renderPage();
    expect(await screen.findByRole('status', { name: '불러오는 중' })).toHaveAttribute('aria-busy', 'true');
    expect(screen.queryByText('불러오는 중…')).toBeNull();
    expect(screen.getAllByText('—')).toHaveLength(3);
    expect(screen.queryByText('0')).toBeNull();
  });

  it('V3: 로드 실패 → 다시 시도하면 목록을 다시 불러온다', async () => {
    vi.mocked(tenantsApi.list)
      .mockRejectedValueOnce(new ApiError(503, 'C999', 'HTTP 503'))
      .mockResolvedValueOnce(three);
    renderPage();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('서버가 잠시 응답하지 않습니다');
    expect(alert).toHaveTextContent('HTTP 503');
    fireEvent.click(within(alert).getByRole('button', { name: '다시 시도' }));
    expect(await screen.findByText('Tenant a1')).toBeInTheDocument();
    expect(tenantsApi.list).toHaveBeenCalledTimes(2);
    expect(screen.getByRole('button', { name: /활성 Tenant/ })).toHaveTextContent('2');
  });
});
