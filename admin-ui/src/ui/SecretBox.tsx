import { useState } from 'react';
import { copyToClipboard } from '@/lib/clipboard';

export function SecretBox({ value, copyLabel = '클립보드', onCopied }: { value: string; copyLabel?: string; onCopied?: () => void }) {
  const [copied, setCopied] = useState(false);
  return (
    <div className="secret">
      <span className="secret__value">{value}</span>
      <button type="button" className="btn btn--sm btn--primary" onClick={async () => { if (await copyToClipboard(value)) { setCopied(true); onCopied?.(); } }}>
        {copied ? '복사됨' : copyLabel}
      </button>
    </div>
  );
}
