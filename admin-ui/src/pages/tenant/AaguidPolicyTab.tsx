// admin-ui/src/pages/tenant/AaguidPolicyTab.tsx
import { useState, useEffect, useRef } from 'react';
import { Icons } from '@/icons/Icons';
import { aaguidPolicyApi } from '@/api/aaguidPolicy';
import type { Tenant, AaguidPolicy } from '@/api/designTypes';
import { AAGUID_MODE_LABELS } from '@/i18n/labels';
import { useLoad } from '@/lib/useLoad';
import {
  Alert, Badge, Button, Card, CardHeader, ChoiceCard, ConfirmDialog, IconButton, LoadError, SaveBar, Skeleton, Switch, useToast, useUnsavedGuard,
} from '@/ui';
import { SetRow } from './SetRow';
import { unverifiedAaguidCount } from './tenantSignals';

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

export type PolicyChange =
  | { kind: 'mode'; from: AaguidPolicy['mode']; to: AaguidPolicy['mode'] }
  | { kind: 'added' | 'removed'; aaguid: string }
  | { kind: 'note'; aaguid: string; from: string | null; to: string | null }
  | { kind: 'mds'; from: boolean; to: boolean };

/**
 * 저장된 정책과 편집 중인 정책의 실제 차이 — 변경 감지·SaveBar·확인 창의 개수와 목록이 모두 이 하나를 쓴다.
 * 백엔드는 entries 를 집합(AAGUID)으로 쓰므로 순서와 서버가 채우는 mdsName 은 변경이 아니다.
 */
export function policyChanges(saved: AaguidPolicy, draft: AaguidPolicy): PolicyChange[] {
  const out: PolicyChange[] = [];
  if (saved.mode !== draft.mode) out.push({ kind: 'mode', from: saved.mode, to: draft.mode });
  const savedMap = new Map(saved.entries.filter((e) => e.aaguid).map((e) => [e.aaguid, e]));
  const draftMap = new Map(draft.entries.filter((e) => e.aaguid).map((e) => [e.aaguid, e]));
  for (const [aaguid, e] of draftMap) {
    const before = savedMap.get(aaguid);
    if (!before) out.push({ kind: 'added', aaguid });
    else if ((before.note ?? null) !== (e.note ?? null)) out.push({ kind: 'note', aaguid, from: before.note ?? null, to: e.note ?? null });
  }
  for (const aaguid of savedMap.keys()) if (!draftMap.has(aaguid)) out.push({ kind: 'removed', aaguid });
  if (saved.mdsStrict !== draft.mdsStrict) out.push({ kind: 'mds', from: saved.mdsStrict, to: draft.mdsStrict });
  return out;
}

/** SaveBar 요약용 변경 필드 이름. */
export function aaguidChangedFields(saved: AaguidPolicy | null, draft: AaguidPolicy | null): string[] {
  if (!saved || !draft) return [];
  const kinds = new Set(policyChanges(saved, draft).map((c) => c.kind));
  const out: string[] = [];
  if (kinds.has('mode')) out.push('mode');
  if (kinds.has('added') || kinds.has('removed') || kinds.has('note')) out.push('AAGUID 목록');
  if (kinds.has('mds')) out.push('MDS strict');
  return out;
}

export type SaveConfirmKind = 'block-all' | 'mds-strict' | 'diff';

/** 저장 확인 등급(V3 가이드). 빈 허용 목록 = L3 체크, MDS 엄격 켜기 = L2 영향 요약, 그 밖 = L2 변경 비교. */
export function saveConfirmKind(saved: AaguidPolicy, draft: AaguidPolicy): SaveConfirmKind {
  if (draft.mode === 'ALLOWLIST' && draft.entries.filter((e) => e.aaguid).length === 0) return 'block-all';
  if (!saved.mdsStrict && draft.mdsStrict) return 'mds-strict';
  return 'diff';
}

