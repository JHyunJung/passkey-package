import { api } from './client';

export type TenantChainVerify = {
  tenantId: string;
  intact: boolean;
  tamperedEntryId: string | null;
  verifiedAt: string;
};

export const auditChainApi = {
  verifyTenant: async (tenantId: string): Promise<TenantChainVerify> => {
    // GET /admin/api/audit/chain/verify 는 ApiResponse envelope 없이 raw JSON 을 돌려준다.
    return api.getRaw<TenantChainVerify>(
      `/admin/api/audit/chain/verify?tenantId=${tenantId}`
    );
  },
};
