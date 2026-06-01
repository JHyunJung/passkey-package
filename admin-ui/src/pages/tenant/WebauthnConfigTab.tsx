import { useState, useEffect } from 'react';
import { Icons } from '@/icons/Icons';
import { Dialog } from '@/shell/Dialog';
import { useToast } from '@/shell/ToastHost';
import { webauthnApi } from '@/api/webauthn';
import type { Tenant, WebauthnConfig } from '@/api/designTypes';

// webauthnApi.get returns WebauthnConfig + _mdsRequired. Use this locally.
type ConfigWithMds = WebauthnConfig & { _mdsRequired: boolean };

// ── util ──────────────────────────────────────────────────────────────────────

function diffObjects(
  a: WebauthnConfig,
  b: WebauthnConfig,
): { key: string; from: unknown; to: unknown }[] {
  const out: { key: string; from: unknown; to: unknown }[] = [];
  const keys = new Set([...Object.keys(a), ...Object.keys(b)]) as Set<keyof WebauthnConfig>;
  keys.forEach((k) => {
    // Skip internal fields not shown in the UI
    if ((k as string).startsWith('_')) return;
    if (JSON.stringify(a[k]) !== JSON.stringify(b[k])) {
      out.push({ key: k, from: a[k], to: b[k] });
    }
  });
  return out;
}

// ── WebauthnConfigTab ─────────────────────────────────────────────────────────

type WebauthnConfigTabProps = { tenant: Tenant };

