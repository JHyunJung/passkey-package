// admin-ui/src/pages/TenantDetailPage.test.tsx
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, within, cleanup, act, waitFor } from '@testing-library/react';
import { ToastHost } from '@/ui';
import type { Tenant } from '@/api/designTypes';
import type { Me } from '@/api/types';
import { ApiError } from '@/api/types';

vi.mock('@/api/tenants', () => ({ tenantsApi: { get: vi.fn(), suspend: vi.fn(), activate: vi.fn() } }));
vi.mock('@/api/aaguidPolicy', () => ({ aaguidPolicyApi: { get: vi.fn(), update: vi.fn() } }));
vi.mock('@/api/apiKeys', () => ({ apiKeysApi: { list: vi.fn() } }));
vi.mock('@/pages/tenant/TenantOverview', () => ({
  default: (p: { expiringKeyCount?: number; isPlatformOperator?: boolean }) => <div>OVERVIEW expiring={String(p.expiringKeyCount)} platform={String(p.isPlatformOperator)}</div>,
}));
vi.mock('@/pages/tenant/WebauthnConfigTab', () => ({
  default: (p: { onSaved?: () => void }) => <button type="button" onClick={() => p.onSaved?.()}>WEBAUTHN 저장 시뮬레이션</button>,
}));
vi.mock('@/pages/tenant/AaguidPolicyTab', () => ({
  default: (p: { onSaved?: (x: unknown) => void }) => (
    <button type="button" onClick={() => p.onSaved?.({ mode: 'ALLOWLIST', mdsStrict: false, entries: [{ aaguid: 'a', note: null, mdsName: 'YubiKey 5' }] })}>AAGUID 저장 시뮬레이션</button>
  ),
}));
vi.mock('@/pages/tenant/ApiKeysTab', () => ({
  default: (p: { onChanged?: () => void }) => <button type="button" onClick={() => p.onChanged?.()}>APIKEYS 발급 시뮬레이션</button>,
}));
vi.mock('@/pages/tenant/CredentialsTab', () => ({ default: () => <div>CREDENTIALS TAB</div> }));
vi.mock('@/pages/tenant/AuditTab', () => ({ default: (p: { isPlatformOperator: boolean }) => <div>AUDIT TAB platform={String(p.isPlatformOperator)}</div> }));
vi.mock('@/pages/tenant/FunnelTab', () => ({ default: () => <div>FUNNEL TAB</div> }));

import { tenantsApi } from '@/api/tenants';
import { aaguidPolicyApi } from '@/api/aaguidPolicy';
import { apiKeysApi } from '@/api/apiKeys';
import TenantDetailRoute, { TenantDetailPage, fmtDateKst } from './TenantDetailPage';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import '@/test/renderWithDataRouter';

const DAY = 86_400_000;
const T: Tenant = {
  id: 't-1', name: 'Acme', slug: 'acme', rpId: 'passkey.acme.com', status: 'ACTIVE', credentials: 10, apiKeys: 3,
  lastEventAt: null, createdAt: '2026-03-01T16:00:00Z', userVerification: 'REQUIRED', attestationConveyance: 'NONE', webauthnTimeoutMs: 60000,
};
const PLATFORM: Me = { email: 'op@x.com', role: 'PLATFORM_OPERATOR', tenantIds: [], mfaEnabled: true, mfaRequired: false, sessionIdleTimeoutMinutes: 30 };
const RP: Me = { ...PLATFORM, email: 'rp@x.com', role: 'RP_ADMIN', tenantIds: ['t-1'] };

function renderPage({ over = {}, me = PLATFORM, tab = 'overview' }: { over?: Partial<Tenant>; me?: Me; tab?: string } = {}) {
  const onReload = vi.fn();
  const onTabChange = vi.fn();
  render(<ToastHost><TenantDetailPage tenant={{ ...T, ...over }} currentTab={tab} onTabChange={onTabChange} me={me} onReload={onReload} /></ToastHost>);
  return { onReload, onTabChange };
}

