// admin-ui/src/pages/settings/AdminUsersTab.test.tsx
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, cleanup, within } from '@testing-library/react';
import { ToastHost } from '@/ui';

vi.mock('@/api/adminUsers', () => ({
  adminUsersApi: {
    list: vi.fn().mockResolvedValue([]),
    suspend: vi.fn(), activate: vi.fn(), addTenant: vi.fn(), removeTenant: vi.fn(),
  },
  adminFetch: vi.fn(),
}));
vi.mock('@/api/signupRequests', () => ({
  signupRequestsApi: {
    list: vi.fn(),
    approve: vi.fn().mockResolvedValue({}),
    reject: vi.fn().mockResolvedValue(undefined),
  },
}));
vi.mock('@/api/tenants', () => ({
  tenantsApi: {
    list: vi.fn().mockResolvedValue([
      { id: 't1', name: 'Acme', slug: 'acme', status: 'ACTIVE' },
      { id: 't2', name: 'Globex', slug: 'globex', status: 'ACTIVE' },
      { id: 't3', name: 'Old Corp', slug: 'old', status: 'SUSPENDED' },
    ]),
  },
}));

import { adminUsersApi } from '@/api/adminUsers';
import { signupRequestsApi } from '@/api/signupRequests';
import { ApiError } from '@/api/types';
import AdminUsersTab from './AdminUsersTab';

const mock = (fn: unknown) => fn as ReturnType<typeof vi.fn>;
const pendingOne = [{ id: 'r1', email: 'new@x.com', reason: 'RP 담당', requestedAt: new Date().toISOString() }];
const base = { createdAt: '2026-01-01T00:00:00Z', suspendedAt: null, createdBy: null };
const USERS = [
  { ...base, id: 'u-platform-0001', email: 'ops@crosscert.com', role: 'PLATFORM_OPERATOR', status: 'ACTIVE', tenantIds: [], lastLoginAt: new Date().toISOString(), mfaEnabled: true },
  { ...base, id: 'u-rp-000000002', email: 'rp@acme.com', role: 'RP_ADMIN', status: 'ACTIVE', tenantIds: ['t1'], lastLoginAt: null, mfaEnabled: false },
  { ...base, id: 'u-rp-000000003', email: 'gone@globex.com', role: 'RP_ADMIN', status: 'SUSPENDED', tenantIds: ['t2', 't3'], lastLoginAt: null, mfaEnabled: true },
];

beforeEach(() => {
  vi.clearAllMocks();
  mock(adminUsersApi.list).mockResolvedValue([]);
  mock(adminUsersApi.suspend).mockResolvedValue(undefined);
  mock(adminUsersApi.activate).mockResolvedValue(undefined);
  mock(signupRequestsApi.list).mockResolvedValue(pendingOne);
  mock(signupRequestsApi.approve).mockResolvedValue({});
  mock(signupRequestsApi.reject).mockResolvedValue(undefined);
});
afterEach(cleanup);

function renderTab(props: { onPendingCountChange?: (n: number) => void } = {}) {
  return render(<ToastHost><AdminUsersTab {...props} /></ToastHost>);
}
const approveDialog = () => screen.getByRole('dialog', { name: '가입 요청 승인' });

