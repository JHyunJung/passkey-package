import { useState } from 'react';
import { Dialog } from '@/shell/Dialog';
import { Icons } from '@/icons/Icons';
import { recoveryCodesFilename } from '@/lib/recoveryCodeFilename';
import { copyToClipboard } from '@/lib/clipboard';

/**
 * MFA 복구 코드 1회 표시 모달. confirm 직후 enroll 응답의 recoveryCodes 를 보여준다.
 * "저장했습니다" 체크 전에는 닫기 불가(IssuedKeyModal 패턴). 닫으면 영구히 다시 못 봄.
 */
export function RecoveryCodesModal({
  codes,
  accountEmail,
  onClose,
}: {
  codes: string[];
  accountEmail?: string;
  onClose: () => void;
}) {
  const [checked, setChecked] = useState(false);
  const [copied, setCopied] = useState(false);
  const [copyFailed, setCopyFailed] = useState(false);
  const text = codes.join('\n');

  async function copy() {
    const ok = await copyToClipboard(text);
    setCopyFailed(!ok);
    if (ok) {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }
  }
  function download() {
    const blob = new Blob([text + '\n'], { type: 'text/plain' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = recoveryCodesFilename(accountEmail, new Date());
    a.click();
    URL.revokeObjectURL(url);
  }

  return (
    <Dialog open onClose={() => { /* enforce */ }} closeOnScrim={false}
      title={<span style={{ display: 'flex', alignItems: 'center', gap: 8 }}><Icons.Alert size={18} /> 복구 코드 — 지금만 표시됩니다</span>}
      sub="인증 기기를 잃었을 때 로그인하는 유일한 방법입니다. 안전한 곳에 보관하세요."
      footer={
        <button className="btn btn--primary" disabled={!checked} onClick={onClose}>
          {checked ? '닫기' : '체크 필요'}
        </button>
      }
    >
      <div className="stack-3">
        <div style={{
          display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 6,
          fontFamily: 'var(--mono)', fontSize: 13,
          background: 'var(--surface-3)', padding: 12, borderRadius: 8, border: '1px solid var(--border)',
        }}>
          {codes.map((c) => <span key={c}>{c}</span>)}
        </div>
        <div style={{ display: 'flex', gap: 8 }}>
          <button className="btn btn--sm" onClick={copy} style={{ flex: 1 }}>
            {copied ? <><Icons.Check size={12} /> 복사됨</> : <><Icons.Copy size={12} /> 복사</>}
          </button>
          <button className="btn btn--sm" onClick={download} style={{ flex: 1 }}>다운로드 (.txt)</button>
        </div>
        {copyFailed && (
          <div style={{ fontSize: 12, color: 'var(--warning)' }}>
            복사에 실패했습니다. 위 코드를 직접 선택해 복사하거나 다운로드하세요.
          </div>
        )}
        <label style={{ display: 'flex', gap: 10, padding: 12, background: checked ? 'var(--success-soft)' : 'var(--warning-soft)', borderRadius: 8, alignItems: 'center', cursor: 'pointer' }}>
          <input type="checkbox" checked={checked} onChange={(e) => setChecked(e.target.checked)} />
          <span style={{ fontSize: 13, fontWeight: 600, color: checked ? 'var(--success)' : 'var(--warning)' }}>안전한 곳에 저장했습니다.</span>
        </label>
      </div>
    </Dialog>
  );
}
