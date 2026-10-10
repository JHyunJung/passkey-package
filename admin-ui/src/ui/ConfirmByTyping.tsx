import { useId, useState, ReactNode } from 'react';

export function ConfirmByTyping({ expected, label, onMatchChange, id }: { expected: string; label: ReactNode; onMatchChange: (ok: boolean) => void; id?: string }) {
  const auto = useId();
  const inputId = id ?? auto;
  const [v, setV] = useState('');
  const ok = v === expected;
  return (
    <div>
      <label className="label" htmlFor={inputId}>{label}</label>
      <input id={inputId} className="input mono" value={v} autoComplete="off" aria-describedby={`${inputId}-h`}
        onChange={(e) => { setV(e.target.value); onMatchChange(e.target.value === expected); }}
        style={ok ? { borderColor: 'var(--ok)' } : undefined} />
      <p id={`${inputId}-h`} className="hint" style={ok ? { color: 'var(--ok)' } : undefined}>{ok ? '일치합니다' : '일치해야 실행 버튼이 활성화됩니다.'}</p>
    </div>
  );
}
