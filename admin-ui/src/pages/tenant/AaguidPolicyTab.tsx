// admin-ui/src/pages/tenant/AaguidPolicyTab.tsx
import { useState, useEffect } from 'react';
import { Icons } from '@/icons/Icons';
import { aaguidPolicyApi } from '@/api/aaguidPolicy';
import type { Tenant, AaguidPolicy } from '@/api/designTypes';
import { AAGUID_MODE_LABELS } from '@/i18n/labels';
import { Alert, Badge, Button, Card, CardHeader, ChoiceCard, IconButton, SaveBar, Switch, useToast, useUnsavedGuard } from '@/ui';
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

/** SaveBar 요약용 변경 필드 이름. */
export function aaguidChangedFields(saved: AaguidPolicy | null, draft: AaguidPolicy | null): string[] {
  if (!saved || !draft) return [];
  const out: string[] = [];
  if (saved.mode !== draft.mode) out.push('mode');
  if (JSON.stringify(saved.entries) !== JSON.stringify(draft.entries)) out.push('AAGUID 목록');
  if (saved.mdsStrict !== draft.mdsStrict) out.push('MDS strict');
  return out;
}

const MODES: { v: AaguidPolicy['mode']; d: string }[] = [
  { v: 'ANY', d: '모든 authenticator 허용' },
  { v: 'ALLOWLIST', d: '지정된 AAGUID만 허용' },
  { v: 'DENYLIST', d: '지정된 AAGUID만 차단' },
];

// ── AaguidPolicyTab ───────────────────────────────────────────────────────────

export default function AaguidPolicyTab({ tenant, onSaved }: { tenant: Tenant; onSaved?: (p: AaguidPolicy) => void }) {
  const [policy, setPolicy] = useState<AaguidPolicy | null>(null);
  const [draft, setDraft] = useState<AaguidPolicy | null>(null);
  const [aaguidInput, setAaguidInput] = useState('');
  const [saving, setSaving] = useState(false);
  const toast = useToast();

  const uuidRe = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
  const inputValid = uuidRe.test(aaguidInput);
  const dirty = policy !== null && draft !== null && JSON.stringify(policy) !== JSON.stringify(draft);
  const changedFields = aaguidChangedFields(policy, draft);
  const guard = useUnsavedGuard(dirty);

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
      onSaved?.(updated);
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

  if (!draft || !policy) {
    return <div className="muted" style={{ padding: 40 }}>Loading…</div>;
  }

  // 탭 점과 같은 기준(저장된 정책). 방금 추가한 항목은 저장 후 서버가 mdsName 을 채운다.
  const unknownCount = unverifiedAaguidCount(policy);

  return (
    <div className="stack-6">
      {unknownCount > 0 && (
        <Alert tone="warn">
          <strong>탭 점 배지 사유</strong> · {AAGUID_MODE_LABELS[policy.mode]}에 MDS 에서 확인되지 않은 AAGUID 가 {unknownCount}건 있습니다.
        </Alert>
      )}

      <Card>
        <CardHeader title="AAGUID Attestation Policy" sub="등록 시 허용할 authenticator 모델을 AAGUID 로 제한합니다." />

        <SetRow label="mode" desc="정책 방식" changed={changedFields.includes('mode')}>
          <div className="choices" role="radiogroup" aria-label="mode">
            {MODES.map((o) => (
              <ChoiceCard key={o.v} checked={draft.mode === o.v} onSelect={() => setDraft({ ...draft, mode: o.v })}
                title={AAGUID_MODE_LABELS[o.v]} description={o.d} />
            ))}
          </div>
        </SetRow>

        {draft.mode !== 'ANY' && (
          <SetRow label={draft.mode === 'ALLOWLIST' ? '허용된 AAGUID' : '차단된 AAGUID'} changed={changedFields.includes('AAGUID 목록')}
            desc="mode 가 전체 허용이 아닐 때만 표시. MDS 에서 모델명을 자동으로 찾아 붙입니다.">
            <div className="stack-3" style={{ gap: 10 }}>
              <div className="row" style={{ gap: 8, flexWrap: 'nowrap' }}>
                <input className="input mono" aria-label="AAGUID 입력" placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" maxLength={36}
                  value={aaguidInput}
                  onChange={(e) => setAaguidInput(formatAaguid(e.target.value))}
                  onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); add(); } }} />
                <Button icon={<Icons.Plus size={14} />} disabled={!inputValid} onClick={add}>추가</Button>
              </div>
              <div style={{ border: '1px solid var(--line)', borderRadius: 'var(--r)', overflow: 'hidden' }}>
                {list.length === 0 ? (
                  <p className="hint" style={{ margin: 0, padding: '12px 14px' }}>
                    비어 있음 — 이 상태에서는 {draft.mode === 'ALLOWLIST' ? '모든 ceremony가 차단' : '모든 ceremony가 허용'}됩니다.
                  </p>
                ) : (
                  <ul style={{ listStyle: 'none', margin: 0, padding: 0 }}>
                    {list.map((entry, i) => (
                      <li key={entry.aaguid} className="row" style={{ flexWrap: 'nowrap', gap: 12, padding: '12px 14px', borderBottom: i === list.length - 1 ? 0 : '1px solid var(--line-soft)' }}>
                        <span className="avatar avatar--sq" aria-hidden="true"><Icons.Fingerprint size={16} /></span>
                        <div className="col" style={{ flex: '1 1 0', minWidth: 0 }}>
                          <strong className="truncate" style={{ fontSize: 13 }} title={entry.mdsName ?? undefined}>{entry.mdsName ?? 'MDS 에 없음'}</strong>
                          <span className="mono hint truncate" style={{ margin: 0 }}>{entry.aaguid}</span>
                        </div>
                        {entry.mdsName == null ? <Badge tone="warn">미확인</Badge> : <Badge tone="ok">MDS 확인</Badge>}
                        <IconButton size="sm" label={`${entry.aaguid} 삭제`} icon={<Icons.X size={14} />}
                          onClick={() => setList(list.filter((x) => x.aaguid !== entry.aaguid))} />
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            </div>
          </SetRow>
        )}

        <SetRow label="MDS Strict 모드" desc="MDS 에 없거나 REVOKED 상태인 authenticator 의 등록을 거부합니다." changed={changedFields.includes('MDS strict')}>
          <Switch checked={draft.mdsStrict} onChange={(v) => setDraft({ ...draft, mdsStrict: v })}
            label={draft.mdsStrict ? 'MDS strict 켜짐' : 'MDS strict 꺼짐'} />
        </SetRow>
      </Card>

      <SaveBar count={changedFields.length} summary={changedFields.join(' · ')} onRevert={handleRevert} onSave={() => void handleSave()} saving={saving} />
      {guard}
    </div>
  );
}
