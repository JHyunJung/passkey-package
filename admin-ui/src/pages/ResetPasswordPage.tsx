import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { passwordResetApi } from '@/api/passwordReset';
import { ApiError } from '@/api/types';

/**
 * 미인증 public 화면 — 이메일 링크의 ?token= 으로 진입해 새 비밀번호 설정.
 * 비번 복잡도/길이는 백엔드 검증(400 message 표시). 클라이언트는 일치/비어있음만.
 */
export default function ResetPasswordPage() {
  const [params] = useSearchParams();
  const token = params.get('token');
  const [pw, setPw] = useState('');
  const [pw2, setPw2] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [done, setDone] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const mismatch = pw.length > 0 && pw2.length > 0 && pw !== pw2;
  const canSubmit = !!token && pw.length > 0 && pw === pw2 && !submitting;

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!canSubmit) return;
    setSubmitting(true);
    setError(null);
    try {
      await passwordResetApi.confirm(token!, pw);
      setDone(true);
    } catch (err: unknown) {
      const msg = err instanceof ApiError ? err.message : '비밀번호 변경에 실패했습니다.';
      setError(msg);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div style={{ minHeight: '100vh', display: 'grid', placeItems: 'center', background: 'var(--bg)' }}>
      <div style={{ width: 360, padding: 28, border: '1px solid var(--border)', borderRadius: 14, background: 'var(--surface)' }}>
        <h2 style={{ marginTop: 0, fontSize: 20 }}>새 비밀번호 설정</h2>

        {!token ? (
          <>
            <div style={{ padding: '12px 14px', background: 'var(--danger-soft)', color: 'var(--danger)', borderRadius: 8, fontSize: 13 }}>
              유효하지 않은 링크입니다. 재설정을 다시 요청하세요.
            </div>
            <a href="/admin/login" className="btn btn--ghost btn--sm" style={{ width: '100%', marginTop: 16, justifyContent: 'center' }}>← 로그인으로</a>
          </>
        ) : done ? (
          <>
            <div style={{ padding: '12px 14px', background: 'var(--success-soft)', color: 'var(--success)', borderRadius: 8, fontSize: 13 }}>
              비밀번호가 변경되었습니다. 새 비밀번호로 로그인하세요.
            </div>
            <a href="/admin/login" className="btn btn--primary btn--sm" style={{ width: '100%', marginTop: 16, justifyContent: 'center' }}>로그인 →</a>
          </>
        ) : (
          <form onSubmit={submit}>
            <div style={{ fontSize: 13, color: 'var(--text-mute)', margin: '6px 0 18px' }}>새 비밀번호를 입력하세요.</div>
            <label className="label">새 비밀번호</label>
            <input className="input" type="password" autoFocus value={pw} onChange={(e) => setPw(e.target.value)} autoComplete="new-password" style={{ width: '100%', marginBottom: 10 }} />
            <label className="label">새 비밀번호 확인</label>
            <input className="input" type="password" value={pw2} onChange={(e) => setPw2(e.target.value)} autoComplete="new-password" style={{ width: '100%' }} />
            {mismatch && <div style={{ color: 'var(--danger)', fontSize: 12, marginTop: 6 }}>두 비밀번호가 일치하지 않습니다.</div>}
            {error && <div style={{ color: 'var(--danger)', fontSize: 12, marginTop: 6 }}>{error}</div>}
            <button type="submit" className="btn btn--primary" disabled={!canSubmit} style={{ width: '100%', marginTop: 16, justifyContent: 'center' }}>
              {submitting ? '변경 중…' : '비밀번호 변경'}
            </button>
          </form>
        )}
      </div>
    </div>
  );
}
