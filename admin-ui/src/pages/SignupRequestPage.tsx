import { useState } from 'react';
import { signupRequestsApi } from '@/api/signupRequests';
import { illustrations } from '@/assets/illustrations';
import { Button, Field } from '@/ui';

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/**
 * 미인증 public 화면 — 관리자 계정 가입 요청. PLATFORM_OPERATOR 가 승인해야 로그인 가능.
 * enumeration 방지: 서버는 항상 202 를 주고, 실패해도 같은 안내를 보여준다.
 * 비밀번호 정책(12~128자)은 서버와 동일하게 클라이언트에서도 선검증.
 * 제출 후 같은 카드 안에서 접수 완료 상태로 전환한다.
 */
export default function SignupRequestPage() {
  const [email, setEmail] = useState('');
  const [pw, setPw] = useState('');
  const [pw2, setPw2] = useState('');
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [done, setDone] = useState(false);

  const mismatch = pw.length > 0 && pw2.length > 0 && pw !== pw2;
  const pwTooShort = pw.length > 0 && pw.length < 12;
  const canSubmit =
    EMAIL_RE.test(email.trim()) && pw.length >= 12 && pw.length <= 128 && pw === pw2
    && reason.length <= 500 && !submitting;

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!canSubmit) return;
    setSubmitting(true);
    try {
      await signupRequestsApi.request({
        email: email.trim().toLowerCase(),
        password: pw,
        reason: reason.trim() ? reason.trim() : undefined,
      });
    } catch {
      /* enumeration 방지: 실패해도 동일 안내. */
    } finally {
      setSubmitting(false);
      setDone(true);
    }
  }

  return (
    <main className="auth">
      <div className="auth__col auth__col--wide">
        <img className="auth__logo" src="/admin/crosscert-logo-transparent.png" alt="CROSSCERT" />
        <div className="auth-card" style={done ? { alignItems: 'center', textAlign: 'center' } : { gap: 18 }}>
          {done ? (
            <>
              <img src={illustrations.signupSent} alt="" style={{ width: 132, height: 132, objectFit: 'contain' }} />
              <h1 className="page__title">요청이 접수되었습니다</h1>
              <p className="card__sub">운영자 승인 후 이메일로 안내됩니다. 승인 전에는 로그인할 수 없습니다.</p>
              <a className="btn" href="/admin">로그인으로 돌아가기</a>
            </>
          ) : (
            <form className="col" style={{ gap: 18 }} onSubmit={submit}>
              <div className="col" style={{ gap: 6 }}>
                <h1 className="page__title">관리자 계정 가입 요청</h1>
                <p className="card__sub">플랫폼 운영자가 승인하면 계정이 활성화됩니다. 역할과 담당 tenant 는 승인 시 지정됩니다.</p>
              </div>
              <Field label="이메일" htmlFor="signup-email">
                <input id="signup-email" className="input" type="email" autoFocus value={email}
                  onChange={(e) => setEmail(e.target.value)} autoComplete="username" placeholder="you@company.com" />
              </Field>
              <Field label="비밀번호" htmlFor="signup-pw" error={pwTooShort ? '12자 이상' : undefined}>
                <input id="signup-pw" className="input" type="password" value={pw}
                  onChange={(e) => setPw(e.target.value)} autoComplete="new-password"
                  aria-invalid={pwTooShort} aria-describedby={pwTooShort ? 'signup-pw-error' : undefined} />
              </Field>
              <Field label="비밀번호 확인" htmlFor="signup-pw2" error={mismatch ? '비밀번호가 일치하지 않습니다.' : undefined}>
                <input id="signup-pw2" className="input" type="password" value={pw2}
                  onChange={(e) => setPw2(e.target.value)} autoComplete="new-password"
                  aria-invalid={mismatch} aria-describedby={mismatch ? 'signup-pw2-error' : undefined} />
              </Field>
              <Field label={<>요청 사유 <span style={{ fontWeight: 400, color: 'var(--mute)' }}>(선택)</span></>} htmlFor="signup-reason">
                <textarea id="signup-reason" className="input" value={reason} maxLength={500}
                  onChange={(e) => setReason(e.target.value)} rows={3}
                  placeholder="소속 · 담당 RP · 필요한 권한" />
              </Field>
              <Button type="submit" variant="primary" size="lg" disabled={!canSubmit}>
                {submitting ? '전송 중…' : '가입 요청 보내기'}
              </Button>
            </form>
          )}
        </div>
        {!done && <a href="/admin" style={{ fontSize: 13, fontWeight: 600 }}>← 로그인으로</a>}
      </div>
    </main>
  );
}