export default function WebauthnConfigTab({ tenant }: WebauthnConfigTabProps) {
  const [cfg, setCfg] = useState<ConfigWithMds | null>(null);
  const [draft, setDraft] = useState<ConfigWithMds | null>(null);
  const [originInput, setOriginInput] = useState('');
  const [showDiff, setShowDiff] = useState(false);
  const [diffChanges, setDiffChanges] = useState<{ key: string; from: unknown; to: unknown }[]>([]);
  const [saving, setSaving] = useState(false);
  const toast = useToast();

  useEffect(() => {
    webauthnApi
      .get(tenant.id)
      .then((c) => {
        setCfg(c);
        setDraft({ ...c });
      })
      .catch((e: unknown) => {
        const msg = e instanceof Error ? e.message : String(e);
        toast({ kind: 'err', title: 'config 로드 실패', message: msg });
      });
  }, [tenant.id]);

  function addOrigin() {
    if (!draft) return;
    const v = originInput.trim();
    if (!v) return;
    if (draft.origins.includes(v)) return;
    setDraft({ ...draft, origins: [...draft.origins, v] });
    setOriginInput('');
  }

  function removeOrigin(o: string) {
    if (!draft) return;
    setDraft({ ...draft, origins: draft.origins.filter((x) => x !== o) });
  }

  const dirty = cfg !== null && draft !== null && JSON.stringify(cfg) !== JSON.stringify(draft);
  const localChanges = cfg && draft ? diffObjects(cfg, draft) : [];

  async function handlePreview() {
    if (!draft) return;
    try {
      const r = await webauthnApi.diff(tenant.id, draft, draft._mdsRequired);
      setDiffChanges(r);
      setShowDiff(true);
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      toast({ kind: 'err', title: 'diff 실패', message: msg });
    }
  }

  async function handleSave() {
    if (!draft) return;
    setSaving(true);
    try {
      const updated = await webauthnApi.update(tenant.id, tenant.name, draft, draft._mdsRequired);
      setCfg(updated);
      setDraft({ ...updated });
      setShowDiff(false);
      toast({
        kind: 'ok',
        title: 'WebAuthn config 저장됨',
        message: `${diffChanges.length}개 필드가 업데이트되었습니다.`,
      });
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      toast({ kind: 'err', title: '저장 실패', message: msg });
    } finally {
      setSaving(false);
    }
  }

  if (!draft) {
    return <div style={{ padding: 40, color: 'var(--text-mute)' }}>Loading…</div>;
  }

  return (
    <div className="stack-4">
      <div className="card">
        <div className="card__head">
          <div>
            <h3 className="card__title">WebAuthn Configuration</h3>
            <div className="card__sub">RP가 ceremony 요청 시 사용하는 파라미터. 변경은 즉시 다음 ceremony부터 적용됩니다.</div>
          </div>
          <div className="row">
            {dirty && <span className="badge badge--warning badge--dot">변경 사항 {localChanges.length}건</span>}
            <button className="btn btn--sm" disabled={!dirty} onClick={() => cfg && setDraft({ ...cfg })}>되돌리기</button>
            <button className="btn btn--primary btn--sm" disabled={!dirty} onClick={handlePreview}>저장…</button>
          </div>
        </div>

        <div className="card__body">
          <div className="grid-2" style={{ gap: 24 }}>
            <div className="stack-3">
              <Field
                label={<>rpId <span className="chip" style={{ fontSize: 10, padding: '1px 6px', gap: 3, background: 'var(--surface-3)', color: 'var(--text-mute)', verticalAlign: 'middle' }}><Icons.Lock size={10} /> 변경 불가</span></>}
                hint="Relying Party의 hostname. 패스키가 묶이는 신뢰 경계라 생성 후에는 변경할 수 없습니다. 바꾸려면 새 테넌트를 만들어야 합니다."
              >
                <div style={{ position: 'relative' }}>
                  <input
                    className="input mono"
                    value={draft.rpId}
                    readOnly
                    aria-readonly="true"
                    tabIndex={-1}
                    title="rpId는 생성 후 변경할 수 없습니다"
                    style={{ background: 'var(--surface-3)', color: 'var(--text-mute)', cursor: 'not-allowed', paddingRight: 32 }}
                  />
                  <span style={{ position: 'absolute', right: 10, top: '50%', transform: 'translateY(-50%)', color: 'var(--text-mute)', pointerEvents: 'none', display: 'inline-flex' }}>
                    <Icons.Lock size={13} />
                  </span>
                </div>
              </Field>
              <Field label="rpName" hint="UA 선택 화면에 표시되는 표시 이름.">
                <input className="input" value={draft.rpName} onChange={(e) => setDraft({ ...draft, rpName: e.target.value })} />
              </Field>
              <Field label="timeoutMs" hint="ceremony 타임아웃 (밀리초). 권장 60000–120000.">
                <input className="input mono" type="number" value={draft.timeoutMs} onChange={(e) => setDraft({ ...draft, timeoutMs: parseInt(e.target.value || '0', 10) })} />
              </Field>
            </div>
            <div className="stack-3">
              <Field label="origins" hint="ceremony가 시작될 수 있는 origin. 정확히 일치해야 함.">
                <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', padding: '8px 8px', border: '1px solid var(--border)', borderRadius: 8, background: 'var(--surface)', minHeight: 38 }}>
                  {draft.origins.map((o) => (
                    <span key={o} className="chip mono" style={{ fontSize: 11 }}>
                      {o}
                      <button className="chip__x" onClick={() => removeOrigin(o)}><Icons.X size={11} /></button>
                    </span>
                  ))}
                  <input
                    placeholder="https://… 입력 후 Enter"
                    style={{ border: 0, outline: 'none', fontSize: 12, padding: '2px 4px', flex: 1, minWidth: 160, background: 'transparent', color: 'var(--text)' }}
                    value={originInput}
                    onChange={(e) => setOriginInput(e.target.value)}
                    onKeyDown={(e) => e.key === 'Enter' && (e.preventDefault(), addOrigin())}
                  />
                </div>
              </Field>
              <Field label="userVerification" hint="UV flag. REQUIRED 권장 — PIN/biometric 강제.">
                <Segmented value={draft.userVerification} onChange={(v) => setDraft({ ...draft, userVerification: v as WebauthnConfig['userVerification'] })} options={['REQUIRED', 'PREFERRED', 'DISCOURAGED']} />
              </Field>
              <Field label="attestationConveyance" hint="attestation 객체 전달 모드.">
                <Segmented value={draft.attestationConveyance} onChange={(v) => setDraft({ ...draft, attestationConveyance: v as WebauthnConfig['attestationConveyance'] })} options={['NONE', 'INDIRECT', 'DIRECT', 'ENTERPRISE']} />
              </Field>
            </div>
          </div>
        </div>
      </div>

      <DiffDialog
        open={showDiff}
        onClose={() => setShowDiff(false)}
        changes={diffChanges}
        onConfirm={handleSave}
        saving={saving}
      />
    </div>
  );
}

// ── Field ─────────────────────────────────────────────────────────────────────

function Field({ label, hint, children }: { label: React.ReactNode; hint?: string; children: React.ReactNode }) {
  return (
    <div>
      <label className="label">{label}</label>
      {children}
      {hint && <div className="hint">{hint}</div>}
    </div>
  );
}

// ── Segmented ─────────────────────────────────────────────────────────────────

