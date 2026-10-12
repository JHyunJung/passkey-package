// admin-ui/src/pages/tenant/ApiKeysTab.tsx
import { useEffect, useRef, useState } from 'react';
import { Icons } from '@/icons/Icons';
import {
  Alert, Badge, Button, Card, CardHeader, DataTable, Dialog, EmptyState, FilterChip, KpiCard, LoadError, Menu, SecretBox, Skeleton, StatusBadge, useToast,
  useUnsavedGuard,
  type Column,
} from '@/ui';
import { illustrations } from '@/assets/illustrations';
import { apiKeysApi } from '@/api/apiKeys';
import { copyToClipboard } from '@/lib/clipboard';
import { describeError } from '@/lib/describeError';
import { useLoad } from '@/lib/useLoad';
import { HELP_LINKS } from '@/config/links';
import type { Tenant, ApiKey } from '@/api/designTypes';
import { KvList } from './KvList';
import { timeAgo, ymd, ymdHm } from './tabFormat';
import {
  ROTATION_DAYS, expiryBadge, keyStatusCounts, latestCreatedAt, previewExpiry, rotationOverdueCount,
} from './apiKeyHelpers';

const NAME_MAX = 64;

const SCOPE_OPTIONS: { value: string; desc: string }[] = [
  { value: 'registration', desc: '패스키 등록 · 목록 · 이름 변경 · 삭제' },
  { value: 'authentication', desc: '패스키 인증' },
];

// 표에서는 한국어 라벨을 보이고 원문(API 가 받는 scope 값)은 title 툴팁으로 남긴다.
const SCOPE_LABEL: Record<string, string> = { registration: '패스키 등록', authentication: '패스키 인증' };

// 만료 프리셋(개월). null = 무기한. 기본 24개월.
const EXPIRY_OPTIONS: { months: number | null; label: string }[] = [
  { months: 6, label: '6개월' },
  { months: 12, label: '12개월' },
  { months: 24, label: '24개월' },
  { months: 36, label: '36개월' },
  { months: null, label: '무기한' },
];

/** expiryUnknown: 회전 응답에는 새 키의 만료가 없다 — 목록을 다시 읽어 찾을 때까지 만료 줄을 숨긴다. */
type Issued = { key: ApiKey; plaintext: string; oldKeyExpiresAt?: string; expiryUnknown?: boolean };

// ── ApiKeysTab ────────────────────────────────────────────────────────────────

