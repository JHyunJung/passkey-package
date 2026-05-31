import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import type { Me } from '@/api/types';

// Sidebar 가 mount 시 호출하는 API 두 모듈을 mock — 네트워크 차단.
vi.mock('@/api/auditChainMonitor', () => ({
  auditChainMonitorApi: { overview: vi.fn().mockResolvedValue(null) },
}));
vi.mock('@/api/license', () => ({
  licenseApi: { get: vi.fn().mockResolvedValue({ deploymentMode: 'saas' }) },
}));

import { Sidebar } from './Sidebar';

const platform: Me = { email: 'a@x.com', role: 'PLATFORM_OPERATOR', tenantId: null, mfaEnabled: false, mfaRequired: false };
const rp: Me = { email: 'b@x.com', role: 'RP_ADMIN', tenantId: 'tid-123', mfaEnabled: false, mfaRequired: false };

function renderSidebar(me: Me) {
  return render(
    <Sidebar me={me} currentRoute={{ name: 'tenants' }} onNavigate={() => {}} tenant={null} />
  );
}

describe('Sidebar nav role filter', () => {
  it('PLATFORM_OPERATOR sees platform nav items', async () => {
    renderSidebar(platform);
    expect(screen.getByText('Tenants')).toBeInTheDocument();
    expect(screen.getByText('Activity')).toBeInTheDocument();
    expect(screen.getByText('Audit Chain')).toBeInTheDocument();
    expect(screen.getByText('설정')).toBeInTheDocument();
    await screen.findByText('설정'); // async license fetch flush
  });

  it('RP_ADMIN does NOT see platform-only nav items but keeps 설정', async () => {
    renderSidebar(rp);
    expect(screen.queryByText('Tenants')).not.toBeInTheDocument();
    expect(screen.queryByText('Activity')).not.toBeInTheDocument();
    expect(screen.queryByText('Audit Chain')).not.toBeInTheDocument();
    expect(screen.getByText('설정')).toBeInTheDocument(); // 내 계정 접근 위해 유지
    await screen.findByText('설정'); // async license fetch flush
  });
});
