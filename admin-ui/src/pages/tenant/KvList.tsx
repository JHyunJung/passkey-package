// admin-ui/src/pages/tenant/KvList.tsx
import { Fragment, ReactNode } from 'react';

export type KvItem = { k: ReactNode; v: ReactNode; mono?: boolean };

/** 공용 `.kv` 클래스(Task 3) 위에 키 폭·2쌍 배치·박스만 덧붙인다. columns=2 면 키·값 쌍 2개씩 한 줄. */
export function KvList({ items, keyWidth = 130, columns = 1, boxed }: { items: KvItem[]; keyWidth?: number; columns?: 1 | 2; boxed?: boolean }) {
  const pair = `${keyWidth}px minmax(0, 1fr)`;
  return (
    <dl className={columns === 2 ? 'kv kv--4' : 'kv'} style={{
      gridTemplateColumns: columns === 2 ? `${pair} ${pair}` : pair,
      ...(boxed ? { padding: '12px 14px', border: '1px solid var(--line-soft)', borderRadius: 'var(--r)' } : {}),
    }}>
      {items.map((it, i) => (
        <Fragment key={i}>
          <dt>{it.k}</dt>
          <dd className={it.mono ? 'mono' : undefined} style={{ overflowWrap: 'anywhere', fontSize: it.mono ? 12 : undefined }}>{it.v}</dd>
        </Fragment>
      ))}
    </dl>
  );
}