const MODES: { v: AaguidPolicy['mode']; d: string }[] = [
  { v: 'ANY', d: '모든 인증기 허용' },
  { v: 'ALLOWLIST', d: '목록의 모델만 허용' },
  { v: 'DENYLIST', d: '목록의 모델만 차단' },
];

const onOff = (b: boolean) => (b ? '켜짐' : '꺼짐');
const modeLabel = (m: AaguidPolicy['mode']) => <span title={m}>{AAGUID_MODE_LABELS[m]}</span>;

const MDS_STRICT_IMPACT = 'MDS에 등록되지 않은 모든 인증기의 등록이 거부됩니다.';
/** 허용 목록 모드에서는 목록 안의 MDS 미확인 모델도 같이 막힌다. */
const allowlistUnknownNote = (p: AaguidPolicy) => {
  const n = p.mode === 'ALLOWLIST' ? unverifiedAaguidCount(p) : 0;
  return n > 0 ? ` 허용 목록 중 MDS 미확인 ${n}개도 차단됩니다.` : '';
};

/** L2 변경 비교 목록 — policyChanges 의 모든 항목을 그대로 보인다. */
function ChangeList({ changes }: { changes: PolicyChange[] }) {
  return (
    <ul className="stack-1" style={{ listStyle: 'none', margin: 0, padding: '12px 14px', border: '1px solid var(--line)', borderRadius: 'var(--r)', fontSize: 13 }}>
      {changes.map((c) => {
        switch (c.kind) {
          case 'mode': return <li key="mode">방식: {modeLabel(c.from)} → {modeLabel(c.to)}</li>;
          case 'added': return <li key={'+' + c.aaguid} className="mono">+ {c.aaguid}</li>;
          case 'removed': return <li key={'-' + c.aaguid} className="mono">- {c.aaguid}</li>;
          case 'note': return <li key={'n' + c.aaguid} className="mono">{c.aaguid} 메모: {c.from ?? '없음'} → {c.to ?? '없음'}</li>;
          case 'mds': return <li key="mds">MDS 엄격: {onOff(c.from)} → {onOff(c.to)}</li>;
        }
      })}
    </ul>
  );
}

// ── AaguidPolicyTab ───────────────────────────────────────────────────────────

