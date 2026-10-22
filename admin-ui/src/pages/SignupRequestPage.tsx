import { useEffect, useRef, useState } from 'react';
import { api } from '@/api/client';
import { signupRequestsApi } from '@/api/signupRequests';
import { ApiError } from '@/api/types';
import { illustrations } from '@/assets/illustrations';
import { isTransientError } from '@/lib/transientError';
import { Alert, Button, ChoiceCard, Field } from '@/ui';

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const PW_MIN = 12;
const PW_MAX = 128;

/**
 * 미인증 public 화면 — 관리자 계정 가입 요청. PLATFORM_OPERATOR 가 승인해야 로그인 가능.
 * enumeration 방지: 서버는 항상 202 를 주고, 2xx·4xx 는 같은 접수 안내를 보여준다.
 * 네트워크·5xx 는 접수되지 않았으므로 실패로 알리고 입력을 유지한다(H9).
 * 비밀번호 정책(12~128자)은 서버와 동일하게 클라이언트에서도 선검증하고 범위를 미리 보여 준다(H5).
 * 요청 중에는 입력을 읽기 전용으로 두어, 늦은 실패 응답이 다시 입력한 내용과 경합하지 않게 한다.
 * 서버가 PLATFORM_OPERATOR 즉시 가입(QA 전용 설정)을 켜 두었으면 역할 선택지를 보이고,
 * 그 역할로 계정이 바로 만들어지면(activated) 승인 대기 대신 바로 로그인 안내를 보여 준다.
 */
