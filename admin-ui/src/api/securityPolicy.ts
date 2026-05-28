import { api } from './client';

export type SecurityPolicyView = {
  sessionIdleTimeoutMinutes: number;
  passwordMinLength: number;
  mfaRequired: boolean;
  corsAllowlist: string[];
  updatedAt: string;
  updatedBy: string | null;
};

export type SecurityPolicyUpdateRequest = {
  sessionIdleTimeoutMinutes: number;
  passwordMinLength: number;
  mfaRequired: boolean;
  corsAllowlist: string[];
};

// SecurityPolicyController returns raw SecurityPolicyDto.View (no ApiResponse envelope)
// — use getRaw/putRaw, not get/put (which expect { success, data } envelope).
// Same pattern as aaguidPolicy.ts.
export const securityPolicyApi = {
  get: (): Promise<SecurityPolicyView> =>
    api.getRaw<SecurityPolicyView>('/admin/api/security-policy'),

  update: (req: SecurityPolicyUpdateRequest): Promise<SecurityPolicyView> =>
    api.putRaw<SecurityPolicyView>('/admin/api/security-policy', req),
};
