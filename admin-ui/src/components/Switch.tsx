import type { ReactNode, KeyboardEvent } from 'react';

interface Props {
  checked: boolean;
  onChange: (v: boolean) => void;
  label?: ReactNode;
  disabled?: boolean;
}

export default function Switch({ checked, onChange, label, disabled }: Props) {
  function handleKey(e: KeyboardEvent<HTMLSpanElement>) {
    if (e.key === ' ' || e.key === 'Enter') {
      e.preventDefault();
      if (!disabled) onChange(!checked);
    }
  }

  return (
    <label className="row" style={{
      gap: 10,
      cursor: disabled ? 'not-allowed' : 'pointer',
      opacity: disabled ? 0.5 : 1
    }}>
      <span
        role="switch"
        aria-checked={checked}
        aria-disabled={disabled}
        tabIndex={disabled ? -1 : 0}
        onClick={() => !disabled && onChange(!checked)}
        onKeyDown={handleKey}
        style={{
          width: 36, height: 20, borderRadius: 999,
          background: checked ? 'var(--accent)' : 'var(--surface-3)',
          position: 'relative',
          transition: 'background var(--dur) var(--ease-out)',
          border: '1px solid var(--border)',
          display: 'inline-block',
          flexShrink: 0,
          outline: 'none',
        }}
      >
        <span style={{
          position: 'absolute', top: 1, left: checked ? 17 : 1,
          width: 16, height: 16, borderRadius: 999,
          background: 'var(--surface)',
          transition: 'left var(--dur) var(--ease-out)',
          boxShadow: 'var(--shadow-xs)',
        }} />
      </span>
      {label && <span style={{ fontSize: 13 }}>{label}</span>}
    </label>
  );
}
