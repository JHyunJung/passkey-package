import { useState } from 'react';
import { passwordResetApi } from '@/api/passwordReset';
import { Alert, Button, Field } from '@/ui';

/**
 * 미인증 public 화면 — 이메일로 재설정 링크 요청.
 * enumeration 방지: 항상 동일한 "메일을 확인하세요" 안내(계정 존재 비노출).
 * 제출 후 같은 카드 안에서 완료 상태로 전환한다.
 */
export default function ForgotPasswordPage() {
  const [email, setEmail] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [sent, setSent] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (submitting || !email.trim()) return;
    setSubmitting(true);
    try {
      await passwordResetApi.request(email.trim());
    } catch {
      /* enumeration 방지: 실패해도 동일 안내. */
    } finally {
      setSubmitting(false);
      setSent(true);
    }
  }

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
              <Field label="이메일" htmlFor="forgot-email">
                <input
                  id="forgot-email"
                  className="input input--lg"
                  type="email"
                  autoFocus
                  autoComplete="username"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  placeholder="you@crosscert.com"
                />
              </Field>
              <Button type="submit" variant="primary" size="lg" disabled={submitting || !email.trim()}>
                {submitting ? '전송 중…' : '재설정 링크 보내기'}
              </Button>
            </form>
          )}
        </div>
        <a href="/admin" style={{ fontSize: 13, fontWeight: 600 }}>← 로그인으로</a>
      </div>
    </main>
  );
}
