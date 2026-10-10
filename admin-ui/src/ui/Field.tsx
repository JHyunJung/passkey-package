import { ReactNode } from 'react';

export function Field({ label, htmlFor, help, error, required, children }: { label: ReactNode; htmlFor?: string; help?: ReactNode; error?: ReactNode; required?: boolean; children: ReactNode }) {
  return (
    <div>
      <label className="label" htmlFor={htmlFor}>{label}{required && <span style={{ color: 'var(--bad)' }}> *</span>}</label>
      {children}
      {error ? <p className="field__error" id={htmlFor ? `${htmlFor}-error` : undefined}>{error}</p> : help ? <p className="hint">{help}</p> : null}
    </div>
  );
}
