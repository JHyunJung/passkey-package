// admin-ui/src/pages/tenant/SetRow.tsx
import { ReactNode } from 'react';
import { Badge } from '@/ui';

/** 설정 화면 2열 행(설명 좌 · 컨트롤 우). V2 `.set` 그리드. */
export function SetRow({ label, htmlFor, desc, changed, children }: {
  label: ReactNode; htmlFor?: string; desc: ReactNode; changed?: boolean; children: ReactNode;
}) {
  return (
    <div className="set">
      <div className="stack-1">
        <div className="row" style={{ gap: 8 }}>
          {htmlFor ? <label className="set__label" htmlFor={htmlFor}>{label}</label> : <span className="set__label">{label}</span>}
          {changed && <Badge tone="warn" dot={false}>변경됨</Badge>}
        </div>
        <p className="set__desc">{desc}</p>
      </div>
      <div style={{ minWidth: 0 }}>{children}</div>
    </div>
  );
}