describe('AdminUsersTab signup requests', () => {
  it('renders pending requests with count badge, email and reason', async () => {
    renderTab();
    await waitFor(() => expect(screen.getByText('new@x.com')).toBeInTheDocument());
    expect(screen.getByText('RP 담당')).toBeInTheDocument();
    expect(screen.getByText('대기 1건')).toHaveClass('badge--warning');
    expect(screen.getByRole('button', { name: '거절' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '승인…' })).toBeInTheDocument();
  });

  it('reason 이 없으면 "요청 사유 없음"', async () => {
    mock(signupRequestsApi.list).mockResolvedValue([{ ...pendingOne[0], reason: null }]);
    renderTab();
    expect(await screen.findByText('요청 사유 없음')).toBeInTheDocument();
  });

  it('shows empty notice when no request is pending', async () => {
    mock(signupRequestsApi.list).mockResolvedValue([]);
    renderTab();
    await waitFor(() => expect(screen.getByText('대기 중인 요청이 없습니다')).toBeInTheDocument());
    expect(screen.getByText('대기 0건')).not.toHaveClass('badge--warning');
  });

  it('대기 건수를 상위(SettingsPage 탭 배지)에 알린다', async () => {
    const onPendingCountChange = vi.fn();
    renderTab({ onPendingCountChange });
    await waitFor(() => expect(onPendingCountChange).toHaveBeenCalledWith(1));
  });

  it('approve opens a dialog showing the requester and submits role + tenants', async () => {
    renderTab();
    await waitFor(() => screen.getByText('new@x.com'));
    fireEvent.click(screen.getByRole('button', { name: '승인…' }));
    const dlg = approveDialog();
    expect(within(dlg).getByText('new@x.com')).toBeInTheDocument();
    expect(within(dlg).getByRole('radio', { name: /RP Admin/ })).toHaveAttribute('aria-checked', 'true');
    await waitFor(() => within(dlg).getByText('Acme'));   // tenant 목록 로드(ACTIVE 만)
    expect(within(dlg).queryByText('Old Corp')).toBeNull();
    expect(within(dlg).getByRole('checkbox', { name: /Acme/ })).toBeChecked();
    expect(within(dlg).getByText('1개 선택')).toBeInTheDocument();
    fireEvent.click(within(dlg).getByRole('button', { name: '승인하고 계정 생성' }));
    await waitFor(() =>
      expect(signupRequestsApi.approve).toHaveBeenCalledWith('r1', { role: 'RP_ADMIN', tenantIds: ['t1'] }),
    );
  });

  it('RP Admin 으로 tenant 0개면 오류를 보이고 API 를 부르지 않는다', async () => {
    renderTab();
    await waitFor(() => screen.getByText('new@x.com'));
    fireEvent.click(screen.getByRole('button', { name: '승인…' }));
    const dlg = approveDialog();
    await waitFor(() => within(dlg).getByText('Acme'));
    fireEvent.click(within(dlg).getByRole('checkbox', { name: /Acme/ }));
    expect(within(dlg).getByText('Tenant를 최소 1개 선택해야 합니다.')).toBeInTheDocument();
    fireEvent.click(within(dlg).getByRole('button', { name: '승인하고 계정 생성' }));
    expect(signupRequestsApi.approve).not.toHaveBeenCalled();
  });

  it('tenant 검색으로 목록을 좁힌다', async () => {
    renderTab();
    await waitFor(() => screen.getByText('new@x.com'));
    fireEvent.click(screen.getByRole('button', { name: '승인…' }));
    const dlg = approveDialog();
    await waitFor(() => within(dlg).getByText('Acme'));
    fireEvent.change(within(dlg).getByRole('textbox', { name: 'tenant 검색' }), { target: { value: 'glob' } });
    expect(within(dlg).queryByText('Acme')).toBeNull();
    expect(within(dlg).getByText('Globex')).toBeInTheDocument();
    fireEvent.change(within(dlg).getByRole('textbox', { name: 'tenant 검색' }), { target: { value: 'zzz' } });
    expect(within(dlg).getByText('일치하는 tenant 없음')).toBeInTheDocument();
  });

  it('Platform Operator 를 고르면 tenant 없이 승인', async () => {
    renderTab();
    await waitFor(() => screen.getByText('new@x.com'));
    fireEvent.click(screen.getByRole('button', { name: '승인…' }));
    const dlg = approveDialog();
    fireEvent.click(within(dlg).getByRole('radio', { name: /Platform Operator/ }));
    expect(within(dlg).queryByRole('textbox', { name: 'tenant 검색' })).toBeNull();
    fireEvent.click(within(dlg).getByRole('button', { name: '승인하고 계정 생성' }));
    await waitFor(() =>
      expect(signupRequestsApi.approve).toHaveBeenCalledWith('r1', { role: 'PLATFORM_OPERATOR', tenantIds: [] }),
    );
  });

  it('reject asks for in-app confirmation (alertdialog) before calling the API', async () => {
    renderTab();
    await waitFor(() => screen.getByText('new@x.com'));
    fireEvent.click(screen.getByRole('button', { name: '거절' }));
    expect(signupRequestsApi.reject).not.toHaveBeenCalled();
    const dlg = screen.getByRole('alertdialog', { name: '가입 요청 거절' });
    expect(within(dlg).getByText(/new@x.com 의 요청을 거절합니다/)).toBeInTheDocument();
    const confirm = within(dlg).getByRole('button', { name: '거절 확정' });
    expect(confirm).toHaveClass('btn--danger-solid');
    fireEvent.click(confirm);
    await waitFor(() => expect(signupRequestsApi.reject).toHaveBeenCalledWith('r1'));
  });

  it('no invite button remains', async () => {
    renderTab();
    await waitFor(() => screen.getByText('new@x.com'));
    expect(screen.queryByText(/운영자 추가/)).not.toBeInTheDocument();
    expect(screen.queryByText(/초대/)).not.toBeInTheDocument();
  });

  it('approve failure other than 409 keeps the dialog open and does not reload', async () => {
    mock(signupRequestsApi.approve).mockRejectedValue(new ApiError(500, 'C999', 'boom'));
    renderTab();
    await waitFor(() => screen.getByText('new@x.com'));
    fireEvent.click(screen.getByRole('button', { name: '승인…' }));
    await waitFor(() => within(approveDialog()).getByText('Acme'));
    fireEvent.click(within(approveDialog()).getByRole('button', { name: '승인하고 계정 생성' }));
    await waitFor(() => expect(signupRequestsApi.approve).toHaveBeenCalled());
    expect(approveDialog()).toBeInTheDocument();
    expect(signupRequestsApi.list).toHaveBeenCalledTimes(1);
  });

  it('approve 409 closes the dialog and reloads', async () => {
    mock(signupRequestsApi.approve).mockRejectedValue(new ApiError(409, 'C409', 'conflict'));
    renderTab();
    await waitFor(() => screen.getByText('new@x.com'));
    fireEvent.click(screen.getByRole('button', { name: '승인…' }));
    await waitFor(() => within(approveDialog()).getByText('Acme'));
    fireEvent.click(within(approveDialog()).getByRole('button', { name: '승인하고 계정 생성' }));
    expect(await screen.findByText('이미 처리된 요청입니다.')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('dialog', { name: '가입 요청 승인' })).toBeNull());
    expect(signupRequestsApi.list).toHaveBeenCalledTimes(2);
  });

  it('reject failure other than 409 keeps the confirm dialog open', async () => {
    mock(signupRequestsApi.reject).mockRejectedValue(new ApiError(500, 'C999', 'boom'));
    renderTab();
    await waitFor(() => screen.getByText('new@x.com'));
    fireEvent.click(screen.getByRole('button', { name: '거절' }));
    fireEvent.click(screen.getByRole('button', { name: '거절 확정' }));
    await waitFor(() => expect(signupRequestsApi.reject).toHaveBeenCalledWith('r1'));
    expect(screen.getByRole('button', { name: '거절 확정' })).toBeInTheDocument();
    expect(signupRequestsApi.list).toHaveBeenCalledTimes(1);
  });
});

