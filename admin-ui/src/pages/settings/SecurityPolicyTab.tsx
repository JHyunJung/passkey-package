import { useState, useEffect } from 'react';
import { Icons } from '@/icons/Icons';
import { securityPolicyApi, type SecurityPolicyView } from '@/api/securityPolicy';
import { useToast } from '@/shell/ToastHost';

// ── Field ─────────────────────────────────────────────────────────────────────

function Field({
  label,
  hint,
  children,
}: {
  label: string;
  hint?: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <div>
      <label className="label">{label}</label>
      {children}
      {hint && <div className="hint">{hint}</div>}
    </div>
  );
}

// ── Toggle ────────────────────────────────────────────────────────────────────

function Toggle({ on, onChange, label }: { on: boolean; onChange: (v: boolean) => void; label: string }) {
  return (
    <button
      onClick={() => onChange(!on)}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 8,
        border: 0,
        background: 'transparent',
        padding: 0,
        cursor: 'pointer',
      }}
    >
      <span
        style={{
          position: 'relative',
          width: 36,
          height: 20,
          borderRadius: 999,
          background: on ? 'var(--accent)' : 'var(--border-strong)',
          transition: 'background 120ms ease',
          display: 'inline-block',
        }}
      >
        <span
          style={{
            position: 'absolute',
            top: 2,
            left: on ? 18 : 2,
            width: 16,
            height: 16,
            borderRadius: 999,
            background: 'white',
            boxShadow: '0 1px 3px rgba(0,0,0,0.2)',
            transition: 'left 120ms ease',
          }}
        />
      </span>
      <span style={{ fontSize: 13, color: 'var(--text)' }}>{label}</span>
    </button>
  );
}

// ── SecurityPolicyTab ─────────────────────────────────────────────────────────

export default function SecurityPolicyTab() {
  const [sessionMin, setSessionMin] = useState(30);
  const [reqMfa, setReqMfa] = useState(true);
  const [corsAllowlist, setCorsAllowlist] = useState<string[]>([]);
  const [originInput, setOriginInput] = useState('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const toast = useToast();

  function applyView(v: SecurityPolicyView) {
    setSessionMin(v.sessionIdleTimeoutMinutes);
    setReqMfa(v.mfaRequired);
    setCorsAllowlist(v.corsAllowlist);
  }

  function addCorsOrigin() {
    const v = originInput.trim();
    if (!v) return;
    if (corsAllowlist.includes(v)) return;
    setCorsAllowlist([...corsAllowlist, v]);
    setOriginInput('');
  }

  function removeCorsOrigin(o: string) {
    setCorsAllowlist(corsAllowlist.filter((x) => x !== o));
  }

  useEffect(() => {
    setLoading(true);
    securityPolicyApi.get()
      .then(applyView)
      .catch((e: unknown) => {
        const msg = e instanceof Error ? e.message : String(e);
        toast({ kind: 'err', title: '보안 정책 로드 실패', message: msg });
      })
      .finally(() => setLoading(false));
  }, []);

  async function handleSave() {
    setSaving(true);
    try {
      const v = await securityPolicyApi.update({
        sessionIdleTimeoutMinutes: sessionMin,
        mfaRequired: reqMfa,
        corsAllowlist,
      });
      applyView(v);
      toast({ kind: 'ok', title: '보안 정책 저장됨', traceId: 'tr_sec_001' });
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      toast({ kind: 'err', title: '보안 정책 저장 실패', message: msg });
    } finally {
      setSaving(false);
    }
  }

  async function handleCancel() {
    try {
      const v = await securityPolicyApi.get();
      applyView(v);
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      toast({ kind: 'err', title: '되돌리기 실패', message: msg });
    }
  }

  return (
    <div className="stack-4">
      <div className="card">
        <div className="card__head">
          <h3 className="card__title">세션 &amp; 인증 정책</h3>
        </div>
        <div className="card__body stack-4">
          <Field label="세션 idle timeout (분)" hint="이 시간 동안 활동이 없으면 자동 로그아웃됩니다. PRD REQ-A-5.">
            <input className="input mono" type="number" value={sessionMin} onChange={(e) => setSessionMin(parseInt(e.target.value || '0', 10))} style={{ width: 120 }} />
          </Field>
          <Field label="MFA 필수">
            <Toggle on={reqMfa} onChange={setReqMfa} label={reqMfa ? '모든 운영자에게 MFA 필수' : 'MFA 선택'} />
          </Field>
          <Field label="CORS Allowlist">
            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', padding: '8px 10px', border: '1px solid var(--border)', borderRadius: 8, background: 'var(--surface)' }}>
              {corsAllowlist.map((origin) => (
                <span key={origin} className="chip mono" style={{ fontSize: 11 }}>
                  {origin}
                  <button className="chip__x" onClick={() => removeCorsOrigin(origin)}><Icons.X size={11} /></button>
                </span>
              ))}
              <input
                placeholder="https://… 추가 후 Enter"
                style={{ border: 0, outline: 'none', fontSize: 12, padding: '2px 4px', flex: 1, minWidth: 200, background: 'transparent', color: 'var(--text)' }}
                value={originInput}
                onChange={(e) => setOriginInput(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && (e.preventDefault(), addCorsOrigin())}
              />
            </div>
          </Field>
        </div>
        <div className="card__head" style={{ borderTop: '1px solid var(--border)', borderBottom: 0, justifyContent: 'flex-end' }}>
          <button className="btn" onClick={handleCancel} disabled={saving || loading}>취소</button>
          <button
            className="btn btn--primary"
            onClick={handleSave}
            disabled={saving || loading}
          >
            저장
          </button>
        </div>
      </div>
    </div>
  );
}
