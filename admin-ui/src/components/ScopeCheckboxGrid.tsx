interface Props {
  value: Set<string>;
  onChange: (v: Set<string>) => void;
}

const SCOPES = [
  { code: 'registration',   label: '등록 (registration)',   desc: 'passkey 등록 ceremony 허용' },
  { code: 'authentication', label: '인증 (authentication)', desc: '인증 ceremony 허용' },
  { code: 'admin',          label: '관리 (admin)',          desc: '관리 API 허용' },
];

export default function ScopeCheckboxGrid({ value, onChange }: Props) {
  function toggle(code: string) {
    const next = new Set(value);
    if (next.has(code)) next.delete(code); else next.add(code);
    onChange(next);
  }
  return (
    <div className="stack-2">
      {SCOPES.map(s => (
        <label key={s.code} className="row" style={{ gap: 10, cursor: 'pointer', alignItems: 'flex-start' }}>
          <input
            type="checkbox"
            checked={value.has(s.code)}
            onChange={() => toggle(s.code)}
            style={{ marginTop: 3 }}
          />
          <div className="stack-1">
            <div style={{ fontSize: 13, fontWeight: 500 }}>{s.label}</div>
            <div className="muted" style={{ fontSize: 12 }}>{s.desc}</div>
          </div>
        </label>
      ))}
    </div>
  );
}
