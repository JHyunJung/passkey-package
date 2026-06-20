import { useState, useEffect } from 'react';
import { Icons } from '@/icons/Icons';
import { Dialog } from '@/shell/Dialog';
import { useToast } from '@/shell/ToastHost';
import { webauthnApi } from '@/api/webauthn';
import type { Tenant, WebauthnConfig } from '@/api/designTypes';
import { apkKeyHashFromFingerprint, isApkKeyHashOrigin, APK_KEY_HASH_PREFIX } from '@/lib/apkKeyHash';

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

// origin 입력값을 검증한다. WebAuthn 스펙상 origin 의 host 는 rpId 와 같거나
// rpId 의 서브도메인이어야 한다(rpId 는 origin host 의 등록 가능 도메인 접미사).
//  rpId=dev-passkey.crosscert.com →
//   ✅ dev-passkey.crosscert.com (자기 자신), sub.dev-passkey.crosscert.com (서브도메인)
//   ❌ de-passkey.crosscert.com (다른 host), crosscert.com (상위 도메인)
// 반환: { ok:true, value } 정규화된 origin, 또는 { ok:false, error } 사유.
export function validateOrigin(input: string, rpId: string): { ok: true; value: string } | { ok: false; error: string } {
  const raw = input.trim();
  if (!raw) return { ok: false, error: 'origin 을 입력하세요.' };

  // Android 네이티브 앱 origin: android:apk-key-hash:<43자 base64url>.
  // 도메인이 아니라 앱 서명키 해시이므로 rpId 서브도메인 범위 검사를 적용하지 않는다.
  if (isApkKeyHashOrigin(raw)) {
    const body = raw.slice(APK_KEY_HASH_PREFIX.length);
    if (/^[A-Za-z0-9_-]{43}$/.test(body)) {
      return { ok: true, value: raw };
    }
    return { ok: false, error: 'android:apk-key-hash 값은 43자 base64url 이어야 합니다.' };
  }

  // 스킴이 없으면 https:// 를 가정해 파싱(사용자가 host만 입력한 경우 허용).
  const withScheme = /^https?:\/\//i.test(raw) ? raw : `https://${raw}`;
  let url: URL;
  try {
    url = new URL(withScheme);
  } catch {
    return { ok: false, error: `'${raw}' 는 올바른 origin 형식이 아닙니다. 예) https://${rpId}` };
  }
  if (url.protocol !== 'https:' && url.protocol !== 'http:') {
    return { ok: false, error: 'origin 스킴은 https(또는 http)만 허용됩니다.' };
  }
  const host = url.hostname.toLowerCase();
  const base = rpId.toLowerCase();
  const isSelf = host === base;
  const isSub = host.endsWith(`.${base}`);
  if (!isSelf && !isSub) {
    return { ok: false, error: `origin host '${host}' 는 rpId '${rpId}' 범위를 벗어납니다. rpId 자신이거나 그 서브도메인(예: sub.${rpId})만 추가할 수 있습니다.` };
  }
  // 정규화: scheme://host[:port] (경로·쿼리 제거). WebAuthn origin 은 path 를 갖지 않는다.
  const normalized = `${url.protocol}//${url.host}`;
  return { ok: true, value: normalized };
}

// ── 옵션별 설명 (선택값에 따라 동적으로 노출) ─────────────────────────────────
// WebAuthn 스펙 기준. ceremony 동작에 직접 영향을 주므로 운영자가 선택 전후로
// 무엇이 바뀌는지 한눈에 알 수 있게 한다.

// UV·attestation 안내는 위험도와 무관하게 단일 색(info)으로 통일한다. 옵션 자체의
// 의미를 설명하는 정보성 안내이므로, 톤으로 우열을 암시하지 않는다.
const UV_GUIDE: Record<string, { tone: 'info' | 'ok' | 'warn'; text: string }> = {
  REQUIRED: { tone: 'info', text: 'PIN·지문·얼굴 등 사용자 확인(UV)을 반드시 요구합니다. UV를 못 하는 인증기는 ceremony가 실패합니다. 2FA 수준의 보안이 필요할 때 권장.' },
  PREFERRED: { tone: 'info', text: '가능하면 UV를 수행하되, 지원하지 않는 인증기는 UV 없이도 허용합니다. 보안과 호환성의 균형 — 대부분의 서비스 기본값.' },
  DISCOURAGED: { tone: 'info', text: 'UV를 요구하지 않습니다(사용자 존재 확인만). 마찰은 가장 적지만 단일 인증 요소가 되므로, 비밀번호 등 다른 요소와 함께 쓸 때만 권장.' },
};