beforeEach(() => {
  vi.clearAllMocks();
  (aaguidPolicyApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({ mode: 'ANY', mdsStrict: false, entries: [] });
  (apiKeysApi.list as ReturnType<typeof vi.fn>).mockResolvedValue([]);
  (tenantsApi.suspend as ReturnType<typeof vi.fn>).mockResolvedValue(undefined);
  (tenantsApi.activate as ReturnType<typeof vi.fn>).mockResolvedValue(undefined);
});
afterEach(cleanup);

describe('TenantDetailPage 헤더', () => {
  it('fmtDateKst 는 KST 날짜(YYYY-MM-DD)', () => {
    expect(fmtDateKst('2026-03-01T16:00:00Z')).toBe('2026-03-02');
    expect(fmtDateKst(null)).toBe('—');
  });
  it('이름·상태·RP 사이트 링크·속성 칩·탭 7개', async () => {
    renderPage();
    expect(screen.getByRole('heading', { level: 1, name: 'Acme' })).toBeInTheDocument();
    expect(screen.getByText('활성')).toHaveClass('badge--success');
    const link = screen.getByRole('link', { name: /RP 사이트 열기/ });
    expect(link).toHaveAttribute('href', 'https://passkey.acme.com');
    expect(link).toHaveAttribute('target', '_blank');
    expect(screen.getByRole('button', { name: 'ID 복사' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'RP ID 복사' })).toBeInTheDocument();
    expect(screen.getByText('acme')).toHaveClass('propchip__v');
    expect(screen.getByText('2026-03-02')).toBeInTheDocument();
    expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual(['개요', 'WebAuthn', 'AAGUID 정책', 'API 키', 'Credentials', '감사 로그', '퍼널']);
    await waitFor(() => expect(apiKeysApi.list).toHaveBeenCalledWith('t-1'));
    expect(aaguidPolicyApi.get).toHaveBeenCalledWith('t-1');
  });
  it('탭 클릭은 onTabChange', () => {
    const { onTabChange } = renderPage();
    fireEvent.click(screen.getByRole('tab', { name: /WebAuthn/ }));
    expect(onTabChange).toHaveBeenCalledWith('webauthn');
  });
  it('감사 로그 탭에 PLATFORM 여부를 넘긴다', () => {
    renderPage({ tab: 'audit', me: RP });
    expect(screen.getByText('AUDIT TAB platform=false')).toBeInTheDocument();
  });
});

describe('탭 "확인 필요 N" 배지', () => {
  it('AAGUID: mode≠ANY 이고 mdsName==null 항목 수를 라벨로, 사유는 툴팁', async () => {
    (aaguidPolicyApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({
      mode: 'ALLOWLIST', mdsStrict: false, entries: [{ aaguid: 'a', note: null, mdsName: null }, { aaguid: 'b', note: null, mdsName: null }],
    });
    renderPage();
    const tab = screen.getByRole('tab', { name: /AAGUID 정책/ });
    const badge = await within(tab).findByText('확인 필요 2');
    expect(badge).toHaveAttribute('title', 'MDS 미확인 AAGUID 2건');
    expect(within(screen.getByRole('tab', { name: /API 키/ })).queryByText(/확인 필요/)).toBeNull();
  });
  it('API 키: 30일 이내 만료 ACTIVE 키 수를 라벨로, 개요에 개수 전달', async () => {
    (apiKeysApi.list as ReturnType<typeof vi.fn>).mockResolvedValue([
      { id: 'k1', prefix: 'pk_a', name: 'prod', status: 'ACTIVE', createdAt: '2026-01-01T00:00:00Z', lastUsedAt: null, scopes: [], expiresAt: new Date(Date.now() + 10 * DAY).toISOString() },
      { id: 'k2', prefix: 'pk_b', name: 'old', status: 'REVOKED', createdAt: '2026-01-01T00:00:00Z', lastUsedAt: null, scopes: [], expiresAt: new Date(Date.now() + 5 * DAY).toISOString() },
    ]);
    renderPage();
    const tab = screen.getByRole('tab', { name: /API 키/ });
    expect(await within(tab).findByText('확인 필요 1')).toHaveAttribute('title', '30일 안에 만료되는 키 1개');
    expect(screen.getByText('OVERVIEW expiring=1 platform=true')).toBeInTheDocument();
  });
  it('조건이 없으면 배지가 없다', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText('OVERVIEW expiring=0 platform=true')).toBeInTheDocument());
    expect(screen.queryByText(/확인 필요/)).toBeNull();
  });
  it('신호 조회 실패는 조용히 무시', async () => {
    (aaguidPolicyApi.get as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('403'));
    (apiKeysApi.list as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('403'));
    renderPage();
    await waitFor(() => expect(apiKeysApi.list).toHaveBeenCalled());
    expect(screen.queryByText(/확인 필요/)).toBeNull();
    expect(screen.queryByRole('status')).toBeNull();
  });
  it('AAGUID 저장 후 onSaved 로 배지가 갱신된다', async () => {
    (aaguidPolicyApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({
      mode: 'ALLOWLIST', mdsStrict: false, entries: [{ aaguid: 'a', note: null, mdsName: null }],
    });
    renderPage({ tab: 'aaguid' });
    const tab = screen.getByRole('tab', { name: /AAGUID 정책/ });
    expect(await within(tab).findByText('확인 필요 1')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'AAGUID 저장 시뮬레이션' }));
    expect(within(tab).queryByText(/확인 필요/)).toBeNull();
  });
});

