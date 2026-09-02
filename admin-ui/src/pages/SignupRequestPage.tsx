import { useState } from 'react';
import { signupRequestsApi } from '@/api/signupRequests';

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/**
 * 미인증 public 화면 — 관리자 계정 가입 요청. PLATFORM_OPERATOR 가 승인해야 로그인 가능.
 * enumeration 방지: 서버는 항상 202 를 주고, 실패해도 같은 안내를 보여준다.
 * 비밀번호 정책(12~128자)은 서버와 동일하게 클라이언트에서도 선검증.
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
    <div style={{ minHeight: '100vh', display: 'grid', placeItems: 'center', background: 'var(--bg)' }}>
      <div style={{ width: 380, padding: 28, border: '1px solid var(--border)', borderRadius: 14, background: 'var(--surface)' }}>
        <h2 style={{ marginTop: 0, fontSize: 20 }}>관리자 계정 가입 요청</h2>
        {done ? (
          <>
            <div style={{ padding: '12px 14px', background: 'var(--info-soft)', color: 'var(--info)', borderRadius: 8, fontSize: 13, lineHeight: 1.6 }}>
              요청이 접수되었습니다. 관리자 승인 후 로그인할 수 있습니다. 결과는 이메일로 안내됩니다.
            </div>
            <a href="/admin" className="btn btn--ghost btn--sm" style={{ width: '100%', marginTop: 16, justifyContent: 'center' }}>← 로그인으로</a>
          </>
        ) : (
          <form onSubmit={submit}>
            <div style={{ fontSize: 13, color: 'var(--text-mute)', margin: '6px 0 18px' }}>
              이메일과 비밀번호를 정하면 플랫폼 운영자가 검토 후 승인합니다.
            </div>
            <label className="label" htmlFor="signup-email">이메일</label>
            <input id="signup-email" className="input" type="email" autoFocus value={email}
              onChange={(e) => setEmail(e.target.value)} autoComplete="username"
              placeholder="you@company.com" style={{ width: '100%', marginBottom: 10 }} />
            <label className="label" htmlFor="signup-pw">비밀번호</label>
            <input id="signup-pw" className="input" type="password" value={pw}
              onChange={(e) => setPw(e.target.value)} autoComplete="new-password"
              style={{ width: '100%', marginBottom: 10 }} />
            {pwTooShort && <div style={{ color: 'var(--danger)', fontSize: 12, marginTop: -6, marginBottom: 8 }}>비밀번호는 12자 이상이어야 합니다.</div>}
            <label className="label" htmlFor="signup-pw2">비밀번호 확인</label>
            <input id="signup-pw2" className="input" type="password" value={pw2}
              onChange={(e) => setPw2(e.target.value)} autoComplete="new-password"
              style={{ width: '100%', marginBottom: 10 }} />
            {mismatch && <div style={{ color: 'var(--danger)', fontSize: 12, marginTop: -6, marginBottom: 8 }}>두 비밀번호가 일치하지 않습니다.</div>}
            <label className="label" htmlFor="signup-reason">요청 사유 (선택)</label>
            <textarea id="signup-reason" className="input" value={reason} maxLength={500}
              onChange={(e) => setReason(e.target.value)} rows={3}
              placeholder="소속·역할 등 승인 판단에 도움이 되는 내용" style={{ width: '100%', resize: 'vertical' }} />
            <button type="submit" className="btn btn--primary" disabled={!canSubmit}
              style={{ width: '100%', marginTop: 16, justifyContent: 'center' }}>
              {submitting ? '전송 중…' : '가입 요청 보내기'}
            </button>
            <a href="/admin" className="btn btn--ghost btn--sm" style={{ width: '100%', marginTop: 8, justifyContent: 'center' }}>← 로그인으로</a>
          </form>
        )}
      </div>
    </div>
  );
}
