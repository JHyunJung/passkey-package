import { useState, KeyboardEvent } from 'react';
import { RemovableChip } from './Chips';

type Props = { values: string[]; onChange: (next: string[]) => void; placeholder: string; ariaLabel: string; validate?: (v: string) => string | null; mono?: boolean; addedValues?: string[]; normalize?: (v: string) => string; disabled?: boolean };

export function ChipInput({ values, onChange, placeholder, ariaLabel, validate, mono, addedValues = [], normalize, disabled }: Props) {
  const [draft, setDraft] = useState('');
  const [error, setError] = useState<string | null>(null);
  function onKeyDown(e: KeyboardEvent<HTMLInputElement>) {
    if (e.key !== 'Enter') return;
    e.preventDefault();
    if (disabled) return;
    const raw = draft.trim();
    if (!raw) return;
    const err = validate?.(raw) ?? null;
    if (err) { setError(err); return; }
    const v = normalize ? normalize(raw) : raw;
    setError(null);
    setDraft('');
    if (!values.includes(v)) onChange([...values, v]);
  }
  return (
    <div className="stack-2">
      {values.length > 0 && (
        <div className="chips">
          {values.map((v) => (
            <RemovableChip key={v} mono={mono} tone={addedValues.includes(v) ? 'added' : 'default'} removeLabel={`${v} 삭제`} disabled={disabled} onRemove={() => onChange(values.filter((x) => x !== v))}>{v}</RemovableChip>
          ))}
        </div>
      )}
      <input className={mono ? 'input mono' : 'input'} aria-label={ariaLabel} aria-invalid={!!error} placeholder={placeholder} value={draft} disabled={disabled}
        onChange={(e) => { setDraft(e.target.value); if (error) setError(null); }} onKeyDown={onKeyDown} />
      {error && <p className="field__error" role="alert">{error}</p>}
    </div>
  );
}
