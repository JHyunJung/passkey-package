import { getFunnel } from '@/fixtures/funnel';
import type { FunnelData } from '@/fixtures/funnel';

export const funnelApi = {
  get: async (tenantId: string, windowDays: 1 | 7 | 30 = 7): Promise<FunnelData> => {
    // 서버 endpoint 없음 — fixture 직접 반환 (Phase E4 에서 서버 추가)
    return getFunnel(tenantId, windowDays);
  },
};
