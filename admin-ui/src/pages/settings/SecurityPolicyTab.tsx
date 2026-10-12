import { useState, useEffect, useRef } from 'react';
import { securityPolicyApi, type SecurityPolicyView } from '@/api/securityPolicy';
import { useLoad } from '@/lib/useLoad';
import { describeError } from '@/lib/describeError';
import { normalizeOrigin, timeoutError, validateOrigin } from '@/lib/policyValidation';
import {
  Alert, Button, CardHeader, ChipInput, ConfirmDialog, LoadError, SaveBar, Skeleton, Switch, useToast, useUnsavedGuard,
} from '@/ui';

function sameList(a: string[], b: string[]): boolean {
  return a.length === b.length && a.every((x, i) => x === b[i]);
}

export default function SecurityPolicyTab() {
  const policy = useLoad(() => securityPolicyApi.get(), []);
  const [sessionDraft, setSessionDraft] = useState('');
  const [reqMfa, setReqMfa] = useState(false);
  const [corsAllowlist, setCorsAllowlist] = useState<string[]>([]);
  const [saved, setSaved] = useState<SecurityPolicyView | null>(null);
  const [saving, setSaving] = useState(false);
  const [confirmLockout, setConfirmLockout] = useState(false);
  const timeoutRef = useRef<HTMLInputElement>(null);
  const toast = useToast();
  const consoleOrigin = window.location.origin;

  function applyView(v: SecurityPolicyView) {
    setSessionDraft(String(v.sessionIdleTimeoutMinutes));
    setReqMfa(v.mfaRequired);
    setCorsAllowlist(v.corsAllowlist);
    setSaved(v);
  }

  useEffect(() => {
    if (policy.data) applyView(policy.data);
  }, [policy.data]);

  const timeoutErr = timeoutError(sessionDraft);
  // 지금 쓰고 있는 콘솔 주소를 허용 목록에서 빼면 저장 즉시 이 콘솔이 잠긴다.
  const removesConsole = saved !== null && saved.corsAllowlist.includes(consoleOrigin) && !corsAllowlist.includes(consoleOrigin);

  /** 저장 본체. 실패는 throw — SaveBar 경로는 토스트로, 확인 다이얼로그 경로는 다이얼로그 안에서 보인다. */
  async function save(): Promise<void> {
    setSaving(true);
    try {
      const v = await securityPolicyApi.update({
        sessionIdleTimeoutMinutes: Number(sessionDraft.trim()),
        mfaRequired: reqMfa,
        corsAllowlist,
      });
      applyView(v);
      toast({ kind: 'ok', title: '보안 정책 저장됨' });
    } finally {
      setSaving(false);
    }
  }

  async function handleSave() {
    if (saving) return;
    if (timeoutErr) { timeoutRef.current?.focus(); return; }
    if (removesConsole) { setConfirmLockout(true); return; }
    try {
      await save();
    } catch (e: unknown) {
      const info = describeError(e);
      toast({ kind: 'err', title: '보안 정책을 저장하지 못했습니다', message: `${info.title}. 입력한 내용은 그대로 있습니다.` });
    }
  }

  async function handleCancel() {
    try {
      const v = await securityPolicyApi.get();
      applyView(v);
    } catch (e: unknown) {
      const info = describeError(e);
      toast({ kind: 'err', title: '되돌리지 못했습니다', message: info.title });
    }
  }

  /** 삭제한 콘솔 origin 을 원래 자리(서버 순서)로 되돌린다 — 되돌린 뒤 변경 0건이 되게. */
  function restoreConsoleOrigin() {
    if (!saved) return;
    const kept = saved.corsAllowlist.filter((o) => o === consoleOrigin || corsAllowlist.includes(o));
    const added = corsAllowlist.filter((o) => !saved.corsAllowlist.includes(o));
    setCorsAllowlist([...kept, ...added]);
  }

  const changed: string[] = [];
  if (saved) {
    if (sessionDraft.trim() !== String(saved.sessionIdleTimeoutMinutes)) changed.push('세션 idle timeout');
    if (reqMfa !== saved.mfaRequired) changed.push('MFA 필수');
    if (!sameList(corsAllowlist, saved.corsAllowlist)) changed.push('CORS Allowlist');
  }
  const guard = useUnsavedGuard(changed.length > 0);
  const addedOrigins = saved ? corsAllowlist.filter((o) => !saved.corsAllowlist.includes(o)) : [];

  // 로드 실패를 기본값(30분 · MFA 켜짐)처럼 보이지 않게 — 입력칸·저장을 숨기고 LoadError.
  if (!saved && policy.status === 'failed') {
    return (
      <section className="card">
        <LoadError what="보안 정책" error={policy.error} onRetry={policy.reload} retrying={policy.reloading} />
      </section>
    );
  }
  if (!saved) {
    return <section className="card"><Skeleton rows={6} label="보안 정책 불러오는 중" /></section>;
  }

  return (
    <div className="stack-4">
      <section className="card">
        <CardHeader title="세션 & 인증 정책" cap="모든 콘솔 운영자에게 적용" />

        <div className="set">
          <div>
            <label className="set__label" htmlFor="sec-idle-timeout" style={{ display: 'block' }}>세션 idle timeout (분)</label>
            <p className="set__desc">이 시간 동안 활동이 없으면 세션이 만료됩니다. 만료 직전 연장 모달이 뜹니다.</p>
          </div>
          <div className="stack-1">
            <div className="row" style={{ gap: 10 }}>
              <input
                ref={timeoutRef}
                id="sec-idle-timeout"
                className="input mono"
                type="number"
                inputMode="numeric"
                min={1}
                max={1440}
                step={1}
                value={sessionDraft}
                aria-invalid={timeoutErr ? true : undefined}
                aria-describedby={timeoutErr ? 'sec-idle-timeout-err' : 'sec-idle-timeout-help'}
                disabled={saving}
                onChange={(e) => setSessionDraft(e.target.value)}
                style={{ maxWidth: 120 }}
              />
              <span style={{ fontSize: 13, color: 'var(--ink-2)' }}>분</span>
            </div>
            {timeoutErr
              ? <p id="sec-idle-timeout-err" className="field__error" role="alert" style={{ margin: 0 }}>{timeoutErr}</p>
              : <p id="sec-idle-timeout-help" className="hint" style={{ margin: 0 }}>1–1440분 (최대 24시간)</p>}
          </div>
        </div>

        <div className="set">
          <div>
            <span className="set__label" style={{ display: 'block' }}>MFA 필수</span>
            <p className="set__desc">켜면 MFA 를 등록하지 않은 운영자는 다음 로그인 때 등록해야 합니다.</p>
          </div>
          <div style={{ alignSelf: 'flex-start' }}>
            <Switch checked={reqMfa} onChange={setReqMfa} label="모든 운영자에게 MFA 필수" disabled={saving} />
          </div>
        </div>

        <div className="set">
          <div>
            <span className="set__label" style={{ display: 'block' }}>CORS Allowlist</span>
            <p className="set__desc">콘솔 API 를 호출할 수 있는 origin. Enter 로 추가합니다.</p>
          </div>
          <div className="stack-2">
            <ChipInput
              values={corsAllowlist}
              onChange={setCorsAllowlist}
              placeholder="https://… 추가 후 Enter"
              ariaLabel="origin 추가"
              mono
              disabled={saving}
              normalize={normalizeOrigin}
              addedValues={addedOrigins}
              validate={(v) => validateOrigin(v, corsAllowlist)}
            />
            <p className="hint" style={{ margin: 0 }}>https://호스트[:포트] 형식. 경로 · 와일드카드(*) 불가.</p>
            {removesConsole && (
              <Alert tone="warn" actions={<Button size="sm" onClick={restoreConsoleOrigin} disabled={saving}>삭제 되돌리기</Button>}>
                <span className="mono">{consoleOrigin}</span> 은 지금 쓰고 있는 콘솔 주소입니다. 저장하면 이 콘솔에서 API 를 호출할 수 없어 바로 잠깁니다.
              </Alert>
            )}
          </div>
        </div>
      </section>

      <SaveBar
        count={changed.length}
        summary={changed.join(' · ')}
        onRevert={() => void handleCancel()}
        onSave={() => void handleSave()}
        saving={saving}
        revertLabel="취소"
      />

      <ConfirmDialog
        open={confirmLockout}
        onClose={() => setConfirmLockout(false)}
        level="L2"
        title="현재 콘솔 주소를 빼고 저장할까요?"
        verb="저장"
        onConfirm={save}
      >
        <p className="card__sub" style={{ margin: 0 }}>
          <span className="mono">{consoleOrigin}</span> 이 CORS 허용 목록에서 빠집니다. 저장하는 즉시 이 콘솔에서 API 를 호출할 수 없어 화면이 잠깁니다.
        </p>
        <p className="hint" style={{ margin: 0 }}>다른 주소의 콘솔에서 이 주소를 다시 추가해야 풀립니다.</p>
      </ConfirmDialog>
      {guard}
    </div>
  );
}