describe('테넌트 정지 / 정지 해제', () => {
  it('PLATFORM 의 ⋯ 트리거는 테두리 있는 버튼(V2 TenantHead)', () => {
    renderPage();
    expect(screen.getByRole('button', { name: '더보기' })).toHaveClass('btn');
  });
  it('RP_ADMIN 에게는 ⋯ 메뉴가 없다(서버 PLATFORM 전용)', () => {
    renderPage({ me: RP });
    expect(screen.queryByRole('button', { name: '더보기' })).toBeNull();
  });
  it('정지: L3 — 제목 동사, 영향 범위, 취소 기본 포커스, slug 일치 전 정지 비활성, 토스트 "회수됨"', async () => {
    const { onReload } = renderPage();
    fireEvent.click(screen.getByRole('button', { name: '더보기' }));
    fireEvent.click(screen.getByRole('menuitem', { name: '테넌트 정지' }));
    const dlg = screen.getByRole('alertdialog', { name: 'Acme을 정지할까요?' });
    expect(within(dlg).getByText('정지하면 이 tenant의 모든 RP 서버 요청이 거부됩니다.')).toBeInTheDocument();
    expect(within(dlg).getByRole('alert')).toHaveTextContent('영향 범위');
    expect(within(dlg).getByText('· 유효한 API 키 3개를 즉시 회수')).toBeInTheDocument();
    expect(within(dlg).getByText('· 이 tenant의 패스키 등록·인증 모두 거부')).toBeInTheDocument();
    expect(dlg).not.toHaveTextContent('ceremony');
    expect(document.activeElement).toBe(within(dlg).getByRole('button', { name: '취소' }));
    expect(within(dlg).getByText('일치해야 정지 버튼이 활성화됩니다.')).toBeInTheDocument();
    const go = within(dlg).getByRole('button', { name: '정지' });
    expect(go).toBeDisabled();
    const input = within(dlg).getByRole('textbox', { name: /확인을 위해 slug/ });
    fireEvent.change(input, { target: { value: 'acm' } });
    expect(go).toBeDisabled();
    fireEvent.change(input, { target: { value: 'acme ' } });
    expect(go).toBeDisabled();
    fireEvent.change(input, { target: { value: 'acme' } });
    expect(go).toBeEnabled();
    await act(async () => { fireEvent.click(go); });
    expect(tenantsApi.suspend).toHaveBeenCalledWith('t-1');
    expect(onReload).toHaveBeenCalled();
    expect(screen.queryByRole('alertdialog')).toBeNull();
    expect(screen.getByText('테넌트가 정지되었습니다.')).toBeInTheDocument();
    expect(screen.getByText('acme · 모든 API 키 회수됨')).toBeInTheDocument();
    expect(screen.queryByText(/revoke/)).toBeNull();
  });
  it('정지 실패는 다이얼로그 안 인라인 오류, 닫히지 않고 토스트도 없다', async () => {
    (tenantsApi.suspend as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('boom'));
    const { onReload } = renderPage();
    fireEvent.click(screen.getByRole('button', { name: '더보기' }));
    fireEvent.click(screen.getByRole('menuitem', { name: '테넌트 정지' }));
    const dlg = screen.getByRole('alertdialog', { name: 'Acme을 정지할까요?' });
    fireEvent.change(within(dlg).getByRole('textbox', { name: /확인을 위해 slug/ }), { target: { value: 'acme' } });
    await act(async () => { fireEvent.click(within(dlg).getByRole('button', { name: '정지' })); });
    expect(within(dlg).getByText('정지하지 못했습니다')).toBeInTheDocument();
    expect(screen.getByRole('alertdialog', { name: 'Acme을 정지할까요?' })).toBeInTheDocument();
    expect(onReload).not.toHaveBeenCalled();
    expect(screen.queryByText('정지 실패')).toBeNull();
  });
  it('취소하면 다시 열 때 입력이 초기화된다', () => {
    renderPage();
    fireEvent.click(screen.getByRole('button', { name: '더보기' }));
    fireEvent.click(screen.getByRole('menuitem', { name: '테넌트 정지' }));
    fireEvent.change(screen.getByRole('textbox', { name: /확인을 위해 slug/ }), { target: { value: 'acme' } });
    fireEvent.click(screen.getByRole('button', { name: '취소' }));
    fireEvent.click(screen.getByRole('button', { name: '더보기' }));
    fireEvent.click(screen.getByRole('menuitem', { name: '테넌트 정지' }));
    expect(screen.getByRole('textbox', { name: /확인을 위해 slug/ })).toHaveValue('');
    expect(screen.getByRole('button', { name: '정지' })).toBeDisabled();
    expect(tenantsApi.suspend).not.toHaveBeenCalled();
  });
  it('정지 해제: SUSPENDED 면 메뉴에 정지 해제 → 확인 다이얼로그 → activate', async () => {
    const { onReload } = renderPage({ over: { status: 'SUSPENDED' } });
    expect(screen.getByText('정지')).toHaveClass('badge--danger');
    fireEvent.click(screen.getByRole('button', { name: '더보기' }));
    expect(screen.queryByRole('menuitem', { name: '테넌트 정지' })).toBeNull();
    fireEvent.click(screen.getByRole('menuitem', { name: '정지 해제' }));
    const dlg = screen.getByRole('alertdialog', { name: '정지 해제' });
    expect(within(dlg).getByText(/이 테넌트의 정지를 해제하시겠습니까\?/)).toBeInTheDocument();
    expect(within(dlg).getByText(/패스키 등록·인증이 다시 허용됩니다/)).toBeInTheDocument();
    await act(async () => { fireEvent.click(within(dlg).getByRole('button', { name: '정지 해제' })); });
    expect(tenantsApi.activate).toHaveBeenCalledWith('t-1');
    expect(onReload).toHaveBeenCalled();
    expect(screen.queryByRole('alertdialog', { name: '정지 해제' })).toBeNull();
  });
  it('정지 해제 취소는 호출하지 않는다', () => {
    renderPage({ over: { status: 'SUSPENDED' } });
    fireEvent.click(screen.getByRole('button', { name: '더보기' }));
    fireEvent.click(screen.getByRole('menuitem', { name: '정지 해제' }));
    fireEvent.click(screen.getByRole('button', { name: '취소' }));
    expect(tenantsApi.activate).not.toHaveBeenCalled();
  });
});

