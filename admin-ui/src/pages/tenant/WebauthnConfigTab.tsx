// admin-ui/src/pages/tenant/WebauthnConfigTab.tsx
import { useState, useEffect, CSSProperties } from 'react';
import { Icons } from '@/icons/Icons';
import { webauthnApi } from '@/api/webauthn';
import type { Tenant, WebauthnConfig } from '@/api/designTypes';
import { apkKeyHashFromFingerprint, isApkKeyHashOrigin, APK_KEY_HASH_PREFIX } from '@/lib/apkKeyHash';
import { Alert, Badge, Button, Card, CardHeader, ChipInput, Dialog, SaveBar, Segmented, useToast, useUnsavedGuard } from '@/ui';
import { SetRow } from './SetRow';

// webauthnApi.get returns WebauthnConfig + _mdsRequired. Use this locally.
type ConfigWithMds = WebauthnConfig & { _mdsRequired: boolean };
type Change = { key: string; from: unknown; to: unknown };

// ── util ──────────────────────────────────────────────────────────────────────

function diffObjects(a: WebauthnConfig, b: WebauthnConfig): Change[] {
  const out: Change[] = [];
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

const FIELD_ORDER = ['rpName', 'timeoutMs', 'origins', 'userVerification', 'attestationConveyance', 'formats'];

/** SaveBar 요약용: 바뀐 필드 이름을 화면 순서로. */
export function changedFieldNames(changes: { key: string }[]): string[] {
  const keys = changes.map((c) => c.key);
  return [...FIELD_ORDER.filter((k) => keys.includes(k)), ...keys.filter((k) => !FIELD_ORDER.includes(k))];
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

// ── timeoutMs → 초/분 환산 안내 ──────────────────────────────────────────────
// 권장 범위 60000–120000ms(=60–120초). 벗어나면 warn 톤으로 주의.

function timeoutGuide(ms: number): { tone: 'info' | 'ok' | 'warn'; text: string } {
  if (!Number.isFinite(ms) || ms <= 0) {
    return { tone: 'warn', text: '0보다 큰 값을 입력하세요. 권장 60000–120000ms(60–120초).' };
  }
  const totalSec = ms / 1000;
  const secLabel = Number.isInteger(totalSec) ? `${totalSec}초` : `${parseFloat(totalSec.toFixed(2))}초`;
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

const WARN_BORDER: CSSProperties = { borderColor: 'var(--warn-dot)' };
const UV_OPTIONS = (['REQUIRED', 'PREFERRED', 'DISCOURAGED'] as const).map((v) => ({ value: v, label: v }));
const AT_OPTIONS = (['NONE', 'INDIRECT', 'DIRECT', 'ENTERPRISE'] as const).map((v) => ({ value: v, label: v }));

// ── WebauthnConfigTab ─────────────────────────────────────────────────────────

/** onSaved: 저장 성공 뒤 호출 — tenant 셸(개요 WebAuthn 요약)을 갱신한다. */
type WebauthnConfigTabProps = { tenant: Tenant; onSaved?: () => void };

export default function WebauthnConfigTab({ tenant, onSaved }: WebauthnConfigTabProps) {
  const [cfg, setCfg] = useState<ConfigWithMds | null>(null);
  const [draft, setDraft] = useState<ConfigWithMds | null>(null);
  const [apkInput, setApkInput] = useState('');
  const [apkError, setApkError] = useState<string | null>(null);
  const [showDiff, setShowDiff] = useState(false);
  const [diffChanges, setDiffChanges] = useState<Change[]>([]);
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

  const dirty = cfg !== null && draft !== null && JSON.stringify(cfg) !== JSON.stringify(draft);
  const localChanges = cfg && draft ? diffObjects(cfg, draft) : [];
  const changed = new Set(localChanges.map((c) => c.key));
  const guard = useUnsavedGuard(dirty);

  // ChipInput validate: 형식·rpId 범위 + 정규화 후 중복.
  function validateOriginInput(v: string): string | null {
    if (!draft) return null;
    const r = validateOrigin(v, draft.rpId);
    if (!r.ok) return r.error;
    if (draft.origins.includes(r.value)) return `'${r.value}' 는 이미 추가되어 있습니다.`;
    return null;
  }

  // ChipInput 은 입력 원문을 넘긴다 → 새로 들어온 값만 정규화(삭제는 그대로 반영).
  function onOriginsChange(next: string[]) {
    if (!draft) return;
    const normalized = next.map((v) => {
      if (draft.origins.includes(v)) return v;
      const r = validateOrigin(v, draft.rpId);
      return r.ok ? r.value : v;
    });
    setDraft({ ...draft, origins: Array.from(new Set(normalized)) });
  }

  function addApkKey() {
    if (!draft) return;
    const conv = apkKeyHashFromFingerprint(apkInput);
    if (!conv.ok) {
      setApkError(conv.error);
      return;
    }
    if (draft.origins.includes(conv.value)) {
      setApkError('이미 추가된 Android 키입니다.');
      return;
    }
    setDraft({ ...draft, origins: [...draft.origins, conv.value] });
    setApkInput('');
    setApkError(null);
  }

  function handleRevert() {
    if (!cfg) return;
    setDraft({ ...cfg });
    setApkInput('');
    setApkError(null);
  }

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
      toast({ kind: 'ok', title: 'WebAuthn config 저장됨', message: `${diffChanges.length}개 필드가 업데이트되었습니다.` });
      onSaved?.();
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      toast({ kind: 'err', title: '저장 실패', message: msg });
    } finally {
      setSaving(false);
    }
  }

  if (!draft || !cfg) {
    return <div className="muted" style={{ padding: 40 }}>Loading…</div>;
  }

  const tg = timeoutGuide(draft.timeoutMs);
  const addedOrigins = draft.origins.filter((o) => !cfg.origins.includes(o));
  const uv = UV_GUIDE[draft.userVerification];
  const at = AT_GUIDE[draft.attestationConveyance];

  return (
    <div className="stack-6">
      <Card>
        <CardHeader title="WebAuthn Configuration" sub="변경 사항은 다음 ceremony부터 적용됩니다." />

        <SetRow label="rpId" htmlFor="wa-rpid" desc="생성 시 고정. 기존 credential 이 이 값에 묶여 있습니다.">
          <div className="row" style={{ gap: 10, flexWrap: 'nowrap' }}>
            <input id="wa-rpid" className="input mono" value={draft.rpId} readOnly aria-readonly="true" title="rpId는 생성 후 변경할 수 없습니다" />
            <Badge dot={false}><Icons.Lock size={11} />변경 불가</Badge>
          </div>
        </SetRow>

        <SetRow label="rpName" htmlFor="wa-rpname" desc="사용자의 패스키 프롬프트에 보이는 이름">
          <div className="row" style={{ gap: 10, flexWrap: 'nowrap' }}>
            <input id="wa-rpname" className="input" value={draft.rpName} style={changed.has('rpName') ? WARN_BORDER : undefined}
              onChange={(e) => setDraft({ ...draft, rpName: e.target.value })} />
            {changed.has('rpName') && <Badge tone="warn" dot={false}>변경됨</Badge>}
          </div>
        </SetRow>

        <SetRow label="timeoutMs" htmlFor="wa-timeout" desc="ceremony 대기 시간">
          <div className="row" style={{ gap: 10 }}>
            <input id="wa-timeout" className="input mono" type="number" value={draft.timeoutMs} aria-describedby="wa-timeout-hint"
              style={{ maxWidth: 180, ...(changed.has('timeoutMs') ? WARN_BORDER : {}) }}
              onChange={(e) => setDraft({ ...draft, timeoutMs: parseInt(e.target.value || '0', 10) })} />
            {changed.has('timeoutMs') && <Badge tone="warn" dot={false}>변경됨</Badge>}
          </div>
          <p id="wa-timeout-hint" className="hint" style={tg.tone === 'warn' ? { color: 'var(--warn)' } : undefined}>{tg.text}</p>
        </SetRow>

        <SetRow label="origins" desc="허용할 웹 origin 과 Android 앱 키 해시. Enter 로 추가합니다." changed={changed.has('origins')}>
          <div className="stack-3" style={{ gap: 10 }}>
            <ChipInput mono values={draft.origins} addedValues={addedOrigins} onChange={onOriginsChange} validate={validateOriginInput}
              ariaLabel="origin 추가" placeholder={`https://${draft.rpId} 입력 후 Enter`} />
            <div className="row" style={{ gap: 8, flexWrap: 'nowrap' }}>
              <input className="input mono" aria-label="Android APK 키 해시" placeholder="Android APK signing key SHA-256" value={apkInput} aria-invalid={!!apkError}
                onChange={(e) => { setApkInput(e.target.value); if (apkError) setApkError(null); }}
                onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); addApkKey(); } }} />
              <Button onClick={addApkKey}>앱 키 추가</Button>
            </div>
            {apkError && <p className="field__error" role="alert" style={{ margin: 0 }}>{apkError}</p>}
          </div>
        </SetRow>

        <SetRow label="userVerification" desc="생체·PIN 검증 요구 수준" changed={changed.has('userVerification')}>
          <div className="stack-2">
            <Segmented ariaLabel="userVerification" value={draft.userVerification} options={UV_OPTIONS}
              onChange={(v) => setDraft({ ...draft, userVerification: v })} />
            {uv && <div className="desc-box">{draft.userVerification} — {uv.text}</div>}
          </div>
        </SetRow>

        <SetRow label="attestationConveyance" desc="등록 시 인증기 증명 요청" changed={changed.has('attestationConveyance')}>
          <div className="stack-2">
            <Segmented ariaLabel="attestationConveyance" value={draft.attestationConveyance} options={AT_OPTIONS}
              onChange={(v) => setDraft({ ...draft, attestationConveyance: v })} />
            {at && <div className="desc-box">{draft.attestationConveyance} — {at.text}</div>}
          </div>
        </SetRow>
      </Card>

      <SaveBar
        count={localChanges.length}
        summary={changedFieldNames(localChanges).join(' · ')}
        onRevert={handleRevert}
        onSave={() => void handlePreview()}
        saving={saving}
        saveLabel="저장…"
      />

      <DiffDialog open={showDiff} onClose={() => setShowDiff(false)} changes={diffChanges} onConfirm={() => void handleSave()} saving={saving} />
      {guard}
    </div>
  );
}

