import { useState, useEffect } from 'react';
import { Icons } from '@/icons/Icons';
import { aaguidPolicyApi } from '@/api/aaguidPolicy';
import type { Tenant, AaguidPolicy } from '@/api/designTypes';
import { useToast } from '@/shell/ToastHost';
import { AAGUID_MODE_LABELS } from '@/i18n/labels';

// ── AAGUID 입력 자동 포맷 ─────────────────────────────────────────────────────
// 입력값에서 16진수만 남기고(최대 32자) UUID 형식 8-4-4-4-12 위치에 하이픈을
// 자동 삽입한다. 사용자가 하이픈을 직접 치거나 대문자를 넣어도 정규화된다.
//  "ea9b8d664d01" → "ea9b8d66-4d01"
//  "EA9B8D66-4D01-1D21-3CE4-B6B48CB575D4" → 동일(소문자) 결과
export function formatAaguid(raw: string): string {
  const hex = raw.toLowerCase().replace(/[^0-9a-f]/g, '').slice(0, 32);
  const seg = [8, 4, 4, 4, 12];
  const out: string[] = [];
  let i = 0;
  for (const len of seg) {
    if (i >= hex.length) break;
    out.push(hex.slice(i, i + len));
    i += len;
  }
  return out.join('-');
}

// ── AaguidPolicyTab ───────────────────────────────────────────────────────────

