import { useEffect, useRef, useState } from 'react';
import { passwordResetApi } from '@/api/passwordReset';
import { ApiError } from '@/api/types';
import { isTransientError } from '@/lib/transientError';
import { Alert, Button, Field } from '@/ui';

/**
 * 미인증 public 화면 — 이메일로 재설정 링크 요청.
 * enumeration 방지: 2xx·4xx 는 항상 동일한 "메일을 확인하세요" 안내(계정 존재 비노출).
 * 네트워크·5xx 는 실제로 보내지 못했으므로 실패로 알리고 입력을 유지한다(H9).
 * 제출 후 같은 카드 안에서 완료 상태로 전환한다.
 */
export default function ForgotPasswordPage() {
  const [email, setEmail] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [sent, setSent] = useState(false);
  const [failure, setFailure] = useState<{ error: unknown } | null>(null);
  const submitRef = useRef<HTMLButtonElement>(null);

  useEffect(() => { if (failure) submitRef.current?.focus(); }, [failure]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (submitting || !email.trim()) return;
    setSubmitting(true);
    setFailure(null);
    try {
      await passwordResetApi.request(email.trim());
      setSent(true);
    } catch (err) {
      if (isTransientError(err)) setFailure({ error: err });
      else setSent(true); /* enumeration 방지: 4xx 는 성공과 같은 안내. */
    } finally {
      setSubmitting(false);
    }
  }

  const status = failure?.error instanceof ApiError ? failure.error.httpStatus : undefined;

  return (
    <main className="auth">
      <div className="auth__col">
        <img className="auth__logo" src="/admin/crosscert-logo-transparent.png" alt="CROSSCERT" />
        <div className="auth-card">
          <div className="col" style={{ gap: 6 }}>
            <h1 className="page__title">비밀번호 재설정</h1>
            <p className="card__sub">
              {sent
                ? '가입한 이메일을 입력하면 재설정 링크를 보내드립니다.'
                : '가입한 이메일을 입력하면 재설정 링크를 보내드립니다. 링크는 일정 시간 후 만료됩니다.'}
            </p>
          </div>
          {sent ? (
            <>
              <Alert tone="ok" title="메일을 확인하세요">해당 이메일로 가입된 계정이 있으면 재설정 링크가 발송됩니다.</Alert>
              <p className="hint" style={{ margin: 0 }}>메일이 오지 않으면 스팸함을 확인하거나 플랫폼 운영자에게 문의하세요.</p>
            </>
          ) : (
            <form className="col" style={{ gap: 20 }} onSubmit={submit}>
              {failure && (
                <Alert tone="bad" title="요청을 보내지 못했습니다.">
                  네트워크 또는 서버 문제입니다. 메일은 발송되지 않았습니다. 잠시 후 다시 시도하세요.
                  {status !== undefined && <span className="mono" style={{ display: 'block', fontSize: 11 }}>HTTP {status}</span>}
                </Alert>
              )}
              <Field label="이메일" htmlFor="forgot-email" help={failure ? '입력한 이메일은 그대로 있습니다.' : undefined}>
                <input
                  id="forgot-email"
                  className="input input--lg"
                  type="email"
                  // eslint-disable-next-line jsx-a11y/no-autofocus
                  autoFocus
                  autoComplete="username"
                  readOnly={submitting}
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  placeholder="you@crosscert.com"
                />
              </Field>
              <Button ref={submitRef} type="submit" variant="primary" size="lg" disabled={submitting || !email.trim()}>
                {submitting ? '전송 중…' : failure ? '다시 시도' : '재설정 링크 보내기'}
              </Button>
            </form>
          )}
        </div>
        <a href="/admin" style={{ fontSize: 13, fontWeight: 600 }}>← 로그인으로</a>
      </div>
    </main>
  );
}
