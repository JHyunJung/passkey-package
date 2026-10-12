import { LONG_TIMEOUT_MS, withTimeout } from './timeout';
import { ApiError } from './types';

export const monthlyReportApi = {
  download: async (from: string, to: string): Promise<Blob> => {
    const url = `/admin/api/audit/chain/monthly-report?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`;
    return withTimeout(LONG_TIMEOUT_MS, async (signal) => {
      const res = await fetch(url, { credentials: 'include', signal });
      if (!res.ok) {
        // describeError 가 상태별 쉬운 문구(권한 없음·서버 일시 오류 등)로 바꿀 수 있게 ApiError 로 던진다.
        throw new ApiError(res.status, 'C999', `PDF download failed (HTTP ${res.status})`);
      }
      return res.blob();
    });
  },
};
