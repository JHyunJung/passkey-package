import { useState } from 'react';

interface Props {
    short: string;   // 화면에 표시할 짧은 형태 (예: 마지막 8자 또는 prefix)
    full: string;    // clipboard 복사 + tooltip 의 전체 값
}

/**
 * monospace 식별자 표시 + click 으로 clipboard 복사 + hover 시 전체 값 tooltip.
 * credential id, user handle 등 base64url 식별자에 사용.
 */
export default function Mono({ short, full }: Props) {
    const [copied, setCopied] = useState(false);

    async function copy() {
        try {
            await navigator.clipboard.writeText(full);
            setCopied(true);
            setTimeout(() => setCopied(false), 1500);
        } catch {
            /* clipboard 권한 없으면 무시 */
        }
    }

    return (
        <span
            title={full}
            onClick={copy}
            style={{
                fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
                fontSize: 12,
                background: 'var(--surface-2, #f4f5f7)',
                padding: '2px 6px',
                borderRadius: 4,
                cursor: 'pointer',
                userSelect: 'all',
            }}
        >
            {copied ? '✓ 복사됨' : short}
        </span>
    );
}
