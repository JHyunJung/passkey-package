// admin-ui/src/pages/SettingsPage.test.tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import type { Me } from '@/api/types';

vi.mock('./settings/AdminUsersTab', () => ({ default: () => <div>ADMIN USERS TAB</div> }));
vi.mock('./settings/MdsStatusTab', () => ({ default: () => <div>MDS TAB</div> }));
vi.mock('./settings/SystemInfoTab', () => ({ default: () => <div>SYSTEM TAB</div> }));
vi.mock('./settings/SecurityPolicyTab', () => ({ default: () => <div>SECURITY TAB</div> }));
vi.mock('./settings/AccountTab', () => ({ default: () => <div>ACCOUNT TAB</div> }));
vi.mock('@/api/signupRequests', () => ({ signupRequestsApi: { list: vi.fn() } }));

import { signupRequestsApi } from '@/api/signupRequests';
import SettingsPage from './SettingsPage';

const platform: Me = { email: 'a@x.com', role: 'PLATFORM_OPERATOR', tenantIds: [], mfaEnabled: false, mfaRequired: false, sessionIdleTimeoutMinutes: 30 };
const rp: Me = { email: 'b@x.com', role: 'RP_ADMIN', tenantIds: ['tid-123'], mfaEnabled: false, mfaRequired: false, sessionIdleTimeoutMinutes: 30 };

function renderAt(me: Me, path = '/settings') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <SettingsPage me={me} onMeChange={() => {}} />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  (signupRequestsApi.list as ReturnType<typeof vi.fn>).mockResolvedValue([
    { id: 'r1', email: 'k@x.com', reason: null, requestedAt: new Date().toISOString() },
    { id: 'r2', email: 'l@x.com', reason: null, requestedAt: new Date().toISOString() },
  ]);
});

describe('SettingsPage V2', () => {
  it('페이지 루트는 .page, 제목과 설명', () => {
    const { container } = renderAt(platform);
    expect(container.firstChild).toHaveClass('page');
    expect(screen.getByRole('heading', { level: 1, name: '설정' })).toBeInTheDocument();
    expect(screen.getByText('콘솔 운영자, MDS 신뢰 anchor, 시스템 상태.')).toBeInTheDocument();
  });

  it('PLATFORM_OPERATOR sees all tabs', () => {
    renderAt(platform);
    const tabs = screen.getByRole('tablist', { name: '설정 탭' });
    expect(tabs).toBeInTheDocument();
    for (const name of [/내 계정/, /Admin 사용자/, /MDS Status/, /시스템/, /보안 정책/]) {
      expect(screen.getByRole('tab', { name })).toBeInTheDocument();
    }
    expect(screen.getByRole('tab', { name: /내 계정/ })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByText('ACCOUNT TAB')).toBeInTheDocument();
  });

  it('RP_ADMIN sees only account tab and does not fetch signup requests', () => {
    renderAt(rp);
    expect(screen.getByRole('tab', { name: /내 계정/ })).toBeInTheDocument();
    expect(screen.queryByText('Admin 사용자')).not.toBeInTheDocument();
    expect(screen.queryByText('MDS Status')).not.toBeInTheDocument();
    expect(screen.queryByText('시스템')).not.toBeInTheDocument();
    expect(screen.queryByText('보안 정책')).not.toBeInTheDocument();
    expect(signupRequestsApi.list).not.toHaveBeenCalled();
  });

  it('Admin 사용자 탭에 대기 중 가입 요청 건수 배지(PLATFORM)', async () => {
    renderAt(platform);
    const count = await screen.findByText('2');
    expect(count).toHaveClass('tabs__count');
    expect(screen.getByRole('tab', { name: /Admin 사용자/ })).toContainElement(count);
  });

  it('탭 클릭으로 내용이 바뀐다', () => {
    renderAt(platform);
    fireEvent.click(screen.getByRole('tab', { name: /시스템/ }));
    expect(screen.getByText('SYSTEM TAB')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: /시스템/ })).toHaveAttribute('aria-selected', 'true');
  });

  it('?tab= 으로 진입하면 해당 탭, RP_ADMIN 은 무시하고 내 계정', () => {
    const { unmount } = renderAt(platform, '/settings?tab=security');
    expect(screen.getByText('SECURITY TAB')).toBeInTheDocument();
    unmount();
    renderAt(rp, '/settings?tab=system');
    expect(screen.getByText('ACCOUNT TAB')).toBeInTheDocument();
    expect(screen.queryByText('SYSTEM TAB')).toBeNull();
  });
});
