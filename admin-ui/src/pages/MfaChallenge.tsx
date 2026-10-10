// admin-ui/src/pages/MfaChallenge.tsx
import { useRef, useState } from 'react';
import { mfaApi } from '@/api/mfa';
import { formatRecoveryCode } from '@/lib/recoveryCode';
import { illustrations } from '@/assets/illustrations';
import { Button, Field, OtpInput, useToast } from '@/ui';

const TOTP_RE = /^\d{6}$/;
const RECOVERY_RE = /^[A-Z2-9]{4}-[A-Z2-9]{4}$/;

export default function MfaChallenge({ onVerified, onLogout }: { onVerified: () => void; onLogout: () => void }) {
  const [mode, setMode] = useState<'totp' | 'recovery'>('totp');
  const [code, setCode] = useState('');
  const [submitting, setSubmitting] = useState(false);
  // 실패하면 칸이 비고(비활성 동안 포커스를 잃음) — 다시 활성이 되면 첫 칸에 포커스한다.
  const [refocus, setRefocus] = useState(0);
  // 자동 제출(onComplete)과 수동 확인이 같은 틱에 겹쳐도 verify 는 한 번만.
  const inFlight = useRef(false);
  const toast = useToast();

  const isReady = (v: string) => (mode === 'totp' ? TOTP_RE.test(v) : RECOVERY_RE.test(v));
  const ready = isReady(code);

  async function verify(value: string) {
    if (inFlight.current || !isReady(value)) return;
    inFlight.current = true;
    setSubmitting(true);
    try {
      const res = await mfaApi.verify(value.trim());
      if (res.usedRecoveryCode && typeof res.remaining === 'number') {
        toast({ kind: 'warn', title: '복구 코드를 사용했습니다.', message: `남은 복구 코드: ${res.remaining}개` });
      }
      onVerified();
    } catch {
      toast({ kind: 'err', title: '인증 실패', message: mode === 'totp' ? '코드가 올바르지 않습니다.' : '복구 코드가 올바르지 않습니다.' });
      setCode('');
      setRefocus((n) => n + 1);
    } finally {
      inFlight.current = false;
      setSubmitting(false);
    }
  }

  function submit(e: React.FormEvent) {
    e.preventDefault();
    void verify(code);
  }

  function switchMode(next: 'totp' | 'recovery') {
    setMode(next);
    setCode('');
  }

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
              <div>
                <OtpInput value={code} onChange={setCode} onComplete={(v) => void verify(v)} disabled={submitting} autoFocus focusKey={refocus} />
                <p className="hint" style={{ textAlign: 'center' }}>붙여넣기 지원 · 6자리를 모두 입력하면 자동 확인</p>
              </div>
              <Button type="submit" variant="primary" size="lg" disabled={submitting || !ready}>
                {submitting ? '확인 중…' : '확인'}
              </Button>
              <div className="row" style={{ justifyContent: 'center', gap: 6, paddingTop: 4, borderTop: '1px solid var(--line-soft)' }}>
                <span className="card__sub">기기를 잃으셨나요?</span>
                <Button variant="ghost" size="sm" onClick={() => switchMode('recovery')}>복구 코드로 로그인</Button>
              </div>
            </>
          ) : (
            <>
              <div className="col" style={{ gap: 6 }}>
                <h1 className="page__title">복구 코드로 로그인</h1>
                <p className="card__sub">MFA 등록 때 저장한 복구 코드 하나를 입력하세요. 사용한 코드는 다시 쓸 수 없습니다.</p>
              </div>
              <Field label="복구 코드" htmlFor="mfa-recovery">
                <input
                  id="mfa-recovery"
                  className="input input--lg mono"
                  autoFocus
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
        <Button variant="ghost" onClick={onLogout} style={{ color: 'var(--ink-2)' }}>로그아웃</Button>
      </div>
    </main>
  );
}
