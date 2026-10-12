// admin-ui/src/pages/settings/AdminUsersTab.test.tsx
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, cleanup, within, act } from '@testing-library/react';
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
import { tenantsApi } from '@/api/tenants';
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

function renderTab(props: { me?: { email: string }; onPendingCountChange?: (n: number) => void } = {}) {
  return render(<ToastHost><AdminUsersTab me={{ email: 'me@crosscert.com' }} {...props} /></ToastHost>);
}
const approveDialog = () => screen.getByRole('dialog', { name: '가입 요청 승인' });


/** 오류 토스트: 원문(raw)은 제목·본문에 없고 접힌 "자세히" 안에만 있다(스펙 §2 알림 규칙). */
function expectRawOnlyInDetails(title: string, raw: RegExp) {
  const toast = screen.getByText(title).closest('.toast') as HTMLElement;
  expect(toast.querySelector('.toast__title')).not.toHaveTextContent(raw);
  expect(toast.querySelector('.toast__sub')).not.toHaveTextContent(raw);
  expect(toast.querySelector('details:not([open])')).toHaveTextContent(raw);
}

describe('AdminUsersTab signup requests', () => {
  it('renders pending requests with count badge, email and reason', async () => {
    renderTab();
    await waitFor(() => expect(screen.getByText('new@x.com')).toBeInTheDocument());
    expect(screen.getByText('RP 담당')).toBeInTheDocument();
    expect(screen.getByText('대기 1건')).toHaveClass('badge--warning');
    expect(screen.getByRole('button', { name: '거절…' })).toBeInTheDocument();
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
    // 임의 tenant 를 미리 고르지 않는다 — 운영자가 직접 선택해야 한다.
    expect(within(dlg).getByRole('checkbox', { name: /Acme/ })).not.toBeChecked();
    fireEvent.click(within(dlg).getByRole('checkbox', { name: /Acme/ }));
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
    fireEvent.click(within(dlg).getByRole('button', { name: '승인하고 계정 생성' }));
    expect(within(dlg).getByText('Tenant를 최소 1개 선택해야 합니다.')).toBeInTheDocument();
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

  it('거절은 L1 확인(기본 포커스 취소) 후 API 호출, 확인 버튼 라벨은 "거절"', async () => {
    renderTab();
    await waitFor(() => screen.getByText('new@x.com'));
    fireEvent.click(screen.getByRole('button', { name: '거절…' }));
    expect(signupRequestsApi.reject).not.toHaveBeenCalled();
    const dlg = screen.getByRole('alertdialog', { name: '가입 요청을 거절할까요?' });
    expect(within(dlg).getByText('new@x.com의 요청을 거절합니다. 요청자는 다시 가입 요청을 보낼 수 있습니다.')).toBeInTheDocument();
    expect(document.activeElement).toBe(within(dlg).getByRole('button', { name: '취소' }));
    expect(within(dlg).queryByRole('button', { name: '거절 확정' })).toBeNull();
    const confirm = within(dlg).getByRole('button', { name: '거절' });
    expect(confirm).toHaveClass('btn--danger-solid');
    fireEvent.click(confirm);
    await waitFor(() => expect(signupRequestsApi.reject).toHaveBeenCalledWith('r1'));
    expect(await screen.findByText('가입 요청을 거절했습니다.')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull());
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
    fireEvent.click(within(approveDialog()).getByRole('checkbox', { name: /Acme/ }));
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
    fireEvent.click(within(approveDialog()).getByRole('checkbox', { name: /Acme/ }));
    fireEvent.click(within(approveDialog()).getByRole('button', { name: '승인하고 계정 생성' }));
    expect(await screen.findByText('이미 처리된 요청입니다.')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('dialog', { name: '가입 요청 승인' })).toBeNull());
    expect(signupRequestsApi.list).toHaveBeenCalledTimes(2);
  });

  it('reject failure other than 409 keeps the confirm dialog open with an inline error', async () => {
    mock(signupRequestsApi.reject).mockRejectedValue(new ApiError(500, 'C999', 'boom'));
    renderTab();
    await waitFor(() => screen.getByText('new@x.com'));
    fireEvent.click(screen.getByRole('button', { name: '거절…' }));
    const dlg = screen.getByRole('alertdialog', { name: '가입 요청을 거절할까요?' });
    fireEvent.click(within(dlg).getByRole('button', { name: '거절' }));
    await waitFor(() => expect(signupRequestsApi.reject).toHaveBeenCalledWith('r1'));
    expect(await within(dlg).findByText('거절하지 못했습니다')).toBeInTheDocument();
    expect(within(dlg).getByRole('button', { name: '거절' })).toBeEnabled();
    expect(signupRequestsApi.list).toHaveBeenCalledTimes(1);
  });

  it('reject 409 는 안내 후 다이얼로그를 닫고 가입 요청을 다시 읽는다', async () => {
    mock(signupRequestsApi.reject).mockRejectedValue(new ApiError(409, 'C409', 'conflict'));
    renderTab();
    await waitFor(() => screen.getByText('new@x.com'));
    fireEvent.click(screen.getByRole('button', { name: '거절…' }));
    fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: '거절' }));
    expect(await screen.findByText('이미 처리된 요청입니다.')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull());
    await waitFor(() => expect(signupRequestsApi.list).toHaveBeenCalledTimes(2));
  });

  it('승인 버튼은 연타해도 API 를 한 번만 부른다', async () => {
    let resolve!: () => void;
    mock(signupRequestsApi.approve).mockReturnValue(new Promise<void>((r) => { resolve = r; }));
    renderTab();
    await waitFor(() => screen.getByText('new@x.com'));
    fireEvent.click(screen.getByRole('button', { name: '승인…' }));
    await waitFor(() => within(approveDialog()).getByText('Acme'));
    fireEvent.click(within(approveDialog()).getByRole('checkbox', { name: /Acme/ }));
    const btn = within(approveDialog()).getByRole('button', { name: '승인하고 계정 생성' });
    fireEvent.click(btn);
    fireEvent.click(btn);
    expect(signupRequestsApi.approve).toHaveBeenCalledTimes(1);
    resolve();
    await waitFor(() => expect(screen.queryByRole('dialog', { name: '가입 요청 승인' })).toBeNull());
  });

  it('승인 실패 토스트는 서버 원문 대신 describeError 문구', async () => {
    mock(signupRequestsApi.approve).mockRejectedValue(new ApiError(500, 'C999', 'SECRET-RAW'));
    renderTab();
    await waitFor(() => screen.getByText('new@x.com'));
    fireEvent.click(screen.getByRole('button', { name: '승인…' }));
    await waitFor(() => within(approveDialog()).getByText('Acme'));
    fireEvent.click(within(approveDialog()).getByRole('checkbox', { name: /Acme/ }));
    fireEvent.click(within(approveDialog()).getByRole('button', { name: '승인하고 계정 생성' }));
    expect(await screen.findByText('승인 실패')).toBeInTheDocument();
    expect(screen.getByText(/^서버가 잠시 응답하지 않습니다/)).toBeInTheDocument();
    expectRawOnlyInDetails('승인 실패', /SECRET-RAW/);
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
    expect(within(goneRow).getByText('Globex')).toBeInTheDocument();
    expect(within(goneRow).getByText('외 1개')).toBeInTheDocument();
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
    fireEvent.click(within(rpRow).getByRole('button', { name: '정지…' }));
    fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: '정지' }));
    await waitFor(() => expect(adminUsersApi.suspend).toHaveBeenCalledWith('u-rp-000000002'));
    const goneRow = within(table()).getByText('gone@globex.com').closest('tr')!;
    fireEvent.click(within(goneRow).getByRole('button', { name: '활성화' }));
    await waitFor(() => expect(adminUsersApi.activate).toHaveBeenCalledWith('u-rp-000000003'));
    await waitFor(() => expect(adminUsersApi.list).toHaveBeenCalledTimes(3));
  });
});

