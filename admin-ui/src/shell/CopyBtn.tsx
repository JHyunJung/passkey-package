import { useState } from 'react';
import { Icons } from '@/icons/Icons';

export function CopyBtn({ value, label = '복사' }: { value: string; label?: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <button
      className="btn btn--sm"
      onClick={(e) => { e.stopPropagation(); navigator.clipboard?.writeText(value); setCopied(true); setTimeout(() => setCopied(false), 1500); }}
    >
      {copied ? <Icons.Check size={13} /> : <Icons.Copy size={13} />}
      {copied ? '복사됨' : label}
    </button>
  );
}
