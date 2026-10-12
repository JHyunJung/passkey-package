import { LONG_TIMEOUT_MS, withTimeout } from './timeout';

export const monthlyReportApi = {
  download: async (from: string, to: string): Promise<Blob> => {
    const url = `/admin/api/audit/chain/monthly-report?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`;
    return withTimeout(LONG_TIMEOUT_MS, async (signal) => {
      const res = await fetch(url, { credentials: 'include', signal });
      if (!res.ok) {
        throw new Error(`PDF download failed (${res.status})`);
      }
      return res.blob();
    });
  },
};
