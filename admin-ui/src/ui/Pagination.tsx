import { Icons } from '@/icons/Icons';

type Props = { page: number; pageSize: number; total: number; onPage: (p: number) => void; pageSizeOptions?: number[]; onPageSize?: (n: number) => void; unit?: string };

export function Pagination({ page, pageSize, total, onPage, pageSizeOptions, onPageSize, unit = '건' }: Props) {
  const last = Math.max(1, Math.ceil(total / pageSize));
  // 필터 등으로 total 이 줄면 page 가 범위를 넘을 수 있으므로 보정한다.
  const p = Math.min(Math.max(1, page), last);
  const from = total === 0 ? 0 : (p - 1) * pageSize + 1;
  const to = Math.min(total, p * pageSize);
  return (
    <div className="pager">
      <span>{total === 0 ? `0${unit}` : `${from}–${to} / ${total}${unit}`}</span>
      <div className="spacer" />
      {pageSizeOptions && onPageSize && (
        <label className="row" style={{ gap: 6 }}>페이지당
          <select className="input" value={pageSize} onChange={(e) => onPageSize(Number(e.target.value))}>
            {pageSizeOptions.map((n) => <option key={n} value={n}>{n}</option>)}
          </select>
        </label>
      )}
      <button type="button" className="btn btn--sm" aria-label="이전 페이지" disabled={p <= 1} onClick={() => onPage(p - 1)}><Icons.ChevronLeft size={14} /></button>
      <span>{p} / {last}</span>
      <button type="button" className="btn btn--sm" aria-label="다음 페이지" disabled={p >= last} onClick={() => onPage(p + 1)}><Icons.ChevronRight size={14} /></button>
    </div>
  );
}
