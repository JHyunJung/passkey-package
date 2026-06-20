import { useState } from 'react';
import { Icons } from '@/icons/Icons';
import { copyToClipboard } from '@/lib/clipboard';

export function CopyBtn({ value, label = '복사' }: { value: string; label?: string }) {
  const [copied, setCopied] = useState(false);
  async function onCopy(e: React.MouseEvent) {
    e.stopPropagation();
    const ok = await copyToClipboard(value);
    if (ok) {
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    }
  }
  return (
    <button className="btn btn--sm" onClick={(e) => void onCopy(e)}>
      {copied ? <Icons.Check size={13} /> : <Icons.Copy size={13} />}
      {copied ? '복사됨' : label}
    </button>
  );
}
