interface Props {
  value: Set<string>;
  onChange: (v: Set<string>) => void;
}

const FORMATS = [
  { code: 'none',                label: 'none (passkey 기본)' },
  { code: 'packed',              label: 'packed' },
  { code: 'android-key',         label: 'Android Key' },
  { code: 'android-safetynet',   label: 'Android SafetyNet' },
  { code: 'fido-u2f',            label: 'FIDO U2F' },
  { code: 'apple',               label: 'Apple Anonymous' },
  { code: 'tpm',                 label: 'TPM' },
];

export default function FormatCheckboxGrid({ value, onChange }: Props) {
  function toggle(code: string) {
    const next = new Set(value);
    if (next.has(code)) next.delete(code); else next.add(code);
    onChange(next);
  }
  return (
    <div className="grid-2" style={{ gap: 6 }}>
      {FORMATS.map(f => (
        <label key={f.code} className="row" style={{ gap: 8, cursor: 'pointer', fontSize: 13 }}>
          <input
            type="checkbox"
            checked={value.has(f.code)}
            onChange={() => toggle(f.code)}
          />
          <span>{f.label}</span>
        </label>
      ))}
    </div>
  );
}