// ── DiffDialog (V2 "변경 사항 확인") ──────────────────────────────────────────

function DiffDialog({ open, onClose, changes, onConfirm, saving }: {
  open: boolean;
  onClose: () => void;
  changes: Change[];
  onConfirm: () => void;
  saving?: boolean;
}) {
  return (
    <Dialog open={open} onClose={onClose} title="변경 사항 확인" sub="다음 ceremony 부터 적용됩니다."
      footer={<>
        <Button onClick={onClose}>취소</Button>
        <Button variant="primary" onClick={onConfirm} disabled={saving}>{changes.length}개 변경 사항 저장</Button>
      </>}>
      <div style={{ border: '1px solid var(--line)', borderRadius: 'var(--r)', overflow: 'hidden' }}>
        {changes.map((c, i) => <DiffRow key={c.key} c={c} last={i === changes.length - 1} />)}
        {changes.length === 0 && <p className="muted" style={{ margin: 0, padding: '12px 14px', fontSize: 13 }}>변경 사항이 없습니다.</p>}
      </div>
      <Alert tone="warn">저장 시 <span className="mono">WEBAUTHN_CONFIG_UPDATED</span> 감사 이벤트가 기록됩니다.</Alert>
    </Dialog>
  );
}

const FROM_TAG: CSSProperties = { background: 'var(--bad-soft)', color: 'var(--bad-ink)', textDecoration: 'line-through', maxWidth: '100%', overflow: 'hidden', textOverflow: 'ellipsis' };
const TO_TAG: CSSProperties = { background: 'var(--ok-soft)', color: 'var(--ok-ink)', maxWidth: '100%', overflow: 'hidden', textOverflow: 'ellipsis' };

