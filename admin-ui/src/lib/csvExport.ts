// Minimal client-side CSV export — no dependencies.
// RFC 4180 lite: quote fields containing comma/quote/newline, escape quotes by doubling.

export function downloadCsv(filename: string, header: string[], rows: (string | number | null | undefined)[][]): void {
  const escape = (v: string | number | null | undefined): string => {
    if (v == null) return '';
    const s = String(v);
    if (/[",\r\n]/.test(s)) {
      return `"${s.replace(/"/g, '""')}"`;
    }
    return s;
  };

  const lines = [
    header.map(escape).join(','),
    ...rows.map((row) => row.map(escape).join(',')),
  ];
  const csv = '\uFEFF' + lines.join('\n');  // BOM for Excel UTF-8 detection
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}
