import { ReactNode } from 'react';

export function PageHeader({ title, sub, actions }: { title: ReactNode; sub?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="page__head">
      <div style={{ minWidth: 0 }}>
        <h1 className="page__title">{title}</h1>
        {sub && <p className="page__sub">{sub}</p>}
      </div>
      {actions && <><div className="spacer" /><div className="row">{actions}</div></>}
    </div>
  );
}
