import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Routes, Route, useParams } from 'react-router-dom';
import { RequirePlatform } from './RequirePlatform';
import type { Me } from '@/api/types';

const platform: Me = { email: 'a@x.com', role: 'PLATFORM_OPERATOR', tenantId: null, mfaEnabled: false, mfaRequired: false, sessionIdleTimeoutMinutes: 30 };
const rp: Me = { email: 'b@x.com', role: 'RP_ADMIN', tenantId: 'tid-123', mfaEnabled: false, mfaRequired: false, sessionIdleTimeoutMinutes: 30 };

function ShowTenantId() { return <div>tenant:{useParams().id}</div>; }

function renderAt(me: Me) {
  return render(
    <MemoryRouter initialEntries={['/tenants']}>
      <Routes>
        <Route path="/tenants" element={<RequirePlatform me={me}><div>PLATFORM CONTENT</div></RequirePlatform>} />
        <Route path="/tenants/:id" element={<ShowTenantId />} />
      </Routes>
    </MemoryRouter>
  );
}

describe('RequirePlatform', () => {
  it('renders children for PLATFORM_OPERATOR', () => {
    renderAt(platform);
    expect(screen.getByText('PLATFORM CONTENT')).toBeInTheDocument();
  });

  it('redirects RP_ADMIN to own tenant', () => {
    renderAt(rp);
    expect(screen.queryByText('PLATFORM CONTENT')).not.toBeInTheDocument();
    expect(screen.getByText('tenant:tid-123')).toBeInTheDocument();
  });

  it('shows error state for RP_ADMIN with null tenantId', () => {
    const broken: Me = { ...rp, tenantId: null };
    render(
      <MemoryRouter initialEntries={['/tenants']}>
        <Routes>
          <Route path="/tenants" element={<RequirePlatform me={broken}><div>PLATFORM CONTENT</div></RequirePlatform>} />
        </Routes>
      </MemoryRouter>
    );
    expect(screen.queryByText('PLATFORM CONTENT')).not.toBeInTheDocument();
    expect(screen.getByText(/계정 구성 오류/)).toBeInTheDocument();
  });
});
