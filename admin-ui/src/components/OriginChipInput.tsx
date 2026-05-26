import { useState, useId, type KeyboardEvent } from 'react';
import { Plus } from './Icons';

interface Props {
  value: string[];
  onChange: (v: string[]) => void;
}

/**
 * Validate a WebAuthn origin: scheme://host[:port] — no path, no query.
 * Uses URL parsing so malformed hostnames (double dots, leading hyphens, etc.) are
 * rejected by the browser URL parser. http://localhost is explicitly allowed.
 */
function validateOrigin(raw: string): string | null {
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    return 'http(s)://… 형식이어야 합니다.';
  }
  if (url.protocol !== 'https:' && url.protocol !== 'http:') {
    return 'https:// 또는 http:// scheme만 허용됩니다.';
  }
  // origin = scheme + host + port — must match exactly (no path allowed)
  if (raw !== url.origin) {
    return 'path 없이 origin만 입력해 주세요 (예: https://example.com:8080).';
  }
  return null;
}

export default function OriginChipInput({ value, onChange }: Props) {
  const [draft, setDraft] = useState('');
  const [err, setErr] = useState<string | null>(null);
  const errId = useId();
  const inputId = useId();

  function add() {
    const v = draft.trim();
    if (!v) return;
    const validationErr = validateOrigin(v);
    if (validationErr) {
      setErr(validationErr);
      return;
    }
    if (value.includes(v)) {
      setErr('중복된 origin입니다.');
      return;
    }
    onChange([...value, v]);
    setDraft('');
    setErr(null);
  }

  function remove(idx: number) {
    onChange(value.filter((_, i) => i !== idx));
  }

  function onKey(e: KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'Enter') { e.preventDefault(); add(); }
  }

  return (
    <div>
      <div className="row" style={{ flexWrap: 'wrap', gap: 6, marginBottom: 8 }}>
        {value.map((o, i) => (
          <span key={o} className="chip">
            <span className="mono" style={{ fontSize: 12 }}>{o}</span>
            <button type="button" className="chip__x" onClick={() => remove(i)} aria-label={`${o} 제거`}>×</button>
          </span>
        ))}
        {value.length === 0 && (
          <span className="muted" style={{ fontSize: 12 }}>등록된 origin이 없습니다.</span>
        )}
      </div>
      <div className="row" style={{ gap: 6 }}>
        <input
          id={inputId}
          aria-label="Origin URL"
          className="input mono"
          value={draft}
          onChange={(e) => { setDraft(e.target.value); setErr(null); }}
          onKeyDown={onKey}
          placeholder="https://example.com"
          style={{ flex: 1 }}
          aria-invalid={err ? true : undefined}
          aria-describedby={err ? errId : undefined}
        />
        <button type="button" className="btn btn--outline btn--sm" onClick={add}>
          <Plus size={12} /> 추가
        </button>
      </div>
      {err && (
        <div
          id={errId}
          role="alert"
          className="hint"
          style={{ color: 'var(--danger)' }}
        >
          {err}
        </div>
      )}
    </div>
  );
}