describe('AdminUsersTab operators', () => {
  beforeEach(() => { mock(adminUsersApi.list).mockResolvedValue(USERS); });

  const table = () => screen.getByRole('table');
  const seg = () => screen.getByRole('group', { name: '역할' });

  it('헤더 요약과 표 열', async () => {
    renderTab();
    await waitFor(() => screen.getByText('ops@crosscert.com'));
    expect(screen.getByText('3명 · 활성 2명')).toBeInTheDocument();
    for (const h of ['운영자', '역할', 'Tenant', 'MFA', '마지막 로그인', '상태', '액션']) {
      expect(within(table()).getByRole('columnheader', { name: h })).toBeInTheDocument();
    }
    const rpRow = within(table()).getByText('rp@acme.com').closest('tr')!;
    expect(within(rpRow).getByText('RP_ADMIN')).toHaveClass('badge--info');
    expect(within(rpRow).getByText('OFF')).toHaveClass('badge--warning');
    expect(within(rpRow).getByText('미접속')).toBeInTheDocument();
    const goneRow = within(table()).getByText('gone@globex.com').closest('tr')!;
    expect(within(goneRow).getByText('2개 RP')).toBeInTheDocument();
  });

  it('세그먼트 PLATFORM / RP_ADMIN / 정지 / 전체 필터', async () => {
    renderTab();
    await waitFor(() => screen.getByText('ops@crosscert.com'));
    fireEvent.click(within(seg()).getByRole('button', { name: 'PLATFORM' }));
    expect(within(seg()).getByRole('button', { name: 'PLATFORM' })).toHaveAttribute('aria-pressed', 'true');
    expect(within(table()).getByText('ops@crosscert.com')).toBeInTheDocument();
    expect(within(table()).queryByText('rp@acme.com')).toBeNull();
    expect(within(table()).queryByText('gone@globex.com')).toBeNull();

    fireEvent.click(within(seg()).getByRole('button', { name: 'RP_ADMIN' }));
    expect(within(table()).queryByText('ops@crosscert.com')).toBeNull();
    expect(within(table()).getByText('rp@acme.com')).toBeInTheDocument();
    expect(within(table()).getByText('gone@globex.com')).toBeInTheDocument();

    fireEvent.click(within(seg()).getByRole('button', { name: '정지' }));
    expect(within(table()).getByText('gone@globex.com')).toBeInTheDocument();
    expect(within(table()).queryByText('rp@acme.com')).toBeNull();

    fireEvent.click(within(seg()).getByRole('button', { name: '전체' }));
    expect(within(table()).getAllByRole('row')).toHaveLength(1 + 3);
  });

  it('검색(이메일·ID, 대소문자 무시)과 결과 없음', async () => {
    renderTab();
    await waitFor(() => screen.getByText('ops@crosscert.com'));
    const search = screen.getByRole('textbox', { name: '운영자 검색' });
    fireEvent.change(search, { target: { value: 'ACME' } });
    expect(within(table()).getByText('rp@acme.com')).toBeInTheDocument();
    expect(within(table()).queryByText('ops@crosscert.com')).toBeNull();
    fireEvent.change(search, { target: { value: 'platform-0001' } });
    expect(within(table()).getByText('ops@crosscert.com')).toBeInTheDocument();
    fireEvent.change(search, { target: { value: 'nobody' } });
    expect(screen.getByText('조건에 맞는 운영자가 없습니다.')).toBeInTheDocument();
  });

  it('세그먼트와 검색은 함께 적용된다', async () => {
    renderTab();
    await waitFor(() => screen.getByText('ops@crosscert.com'));
    fireEvent.click(within(seg()).getByRole('button', { name: 'RP_ADMIN' }));
    fireEvent.change(screen.getByRole('textbox', { name: '운영자 검색' }), { target: { value: 'globex' } });
    expect(within(table()).getAllByRole('row')).toHaveLength(1 + 1);
    expect(within(table()).getByText('gone@globex.com')).toBeInTheDocument();
  });

  it('정지·활성화 액션', async () => {
    renderTab();
    await waitFor(() => screen.getByText('rp@acme.com'));
    const rpRow = within(table()).getByText('rp@acme.com').closest('tr')!;
    fireEvent.click(within(rpRow).getByRole('button', { name: '정지' }));
    await waitFor(() => expect(adminUsersApi.suspend).toHaveBeenCalledWith('u-rp-000000002'));
    const goneRow = within(table()).getByText('gone@globex.com').closest('tr')!;
    fireEvent.click(within(goneRow).getByRole('button', { name: '활성화' }));
    await waitFor(() => expect(adminUsersApi.activate).toHaveBeenCalledWith('u-rp-000000003'));
    await waitFor(() => expect(adminUsersApi.list).toHaveBeenCalledTimes(3));
  });
});