const AT_GUIDE: Record<string, { tone: 'info' | 'ok' | 'warn'; text: string }> = {
  NONE: { tone: 'info', text: 'attestation을 요청하지 않습니다. 프라이버시가 가장 높고 등록이 단순해, 인증기 모델 검증이 불필요한 일반 서비스에 권장.' },
  INDIRECT: { tone: 'info', text: 'attestation을 원하되 클라이언트가 익명화할 수 있게 허용합니다. 출처 정보를 받되 개별 기기 추적은 피하고 싶을 때.' },
  DIRECT: { tone: 'info', text: '인증기의 attestation을 그대로 받습니다. AAGUID·모델 확인이나 MDS 매칭으로 허용 기기를 통제하려면 필요. 등록 시 사용자 동의 프롬프트가 뜰 수 있습니다.' },
  ENTERPRISE: { tone: 'info', text: '기기를 개별 식별할 수 있는 attestation을 요구합니다. 사전 등록된 인증기만 쓰는 사내 환경 전용 — 일반 사용자 대상 서비스에는 부적합.' },
};

// ── OptionGuide — 선택값에 맞는 안내 박스 ────────────────────────────────────

function OptionGuide({ guide }: { guide?: { tone: 'info' | 'ok' | 'warn'; text: string } }) {
  if (!guide) return null;
  const palette = {
    info: { bg: 'var(--surface-3)', fg: 'var(--text-soft)', icon: 'var(--text-mute)' },
    ok: { bg: 'var(--success-soft)', fg: 'var(--success)', icon: 'var(--success)' },
    warn: { bg: 'var(--warning-soft)', fg: 'var(--warning)', icon: 'var(--warning)' },
  }[guide.tone];
  return (
    <div style={{ marginTop: 8, padding: '8px 10px', background: palette.bg, borderRadius: 6, fontSize: 12, lineHeight: 1.6, color: palette.fg, display: 'flex', gap: 7 }}>
      <span style={{ color: palette.icon, flexShrink: 0, marginTop: 1 }}><Icons.Info size={13} /></span>
      <span>{guide.text}</span>
    </div>
  );
}

// ── timeoutMs → 초/분 환산 안내 ──────────────────────────────────────────────
// 권장 범위 60000–120000ms(=60–120초). 벗어나면 warn 톤으로 주의.

function timeoutGuide(ms: number): { tone: 'info' | 'ok' | 'warn'; text: string } {
  if (!Number.isFinite(ms) || ms <= 0) {
    return { tone: 'warn', text: '0보다 큰 값을 입력하세요. 권장 60000–120000ms(60–120초).' };
  }
  const totalSec = ms / 1000;
  // 정수 초면 "60초", 소수면 "1.5초"처럼 불필요한 0 제거
  const secLabel = Number.isInteger(totalSec) ? `${totalSec}초` : `${parseFloat(totalSec.toFixed(2))}초`;
  // 60초 이상이면 분·초로도 풀어서 표기 (예: 90초 → 1분 30초)
  let human = `= ${secLabel}`;
  if (totalSec >= 60) {
    const m = Math.floor(totalSec / 60);
    const s = Math.round(totalSec - m * 60);
    human = `= ${secLabel}` + (s === 0 ? ` (${m}분)` : ` (${m}분 ${s}초)`);
  }
  if (ms < 60000) return { tone: 'warn', text: `${human} — 권장(60초)보다 짧습니다. 너무 짧으면 사용자가 인증을 끝내기 전에 만료될 수 있습니다.` };
  if (ms > 120000) return { tone: 'warn', text: `${human} — 권장(120초)보다 깁니다. 너무 길면 만료를 기다리는 동안 ceremony 자원이 오래 점유됩니다.` };
  return { tone: 'ok', text: `${human} — 권장 범위(60–120초) 안입니다.` };
}

// ── WebauthnConfigTab ─────────────────────────────────────────────────────────

type WebauthnConfigTabProps = { tenant: Tenant };