export default function SignupRequestPage() {
  const [email, setEmail] = useState('');
  const [emailTouched, setEmailTouched] = useState(false);
  const [pw, setPw] = useState('');
  const [pw2, setPw2] = useState('');
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [done, setDone] = useState<false | 'pending' | 'activated'>(false);
  const [selfSignupAvailable, setSelfSignupAvailable] = useState(false);
  const [role, setRole] = useState<'RP_ADMIN' | 'PLATFORM_OPERATOR'>('RP_ADMIN');
  const [failure, setFailure] = useState<{ error: unknown } | null>(null);
  const submitRef = useRef<HTMLButtonElement>(null);

  const emailInvalid = emailTouched && email.trim().length > 0 && !EMAIL_RE.test(email.trim());
  const mismatch = pw.length > 0 && pw2.length > 0 && pw !== pw2;
  const pwTooShort = pw.length > 0 && pw.length < PW_MIN;
  const canSubmit =
    EMAIL_RE.test(email.trim()) && pw.length >= PW_MIN && pw.length <= PW_MAX && pw === pw2
    && reason.length <= 500 && !submitting;

  useEffect(() => { if (failure) submitRef.current?.focus(); }, [failure]);

  useEffect(() => {
    api.get<{ platformOperatorSelfSignup?: boolean }>('/admin/api/profile')
      .then((p) => setSelfSignupAvailable(Boolean(p?.platformOperatorSelfSignup)))
      .catch(() => { /* 조회 실패 — 기본(승인 필요) 화면 그대로 */ });
  }, []);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!canSubmit) return;
    setSubmitting(true);
    setFailure(null);
    try {
      const res = await signupRequestsApi.request({
        email: email.trim().toLowerCase(),
        password: pw,
        reason: reason.trim() ? reason.trim() : undefined,
        role: selfSignupAvailable && role === 'PLATFORM_OPERATOR' ? 'PLATFORM_OPERATOR' : undefined,
      });
      setDone(res?.activated ? 'activated' : 'pending');
    } catch (err) {
      if (isTransientError(err)) setFailure({ error: err });
      else setDone('pending'); /* enumeration 방지: 4xx 는 성공과 같은 안내. */
    } finally {
      setSubmitting(false);
    }
  }

  const status = failure?.error instanceof ApiError ? failure.error.httpStatus : undefined;

  return (
    <main className="auth">
      <div className="auth__col auth__col--wide">
        <img className="auth__logo" src="/admin/crosscert-logo-transparent.png" alt="CROSSCERT" />
        <div className="auth-card" style={done ? { alignItems: 'center', textAlign: 'center' } : { gap: 18 }}>
          {done === 'activated' ? (
            <>
              <img src={illustrations.signupSent} alt="" style={{ width: 132, height: 132, objectFit: 'contain' }} />
              <h1 className="page__title">계정이 만들어졌습니다</h1>
              <p className="card__sub">Platform Operator 계정이 활성화되었습니다. 바로 로그인할 수 있습니다.</p>
              <a className="btn" href="/admin">로그인하기</a>
            </>
          ) : done ? (
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
              {failure && (
                <Alert tone="bad" title="요청을 보내지 못했습니다.">
                  네트워크 또는 서버 문제로 접수되지 않았습니다. 입력한 내용은 그대로 있습니다.
                  {status !== undefined && <span className="mono" style={{ display: 'block', fontSize: 11 }}>HTTP {status}</span>}
                </Alert>
              )}
              {selfSignupAvailable && (
                <div>
                  <span className="label">역할</span>
                  <div className="choices choices--2" role="radiogroup" aria-label="역할">
                    <ChoiceCard checked={role === 'RP_ADMIN'} onSelect={() => setRole('RP_ADMIN')}
                      title="승인 후 사용" description="운영자가 승인하며 역할·tenant 를 지정" />
                    <ChoiceCard checked={role === 'PLATFORM_OPERATOR'} onSelect={() => setRole('PLATFORM_OPERATOR')}
                      title="Platform Operator" description="승인 없이 바로 사용 (QA 전용)" />
                  </div>
                </div>
              )}
              <Field label="이메일" htmlFor="signup-email" error={emailInvalid ? '이메일 형식이 올바르지 않습니다. 예: you@company.com' : undefined}>
                {/* eslint-disable-next-line jsx-a11y/no-autofocus */}
                <input id="signup-email" className="input" type="email" autoFocus value={email} readOnly={submitting}
                  onChange={(e) => setEmail(e.target.value)} onBlur={() => setEmailTouched(true)}
                  autoComplete="username" placeholder="you@company.com"
                  aria-invalid={emailInvalid} aria-describedby={emailInvalid ? 'signup-email-error' : undefined} />
              </Field>
              <Field label="비밀번호" htmlFor="signup-pw" help="12–128자"
                error={pwTooShort ? `${pw.length}자 입력됨 · 12–128자로 입력하세요` : undefined}>
                <input id="signup-pw" className="input" type="password" value={pw} maxLength={PW_MAX} readOnly={submitting}
                  onChange={(e) => setPw(e.target.value)} autoComplete="new-password"
                  aria-invalid={pwTooShort} aria-describedby={pwTooShort ? 'signup-pw-error' : undefined} />
              </Field>
              <Field label="비밀번호 확인" htmlFor="signup-pw2" error={mismatch ? '비밀번호가 일치하지 않습니다.' : undefined}>
                <input id="signup-pw2" className="input" type="password" value={pw2} maxLength={PW_MAX} readOnly={submitting}
                  onChange={(e) => setPw2(e.target.value)} autoComplete="new-password"
                  aria-invalid={mismatch} aria-describedby={mismatch ? 'signup-pw2-error' : undefined} />
              </Field>
              <Field label={<>요청 사유 <span style={{ fontWeight: 400, color: 'var(--mute)' }}>(선택)</span></>} htmlFor="signup-reason">
                <textarea id="signup-reason" className="input" value={reason} maxLength={500} readOnly={submitting}
                  onChange={(e) => setReason(e.target.value)} rows={3}
                  placeholder="소속 · 담당 RP · 필요한 권한" />
              </Field>
              <Button ref={submitRef} type="submit" variant="primary" size="lg" disabled={!canSubmit}>
                {submitting ? '전송 중…' : failure ? '다시 시도' : '가입 요청 보내기'}
              </Button>
            </form>
          )}
        </div>
        {!done && <a href="/admin" style={{ fontSize: 13, fontWeight: 600 }}>← 로그인으로</a>}
      </div>
    </main>
  );
}
