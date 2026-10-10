// admin-ui/src/pages/settings/RecoveryCodesModal.tsx
import { useState } from 'react';
import { Icons } from '@/icons/Icons';
import { illustrations } from '@/assets/illustrations';
import { recoveryCodesFilename } from '@/lib/recoveryCodeFilename';
import { copyToClipboard } from '@/lib/clipboard';
import { Button, Dialog } from '@/ui';

const TITLE = '복구 코드 — 지금만 표시됩니다';

/**
 * MFA 복구 코드 1회 표시 모달(MFA 켜기 3단계). confirm 직후 응답의 recoveryCodes 를 보여준다.
 * "저장했습니다" 체크 전에는 닫기 불가, ESC·바깥 클릭으로도 닫히지 않는다(dismissible=false).
 * 닫으면 영구히 다시 못 본다.
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
    <Dialog
      open
      onClose={() => { /* 저장 확인 전후 모두 ESC·스크림으로 닫지 않는다 — 닫기 버튼만 */ }}
      dismissible={false}
      role="alertdialog"
      ariaLabel={TITLE}
      footer={
        <Button variant={checked ? 'primary' : 'secondary'} disabled={!checked} onClick={onClose}>
          {checked ? '닫기' : '닫기 · 체크 필요'}
        </Button>
      }
    >
      <div className="row" style={{ gap: 14, flexWrap: 'nowrap', marginTop: 4 }}>
        <img src={illustrations.recoveryCodes} alt="" style={{ width: 72, height: 72, objectFit: 'contain', flex: 'none' }} />
        <div className="stack-1">
          <h3 className="dialog__title">{TITLE}</h3>
          <p className="dialog__sub" style={{ margin: 0 }}>
            authenticator 기기를 잃었을 때 각 코드를 한 번씩 로그인에 쓸 수 있습니다.
          </p>
        </div>
      </div>

      <ol
        className="mono"
        aria-label="복구 코드 목록"
        style={{
          listStyle: 'none', margin: 0,
          display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: '10px 20px',
          border: '1px dashed var(--brand-300)', background: 'var(--brand-50)', borderRadius: 'var(--r)',
          padding: '16px 18px', fontSize: 14,
        }}
      >
        {codes.map((c, i) => (
          <li key={c}>
            <span className="hint" style={{ display: 'inline-block', width: 22, margin: 0 }}>{String(i + 1).padStart(2, '0')}</span>
            <span>{c}</span>
          </li>
        ))}
      </ol>

      <div className="row">
        <Button onClick={() => void copy()} icon={copied ? <Icons.Check size={14} /> : <Icons.Copy size={14} />}>
          {copied ? '복사됨' : '복사'}
        </Button>
        <Button onClick={download} icon={<Icons.Download size={14} />}>다운로드 (.txt)</Button>
        <span className="hint" style={{ margin: 0 }}>복사 실패 시: 직접 선택하거나 다운로드</span>
      </div>
      {copyFailed && (
        <p className="field__error" role="alert" style={{ margin: 0 }}>
          복사에 실패했습니다. 위 코드를 직접 선택해 복사하거나 다운로드하세요.
        </p>
      )}

      <label
        className="row"
        style={{ gap: 10, padding: '12px 14px', border: '1px solid var(--brand-700)', borderRadius: 'var(--r)', background: 'var(--brand-50)', cursor: 'pointer' }}
      >
        <input
          type="checkbox"
          checked={checked}
          onChange={(e) => setChecked(e.target.checked)}
          style={{ width: 16, height: 16, margin: 0, accentColor: 'var(--brand-700)' }}
        />
        <strong style={{ fontSize: 13 }}>안전한 곳에 저장했습니다.</strong>
      </label>
    </Dialog>
  );
}
