import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import type { Me } from '@/api/types';

const overview = vi.fn();
vi.mock('@/api/auditChainMonitor', () => ({ auditChainMonitorApi: { overview: (...a: unknown[]) => overview(...a) } }));
vi.mock('@/api/license', () => ({ licenseApi: { get: vi.fn().mockResolvedValue({ deploymentMode: 'saas' }) } }));

import { Sidebar } from './Sidebar';

const platform: Me = { email: 'a@x.com', role: 'PLATFORM_OPERATOR', tenantIds: [], mfaEnabled: false, mfaRequired: false, sessionIdleTimeoutMinutes: 30 };
const rp: Me = { email: 'b@x.com', role: 'RP_ADMIN', tenantIds: ['tid-123'], mfaEnabled: false, mfaRequired: false, sessionIdleTimeoutMinutes: 30 };

describe('Sidebar V2', () => {
  it('PLATFORM: 플랫폼/관리 그룹과 4개 항목, 활성 aria-current', async () => {
    overview.mockResolvedValue(null);
    render(<Sidebar me={platform} currentRoute={{ name: 'tenants' }} onNavigate={() => {}} />);
    expect(screen.getByText('플랫폼')).toBeInTheDocument();
    expect(screen.getByText('관리')).toBeInTheDocument();
    for (const l of ['Tenants', '활동', '감사 체인', '설정']) expect(screen.getByRole('button', { name: new RegExp(l) })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Tenants/ })).toHaveAttribute('aria-current', 'page');
    expect(await screen.findByText('확인 중…')).toBeInTheDocument();
    // 환경 배지는 서버 profile 을 읽는 클라이언트 API 가 없으므로 하드코딩하지 않는다.
    expect(screen.queryByText('prod')).toBeNull();
  });
  it('RP_ADMIN: 설정만, 체인 카드 없음', async () => {
    render(<Sidebar me={rp} currentRoute={{ name: 'settings' }} onNavigate={() => {}} />);
    expect(screen.queryByText('Tenants')).toBeNull();
    expect(screen.queryByText('플랫폼')).toBeNull();
    expect(screen.getByRole('button', { name: /설정/ })).toBeInTheDocument();
    expect(screen.queryByText(/AUDIT CHAIN/)).toBeNull();
  });
  it('위변조가 있으면 감사 체인 항목에 건수 배지와 빨간 카드', async () => {
    overview.mockResolvedValue({ verifiedAt: new Date().toISOString(), totals: { tenantsTotal: 5, tenantsTampered: 1, verifiedRows: 10 } });
    const onNavigate = vi.fn();
    render(<Sidebar me={platform} currentRoute={{ name: 'tenants' }} onNavigate={onNavigate} />);
    expect(await screen.findByText('위변조 의심 · 1 tenant')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /감사 체인/ })).toHaveTextContent('1');
    fireEvent.click(screen.getByText('위변조 의심 · 1 tenant'));
    expect(onNavigate).toHaveBeenCalledWith({ name: 'audit-chain' });
  });
});