export default function AaguidPolicyTab({ tenant, onSaved }: { tenant: Tenant; onSaved?: (p: AaguidPolicy) => void }) {
  const load = useLoad(() => aaguidPolicyApi.get(tenant.id), [tenant.id]);
  const [policy, setPolicy] = useState<AaguidPolicy | null>(null);
  const [draft, setDraft] = useState<AaguidPolicy | null>(null);
  const [aaguidInput, setAaguidInput] = useState('');
  // 확인 창을 연 시점의 종류를 고정한다 — 저장 성공으로 policy 가 바뀌어도 다른 창으로 바뀌지 않게.
  const [confirm, setConfirm] = useState<SaveConfirmKind | null>(null);
  const [saving, setSaving] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  const toast = useToast();

  const uuidRe = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
  const inputValid = uuidRe.test(aaguidInput);
  const changes = policy && draft ? policyChanges(policy, draft) : [];
  const dirty = changes.length > 0;
  const changedFields = aaguidChangedFields(policy, draft);
  const guard = useUnsavedGuard(dirty);

  useEffect(() => {
    if (!load.data) return;
    setPolicy(load.data);
    setDraft(load.data);
  }, [load.data]);

  const list = draft == null || draft.mode === 'ANY' ? [] : draft.entries.filter((e) => e.aaguid);

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

  /** ConfirmDialog 의 onConfirm — 실패하면 throw 해서 다이얼로그 안에 오류를 보인다(입력 유지). */
  async function save() {
    if (!draft || saving) return;
    setSaving(true);
    try {
      const updated = await aaguidPolicyApi.update(tenant.id, draft);
      setPolicy(updated);
      setDraft(updated);
      onSaved?.(updated);
      toast({ kind: 'ok', title: 'AAGUID 정책 저장 완료' });
    } finally {
      setSaving(false);
    }
  }

  function handleRevert() {
    if (!policy) return;
    setDraft({ ...policy, entries: [...policy.entries] });
  }

  if (load.status === 'failed' && !policy) {
    return <LoadError what="AAGUID 정책" error={load.error} onRetry={load.reload} retrying={load.reloading} />;
  }
  if (!draft || !policy) {
    return <Skeleton rows={6} label="AAGUID 정책 불러오는 중" />;
  }

  // 탭 배지와 같은 기준(저장된 정책). 방금 추가한 항목은 저장 후 서버가 mdsName 을 채운다.
  const unknownCount = unverifiedAaguidCount(policy);
  const closeConfirm = () => setConfirm(null);

  return (
    <div className="stack-6">
      {unknownCount > 0 && (
        <Alert tone="warn" title={`MDS 미확인 AAGUID ${unknownCount}건 — 인증기 모델을 확인하세요`}
          actions={<Button size="sm" onClick={() => document.getElementById('aaguid-list')?.scrollIntoView?.({ block: 'start' })}>항목 보기</Button>}>
          {modeLabel(policy.mode)}의 아래 “MDS 미확인” 항목이 MDS에 없습니다.
        </Alert>
      )}

      <Card>
        <CardHeader title="AAGUID 정책" sub="등록할 수 있는 인증기 모델을 AAGUID로 제한합니다." />
        <fieldset disabled={saving} style={{ border: 0, margin: 0, padding: 0, minWidth: 0 }}>

        <SetRow label="mode" desc="정책 방식 · 저장 전 영향 요약을 보여 줍니다." changed={changedFields.includes('mode')}>
          <div className="choices" role="radiogroup" aria-label="mode">
            {MODES.map((o) => (
              <ChoiceCard key={o.v} checked={draft.mode === o.v} onSelect={() => setDraft({ ...draft, mode: o.v })}
                title={modeLabel(o.v)} description={o.d} />
            ))}
          </div>
        </SetRow>

        {draft.mode !== 'ANY' && (
          <div id="aaguid-list">
            <SetRow label={draft.mode === 'ALLOWLIST' ? '허용된 AAGUID' : '차단된 AAGUID'} changed={changedFields.includes('AAGUID 목록')}
              desc="MDS 에서 모델명을 자동으로 찾아 붙입니다.">
              <div className="stack-3" style={{ gap: 10 }}>
                <div className="row" style={{ gap: 8, flexWrap: 'nowrap' }}>
                  <input ref={inputRef} className="input mono" aria-label="AAGUID 입력" placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" maxLength={36}
                    value={aaguidInput}
                    onChange={(e) => setAaguidInput(formatAaguid(e.target.value))}
                    onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); add(); } }} />
                  <Button icon={<Icons.Plus size={14} />} disabled={!inputValid} onClick={add}>추가</Button>
                </div>
                {list.length === 0 ? (
                  draft.mode === 'ALLOWLIST' ? (
                    <Alert tone="bad" title="허용 목록이 비어 있습니다"
                      actions={<>
                        <Button size="sm" onClick={() => inputRef.current?.focus()}>AAGUID 추가</Button>
                        <Button size="sm" variant="ghost" onClick={() => setDraft({ ...draft, mode: 'ANY' })}>전체 허용으로 되돌리기</Button>
                      </>}>
                      이대로 저장하면 이 tenant의 모든 패스키 등록·인증이 차단됩니다.
                    </Alert>
                  ) : (
                    <p className="hint" style={{ margin: 0, padding: '12px 14px', border: '1px solid var(--line)', borderRadius: 'var(--r)' }}>
                      비어 있음 — 이 상태에서는 모든 패스키 등록·인증이 허용됩니다.
                    </p>
                  )
                ) : (
                  <ul style={{ listStyle: 'none', margin: 0, padding: 0, border: '1px solid var(--line)', borderRadius: 'var(--r)', overflow: 'hidden' }}>
                    {list.map((entry, i) => (
                      <li key={entry.aaguid} className="row" style={{ flexWrap: 'nowrap', gap: 12, padding: '12px 14px', borderBottom: i === list.length - 1 ? 0 : '1px solid var(--line-soft)' }}>
                        <span className="avatar avatar--sq" aria-hidden="true"><Icons.Fingerprint size={16} /></span>
                        <div className="col" style={{ flex: '1 1 0', minWidth: 0 }}>
                          <strong className="truncate" style={{ fontSize: 13 }} title={entry.mdsName ?? undefined}>{entry.mdsName ?? 'MDS 에 없음'}</strong>
                          <span className="mono hint truncate" style={{ margin: 0 }}>{entry.aaguid}</span>
                        </div>
                        {entry.mdsName == null ? <Badge tone="warn">MDS 미확인</Badge> : <Badge tone="ok">MDS 확인</Badge>}
                        <IconButton size="sm" label={`${entry.aaguid} 삭제`} icon={<Icons.X size={14} />}
                          onClick={() => setList(list.filter((x) => x.aaguid !== entry.aaguid))} />
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            </SetRow>
          </div>
        )}

        <SetRow label="MDS 엄격 모드" desc="MDS 에 등록되지 않은 인증기의 등록을 거부합니다." changed={changedFields.includes('MDS strict')}>
          <div className="stack-2">
            <Switch checked={draft.mdsStrict} onChange={(v) => setDraft({ ...draft, mdsStrict: v })}
              label={draft.mdsStrict ? 'MDS strict 켜짐' : 'MDS strict 꺼짐'} />
            <p className="hint" style={{ margin: 0 }}>
              {draft.mdsStrict ? '' : '켜면 '}{MDS_STRICT_IMPACT}{allowlistUnknownNote(draft)}
            </p>
          </div>
        </SetRow>
        </fieldset>
      </Card>

      <SaveBar count={changedFields.length} summary={changedFields.join(' · ')} onRevert={handleRevert} onSave={() => setConfirm(saveConfirmKind(policy, draft))} saving={saving} saveLabel="저장…" />

      <ConfirmDialog open={confirm === 'block-all'} onClose={closeConfirm} level="L3" verb="차단"
        title="모든 등록·인증을 차단할까요?" acknowledge="차단된다는 것을 이해했습니다" onConfirm={save}>
        <p style={{ margin: 0, fontSize: 13, color: 'var(--ink-2)' }}>
          결과: 허용 목록(0개)<br />
          영향: 이 tenant의 모든 사용자가 패스키로 등록·인증할 수 없습니다.
        </p>
        <ChangeList changes={changes} />
      </ConfirmDialog>

      <ConfirmDialog open={confirm === 'mds-strict'} onClose={closeConfirm} level="L2" danger={false} verb="저장"
        title="MDS 엄격 모드를 켜고 저장할까요?" onConfirm={save}>
        <p style={{ margin: 0, fontSize: 13, color: 'var(--ink-2)' }}>
          MDS 엄격: 꺼짐 → 켜짐<br />
          영향: {MDS_STRICT_IMPACT}{allowlistUnknownNote(draft)} 기존 Credential은 유지됩니다.
        </p>
        <ChangeList changes={changes} />
      </ConfirmDialog>

      <ConfirmDialog open={confirm === 'diff'} onClose={closeConfirm} level="L2" danger={false} verb="저장"
        title={`변경 사항 ${changes.length}개를 저장할까요?`} sub="다음 패스키 등록부터 적용됩니다." onConfirm={save}>
        <ChangeList changes={changes} />
      </ConfirmDialog>
      {guard}
    </div>
  );
}
