import { api } from './client';
import type { AaguidPolicy } from './designTypes';

type ServerAaguidPolicy = {
  tenantId: string;
  mode: 'ANY' | 'ALLOWLIST' | 'DENYLIST';
  mdsStrict: boolean;
  entries: { aaguid: string; note: string | null; mdsName: string | null }[];
  updatedAt: string;
  updatedBy: string | null;
};

// AaguidPolicyController returns raw AaguidPolicyDto.View (no ApiResponse envelope)
// — use getRaw/putRaw, not get/put (which expect { success, data } envelope).
export const aaguidPolicyApi = {
  get: async (tenantId: string): Promise<AaguidPolicy> => {
    const s = await api.getRaw<ServerAaguidPolicy>(`/admin/api/tenants/${tenantId}/aaguid-policy`);
    return { mode: s.mode, mdsStrict: s.mdsStrict, entries: s.entries };
  },
  update: async (tenantId: string, body: AaguidPolicy): Promise<AaguidPolicy> => {
    const updateReq = {
      mode: body.mode,
      mdsStrict: body.mdsStrict,
      entries: body.entries.map((e) => ({ aaguid: e.aaguid, note: e.note })),
    };
    const s = await api.putRaw<ServerAaguidPolicy>(
      `/admin/api/tenants/${tenantId}/aaguid-policy`,
      updateReq
    );
    return { mode: s.mode, mdsStrict: s.mdsStrict, entries: s.entries };
  },
};