describe('AdminUsersTab V3 — 로드 상태 · Tenant 이름', () => {
  beforeEach(() => { mock(adminUsersApi.list).mockResolvedValue(USERS); });
  const rowOf = (email: string) => within(screen.getByRole('table')).getByText(email).closest('tr')!;

  it('로딩 중에는 Skeleton(“Loading…” 텍스트 없음)', async () => {
    let resolve!: (v: unknown[]) => void;
    mock(adminUsersApi.list).mockReturnValue(new Promise((r) => { resolve = r; }));
    renderTab();
    expect(screen.getByRole('status', { name: '운영자 목록 불러오는 중' })).toHaveAttribute('aria-busy', 'true');
    expect(screen.queryByText('Loading…')).toBeNull();
    resolve([]);
    await waitFor(() => expect(screen.queryByRole('status', { name: '운영자 목록 불러오는 중' })).toBeNull());
  });

  it('운영자 목록 실패 → LoadError, 건수 "—", 검색 비활성, 다시 시도로 복구', async () => {
    mock(adminUsersApi.list).mockRejectedValueOnce(new ApiError(503, 'C999', 'HTTP 503'));
    renderTab();
    expect(await screen.findByText('운영자 목록을 불러오지 못했습니다')).toBeInTheDocument();
    expect(screen.queryByRole('table')).toBeNull();
    expect(screen.getByText('콘솔 운영자').parentElement).toHaveTextContent('—');
    expect(screen.getByRole('textbox', { name: '운영자 검색' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(await screen.findByText('ops@crosscert.com')).toBeInTheDocument();
    expect(screen.getByText('3명 · 활성 2명')).toBeInTheDocument();
  });

  it('가입 요청 실패 → LoadError, 대기 "—" (“없음”으로 보이지 않음)', async () => {
    mock(signupRequestsApi.list).mockRejectedValueOnce(new ApiError(503, 'C999', 'HTTP 503'));
    renderTab();
    expect(await screen.findByText('가입 요청을 불러오지 못했습니다')).toBeInTheDocument();
    expect(screen.getByText('대기 —')).toBeInTheDocument();
    expect(screen.queryByText('대기 중인 요청이 없습니다')).toBeNull();
  });

  it('Tenant 열: tenants 목록과 조인한 이름, 여러 개면 "외 N개", PLATFORM 은 "전체 tenant"', async () => {
    renderTab();
    await screen.findByText('rp@acme.com');
    expect(within(rowOf('rp@acme.com')).getByText('Acme')).toBeInTheDocument();
    expect(within(rowOf('gone@globex.com')).getByText('Globex')).toBeInTheDocument();
    expect(within(rowOf('gone@globex.com')).getByText('외 1개')).toBeInTheDocument();
    expect(within(rowOf('ops@crosscert.com')).getByText('전체 tenant')).toBeInTheDocument();
  });

  it('tenants 목록을 못 읽으면 오류 없이 id 꼬리로 표시(전체 id 는 title)', async () => {
    mock(tenantsApi.list).mockRejectedValueOnce(new Error('x'));
    renderTab();
    await screen.findByText('rp@acme.com');
    const cell = within(rowOf('rp@acme.com')).getByText('…t1');
    expect(cell).toHaveAttribute('title', 't1');
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('같은 화면에서 다시 읽기가 실패하면 데이터를 유지하고 경고를 보인다', async () => {
    renderTab();
    await screen.findByText('rp@acme.com');
    mock(adminUsersApi.list).mockRejectedValueOnce(new ApiError(503, 'C999', 'HTTP 503'));
    const rpRow = rowOf('rp@acme.com');
    fireEvent.click(within(rpRow).getByRole('button', { name: '정지…' }));
    fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: '정지' }));
    expect(await screen.findByText('다시 확인하지 못했습니다')).toBeInTheDocument();
    expect(rowOf('rp@acme.com')).toBeInTheDocument();
    expect(screen.queryByText('운영자 목록을 불러오지 못했습니다')).toBeNull();
  });

  it('운영자 목록이 낡았으면 다시 시도 버튼, 행 액션 비활성 → 성공하면 경고 해제·활성화', async () => {
    renderTab();
    await screen.findByText('rp@acme.com');
    mock(adminUsersApi.list).mockRejectedValueOnce(new ApiError(503, 'C999', 'HTTP 503'));
    fireEvent.click(within(rowOf('rp@acme.com')).getByRole('button', { name: '정지…' }));
    fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: '정지' }));
    expect(await screen.findByText('다시 확인하지 못했습니다')).toBeInTheDocument();
    const stop = within(rowOf('rp@acme.com')).getByRole('button', { name: '정지…' });
    expect(stop).toBeDisabled();
    expect(stop).toHaveAttribute('title', '목록을 새로 고친 뒤 다시 시도하세요');
    expect(within(rowOf('gone@globex.com')).getByRole('button', { name: '활성화' })).toBeDisabled();
    expect(screen.getByRole('textbox', { name: '운영자 검색' })).toBeEnabled();
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    await waitFor(() => expect(screen.queryByText('다시 확인하지 못했습니다')).toBeNull());
    expect(within(rowOf('rp@acme.com')).getByRole('button', { name: '정지…' })).toBeEnabled();
    expect(within(rowOf('gone@globex.com')).getByRole('button', { name: '활성화' })).toBeEnabled();
  });

  it('가입 요청이 낡았으면 다시 시도 버튼, 승인·거절 비활성 → 성공하면 활성화', async () => {
    renderTab();
    await screen.findByText('new@x.com');
    mock(signupRequestsApi.list).mockRejectedValueOnce(new ApiError(503, 'C999', 'HTTP 503'));
    fireEvent.click(screen.getByRole('button', { name: '거절…' }));
    fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: '거절' }));
    expect(await screen.findByText('다시 확인하지 못했습니다')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull());
    // 거절 성공 → requests 만 낡음(users 는 정상).
    expect(screen.getByRole('button', { name: '승인…' })).toBeDisabled();
    const rej = screen.getByRole('button', { name: '거절…' });
    expect(rej).toBeDisabled();
    expect(rej).toHaveAttribute('title', '목록을 새로 고친 뒤 다시 시도하세요');
    expect(within(rowOf('rp@acme.com')).getByRole('button', { name: '정지…' })).toBeEnabled();
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    await waitFor(() => expect(screen.queryByText('다시 확인하지 못했습니다')).toBeNull());
    expect(screen.getByRole('button', { name: '승인…' })).toBeEnabled();
    expect(screen.getByRole('button', { name: '거절…' })).toBeEnabled();
  });
});

