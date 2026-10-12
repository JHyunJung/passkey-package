import { useEffect, useState } from 'react';

const WIDTHS = ['100%', '92%', '96%', '70%', '88%', '80%'];

/** 로딩 표시 통일. 3초가 넘으면 기다리는 중이라는 문구를 덧붙인다(도허티·H1). */
export function Skeleton({ rows = 4, label = '불러오는 중' }: { rows?: number; label?: string }) {
  const [slow, setSlow] = useState(false);
  useEffect(() => { const t = setTimeout(() => setSlow(true), 3000); return () => clearTimeout(t); }, []);
  return (
    <div className="skeleton-block" role="status" aria-busy="true" aria-label={label}>
      {Array.from({ length: rows }, (_, i) => <span key={i} className="skeleton-block__line" style={{ width: WIDTHS[i % WIDTHS.length] }} />)}
      {slow && <p className="hint" style={{ margin: 0 }}>아직 불러오는 중입니다…</p>}
    </div>
  );
}