/** onChanged: 발급·회전·회수 성공 뒤 호출 — tenant 셸(키 수 KPI·탭 "확인 필요 N" 배지)을 갱신한다. */
export default function ApiKeysTab({ tenant, onChanged }: { tenant: Tenant; onChanged?: () => void }) {
  const load = useLoad(() => apiKeysApi.list(tenant.id), [tenant.id]);
  const keys = load.data ?? [];
  const failed = load.status === 'failed';
  const [showNew, setShowNew] = useState(false);
  const [issued, setIssued] = useState<Issued | null>(null);
  const [revoking, setRevoking] = useState<ApiKey | null>(null);
  const [rotating, setRotating] = useState<ApiKey | null>(null);
  // 발급·회전·회수 진행 중 표시. ref 는 같은 틱의 연타(두 번째 클릭)까지 막는다.
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const toast = useToast();
  // 평문 키는 이 다이얼로그에서 한 번만 보인다 — 닫기 전 이동·새로고침을 막는다.
  const leaveGuard = useUnsavedGuard(issued !== null, {
    title: '키를 아직 저장하지 않았습니다',
    body: '이동하면 발급된 키를 다시 볼 수 없습니다. 키는 서버에서 계속 유효합니다.',
  });

  // 회전 응답에는 새 키의 만료가 없다 — 목록을 다시 읽어 새 키가 보이면 만료를 채운다.
  useEffect(() => {
    if (!issued?.expiryUnknown || !load.data) return;
    const fresh = load.data.find((x) => x.id === issued.key.id);
    if (fresh) setIssued((prev) => (prev && prev.key.id === fresh.id ? { ...prev, key: fresh, expiryUnknown: false } : prev));
  }, [load.data, issued]);

  async function guarded(run: () => Promise<void>) {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    try {
      await run();
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  }

  /** NewKeyDialog 의 onIssue — 실패하면 throw 해서 다이얼로그 안에 경고를 띄우고 입력을 유지한다. */
  async function handleIssue(name: string, scopes: string[], expiresInMonths: number | null) {
    const result = await apiKeysApi.create(tenant.id, name, scopes, expiresInMonths);
    setShowNew(false);
    setIssued(result);
    onChanged?.();
    load.reload();
  }

  function handleRevoke(k: ApiKey) {
    return guarded(async () => {
      try {
        await apiKeysApi.revoke(k.id);
        toast({ kind: 'warn', title: 'API key가 회수되었습니다.', message: `${k.prefix} · ${k.name}` });
        setRevoking(null);
        onChanged?.();
        load.reload();
      } catch (e: unknown) {
        toast({ kind: 'err', title: '회수 실패', message: e instanceof Error ? e.message : String(e) });
      }
    });
  }

  function handleRotate(k: ApiKey) {
    return guarded(async () => {
      try {
        const res = await apiKeysApi.rotate(k.id);
        setRotating(null);
        // 회전 응답의 id 는 새 키다. 만료는 응답에 없으므로 목록에서 새 키를 찾을 때까지 숨긴다(이전 키 만료를 보이지 않음).
        setIssued({
          key: { ...k, id: res.id, prefix: res.prefix, scopes: res.scopes, createdAt: new Date().toISOString(), lastUsedAt: null },
          plaintext: res.plaintextKey, oldKeyExpiresAt: res.oldKeyExpiresAt, expiryUnknown: true,
        });
        onChanged?.();
        load.reload(); // 새 키 만료는 위 useEffect 가 목록에서 찾아 채운다
      } catch (e: unknown) {
        toast({ kind: 'err', title: '회전 실패', message: e instanceof Error ? e.message : String(e) });
      }
    });
  }

  async function copyPrefix(k: ApiKey) {
    if (await copyToClipboard(k.prefix)) toast({ kind: 'ok', title: '접두사를 복사했습니다.', message: k.prefix });
    else toast({ kind: 'warn', title: '복사 실패', message: '클립보드 복사에 실패했습니다.' });
  }

  if (load.status === 'loading' && !issued) {
    return <Skeleton rows={5} label="API 키 불러오는 중" />;
  }

  const counts = keyStatusCounts(keys);
  const latest = latestCreatedAt(keys);
  const overdue = rotationOverdueCount(keys);

  const columns: Column<ApiKey>[] = [
    {
      key: 'name', header: '이름 · 키 접두사',
      render: (k) => (
        <div className="stack-1" style={{ gap: 0 }}>
          <strong style={{ fontSize: 13 }}>{k.name}</strong>
          <span className="mono" style={{ fontSize: 12, color: 'var(--mute)' }}>{k.prefix}…{k.keySuffix ?? ''}</span>
        </div>
      ),
    },
    { key: 'scopes', header: '권한 범위', render: (k) => <div className="row" style={{ gap: 4 }}>{k.scopes.map((s) => <span key={s} className="tag" title={s}>{SCOPE_LABEL[s] ?? s}</span>)}</div> },
    { key: 'status', header: '상태', render: (k) => <StatusBadge status={k.status} /> },
    { key: 'used', header: '마지막 사용', render: (k) => <span style={{ fontSize: 13, color: 'var(--ink-2)' }}>{k.lastUsedAt ? timeAgo(k.lastUsedAt) : '미사용'}</span> },
    { key: 'created', header: '생성일', render: (k) => <span style={{ fontSize: 13, color: 'var(--ink-2)' }}>{ymd(k.createdAt)}</span> },
    { key: 'expires', header: '만료', render: (k) => <ExpiryCell k={k} /> },
    {
      key: 'actions', header: '액션', srOnlyHeader: true, width: 48,
      render: (k) => k.status === 'ACTIVE' ? (
        <Menu
          label={`${k.name} 키 액션`}
          items={[
            { label: '회전', icon: <Icons.Refresh size={15} />, onSelect: () => setRotating(k) },
            { label: '접두사 복사', icon: <Icons.Copy size={15} />, onSelect: () => { void copyPrefix(k); } },
          ]}
          dangerItems={[{ label: '회수', icon: <Icons.Trash size={15} />, danger: true, onSelect: () => setRevoking(k) }]}
        />
      ) : null,
    },
  ];

  const guide = HELP_LINKS.rpGuide
    ? <a className="btn btn--ghost" href={HELP_LINKS.rpGuide} target="_blank" rel="noopener noreferrer">RP 연동 가이드 <Icons.ExternalLink size={13} /></a>
    : null;

  return (
    <div className="stack-6">
      <div className="kpis kpis--3">
        <KpiCard label="총 API Key" value={failed ? '—' : keys.length} hint={failed ? undefined : `활성 ${counts.active} · 만료 ${counts.expired} · 회수 ${counts.revoked}`} />
        <KpiCard label="최근 발급" value={!failed && latest ? timeAgo(latest) : '—'} />
        <KpiCard label="권장 rotation" value={`${ROTATION_DAYS}일`} hint={failed ? undefined : `초과 키 ${overdue}개`} hintTone={overdue > 0 ? 'warn' : 'default'} />
      </div>

      <Card>
        <CardHeader
          title="API 키"
          sub="키 원문은 발급 직후 한 번만 보입니다. 서버는 해시만 저장합니다."
          actions={<div className="row" style={{ gap: 8 }}>
            {guide}
            <Button variant="primary" icon={<Icons.Plus size={14} />} onClick={() => setShowNew(true)}>새 키 발급</Button>
          </div>}
        />
        {failed ? (
          <LoadError what="API 키 목록" error={load.error} onRetry={load.reload} retrying={load.reloading} />
        ) : (
          <DataTable
            columns={columns}
            rows={keys}
            rowKey={(k) => k.id}
            empty={
              <EmptyState
                illustration={illustrations.apiKeyIssued}
                title="아직 발급한 API 키가 없습니다"
                description="RP 서버가 패스키 등록·인증 API를 호출하려면 키가 필요합니다."
                action={<div className="row" style={{ gap: 8, justifyContent: 'center' }}>
                  <Button variant="primary" icon={<Icons.Plus size={14} />} onClick={() => setShowNew(true)}>첫 API 키 발급</Button>
                  {guide}
                </div>}
              />
            }
          />
        )}
      </Card>

      <NewKeyDialog open={showNew} onClose={() => setShowNew(false)} onIssue={handleIssue} />
      <IssuedKeyDialog issued={issued} onClose={() => setIssued(null)} />
      <RotateKeyDialog k={rotating} busy={busy} onClose={() => setRotating(null)} onConfirm={(k) => void handleRotate(k)} />
      <RevokeKeyDialog k={revoking} busy={busy} onClose={() => setRevoking(null)} onConfirm={(k) => void handleRevoke(k)} />
      {leaveGuard}
    </div>
  );
}

// ── 만료 셀: 상태를 글로 된 배지로 + 날짜 ────────────────────────────────────

function ExpiryCell({ k }: { k: ApiKey }) {
  const b = expiryBadge(k);
  return (
    <div className="stack-1" style={{ gap: 2 }}>
      <Badge tone={b.tone} dot={false}>{b.label}</Badge>
      <span className="hint" style={{ margin: 0 }}>{b.sub}</span>
    </div>
  );
}

// ── 새 키 발급 — 실패해도 입력 유지, 성공했을 때만 비운다 ──────────────────────

function NewKeyDialog({ open, onClose, onIssue }: {
  open: boolean;
  onClose: () => void;
  onIssue: (name: string, scopes: string[], expiresInMonths: number | null) => Promise<void>;
}) {
  const [name, setName] = useState('');
  const [scopes, setScopes] = useState<string[]>(['registration', 'authentication']);
  const [expiresInMonths, setExpiresInMonths] = useState<number | null>(24);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const [error, setError] = useState<unknown>(null);

  useEffect(() => { if (open) setError(null); }, [open]);

  function toggle(v: string) {
    setScopes((prev) => (prev.includes(v) ? prev.filter((s) => s !== v) : [...prev, v]));
  }
  async function submit() {
    if (busyRef.current || !name || scopes.length === 0) return;
    busyRef.current = true;
    setBusy(true);
    setError(null);
    try {
      await onIssue(name, scopes, expiresInMonths);
      setName('');
      setScopes(['registration', 'authentication']);
      setExpiresInMonths(24);
    } catch (e: unknown) {
      setError(e);
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  }
  const preview = previewExpiry(expiresInMonths);
  const info = error ? describeError(error) : null;

  return (
    <Dialog open={open} onClose={busy ? () => {} : onClose} title="새 API 키 발급" sub="RP 서버가 Passkey API 를 호출할 때 쓰는 키입니다."
      footer={<>
        <Button onClick={onClose} disabled={busy}>취소</Button>
        <Button variant="primary" disabled={busy || !name || scopes.length === 0} onClick={() => void submit()}>{busy ? '발급 중…' : '발급'}</Button>
      </>}>
      {info && (
        <Alert tone="bad" title="API 키를 발급하지 못했습니다.">
          {info.title}. {info.detail} 입력한 내용은 그대로 있습니다.
        </Alert>
      )}
      <div>
        <div className="row" style={{ justifyContent: 'space-between' }}>
          <label className="label" htmlFor="apikey-name">용도 (이름)</label>
          <span className="hint" style={{ margin: 0 }}>{name.length}/{NAME_MAX}</span>
        </div>
        <input id="apikey-name" className="input" maxLength={NAME_MAX} value={name} disabled={busy} onChange={(e) => setName(e.target.value)} placeholder="예: prod RP 서버" />
      </div>
      <fieldset style={{ border: 0, padding: 0, margin: 0 }} disabled={busy}>
        <legend className="label">권한 범위 (scope)</legend>
        <div className="stack-2">
          {SCOPE_OPTIONS.map((o) => {
            const on = scopes.includes(o.value);
            return (
              <label key={o.value} style={{ display: 'flex', gap: 10, alignItems: 'flex-start', padding: '10px 12px', border: `1px solid ${on ? 'var(--brand-300)' : 'var(--line)'}`, borderRadius: 'var(--r)', background: on ? 'var(--brand-50)' : 'var(--surface)', cursor: 'pointer' }}>
                <input type="checkbox" checked={on} onChange={() => toggle(o.value)} style={{ marginTop: 3 }} />
                <span className="stack-1" style={{ gap: 0 }}>
                  <span className="mono" style={{ fontSize: 13, fontWeight: 600 }}>{o.value}</span>
                  <span className="hint" style={{ margin: 0 }}>{o.desc}</span>
                </span>
              </label>
            );
          })}
        </div>
        {scopes.length === 0 && <p className="field__error">하나 이상 선택하세요.</p>}
      </fieldset>
      <div>
        <span className="label" id="apikey-expiry">만료 기간</span>
        <div className="row" style={{ gap: 6 }} role="group" aria-labelledby="apikey-expiry">
          {EXPIRY_OPTIONS.map((o) => (
            <FilterChip key={o.label} active={expiresInMonths === o.months} disabled={busy} onClick={() => setExpiresInMonths(o.months)}>{o.label}</FilterChip>
          ))}
        </div>
        <p className="hint">{preview ? `만료일 ${preview} (KST)` : '만료 없음 — 키가 무기한으로 유효합니다.'} · 권장 rotation {ROTATION_DAYS}일</p>
      </div>
    </Dialog>
  );
}

// ── 발급 완료: ESC·바깥 클릭으로 닫히지 않음, 확인 체크 후에만 닫기 ───────────

function IssuedKeyDialog({ issued, onClose }: { issued: Issued | null; onClose: () => void }) {
  const [checked, setChecked] = useState(false);
  useEffect(() => { if (issued) setChecked(false); }, [issued]);
  if (!issued) return null;
  const oldHours = issued.oldKeyExpiresAt
    ? Math.max(0, Math.round((new Date(issued.oldKeyExpiresAt).getTime() - Date.now()) / 3_600_000))
    : null;

  return (
    <Dialog open onClose={onClose} dismissible={false} role="alertdialog"
      title={
        <span className="row" style={{ gap: 10, flexWrap: 'nowrap' }}>
          <img src={illustrations.apiKeyIssued} alt="" width={72} height={72} style={{ objectFit: 'contain', flex: 'none' }} />
          <span>새 API key가 발급되었습니다 — 지금만 표시됩니다</span>
        </span>
      }
      footerStart={<span className="hint" style={{ margin: 0 }}>server는 plaintext의 해시만 저장합니다.</span>}
      footer={
        <Button variant="primary" disabled={!checked} onClick={onClose}>
          {checked ? '닫기 (영구 소실)' : '닫기 (영구 소실) · 체크 필요'}
        </Button>
      }>
      {oldHours !== null && (
        <Alert tone="warn">
          이전 키는 {oldHours}시간 후({ymdHm(issued.oldKeyExpiresAt)}) 만료됩니다. 그 전에 RP 서버를 새 키로 교체하세요.
        </Alert>
      )}
      <KvList boxed keyWidth={80} items={[
        { k: '이름', v: <>{issued.key.name} <span style={{ marginLeft: 6 }}><Badge tone="ok">발급 완료</Badge></span></> },
        { k: 'prefix', v: issued.key.prefix, mono: true },
        ...(issued.expiryUnknown ? [] : [{ k: '만료', v: issued.key.expiresAt ? ymd(issued.key.expiresAt) : '무기한' }]),
        { k: 'id', v: issued.key.id, mono: true },
      ]} />
      <SecretBox value={issued.plaintext} />
      <label style={{ display: 'flex', gap: 10, alignItems: 'center', padding: '10px 12px', border: '1px solid var(--brand-700)', borderRadius: 'var(--r)', background: 'var(--brand-50)', cursor: 'pointer' }}>
        <input type="checkbox" checked={checked} onChange={(e) => setChecked(e.target.checked)} />
        <span style={{ fontSize: 13, fontWeight: 600 }}>안전한 장소에 복사했습니다.</span>
      </label>
    </Dialog>
  );
}

// ── 회수 (위험) — 취소 기본 포커스 ───────────────────────────────────────────

function RevokeKeyDialog({ k, busy = false, onClose, onConfirm }: { k: ApiKey | null; busy?: boolean; onClose: () => void; onConfirm: (k: ApiKey) => void }) {
  const cancelRef = useRef<HTMLButtonElement>(null);
  if (!k) return null;
  return (
    <Dialog open onClose={onClose} role="alertdialog" title="API key를 회수하시겠습니까?" initialFocusRef={cancelRef}
      footer={<>
        <button ref={cancelRef} type="button" className="btn" onClick={onClose}>취소</button>
        <Button variant="danger-solid" disabled={busy} onClick={() => onConfirm(k)}>회수</Button>
      </>}>
      <KvList keyWidth={80} items={[
        { k: 'prefix', v: k.prefix, mono: true },
        { k: '이름', v: k.name },
        { k: '생성', v: ymd(k.createdAt) },
      ]} />
      <Alert tone="bad">회수는 되돌릴 수 없습니다. 이 키를 쓰는 RP 서버의 요청이 즉시 401 로 거부됩니다.</Alert>
    </Dialog>
  );
}

// ── 회전 — 기존 키 24시간 겹침 타임라인 ───────────────────────────────────────

function RotateKeyDialog({ k, busy = false, onClose, onConfirm }: { k: ApiKey | null; busy?: boolean; onClose: () => void; onConfirm: (k: ApiKey) => void }) {
  if (!k) return null;
  return (
    <Dialog open onClose={onClose} title="API key를 회전하시겠습니까?"
      footer={<>
        <Button onClick={onClose}>취소</Button>
        <Button variant="primary" disabled={busy} onClick={() => onConfirm(k)}>회전 실행</Button>
      </>}>
      <KvList keyWidth={80} items={[
        { k: 'prefix', v: k.prefix, mono: true },
        { k: '이름', v: k.name },
      ]} />
      <div className="row" style={{ gap: 10, flexWrap: 'nowrap', fontSize: 13, padding: '12px 14px', background: 'var(--canvas)', borderRadius: 'var(--r)' }}>
        <span className="tag">기존 키</span>
        <span style={{ color: 'var(--ink-2)' }}>24시간 함께 유효</span>
        <Icons.ChevronRight size={14} />
        <span className="tag" style={{ background: 'var(--brand-100)', color: 'var(--brand-700)' }}>새 키</span>
        <span style={{ color: 'var(--ink-2)' }}>즉시 발급</span>
      </div>
    </Dialog>
  );
}
