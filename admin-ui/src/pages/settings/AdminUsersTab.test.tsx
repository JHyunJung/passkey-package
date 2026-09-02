import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react';
import { ToastHost } from '@/shell/ToastHost';

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
  tenantsApi: { list: vi.fn().mockResolvedValue([{ id: 't1', name: 'Acme', slug: 'acme', status: 'ACTIVE' }]) },
}));

import { signupRequestsApi } from '@/api/signupRequests';
import { ApiError } from '@/api/types';
import AdminUsersTab from './AdminUsersTab';

const pendingOne = [{ id: 'r1', email: 'new@x.com', reason: 'RP 담당', requestedAt: new Date().toISOString() }];

beforeEach(() => {
  vi.clearAllMocks();
  (signupRequestsApi.list as ReturnType<typeof vi.fn>).mockResolvedValue(pendingOne);
  (signupRequestsApi.approve as ReturnType<typeof vi.fn>).mockResolvedValue({});
  (signupRequestsApi.reject as ReturnType<typeof vi.fn>).mockResolvedValue(undefined);
});
afterEach(cleanup);

function renderTab() {
  return render(<ToastHost><AdminUsersTab /></ToastHost>);
}

describe('AdminUsersTab signup requests', () => {
  it('renders pending requests with count badge, email and reason', async () => {
    renderTab();
    await waitFor(() => expect(screen.getByText('new@x.com')).toBeInTheDocument());
    expect(screen.getByText('RP 담당')).toBeInTheDocument();
    expect(screen.getByText('대기 1건')).toBeInTheDocument();
  });

  it('shows empty notice when no request is pending', async () => {
    (signupRequestsApi.list as ReturnType<typeof vi.fn>).mockResolvedValue([]);
    renderTab();
    await waitFor(() => expect(screen.getByText('대기 중인 요청이 없습니다')).toBeInTheDocument());
  });

  it('approve opens a dialog with the email read-only and submits role + tenants', async () => {
    renderTab();
    await waitFor(() => screen.getByText('new@x.com'));
    fireEvent.click(screen.getByRole('button', { name: '승인' }));
    const emailInput = screen.getByDisplayValue('new@x.com') as HTMLInputElement;
    expect(emailInput.readOnly).toBe(true);
    await waitFor(() => screen.getByText('Acme'));   // tenant 목록 로드
    fireEvent.click(screen.getByRole('button', { name: '승인하고 계정 생성' }));
    await waitFor(() =>
      expect(signupRequestsApi.approve).toHaveBeenCalledWith('r1', { role: 'RP_ADMIN', tenantIds: ['t1'] }),
    );
  });

  it('reject asks for in-app confirmation before calling the API', async () => {
    renderTab();
    await waitFor(() => screen.getByText('new@x.com'));
    fireEvent.click(screen.getByRole('button', { name: '거절' }));
    expect(signupRequestsApi.reject).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '거절 확정' }));
    await waitFor(() => expect(signupRequestsApi.reject).toHaveBeenCalledWith('r1'));
  });

  it('no invite button remains', async () => {
    renderTab();
    await waitFor(() => screen.getByText('new@x.com'));
    expect(screen.queryByText(/운영자 추가/)).not.toBeInTheDocument();
    expect(screen.queryByText(/초대/)).not.toBeInTheDocument();
  });

  it('approve failure other than 409 keeps the dialog open and does not reload', async () => {
    (signupRequestsApi.approve as ReturnType<typeof vi.fn>).mockRejectedValue(
      new ApiError(500, 'C999', 'boom'),
    );
    renderTab();
    await waitFor(() => screen.getByText('new@x.com'));
    fireEvent.click(screen.getByRole('button', { name: '승인' }));
    await waitFor(() => screen.getByText('Acme'));
    fireEvent.click(screen.getByRole('button', { name: '승인하고 계정 생성' }));
    await waitFor(() => expect(signupRequestsApi.approve).toHaveBeenCalled());
    expect(screen.getByDisplayValue('new@x.com')).toBeInTheDocument();
    expect(signupRequestsApi.list).toHaveBeenCalledTimes(1);
  });

  it('reject failure other than 409 keeps the confirm dialog open', async () => {
    (signupRequestsApi.reject as ReturnType<typeof vi.fn>).mockRejectedValue(
      new ApiError(500, 'C999', 'boom'),
    );
    renderTab();
    await waitFor(() => screen.getByText('new@x.com'));
    fireEvent.click(screen.getByRole('button', { name: '거절' }));
    fireEvent.click(screen.getByRole('button', { name: '거절 확정' }));
    await waitFor(() => expect(signupRequestsApi.reject).toHaveBeenCalledWith('r1'));
    expect(screen.getByRole('button', { name: '거절 확정' })).toBeInTheDocument();
    expect(signupRequestsApi.list).toHaveBeenCalledTimes(1);
  });
});
