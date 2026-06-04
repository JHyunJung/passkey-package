import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import type { Me } from '@/api/types';

vi.mock('./settings/AdminUsersTab', () => ({ default: () => <div>ADMIN USERS TAB</div> }));
vi.mock('./settings/MdsStatusTab', () => ({ default: () => <div>MDS TAB</div> }));
vi.mock('./settings/SystemInfoTab', () => ({ default: () => <div>SYSTEM TAB</div> }));
vi.mock('./settings/SecurityPolicyTab', () => ({ default: () => <div>SECURITY TAB</div> }));
vi.mock('./settings/AccountTab', () => ({ default: () => <div>ACCOUNT TAB</div> }));

import SettingsPage from './SettingsPage';

const platform: Me = { email: 'a@x.com', role: 'PLATFORM_OPERATOR', tenantId: null, mfaEnabled: false, mfaRequired: false, sessionIdleTimeoutMinutes: 30 };
const rp: Me = { email: 'b@x.com', role: 'RP_ADMIN', tenantId: 'tid-123', mfaEnabled: false, mfaRequired: false, sessionIdleTimeoutMinutes: 30 };

describe('SettingsPage tab role filter', () => {
  it('PLATFORM_OPERATOR sees all tabs', () => {
    render(<SettingsPage me={platform} onMeChange={() => {}} />);
    expect(screen.getByText('내 계정')).toBeInTheDocument();
    expect(screen.getByText('Admin 사용자')).toBeInTheDocument();
    expect(screen.getByText('MDS Status')).toBeInTheDocument();
    expect(screen.getByText('시스템')).toBeInTheDocument();
    expect(screen.getByText('보안 정책')).toBeInTheDocument();
  });

  it('RP_ADMIN sees only account tab', () => {
    render(<SettingsPage me={rp} onMeChange={() => {}} />);
    expect(screen.getByText('내 계정')).toBeInTheDocument();
    expect(screen.queryByText('Admin 사용자')).not.toBeInTheDocument();
    expect(screen.queryByText('MDS Status')).not.toBeInTheDocument();
    expect(screen.queryByText('시스템')).not.toBeInTheDocument();
    expect(screen.queryByText('보안 정책')).not.toBeInTheDocument();
  });
});
