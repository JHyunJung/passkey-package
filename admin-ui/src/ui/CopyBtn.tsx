import { useState } from 'react';
import { Icons } from '@/icons/Icons';
import { copyToClipboard } from '@/lib/clipboard';
import { IconButton } from './IconButton';

export function CopyBtn({ value, label = '복사', iconOnly }: { value: string; label?: string; iconOnly?: boolean }) {
  const [copied, setCopied] = useState(false);
  async function onCopy(e: React.MouseEvent) {
    e.stopPropagation();
    if (await copyToClipboard(value)) { setCopied(true); setTimeout(() => setCopied(false), 1500); }
  }
  const icon = copied ? <Icons.Check size={13} /> : <Icons.Copy size={13} />;
  if (iconOnly) return <IconButton label={label} icon={icon} size="sm" onClick={(e) => void onCopy(e)} />;
  return (
    <button type="button" className="btn btn--sm" onClick={(e) => void onCopy(e)}>
      {icon}{copied ? '복사됨' : label}
    </button>
  );
}