export default function WebauthnConfigTab({ tenant }: WebauthnConfigTabProps) {
  const [cfg, setCfg] = useState<ConfigWithMds | null>(null);
  const [draft, setDraft] = useState<ConfigWithMds | null>(null);
  const [originInput, setOriginInput] = useState('');
  const [apkInput, setApkInput] = useState('');
  const [originError, setOriginError] = useState<string | null>(null);
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
    const result = validateOrigin(v, draft.rpId);
    if (!result.ok) {
      setOriginError(result.error);
      return;
    }
    if (draft.origins.includes(result.value)) {
      setOriginError(`'${result.value}' 는 이미 추가되어 있습니다.`);
      return;
    }
    setDraft({ ...draft, origins: [...draft.origins, result.value] });
    setOriginInput('');
    setOriginError(null);
  }

  function addApkKey() {
    if (!draft) return;
    const conv = apkKeyHashFromFingerprint(apkInput);
    if (!conv.ok) {
      setOriginError(conv.error);
      return;
    }
    if (draft.origins.includes(conv.value)) {
      setOriginError('이미 추가된 Android 키입니다.');
      return;
    }
    setDraft({ ...draft, origins: [...draft.origins, conv.value] });
    setApkInput('');
    setOriginError(null);
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
                hint="Relying Party hostname (생성 후 변경 불가)"
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
              <Field label="rpName" hint="UA 선택 화면에 표시되는 표시 이름">
                <input className="input" value={draft.rpName} onChange={(e) => setDraft({ ...draft, rpName: e.target.value })} />
              </Field>
              <Field label="timeoutMs" hint="ceremony 타임아웃 (밀리초)">
                <input className="input mono" type="number" value={draft.timeoutMs} onChange={(e) => setDraft({ ...draft, timeoutMs: parseInt(e.target.value || '0', 10) })} />
                {/* 초/분 환산 안내는 값을 바꿨을 때만 노출(첫 진입 시엔 숨김). */}
                {cfg && draft.timeoutMs !== cfg.timeoutMs && <OptionGuide guide={timeoutGuide(draft.timeoutMs)} />}
              </Field>
            </div>
            <div className="stack-3">
              <Field label="origins" hint={`rpId(${draft.rpId}) 또는 그 서브도메인만 허용`}>
                <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', padding: '8px 8px', border: `1px solid ${originError ? 'var(--danger)' : 'var(--border)'}`, borderRadius: 8, background: 'var(--surface)', minHeight: 38 }}>
                  {draft.origins.map((o) => (
                    <span key={o} className="chip mono" style={{ fontSize: 11 }}>
                      {isApkKeyHashOrigin(o)
                        ? `🤖 ${o.slice(APK_KEY_HASH_PREFIX.length, APK_KEY_HASH_PREFIX.length + 8)}…`
                        : o}
                      <button className="chip__x" onClick={() => removeOrigin(o)}><Icons.X size={11} /></button>
                    </span>
                  ))}
                  <input
                    placeholder={`https://${draft.rpId} 입력 후 Enter`}
                    style={{ border: 0, outline: 'none', fontSize: 12, padding: '2px 4px', flex: 1, minWidth: 160, background: 'transparent', color: 'var(--text)' }}
                    value={originInput}
                    onChange={(e) => { setOriginInput(e.target.value); if (originError) setOriginError(null); }}
                    onKeyDown={(e) => e.key === 'Enter' && (e.preventDefault(), addOrigin())}
                  />
                </div>
                <div style={{ display: 'flex', gap: 6, marginTop: 8, alignItems: 'center' }}>
                  <input
                    className="input mono"
                    placeholder="Android 서명키 SHA-256 지문 (keytool 출력 붙여넣기) 후 Enter"
                    style={{ flex: 1, fontSize: 12 }}
                    value={apkInput}
                    onChange={(e) => { setApkInput(e.target.value); if (originError) setOriginError(null); }}
                    onKeyDown={(e) => e.key === 'Enter' && (e.preventDefault(), addApkKey())}
                  />
                  <button className="btn btn--sm" onClick={addApkKey}>앱 키 추가</button>
                </div>
                {originError && (
                  <div style={{ marginTop: 8, padding: '8px 10px', background: 'var(--danger-soft)', borderRadius: 6, fontSize: 12, lineHeight: 1.6, color: 'var(--danger)', display: 'flex', gap: 7 }}>
                    <span style={{ flexShrink: 0, marginTop: 1 }}><Icons.Alert size={13} /></span>
                    <span>{originError}</span>
                  </div>
                )}
              </Field>
              <Field label="userVerification" hint="사용자 확인(PIN·생체) 강제 수준">
                <Segmented value={draft.userVerification} onChange={(v) => setDraft({ ...draft, userVerification: v as WebauthnConfig['userVerification'] })} options={['REQUIRED', 'PREFERRED', 'DISCOURAGED']} />
                <OptionGuide guide={UV_GUIDE[draft.userVerification]} />
              </Field>
              <Field label="attestationConveyance" hint="인증기 출처 증명 전달 모드">
                <Segmented value={draft.attestationConveyance} onChange={(v) => setDraft({ ...draft, attestationConveyance: v as WebauthnConfig['attestationConveyance'] })} options={['NONE', 'INDIRECT', 'DIRECT', 'ENTERPRISE']} />
                <OptionGuide guide={AT_GUIDE[draft.attestationConveyance]} />
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
      {/* 옵션 이름(굵게) 옆에 "- 설명"을 같은 줄에 표기. 설명은 일반 회색으로
          이름과 톤을 구분. (.label 기본 500 → 이름만 700 오버라이드) */}
      <label className="label" style={{ display: 'flex', alignItems: 'baseline', gap: 6, flexWrap: 'wrap' }}>
        <span style={{ fontWeight: 700 }}>{label}</span>
        {hint && <span style={{ fontWeight: 400, color: 'var(--text-mute)' }}>- {hint}</span>}
      </label>
      {children}
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
