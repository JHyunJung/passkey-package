import { describe, it, expect } from 'vitest';
import { isPlatform, isRpAdmin, rpTenantId } from './roles';
import type { Me } from '@/api/types';

const platform: Me = { email: 'a@x.com', role: 'PLATFORM_OPERATOR', tenantId: null, mfaEnabled: false, mfaRequired: false, sessionIdleTimeoutMinutes: 30 };
const rp: Me = { email: 'b@x.com', role: 'RP_ADMIN', tenantId: 'tid-123', mfaEnabled: false, mfaRequired: false, sessionIdleTimeoutMinutes: 30 };

describe('roles', () => {
  it('isPlatform', () => {
    expect(isPlatform(platform)).toBe(true);
    expect(isPlatform(rp)).toBe(false);
  });
  it('isRpAdmin', () => {
    expect(isRpAdmin(rp)).toBe(true);
    expect(isRpAdmin(platform)).toBe(false);
  });
  it('rpTenantId returns RP tenant, null for platform', () => {
    expect(rpTenantId(rp)).toBe('tid-123');
    expect(rpTenantId(platform)).toBeNull();
  });
  it('rpTenantId null when RP_ADMIN has null tenantId (data anomaly)', () => {
    const broken: Me = { ...rp, tenantId: null };
    expect(rpTenantId(broken)).toBeNull();
  });
});
