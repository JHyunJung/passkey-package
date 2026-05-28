import { api } from './client';

export type TenantChainVerify = {
  tenantId: string;
  intact: boolean;
  tamperedEntryId: string | null;
  verifiedAt: string;
};

export const auditChainApi = {
  verifyTenant: async (tenantId: string): Promise<TenantChainVerify> => {
    return api.get<TenantChainVerify>(
      `/admin/api/audit/chain/verify?tenantId=${tenantId}`
    );
  },
};
