import { useState, type FormEvent } from 'react';
import { api } from '../api/client';
import { ApiError } from '../api/types';
import type { ApiKeyCreateResponse } from '../api/types';
import { useToast } from '../components/Toast';
import Dialog from '../components/Dialog';
import { Copy, Alert } from '../components/Icons';

interface Props {
  tenantId: string;
  onClose: () => void;
  onIssued: () => void;
}

export default function ApiKeyCreateModal({ tenantId, onClose, onIssued }: Props) {
  const [name, setName] = useState('');
  const [scopesJson, setScopesJson] = useState('[]');
  const [busy, setBusy] = useState(false);
  const [issued, setIssued] = useState<ApiKeyCreateResponse | null>(null);
  const [copied, setCopied] = useState(false);
  const toast = useToast();

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    try {
      const resp = await api.post<ApiKeyCreateResponse>('/admin/api/api-keys', {
        tenantId,
        name,
        scopesJson,
      });
      setIssued(resp);
    } catch (err) {
      const e = err instanceof ApiError ? err : null;
      toast({
        kind: 'err',
        title: '발급 실패',
        message: e?.serverMessage ?? String(err),
        traceId: e?.traceId,
      });
    } finally {
      setBusy(false);
    }
  }

  async function copyPlaintext() {
    if (!issued) return;
    try {
      await navigator.clipboard.writeText(issued.plainText);
      setCopied(true);
      toast({ kind: 'ok', title: '클립보드에 복사됨' });
    } catch {
      toast({ kind: 'err', title: '복사 실패', message: '브라우저 권한을 확인하거나 위 텍스트를 수동으로 복사하세요.' });
    }
  }

  // ── Stage 2: one-time plaintext display ──────────────────────────────
  if (issued) {
    // Block ALL close paths (Escape, scrim) until the user has copied the
    // one-time plaintext. Once copied, allow onIssued() to close + refresh.
    const stage2Close = copied ? () => { onIssued(); } : () => {};
    return (
      <Dialog
        open
        onClose={stage2Close}
        closeOnScrim={false}
        title="API 키 발급 완료"
        sub="이 plaintext는 지금 한 번만 표시됩니다. 닫으면 영구 소실됩니다."
        wide
        footer={
          <>
            <button className="btn btn--outline" onClick={copyPlaintext}>
              <Copy size={14} /> 복사
            </button>
            <button
              className="btn btn--primary"
              onClick={stage2Close}
              disabled={!copied}
            >
              {copied ? '복사함, 닫기' : '먼저 복사하세요'}
            </button>
          </>
        }
      >
        <div className="banner banner--warning" style={{ marginBottom: 16 }}>
          <Alert size={16} className="banner__icon" />
          <div>
            <div className="banner__title">한 번만 노출되는 plaintext</div>
            <div className="banner__body">서버는 hash만 저장합니다. 복사 후 안전한 곳에 보관하세요.</div>
          </div>
        </div>
        <div className="label">PREFIX</div>
        <div className="mono" style={{ marginBottom: 12 }}>{issued.prefix}</div>
        <div className="label">PLAINTEXT (1회성)</div>
        <code
          className="mono"
          style={{
            display: 'block',
            padding: 12,
            background: 'var(--surface-sunk)',
            borderRadius: 'var(--radius)',
            wordBreak: 'break-all',
            fontSize: 12,
          }}
        >
          {issued.plainText}
        </code>
      </Dialog>
    );
  }

  // ── Stage 1: form ────────────────────────────────────────────────────
  return (
    <Dialog
      open
      onClose={onClose}
      title="API 키 발급"
      sub={`tenant ${tenantId}에 새 API key를 발급합니다.`}
      footer={
        <>
          <button className="btn btn--outline" onClick={onClose}>취소</button>
          <button form="apikey-create-form" type="submit" className="btn btn--primary" disabled={busy}>
            {busy ? '발급 중…' : '발급'}
          </button>
        </>
      }
    >
      <form id="apikey-create-form" onSubmit={submit} className="stack-4">
        <div>
          <label className="label">키 이름</label>
          <input
            className="input"
            value={name}
            onChange={(e) => setName(e.target.value)}
            required
            placeholder="primary, staging-tester, …"
            autoFocus
          />
        </div>
        <div>
          <label className="label">scopes (JSON 배열)</label>
          <textarea
            className="input mono"
            value={scopesJson}
            onChange={(e) => setScopesJson(e.target.value)}
            rows={2}
          />
          <div className="hint">비워두면 모든 scope. 예: ["registration", "authentication"]</div>
        </div>
      </form>
    </Dialog>
  );
}
