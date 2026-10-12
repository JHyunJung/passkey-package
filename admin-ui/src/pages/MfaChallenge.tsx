// admin-ui/src/pages/MfaChallenge.tsx
import { useRef, useState } from 'react';
import { mfaApi } from '@/api/mfa';
import { formatRecoveryCode } from '@/lib/recoveryCode';
import { describeError } from '@/lib/describeError';
import { isTransientError } from '@/lib/transientError';
import { illustrations } from '@/assets/illustrations';
import { Alert, Button, Field, OtpInput, useToast } from '@/ui';

const TOTP_RE = /^\d{6}$/;
const RECOVERY_RE = /^[A-Z2-9]{4}-[A-Z2-9]{4}$/;

type Mode = 'totp' | 'recovery';

/** 실패 원인별 문구. 서버·네트워크 오류를 "코드가 틀렸다"로 말하지 않는다(H9). */
function VerifyError({ error, mode }: { error: unknown; mode: Mode }) {
  if (isTransientError(error)) {
    const info = describeError(error);
    return <Alert tone="bad" title="확인하지 못했습니다">{info.title}. 잠시 후 다시 시도하세요.</Alert>;
  }
  return mode === 'totp'
    ? <Alert tone="bad" title="코드가 맞지 않습니다.">앱에 지금 표시된 코드를 다시 입력하세요.</Alert>
    : <Alert tone="bad" title="복구 코드가 맞지 않습니다.">저장해 둔 복구 코드 중 아직 쓰지 않은 코드를 입력하세요.</Alert>;
}

export default function MfaChallenge({ onVerified, onLogout }: { onVerified: () => void; onLogout: () => void }) {
  const [mode, setMode] = useState<Mode>('totp');
  const [code, setCode] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [failure, setFailure] = useState<{ error: unknown } | null>(null);
  // 실패하면 칸이 비고(비활성 동안 포커스를 잃음) — 다시 활성이 되면 첫 칸에 포커스한다.
  const [refocus, setRefocus] = useState(0);
  // 자동 제출(onComplete)과 수동 확인이 같은 틱에 겹쳐도 verify 는 한 번만.
  const inFlight = useRef(false);
  const recoveryRef = useRef<HTMLInputElement>(null);
  const toast = useToast();

  const isReady = (v: string) => (mode === 'totp' ? TOTP_RE.test(v) : RECOVERY_RE.test(v));
  const ready = isReady(code);

  async function verify(value: string) {
    if (inFlight.current || !isReady(value)) return;
    inFlight.current = true;
    setSubmitting(true);
    setFailure(null);
    try {
      const res = await mfaApi.verify(value.trim());
      if (res.usedRecoveryCode && typeof res.remaining === 'number') {
        toast({ kind: 'warn', title: '복구 코드를 사용했습니다.', message: `남은 복구 코드: ${res.remaining}개` });
      }
      onVerified();
    } catch (e) {
      // 오류는 카드 안 한 곳(role="alert")에만. 칸을 비우고 첫 칸으로 포커스를 돌려 바로 다시 입력하게 한다.
      setFailure({ error: e });
      setCode('');
      if (mode === 'totp') setRefocus((n) => n + 1);
      else recoveryRef.current?.focus();
    } finally {
      inFlight.current = false;
      setSubmitting(false);
    }
  }

  function submit(e: React.FormEvent) {
    e.preventDefault();
    void verify(code);
  }

  function switchMode(next: Mode) {
    setMode(next);
    setCode('');
    setFailure(null);
  }

  const wrongTotp = mode === 'totp' && failure !== null && !isTransientError(failure.error);

  return (
    <main className="auth">
      <div className="auth__col">
        <img className="auth__logo" src="/admin/crosscert-logo-transparent.png" alt="CROSSCERT" />
        <form className="auth-card" onSubmit={submit}>
          {mode === 'totp' ? (
            <>
              <div className="col" style={{ gap: 6, alignItems: 'center', textAlign: 'center' }}>
                <img src={illustrations.mfa} alt="" style={{ width: 88, height: 88, objectFit: 'contain' }} />
                <h1 className="page__title">2단계 인증</h1>
                <p className="card__sub">authenticator 앱에 표시된 6자리 코드를 입력하세요.</p>
              </div>
              {failure && <VerifyError error={failure.error} mode="totp" />}
              <div>
                <OtpInput value={code} onChange={setCode} onComplete={(v) => void verify(v)} disabled={submitting} autoFocus focusKey={refocus} />
                <p className="hint" style={{ textAlign: 'center' }}>붙여넣기 지원 · 6자리를 모두 입력하면 자동 확인</p>
              </div>
              <Button type="submit" variant="primary" size="lg" disabled={submitting || !ready}>
                {submitting ? '확인 중…' : '확인'}
              </Button>
              <div className="col" style={{ gap: 6, alignItems: 'center', paddingTop: 4, borderTop: '1px solid var(--line-soft)' }}>
                <Button variant="ghost" size="sm" onClick={() => switchMode('recovery')}>복구 코드로 로그인</Button>
                <p className="hint" style={{ margin: 0, textAlign: 'center' }}>기기를 잃어버렸나요? 운영자에게 MFA 초기화를 요청하세요.</p>
                {wrongTotp && (
                  <p className="hint" style={{ margin: 0, textAlign: 'center' }}>시계가 맞지 않으면 코드가 계속 틀릴 수 있습니다. 휴대폰 시간 자동 설정을 확인하세요.</p>
                )}
              </div>
            </>
          ) : (
            <>
              <div className="col" style={{ gap: 6 }}>
                <h1 className="page__title">복구 코드로 로그인</h1>
                <p className="card__sub">MFA 등록 때 저장한 복구 코드 하나를 입력하세요. 사용한 코드는 다시 쓸 수 없습니다.</p>
              </div>
              {failure && <VerifyError error={failure.error} mode="recovery" />}
              <Field label="복구 코드" htmlFor="mfa-recovery">
                <input
                  id="mfa-recovery"
                  ref={recoveryRef}
                  className="input input--lg mono"
                  // eslint-disable-next-line jsx-a11y/no-autofocus
                  autoFocus
                  readOnly={submitting}
                  value={code}
                  onChange={(e) => setCode(formatRecoveryCode(e.target.value))}
                  placeholder="AB3F-2K7M"
                  style={{ minHeight: 48, fontSize: 16, letterSpacing: '0.08em', textAlign: 'center' }}
                />
              </Field>
              <Button type="submit" variant="primary" size="lg" disabled={submitting || !ready}>
                {submitting ? '확인 중…' : '확인'}
              </Button>
              <Button variant="ghost" size="sm" onClick={() => switchMode('totp')}>← authenticator 코드로 돌아가기</Button>
            </>
          )}
        </form>
        {/* 출구(H3): MFA 대기 세션을 끝내고 로그인 화면으로 */}
        <Button variant="ghost" onClick={onLogout} style={{ color: 'var(--ink-2)' }}>← 로그인으로</Button>
      </div>
    </main>
  );
}