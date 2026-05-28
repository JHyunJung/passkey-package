export const monthlyReportApi = {
  download: async (from: string, to: string): Promise<Blob> => {
    const url = `/admin/api/audit/chain/monthly-report?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`;
    const res = await fetch(url, { credentials: 'include' });
    if (!res.ok) {
      throw new Error(`PDF download failed (${res.status})`);
    }
    return res.blob();
  },
};
