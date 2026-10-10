// admin-ui/src/pages/TenantDetailPage.test.tsx
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, within, cleanup, act, waitFor } from '@testing-library/react';
import { ToastHost } from '@/ui';
import type { Tenant } from '@/api/designTypes';
import type { Me } from '@/api/types';

vi.mock('@/api/tenants', () => ({ tenantsApi: { get: vi.fn(), suspend: vi.fn(), activate: vi.fn() } }));
vi.mock('@/api/aaguidPolicy', () => ({ aaguidPolicyApi: { get: vi.fn(), update: vi.fn() } }));
vi.mock('@/api/apiKeys', () => ({ apiKeysApi: { list: vi.fn() } }));
vi.mock('@/pages/tenant/TenantOverview', () => ({
  default: (p: { expiringKeyCount?: number; isPlatformOperator?: boolean }) => <div>OVERVIEW expiring={String(p.expiringKeyCount)} platform={String(p.isPlatformOperator)}</div>,
}));
vi.mock('@/pages/tenant/WebauthnConfigTab', () => ({ default: () => <div>WEBAUTHN TAB</div> }));
vi.mock('@/pages/tenant/AaguidPolicyTab', () => ({ default: () => <div>AAGUID TAB</div> }));
vi.mock('@/pages/tenant/ApiKeysTab', () => ({ default: () => <div>APIKEYS TAB</div> }));
vi.mock('@/pages/tenant/CredentialsTab', () => ({ default: () => <div>CREDENTIALS TAB</div> }));
vi.mock('@/pages/tenant/AuditTab', () => ({ default: (p: { isPlatformOperator: boolean }) => <div>AUDIT TAB platform={String(p.isPlatformOperator)}</div> }));
vi.mock('@/pages/tenant/FunnelTab', () => ({ default: () => <div>FUNNEL TAB</div> }));

import { tenantsApi } from '@/api/tenants';
import { aaguidPolicyApi } from '@/api/aaguidPolicy';
import { apiKeysApi } from '@/api/apiKeys';
import { TenantDetailPage, fmtDateKst } from './TenantDetailPage';

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

describe('탭 점 배지', () => {
  it('AAGUID: mode≠ANY 이고 mdsName==null 항목이 있으면 점', async () => {
    (aaguidPolicyApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({
      mode: 'ALLOWLIST', mdsStrict: false, entries: [{ aaguid: 'a', note: null, mdsName: null }],
    });
    renderPage();
    const tab = screen.getByRole('tab', { name: /AAGUID 정책/ });
    expect(await within(tab).findByLabelText('확인 필요')).toBeInTheDocument();
    expect(within(screen.getByRole('tab', { name: /API 키/ })).queryByLabelText('확인 필요')).toBeNull();
  });
  it('API 키: 30일 이내 만료 ACTIVE 키가 있으면 점, 개요에 개수 전달', async () => {
    (apiKeysApi.list as ReturnType<typeof vi.fn>).mockResolvedValue([
      { id: 'k1', prefix: 'pk_a', name: 'prod', status: 'ACTIVE', createdAt: '2026-01-01T00:00:00Z', lastUsedAt: null, scopes: [], expiresAt: new Date(Date.now() + 10 * DAY).toISOString() },
      { id: 'k2', prefix: 'pk_b', name: 'old', status: 'REVOKED', createdAt: '2026-01-01T00:00:00Z', lastUsedAt: null, scopes: [], expiresAt: new Date(Date.now() + 5 * DAY).toISOString() },
    ]);
    renderPage();
    const tab = screen.getByRole('tab', { name: /API 키/ });
    expect(await within(tab).findByLabelText('확인 필요')).toBeInTheDocument();
    expect(screen.getByText('OVERVIEW expiring=1 platform=true')).toBeInTheDocument();
  });
  it('조건이 없으면 점이 없다', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText('OVERVIEW expiring=0 platform=true')).toBeInTheDocument());
    expect(screen.queryByLabelText('확인 필요')).toBeNull();
  });
  it('신호 조회 실패는 조용히 무시', async () => {
    (aaguidPolicyApi.get as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('403'));
    (apiKeysApi.list as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('403'));
    renderPage();
    await waitFor(() => expect(apiKeysApi.list).toHaveBeenCalled());
    expect(screen.queryByLabelText('확인 필요')).toBeNull();
    expect(screen.queryByRole('status')).toBeNull();
  });
});

describe('테넌트 정지 / 정지 해제', () => {
  it('RP_ADMIN 에게는 ⋯ 메뉴가 없다(서버 PLATFORM 전용)', () => {
    renderPage({ me: RP });
    expect(screen.queryByRole('button', { name: '더보기' })).toBeNull();
  });
  it('정지: alertdialog, 영향 범위, 취소 기본 포커스, slug 일치 전 정지 비활성', async () => {
    const { onReload } = renderPage();
    fireEvent.click(screen.getByRole('button', { name: '더보기' }));
    fireEvent.click(screen.getByRole('menuitem', { name: '테넌트 정지' }));
    const dlg = screen.getByRole('alertdialog', { name: '테넌트 정지' });
    expect(within(dlg).getByText('Acme 을(를) 정지합니다.')).toBeInTheDocument();
    expect(within(dlg).getByRole('alert')).toHaveTextContent('영향 범위');
    expect(within(dlg).getByText('· 유효 API key 3개 즉시 회수')).toBeInTheDocument();
    expect(document.activeElement).toBe(within(dlg).getByRole('button', { name: '취소' }));
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
    const dlg = screen.getByRole('dialog', { name: '정지 해제' });
    expect(within(dlg).getByText(/이 테넌트의 정지를 해제하시겠습니까\?/)).toBeInTheDocument();
    await act(async () => { fireEvent.click(within(dlg).getByRole('button', { name: '정지 해제' })); });
    expect(tenantsApi.activate).toHaveBeenCalledWith('t-1');
    expect(onReload).toHaveBeenCalled();
    expect(screen.queryByRole('dialog', { name: '정지 해제' })).toBeNull();
  });
  it('정지 해제 취소는 호출하지 않는다', () => {
    renderPage({ over: { status: 'SUSPENDED' } });
    fireEvent.click(screen.getByRole('button', { name: '더보기' }));
    fireEvent.click(screen.getByRole('menuitem', { name: '정지 해제' }));
    fireEvent.click(screen.getByRole('button', { name: '취소' }));
    expect(tenantsApi.activate).not.toHaveBeenCalled();
  });
});
