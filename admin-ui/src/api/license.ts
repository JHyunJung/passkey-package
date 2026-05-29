import { api } from './client';

export type LicenseState = 'VALID' | 'WARNING' | 'NETWORK_GRACE' | 'DEAD';

export type LicenseView = {
  deploymentMode: 'saas' | 'onprem';
  state: LicenseState | null;
  sub: string | null;
  jti: string | null;
  expiresAt: string | null;
  daysUntilExpiry: number;
  features: string[];
  lastVerifiedAt: string | null;
  graceRemainingHours: number | null;
  nextHeartbeatAt: string | null;
};

export const licenseApi = {
  get: (): Promise<LicenseView> =>
    api.get<LicenseView>('/admin/api/license'),
};