function Segmented({ value, onChange, options }: {
  value: string;
  onChange: (v: string) => void;
  options: string[];
}) {
  return (
    <div style={{ display: 'inline-flex', padding: 3, background: 'var(--surface-3)', borderRadius: 8, border: '1px solid var(--border)' }}>
      {options.map((o) => (
        <button
          key={o}
          onClick={() => onChange(o)}
          style={{
            padding: '4px 10px',
            border: 0, borderRadius: 6,
            background: value === o ? 'var(--surface)' : 'transparent',
            color: value === o ? 'var(--text)' : 'var(--text-mute)',
            fontWeight: value === o ? 600 : 500,
            fontSize: 11, letterSpacing: '0.02em',
            boxShadow: value === o ? 'var(--shadow-sm)' : 'none',
            cursor: 'pointer', fontFamily: 'var(--mono)',
          }}
        >
          {o}
        </button>
      ))}
    </div>
  );
}

// ── DiffDialog ────────────────────────────────────────────────────────────────

function DiffDialog({ open, onClose, changes, onConfirm, saving }: {
  open: boolean;
  onClose: () => void;
  changes: { key: string; from: unknown; to: unknown }[];
  onConfirm: () => void;
  saving?: boolean;
}) {
  return (
    <Dialog
      open={open}
      onClose={onClose}
      title="변경 사항 확인"
      sub="저장하면 다음 ceremony부터 라이브 RP에 즉시 적용됩니다."
      wide
      footer={
        <>
          <button className="btn" onClick={onClose}>취소</button>
          <button className="btn btn--primary" onClick={onConfirm} disabled={saving}>{changes.length}개 변경 사항 저장</button>
        </>
      }
    >
      <div style={{ border: '1px solid var(--border)', borderRadius: 8, overflow: 'hidden' }}>
        {changes.map((c, i) => <DiffRow key={c.key} c={c} last={i === changes.length - 1} />)}
        {changes.length === 0 && <div style={{ padding: 18, fontSize: 13, color: 'var(--text-mute)' }}>변경 사항이 없습니다.</div>}
      </div>
      <div style={{ marginTop: 12, padding: 10, background: 'var(--warning-soft)', color: 'var(--warning)', borderRadius: 6, fontSize: 12, display: 'flex', gap: 8 }}>
        <Icons.Alert size={14} />
        <span>이 변경은 즉시 라이브 RP에 영향을 줍니다. 변경 사항은 audit log에 <code style={{ background: 'rgba(0,0,0,0.05)', padding: '0 4px', borderRadius: 3 }}>WEBAUTHN_CONFIG_UPDATED</code>로 기록됩니다.</span>
      </div>
    </Dialog>
  );
}

// ── DiffRow ───────────────────────────────────────────────────────────────────

function DiffRow({ c, last }: { c: { key: string; from: unknown; to: unknown }; last?: boolean }) {
  // origin-array diff: enumerate added / removed lines
  if (Array.isArray(c.from) && Array.isArray(c.to)) {
    const added = (c.to as string[]).filter((x) => !(c.from as string[]).includes(x));
    const removed = (c.from as string[]).filter((x) => !(c.to as string[]).includes(x));
    return (
      <div style={{ padding: 14, borderBottom: last ? 0 : '1px solid var(--border)' }}>
        <div style={{ fontFamily: 'var(--mono)', fontSize: 12, fontWeight: 600, color: 'var(--text)' }}>{c.key}</div>
        <div style={{ marginTop: 8, display: 'grid', gap: 4 }}>
          {removed.map((x) => <DiffLine key={'-' + x} sign="-" value={x} />)}
          {added.map((x) => <DiffLine key={'+' + x} sign="+" value={x} />)}
        </div>
      </div>
    );
  }
  return (
    <div style={{ padding: 14, borderBottom: last ? 0 : '1px solid var(--border)' }}>
      <div style={{ fontFamily: 'var(--mono)', fontSize: 12, fontWeight: 600 }}>{c.key}</div>
      <div style={{ marginTop: 8, display: 'grid', gap: 4 }}>
        <DiffLine sign="-" value={c.from} />
        <DiffLine sign="+" value={c.to} />
      </div>
    </div>
  );
}

// ── DiffLine ──────────────────────────────────────────────────────────────────

function DiffLine({ sign, value }: { sign: '+' | '-'; value: unknown }) {
  const isAdd = sign === '+';
  return (
    <div style={{
      display: 'flex', gap: 8,
      padding: '4px 10px',
      background: isAdd ? 'color-mix(in oklab, var(--success-soft) 70%, transparent)' : 'color-mix(in oklab, var(--danger-soft) 70%, transparent)',
      borderRadius: 4,
      fontFamily: 'var(--mono)', fontSize: 12,
      color: isAdd ? 'var(--success)' : 'var(--danger)',
    }}>
      <span style={{ width: 10, fontWeight: 700 }}>{sign}</span>
      <span style={{ color: 'var(--text)' }}>{String(value)}</span>
    </div>
  );
}
