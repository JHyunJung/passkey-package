import { useState, useEffect } from 'react';
import { securityPolicyApi, type SecurityPolicyView } from '@/api/securityPolicy';
import { CardHeader, ChipInput, SaveBar, Switch, useToast, useUnsavedGuard } from '@/ui';

function sameList(a: string[], b: string[]): boolean {
  return a.length === b.length && a.every((x, i) => x === b[i]);
}

export default function SecurityPolicyTab() {
  const [sessionMin, setSessionMin] = useState(30);
  const [reqMfa, setReqMfa] = useState(true);
  const [corsAllowlist, setCorsAllowlist] = useState<string[]>([]);
  const [saved, setSaved] = useState<SecurityPolicyView | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const toast = useToast();

  function applyView(v: SecurityPolicyView) {
    setSessionMin(v.sessionIdleTimeoutMinutes);
    setReqMfa(v.mfaRequired);
    setCorsAllowlist(v.corsAllowlist);
    setSaved(v);
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
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

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

  const changed: string[] = [];
  if (saved) {
    if (sessionMin !== saved.sessionIdleTimeoutMinutes) changed.push('세션 idle timeout');
    if (reqMfa !== saved.mfaRequired) changed.push('MFA 필수');
    if (!sameList(corsAllowlist, saved.corsAllowlist)) changed.push('CORS Allowlist');
  }
  const guard = useUnsavedGuard(changed.length > 0);
  const addedOrigins = saved ? corsAllowlist.filter((o) => !saved.corsAllowlist.includes(o)) : [];

  return (
    <div className="stack-4">
      <section className="card">
        <CardHeader title="세션 & 인증 정책" sub="모든 콘솔 운영자에게 적용" />

        <div className="set">
          <div>
            <label className="set__label" htmlFor="sec-idle-timeout" style={{ display: 'block' }}>세션 idle timeout (분)</label>
            <p className="set__desc">이 시간 동안 활동이 없으면 세션이 만료됩니다. 만료 직전 연장 모달이 뜹니다.</p>
          </div>
          <div className="row" style={{ gap: 10 }}>
            <input
              id="sec-idle-timeout"
              className="input mono"
              type="number"
              value={sessionMin}
              disabled={loading}
              onChange={(e) => setSessionMin(parseInt(e.target.value || '0', 10))}
              style={{ maxWidth: 120 }}
            />
            <span style={{ fontSize: 13, color: 'var(--ink-2)' }}>분</span>
          </div>
        </div>

        <div className="set">
          <div>
            <span className="set__label" style={{ display: 'block' }}>MFA 필수</span>
            <p className="set__desc">켜면 MFA 를 등록하지 않은 운영자는 다음 로그인 때 등록해야 합니다.</p>
          </div>
          <div style={{ alignSelf: 'flex-start' }}>
            <Switch checked={reqMfa} onChange={setReqMfa} label="모든 운영자에게 MFA 필수" disabled={loading} />
          </div>
        </div>

        <div className="set">
          <div>
            <span className="set__label" style={{ display: 'block' }}>CORS Allowlist</span>
            <p className="set__desc">콘솔 API 를 호출할 수 있는 origin. Enter 로 추가합니다.</p>
          </div>
          <ChipInput
            values={corsAllowlist}
            onChange={setCorsAllowlist}
            placeholder="https://… 추가 후 Enter"
            ariaLabel="origin 추가"
            mono
            addedValues={addedOrigins}
          />
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
      {guard}
    </div>
  );
}
