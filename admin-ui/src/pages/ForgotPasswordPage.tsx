import { useState } from 'react';
import { passwordResetApi } from '@/api/passwordReset';

/**
 * 미인증 public 화면 — 이메일로 재설정 링크 요청.
 * enumeration 방지: 항상 동일한 "메일을 보냈습니다" 안내(계정 존재 비노출).
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
    <div style={{ minHeight: '100vh', display: 'grid', placeItems: 'center', background: 'var(--bg)' }}>
      <div style={{ width: 360, padding: 28, border: '1px solid var(--border)', borderRadius: 14, background: 'var(--surface)' }}>
        <h2 style={{ marginTop: 0, fontSize: 20 }}>비밀번호 재설정</h2>
        {sent ? (
          <>
            <div style={{ padding: '12px 14px', background: 'var(--info-soft)', color: 'var(--info)', borderRadius: 8, fontSize: 13, lineHeight: 1.6 }}>
              해당 이메일이 등록돼 있다면 재설정 링크를 보냈습니다. 메일함을 확인하세요.
            </div>
            <a href="/admin/login" className="btn btn--ghost btn--sm" style={{ width: '100%', marginTop: 16, justifyContent: 'center' }}>← 로그인으로</a>
          </>
        ) : (
          <form onSubmit={submit}>
            <div style={{ fontSize: 13, color: 'var(--text-mute)', margin: '6px 0 18px' }}>
              가입한 이메일을 입력하면 재설정 링크를 보냅니다.
            </div>
            <label className="label">이메일</label>
            <input
              className="input"
              type="email"
              autoFocus
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="you@crosscert.com"
              style={{ width: '100%' }}
            />
            <button type="submit" className="btn btn--primary" disabled={submitting || !email.trim()} style={{ width: '100%', marginTop: 16, justifyContent: 'center' }}>
              {submitting ? '전송 중…' : '재설정 링크 보내기'}
            </button>
            <a href="/admin/login" className="btn btn--ghost btn--sm" style={{ width: '100%', marginTop: 8, justifyContent: 'center' }}>← 로그인으로</a>
          </form>
        )}
      </div>
    </div>
  );
}