describe('AdminUsersTab V3 — 운영자 정지', () => {
  const RP_SUSPENDED = USERS.map((u) => (u.id === 'u-rp-000000002' ? { ...u, status: 'SUSPENDED' } : u));
  beforeEach(() => { mock(adminUsersApi.list).mockResolvedValue(USERS); });
  const rowOf = (email: string) => within(screen.getByRole('table')).getByText(email).closest('tr')!;

  it('정지… → L1 확인(기본 포커스 취소) → 정지 → 되돌리기 토스트 → 기존 활성화 API', async () => {
    renderTab();
    await screen.findByText('rp@acme.com');
    fireEvent.click(within(rowOf('rp@acme.com')).getByRole('button', { name: '정지…' }));
    expect(adminUsersApi.suspend).not.toHaveBeenCalled();
    const dlg = screen.getByRole('alertdialog', { name: 'rp@acme.com을 정지할까요?' });
    expect(document.activeElement).toBe(within(dlg).getByRole('button', { name: '취소' }));
    expect(within(dlg).getByText('Acme')).toBeInTheDocument();
    expect(within(dlg).getByText(/언제든 되돌릴 수 있습니다/)).toBeInTheDocument();
    mock(adminUsersApi.list).mockResolvedValueOnce(RP_SUSPENDED);
    fireEvent.click(within(dlg).getByRole('button', { name: '정지' }));
    await waitFor(() => expect(adminUsersApi.suspend).toHaveBeenCalledWith('u-rp-000000002'));
    expect(await screen.findByText('rp@acme.com 운영자를 정지했습니다.')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull());
    await waitFor(() => expect(within(rowOf('rp@acme.com')).getByRole('button', { name: '활성화' })).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: '되돌리기' }));
    await waitFor(() => expect(adminUsersApi.activate).toHaveBeenCalledWith('u-rp-000000002'));
    expect(await screen.findByText('rp@acme.com 운영자의 정지를 되돌렸습니다.')).toBeInTheDocument();
  });

  it('정지 실패는 다이얼로그 안 인라인 오류, 다이얼로그 유지, 되돌리기 토스트 없음', async () => {
    mock(adminUsersApi.suspend).mockRejectedValue(new ApiError(503, 'C999', 'HTTP 503'));
    renderTab();
    await screen.findByText('rp@acme.com');
    fireEvent.click(within(rowOf('rp@acme.com')).getByRole('button', { name: '정지…' }));
    const dlg = screen.getByRole('alertdialog', { name: 'rp@acme.com을 정지할까요?' });
    fireEvent.click(within(dlg).getByRole('button', { name: '정지' }));
    expect(await within(dlg).findByText('정지하지 못했습니다')).toBeInTheDocument();
    expect(screen.getByRole('alertdialog')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '되돌리기' })).toBeNull();
  });

  it('본인 계정과 마지막 활성 PLATFORM 운영자는 정지 버튼 비활성 + 사유', async () => {
    renderTab({ me: { email: 'rp@acme.com' } });
    await screen.findByText('rp@acme.com');
    const self = within(rowOf('rp@acme.com')).getByRole('button', { name: '정지…' });
    expect(self).toBeDisabled();
    expect(self).toHaveAccessibleDescription('본인 계정은 정지할 수 없음');
    const last = within(rowOf('ops@crosscert.com')).getByRole('button', { name: '정지…' });
    expect(last).toBeDisabled();
    expect(last).toHaveAccessibleDescription('마지막 활성 PLATFORM 운영자');
  });

  it('활성 PLATFORM 운영자가 둘 이상이면 PLATFORM 도 정지할 수 있다', async () => {
    mock(adminUsersApi.list).mockResolvedValue([...USERS, { ...USERS[0], id: 'u-platform-0002', email: 'ops2@crosscert.com' }]);
    renderTab();
    await screen.findByText('ops2@crosscert.com');
    expect(within(rowOf('ops@crosscert.com')).getByRole('button', { name: '정지…' })).toBeEnabled();
  });

  it('활성화 처리 중에는 버튼이 "활성화 중…"으로 비활성이고 연타해도 한 번만 호출', async () => {
    let resolve!: () => void;
    mock(adminUsersApi.activate).mockReturnValue(new Promise<void>((r) => { resolve = r; }));
    renderTab();
    await screen.findByText('gone@globex.com');
    const btn = within(rowOf('gone@globex.com')).getByRole('button', { name: '활성화' });
    fireEvent.click(btn);
    fireEvent.click(btn);
    expect(within(rowOf('gone@globex.com')).getByRole('button', { name: '활성화 중…' })).toBeDisabled();
    expect(adminUsersApi.activate).toHaveBeenCalledTimes(1);
    await act(async () => { resolve(); });
    await waitFor(() => expect(within(rowOf('gone@globex.com')).getByRole('button', { name: '활성화' })).toBeEnabled());
  });

  it('되돌리기 연타는 활성화를 한 번만 호출', async () => {
    let resolve!: () => void;
    renderTab();
    await screen.findByText('rp@acme.com');
    fireEvent.click(within(rowOf('rp@acme.com')).getByRole('button', { name: '정지…' }));
    mock(adminUsersApi.list).mockResolvedValueOnce(RP_SUSPENDED);
    fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: '정지' }));
    const undo = await screen.findByRole('button', { name: '되돌리기' });
    await waitFor(() => expect(within(rowOf('rp@acme.com')).getByRole('button', { name: '활성화' })).toBeInTheDocument());
    mock(adminUsersApi.activate).mockReturnValue(new Promise<void>((r) => { resolve = r; }));
    fireEvent.click(undo);
    fireEvent.click(undo);
    expect(adminUsersApi.activate).toHaveBeenCalledTimes(1);
    await act(async () => { resolve(); });
    expect(await screen.findByText('rp@acme.com 운영자의 정지를 되돌렸습니다.')).toBeInTheDocument();
  });

  it('되돌리기 실패는 서버 원문 없이 describeError 문구로 알린다', async () => {
    renderTab();
    await screen.findByText('rp@acme.com');
    fireEvent.click(within(rowOf('rp@acme.com')).getByRole('button', { name: '정지…' }));
    mock(adminUsersApi.list).mockResolvedValueOnce(RP_SUSPENDED);
    fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: '정지' }));
    const undo = await screen.findByRole('button', { name: '되돌리기' });
    await waitFor(() => expect(within(rowOf('rp@acme.com')).getByRole('button', { name: '활성화' })).toBeInTheDocument());
    mock(adminUsersApi.activate).mockRejectedValue(new ApiError(500, 'C999', 'SECRET-RAW'));
    fireEvent.click(undo);
    expect(await screen.findByText('정지를 되돌리지 못했습니다')).toBeInTheDocument();
    expect(screen.getByText(/서버가 잠시 응답하지 않습니다/)).toBeInTheDocument();
    expectRawOnlyInDetails('정지를 되돌리지 못했습니다', /SECRET-RAW/);
  });

  it('활성화 실패 토스트도 describeError 문구', async () => {
    mock(adminUsersApi.activate).mockRejectedValue(new ApiError(500, 'C999', 'SECRET-RAW'));
    renderTab();
    await screen.findByText('gone@globex.com');
    fireEvent.click(within(rowOf('gone@globex.com')).getByRole('button', { name: '활성화' }));
    expect(await screen.findByText('활성화하지 못했습니다')).toBeInTheDocument();
    expect(screen.getByText(/^서버가 잠시 응답하지 않습니다/)).toBeInTheDocument();
    expectRawOnlyInDetails('활성화하지 못했습니다', /SECRET-RAW/);
  });

  it('서로 다른 운영자의 처리가 겹쳐도 각 행의 처리 중 표시는 독립적이다', async () => {
    const held: Record<string, () => void> = {};
    mock(adminUsersApi.activate).mockImplementation((id: string) => new Promise<void>((r) => { held[id] = r; }));
    const two = USERS.map((u) => (u.id === 'u-rp-000000002' ? { ...u, status: 'SUSPENDED' } : u));
    mock(adminUsersApi.list).mockResolvedValue(two);
    renderTab();
    await screen.findByText('gone@globex.com');
    fireEvent.click(within(rowOf('gone@globex.com')).getByRole('button', { name: '활성화' }));
    fireEvent.click(within(rowOf('rp@acme.com')).getByRole('button', { name: '활성화' }));
    expect(within(rowOf('gone@globex.com')).getByRole('button', { name: '활성화 중…' })).toBeDisabled();
    expect(within(rowOf('rp@acme.com')).getByRole('button', { name: '활성화 중…' })).toBeDisabled();
    await act(async () => { held['u-rp-000000003'](); });
    await waitFor(() => expect(within(rowOf('gone@globex.com')).getByRole('button', { name: '활성화' })).toBeEnabled());
    expect(within(rowOf('rp@acme.com')).getByRole('button', { name: '활성화 중…' })).toBeDisabled();
    await act(async () => { held['u-rp-000000002'](); });
    await waitFor(() => expect(within(rowOf('rp@acme.com')).getByRole('button', { name: '활성화' })).toBeEnabled());
  });

  it('열려 있던 정지 다이얼로그는 목록이 낡아지면 정지하지 않고 안내를 보인다', async () => {
    renderTab();
    await screen.findByText('gone@globex.com');
    const activateGone = within(rowOf('gone@globex.com')).getByRole('button', { name: '활성화' });
    fireEvent.click(within(rowOf('rp@acme.com')).getByRole('button', { name: '정지…' }));
    const dlg = screen.getByRole('alertdialog');
    mock(adminUsersApi.list).mockRejectedValueOnce(new ApiError(503, 'C999', 'HTTP 503'));
    fireEvent.click(activateGone);
    expect(await screen.findByText('다시 확인하지 못했습니다')).toBeInTheDocument();
    fireEvent.click(within(dlg).getByRole('button', { name: '정지' }));
    expect(await within(dlg).findByText('정지하지 못했습니다')).toBeInTheDocument();
    expect(within(dlg).getByText('요청을 처리하지 못했습니다. 목록을 새로 고친 뒤 다시 시도하세요')).toBeInTheDocument();
    expect(within(dlg).queryByText(/알 수 없는 오류/)).toBeNull();
    expect(adminUsersApi.suspend).not.toHaveBeenCalled();
  });

  it('이미 처리 중인 운영자는 정지하지 않고, 성공 토스트도 없다', async () => {
    let resolve!: () => void;
    mock(adminUsersApi.activate).mockReturnValue(new Promise<void>((r) => { resolve = r; }));
    renderTab();
    await screen.findByText('gone@globex.com');
    const activateGone = within(rowOf('gone@globex.com')).getByRole('button', { name: '활성화' });
    fireEvent.click(within(rowOf('rp@acme.com')).getByRole('button', { name: '정지…' }));
    const dlg = screen.getByRole('alertdialog');
    // 다른 관리자가 rp 를 정지한 상태로 목록이 갱신되고, 그 행의 활성화가 진행 중이 된다.
    mock(adminUsersApi.list).mockResolvedValueOnce(RP_SUSPENDED);
    fireEvent.click(activateGone);
    await act(async () => { resolve(); });
    await waitFor(() => expect(within(rowOf('rp@acme.com')).getByRole('button', { name: '활성화' })).toBeEnabled());
    mock(adminUsersApi.activate).mockReturnValue(new Promise<void>((r) => { resolve = r; }));
    fireEvent.click(within(rowOf('rp@acme.com')).getByRole('button', { name: '활성화' }));
    fireEvent.click(within(dlg).getByRole('button', { name: '정지' }));
    expect(await within(dlg).findByText('정지하지 못했습니다')).toBeInTheDocument();
    expect(within(dlg).getByText('요청을 처리하지 못했습니다. 이미 처리 중입니다. 잠시 후 다시 시도하세요.')).toBeInTheDocument();
    expect(within(dlg).queryByText(/알 수 없는 오류/)).toBeNull();
    expect(adminUsersApi.suspend).not.toHaveBeenCalled();
    expect(screen.queryByText('rp@acme.com 운영자를 정지했습니다.')).toBeNull();
    await act(async () => { resolve(); });
  });

  it('되돌리기는 목록이 낡았거나 이미 정지 상태가 아니면 활성화를 부르지 않는다', async () => {
    renderTab();
    await screen.findByText('rp@acme.com');
    // 정지 후 갱신된 목록에서 rp 가 ACTIVE 로 보이면(다른 관리자가 이미 되돌림) 되돌리지 않는다.
    fireEvent.click(within(rowOf('rp@acme.com')).getByRole('button', { name: '정지…' }));
    fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: '정지' }));
    const undoBtn = await screen.findByRole('button', { name: '되돌리기' });
    await waitFor(() => expect(adminUsersApi.list).toHaveBeenCalledTimes(2));
    await act(async () => { await new Promise((r) => setTimeout(r, 0)); });
    fireEvent.click(undoBtn);
    expect(await screen.findByText('되돌릴 수 없습니다')).toBeInTheDocument();
    expect(adminUsersApi.activate).not.toHaveBeenCalled();
  });

  it('되돌리기는 목록이 낡았으면 활성화를 부르지 않는다', async () => {
    renderTab();
    await screen.findByText('rp@acme.com');
    mock(adminUsersApi.list).mockResolvedValueOnce(RP_SUSPENDED);
    fireEvent.click(within(rowOf('rp@acme.com')).getByRole('button', { name: '정지…' }));
    fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: '정지' }));
    const undo = await screen.findByRole('button', { name: '되돌리기' });
    mock(adminUsersApi.list).mockRejectedValueOnce(new ApiError(503, 'C999', 'HTTP 503'));
    fireEvent.click(within(rowOf('gone@globex.com')).getByRole('button', { name: '활성화' }));
    expect(await screen.findByText('다시 확인하지 못했습니다')).toBeInTheDocument();
    mock(adminUsersApi.activate).mockClear();
    fireEvent.click(undo);
    expect(await screen.findByText('되돌릴 수 없습니다')).toBeInTheDocument();
    expect(adminUsersApi.activate).not.toHaveBeenCalled();
  });

  it('목록 다시 읽기가 끝나지 않아도 정지 다이얼로그는 바로 닫히고, 그 사이 되돌리기는 거절된다', async () => {
    renderTab();
    await screen.findByText('rp@acme.com');
    let resolveList!: (v: unknown) => void;
    mock(adminUsersApi.list).mockReturnValueOnce(new Promise((r) => { resolveList = r; }));
    fireEvent.click(within(rowOf('rp@acme.com')).getByRole('button', { name: '정지…' }));
    fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: '정지' }));
    const undo = await screen.findByRole('button', { name: '되돌리기' });
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull());
    fireEvent.click(undo);
    expect(await screen.findByText('되돌릴 수 없습니다')).toBeInTheDocument();
    expect(adminUsersApi.activate).not.toHaveBeenCalled();
    await act(async () => { resolveList(RP_SUSPENDED); });
    await waitFor(() => expect(within(rowOf('rp@acme.com')).getByRole('button', { name: '활성화' })).toBeEnabled());
  });
});
