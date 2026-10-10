import { Fragment, ReactNode } from 'react';
import { cn } from '@/lib/cn';

export type Column<T> = { key: string; header: ReactNode; render: (row: T) => ReactNode; align?: 'left' | 'right'; width?: number | string; srOnlyHeader?: boolean };

type Props<T> = {
  columns: Column<T>[]; rows: T[]; rowKey: (r: T) => string; onRowClick?: (r: T) => void;
  groupBy?: (r: T) => string; renderExpanded?: (r: T) => ReactNode; expandedKey?: string | null;
  empty?: ReactNode; rowClassName?: (r: T) => string | undefined;
};

export function DataTable<T>({ columns, rows, rowKey, onRowClick, groupBy, renderExpanded, expandedKey, empty, rowClassName }: Props<T>) {
  const span = columns.length;
  let lastGroup: string | null = null;
  return (
    <div className="tw">
      <table className="table">
        <thead>
          <tr>
            {columns.map((c) => (
              <th key={c.key} className={c.align === 'right' ? 'num' : undefined} style={c.width != null ? { width: c.width } : undefined}>
                {c.srOnlyHeader ? <span className="sr-only">{c.header}</span> : c.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.length === 0 && empty != null && <tr className="table__empty"><td colSpan={span}>{empty}</td></tr>}
          {rows.map((r) => {
            const key = rowKey(r);
            const g = groupBy?.(r);
            const groupRow = g != null && g !== lastGroup ? <tr className="table__group"><td colSpan={span}>{g}</td></tr> : null;
            if (g != null) lastGroup = g;
            const expanded = expandedKey != null && expandedKey === key;
            return (
              <Fragment key={key}>
                {groupRow}
                <tr className={cn(onRowClick && 'is-clickable', expanded && 'is-expanded', rowClassName?.(r))}
                  tabIndex={onRowClick ? 0 : undefined}
                  onClick={onRowClick ? () => onRowClick(r) : undefined}
                  onKeyDown={onRowClick ? (e) => {
                    // 행 자신에 포커스가 있을 때만. 셀 안 버튼(복사·회수 등)에서 버블된 Enter/Space 는 무시.
                    if (e.target !== e.currentTarget) return;
                    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onRowClick(r); }
                  } : undefined}>
                  {columns.map((c) => <td key={c.key} className={c.align === 'right' ? 'num' : undefined}>{c.render(r)}</td>)}
                </tr>
                {expanded && renderExpanded && <tr className="table__expanded"><td colSpan={span}>{renderExpanded(r)}</td></tr>}
              </Fragment>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