function DiffRow({ c, last }: { c: Change; last?: boolean }) {
  const rowStyle: CSSProperties = { gap: 10, padding: '12px 14px', borderBottom: last ? 0 : '1px solid var(--line-soft)', fontSize: 12 };
  const keyEl = <span className="mono" style={{ width: 92, flex: 'none', color: 'var(--mute)' }}>{c.key}</span>;
  // origin-array diff: enumerate added / removed values
  if (Array.isArray(c.from) && Array.isArray(c.to)) {
    const from = c.from as string[];
    const to = c.to as string[];
    const added = to.filter((x) => !from.includes(x));
    const removed = from.filter((x) => !to.includes(x));
    return (
      <div className="row" style={rowStyle}>
        {keyEl}
        {removed.map((x) => <span key={'-' + x} className="tag" style={FROM_TAG} title={x}>- {x}</span>)}
        {added.map((x) => <span key={'+' + x} className="tag" style={TO_TAG} title={x}>+ {x}</span>)}
      </div>
    );
  }
  return (
    <div className="row" style={rowStyle}>
      {keyEl}
      <span className="tag" style={FROM_TAG}>{String(c.from)}</span>
      <span aria-hidden="true" style={{ display: 'inline-flex', color: 'var(--mute)' }}><Icons.ChevronRight size={14} /></span>
      <span className="tag" style={TO_TAG}>{String(c.to)}</span>
    </div>
  );
}