export default function AaguidPolicyTab({ tenant }: { tenant: Tenant }) {
  const [policy, setPolicy] = useState<AaguidPolicy | null>(null);
  const [draft, setDraft] = useState<AaguidPolicy | null>(null);
  const [aaguidInput, setAaguidInput] = useState('');
  const [saving, setSaving] = useState(false);
  const toast = useToast();

  const uuidRe = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
  const inputValid = uuidRe.test(aaguidInput);
  const dirty = policy !== null && draft !== null && JSON.stringify(policy) !== JSON.stringify(draft);

  useEffect(() => {
    aaguidPolicyApi
      .get(tenant.id)
      .then((p) => {
        setPolicy(p);
        setDraft(p);
      })
      .catch((e: unknown) => {
        const msg = e instanceof Error ? e.message : String(e);
        toast({ kind: 'err', title: 'AAGUID policy 로드 실패', message: msg });
      });
  }, [tenant.id]);

  const list =
    draft == null
      ? []
      : draft.mode === 'ALLOWLIST'
        ? draft.entries.filter((e) => e.aaguid)
        : draft.mode === 'DENYLIST'
          ? draft.entries.filter((e) => e.aaguid)
          : [];

  function setList(next: AaguidPolicy['entries']) {
    if (!draft) return;
    setDraft({ ...draft, entries: next });
  }

  function add() {
    if (!inputValid || !draft) return;
    const lower = aaguidInput.toLowerCase();
    if (list.some((e) => e.aaguid === lower)) return;
    setList([...list, { aaguid: lower, note: null, mdsName: null }]);
    setAaguidInput('');
  }

  async function handleSave() {
    if (!draft) return;
    setSaving(true);
    try {
      const updated = await aaguidPolicyApi.update(tenant.id, draft);
      setPolicy(updated);
      setDraft(updated);
      toast({ kind: 'ok', title: 'AAGUID 정책 저장 완료' });
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      toast({ kind: 'err', title: '저장 실패', message: msg });
    } finally {
      setSaving(false);
    }
  }

  function handleRevert() {
    if (!policy) return;
    setDraft({ ...policy, entries: [...policy.entries] });
  }

  if (!draft) {
    return <div style={{ padding: 40, color: 'var(--text-mute)' }}>Loading…</div>;
  }

  return (
    <div className="stack-4">
      <div className="card">
        <div className="card__head">
          <div>
            <h3 className="card__title">AAGUID Attestation Policy</h3>
            <div className="card__sub">authenticator 모델별 허용/차단. ANY는 RP가 처음 온보딩할 때만 사용 권장.</div>
          </div>
          <div className="row">
            <button className="btn btn--sm" disabled={!dirty} onClick={handleRevert}>되돌리기</button>
            <button className="btn btn--primary btn--sm" disabled={!dirty || saving} onClick={handleSave}>저장</button>
          </div>
        </div>

        <div className="card__body stack-4">
          <Field label="mode">
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 8, maxWidth: 540 }}>
              {[
                { v: 'ANY', t: AAGUID_MODE_LABELS.ANY, d: '모든 authenticator 허용' },
                { v: 'ALLOWLIST', t: AAGUID_MODE_LABELS.ALLOWLIST, d: '지정된 AAGUID만 허용' },
                { v: 'DENYLIST', t: AAGUID_MODE_LABELS.DENYLIST, d: '지정된 AAGUID만 차단' },
              ].map((o) => (
                <button
                  key={o.v}
                  onClick={() => setDraft({ ...draft, mode: o.v as AaguidPolicy['mode'] })}
                  style={{
                    padding: 10,
                    textAlign: 'left',
                    border: `1px solid ${draft.mode === o.v ? 'var(--accent)' : 'var(--border)'}`,
                    background: draft.mode === o.v ? 'var(--accent-soft)' : 'var(--surface)',
                    color: draft.mode === o.v ? 'var(--accent)' : 'var(--text)',
                    borderRadius: 8,
                    cursor: 'pointer',
                  }}
                >
                  <div className="mono" style={{ fontSize: 12, fontWeight: 600 }}>{o.t}</div>
                  <div style={{ fontSize: 11, color: 'var(--text-mute)', marginTop: 4 }}>{o.d}</div>
                </button>
              ))}
            </div>
          </Field>

          {draft.mode !== 'ANY' && (
            <Field
              label={draft.mode === 'ALLOWLIST' ? '허용된 AAGUID' : '차단된 AAGUID'}
              hint="UUID v4 형식. FIDO MDS 매핑된 이름이 옆에 표시됩니다."
            >
              <div style={{ display: 'flex', gap: 6, marginBottom: 8 }}>
                <input
                  className="input mono"
                  placeholder="ea9b8d66-4d01-1d21-3ce4-b6b48cb575d4"
                  value={aaguidInput}
                  onChange={(e) => setAaguidInput(formatAaguid(e.target.value))}
                  onKeyDown={(e) => e.key === 'Enter' && add()}
                  maxLength={36}
                  style={{ flex: 1, minWidth: 0 }}
                />
                <button className="btn btn--primary btn--sm" disabled={!inputValid} onClick={add} style={{ flexShrink: 0, whiteSpace: 'nowrap' }}>
                  <Icons.Plus size={12} /> 추가
                </button>
              </div>
              <div style={{ border: '1px solid var(--border)', borderRadius: 8, overflow: 'hidden' }}>
                {list.length === 0 ? (
                  <div style={{ padding: 14, fontSize: 12, color: 'var(--text-mute)' }}>
                    비어 있음 — 이 상태에서는{' '}
                    {draft.mode === 'ALLOWLIST' ? '모든 ceremony가 차단' : '모든 ceremony가 허용'}됩니다.
                  </div>
                ) : (
                  list.map((entry, i) => (
                    <div
                      key={entry.aaguid}
                      style={{
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'space-between',
                        padding: '8px 12px',
                        borderBottom: i === list.length - 1 ? 0 : '1px solid var(--border)',
                      }}
                    >
                      <div className="row">
                        <Icons.Fingerprint size={14} />
                        <span className="mono" style={{ fontSize: 12 }}>{entry.aaguid}</span>
                        {entry.mdsName && <span className="badge badge--accent">{entry.mdsName}</span>}
                      </div>
                      <button
                        className="btn btn--ghost btn--xs"
                        onClick={() => setList(list.filter((x) => x.aaguid !== entry.aaguid))}
                      >
                        <Icons.X size={12} />
                      </button>
                    </div>
                  ))
                )}
              </div>
            </Field>
          )}

          <Field
            label="MDS Strict 모드"
            hint="FIDO Metadata Service의 trust anchor만 허용. MDS 서비스 다운 시 모든 등록이 차단됩니다."
          >
            <Toggle
              on={draft.mdsStrict}
              onChange={(v) => setDraft({ ...draft, mdsStrict: v })}
              label={draft.mdsStrict ? 'MDS strict 켜짐' : 'MDS strict 꺼짐'}
            />
          </Field>
        </div>
      </div>
    </div>
  );
}

// ── Field ─────────────────────────────────────────────────────────────────────

function Field({ label, hint, children }: { label: string; hint?: string; children: React.ReactNode }) {
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