describe('편집 후 tenant 셸 갱신 (M5)', () => {
  it('WebAuthn 저장 후 onReload — 개요 요약이 새 값을 보인다', () => {
    const { onReload } = renderPage({ tab: 'webauthn' });
    fireEvent.click(screen.getByRole('button', { name: 'WEBAUTHN 저장 시뮬레이션' }));
    expect(onReload).toHaveBeenCalledTimes(1);
  });
  it('API 키 발급·회전·회수 후 onReload — 탭 배지와 키 수가 갱신된다', () => {
    const { onReload } = renderPage({ tab: 'apikeys' });
    fireEvent.click(screen.getByRole('button', { name: 'APIKEYS 발급 시뮬레이션' }));
    expect(onReload).toHaveBeenCalledTimes(1);
  });
});

describe('TenantDetailRoute (M14)', () => {
  it('A → B 로 빠르게 이동하면 늦게 온 A 응답을 B 주소에 그리지 않는다', async () => {
    let resolveA: (t: Tenant) => void = () => {};
    (tenantsApi.get as ReturnType<typeof vi.fn>).mockImplementation((id: string) =>
      id === 'a' ? new Promise((r) => { resolveA = r; }) : Promise.resolve({ ...T, id: 'b', name: 'Beta' }));
    const router = createMemoryRouter(
      [{ path: '/tenants/:id', element: <ToastHost><TenantDetailRoute me={PLATFORM} /></ToastHost> }],
      { initialEntries: ['/tenants/a'] },
    );
    render(<RouterProvider router={router} />);
    await act(async () => { await router.navigate('/tenants/b'); });
    expect(await screen.findByRole('heading', { name: 'Beta' })).toBeInTheDocument();
    await act(async () => { resolveA({ ...T, id: 'a', name: 'Alpha' }); });
    expect(screen.queryByRole('heading', { name: 'Alpha' })).toBeNull();
    expect(screen.getByRole('heading', { name: 'Beta' })).toBeInTheDocument();
  });

  function renderRoute(path = '/tenants/t-1') {
    const router = createMemoryRouter(
      [
        { path: '/tenants/:id', element: <ToastHost><TenantDetailRoute me={PLATFORM} /></ToastHost> },
        { path: '/tenants', element: <div>TENANTS LIST</div> },
      ],
      { initialEntries: [path] },
    );
    render(<RouterProvider router={router} />);
    return router;
  }

  it('로딩 중에는 Skeleton, "Loading…" 텍스트 없음', async () => {
    let settle: (t: Tenant) => void = () => {};
    (tenantsApi.get as ReturnType<typeof vi.fn>).mockReturnValue(new Promise<Tenant>((r) => { settle = r; }));
    renderRoute();
    expect(screen.getByRole('status', { name: 'Tenant 정보 불러오는 중' })).toBeInTheDocument();
    expect(screen.queryByText('Loading…')).toBeNull();
    await act(async () => { settle(T); });
    expect(await screen.findByRole('heading', { level: 1, name: 'Acme' })).toBeInTheDocument();
  });

  it('404 면 "찾을 수 없습니다" + Tenants로 돌아가기(다시 시도 없음)', async () => {
    (tenantsApi.get as ReturnType<typeof vi.fn>).mockRejectedValue(new ApiError(404, 'C404', 'not found'));
    const router = renderRoute();
    expect(await screen.findByText('Tenant를 찾을 수 없습니다')).toBeInTheDocument();
    expect(screen.getByText('삭제됐거나 주소가 잘못된 tenant입니다.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '다시 시도' })).toBeNull();
    await act(async () => { fireEvent.click(screen.getByRole('link', { name: 'Tenants로 돌아가기' })); });
    expect(router.state.location.pathname).toBe('/tenants');
  });

  it('5xx 면 LoadError(다시 시도) — 다시 시도 성공 시 상세를 그린다', async () => {
    (tenantsApi.get as ReturnType<typeof vi.fn>)
      .mockRejectedValueOnce(new ApiError(503, 'C999', 'HTTP 503', undefined, 'tr-9'))
      .mockResolvedValueOnce(T);
    renderRoute();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Tenant 정보를 불러오지 못했습니다');
    expect(alert).toHaveTextContent('서버가 잠시 응답하지 않습니다');
    expect(alert).toHaveTextContent('tr-9');
    expect(screen.getByRole('link', { name: '‹ Tenant 목록으로' })).toHaveAttribute('href', '/tenants');
    expect(screen.queryByText('Tenant를 찾을 수 없습니다')).toBeNull();
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '다시 시도' })); });
    expect(await screen.findByRole('heading', { level: 1, name: 'Acme' })).toBeInTheDocument();
    expect(tenantsApi.get).toHaveBeenCalledTimes(2);
  });

  it('로드 실패를 토스트로 띄우지 않는다(인라인 하나만)', async () => {
    (tenantsApi.get as ReturnType<typeof vi.fn>).mockRejectedValue(new ApiError(503, 'C999', 'HTTP 503'));
    renderRoute();
    await screen.findByText('Tenant 정보를 불러오지 못했습니다');
    expect(screen.queryByText('Tenant 로드 실패')).toBeNull();
    expect(screen.getAllByRole('alert')).toHaveLength(1);
  });

  it('상세를 연 뒤 재조회가 503 으로 실패하면 상세를 유지하고 경고 + 다시 시도, 성공하면 경고가 사라진다', async () => {
    (tenantsApi.get as ReturnType<typeof vi.fn>)
      .mockResolvedValueOnce(T)
      .mockRejectedValueOnce(new ApiError(503, 'C999', 'HTTP 503'))
      .mockResolvedValueOnce({ ...T, name: 'Acme2' });
    renderRoute('/tenants/t-1?tab=webauthn');
    expect(await screen.findByRole('heading', { level: 1, name: 'Acme' })).toBeInTheDocument();
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'WEBAUTHN 저장 시뮬레이션' })); });
    const warn = await screen.findByText('Tenant 정보를 새로 고치지 못했습니다');
    expect(warn).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: 'Acme' })).toBeInTheDocument();
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '다시 시도' })); });
    expect(await screen.findByRole('heading', { level: 1, name: 'Acme2' })).toBeInTheDocument();
    expect(screen.queryByText('Tenant 정보를 새로 고치지 못했습니다')).toBeNull();
  });

  it('상세를 연 뒤 재조회가 404 면 not-found 상태를 보인다', async () => {
    (tenantsApi.get as ReturnType<typeof vi.fn>)
      .mockResolvedValueOnce(T)
      .mockRejectedValueOnce(new ApiError(404, 'C404', 'not found'));
    renderRoute('/tenants/t-1?tab=webauthn');
    expect(await screen.findByRole('heading', { level: 1, name: 'Acme' })).toBeInTheDocument();
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'WEBAUTHN 저장 시뮬레이션' })); });
    expect(await screen.findByText('Tenant를 찾을 수 없습니다')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { level: 1, name: 'Acme' })).toBeNull();
  });

  it('재조회가 실패해 정보가 이전 내용이면 정지 메뉴는 비활성 + 사유, 다시 시도에 성공하면 다시 활성', async () => {
    (tenantsApi.get as ReturnType<typeof vi.fn>)
      .mockResolvedValueOnce(T)
      .mockRejectedValueOnce(new ApiError(503, 'C999', 'HTTP 503'))
      .mockResolvedValueOnce(T);
    renderRoute('/tenants/t-1?tab=webauthn');
    expect(await screen.findByRole('heading', { level: 1, name: 'Acme' })).toBeInTheDocument();
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'WEBAUTHN 저장 시뮬레이션' })); });
    await screen.findByText('Tenant 정보를 새로 고치지 못했습니다');
    fireEvent.click(screen.getByRole('button', { name: '더보기' }));
    const item = screen.getByRole('menuitem', { name: /테넌트 정지/ });
    expect(item).toHaveAttribute('aria-disabled', 'true');
    expect(item).toHaveTextContent('정보를 새로 고친 뒤 다시 시도하세요.');
    fireEvent.click(item);
    expect(screen.queryByRole('alertdialog')).toBeNull();
    fireEvent.keyDown(item, { key: 'Escape' });
    expect(screen.queryByRole('menuitem')).toBeNull();
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '다시 시도' })); });
    await waitFor(() => expect(screen.queryByText('Tenant 정보를 새로 고치지 못했습니다')).toBeNull());
    fireEvent.click(screen.getByRole('button', { name: '더보기' }));
    expect(screen.getByRole('menuitem', { name: '테넌트 정지' })).not.toHaveAttribute('aria-disabled', 'true');
  });

  it('정지 다이얼로그를 연 뒤 재조회가 실패하면 확인해도 정지하지 않고 다이얼로그 안에 이유를 보인다', async () => {
    let failRefresh!: (e: unknown) => void;
    (tenantsApi.get as ReturnType<typeof vi.fn>)
      .mockResolvedValueOnce(T)
      .mockReturnValueOnce(new Promise((_, rej) => { failRefresh = rej; }));
    renderRoute('/tenants/t-1?tab=webauthn');
    expect(await screen.findByRole('heading', { level: 1, name: 'Acme' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '더보기' }));
    fireEvent.click(screen.getByRole('menuitem', { name: '테넌트 정지' }));
    const dlg = screen.getByRole('alertdialog', { name: 'Acme을 정지할까요?' });
    fireEvent.change(within(dlg).getByRole('textbox', { name: /확인을 위해 slug/ }), { target: { value: 'acme' } });
    // 다이얼로그가 열린 채로 재조회가 시작돼 실패한다.
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'WEBAUTHN 저장 시뮬레이션' })); });
    await act(async () => { failRefresh(new ApiError(503, 'C999', 'HTTP 503')); });
    await act(async () => { fireEvent.click(within(dlg).getByRole('button', { name: '정지' })); });
    const inline = within(dlg).getAllByRole('alert').find((a) => a.textContent?.includes('정지하지 못했습니다'));
    expect(inline).toBeDefined();
    expect(inline).toHaveTextContent('정보를 새로 고친 뒤 다시 시도하세요.');
    expect(tenantsApi.suspend).not.toHaveBeenCalled();
  });
});
