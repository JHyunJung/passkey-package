import { useState } from 'react';
import { mfaApi } from '@/api/mfa';
import { useToast } from '@/shell/ToastHost';

export default function MfaChallenge({ onVerified, onLogout }: { onVerified: () => void; onLogout: () => void }) {
  const [code, setCode] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const toast = useToast();

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (submitting || code.length !== 6) return;
    setSubmitting(true);
    try {
      await mfaApi.verify(code);
      onVerified();
    } catch {
      toast({ kind: 'err', title: '인증 실패', message: '코드가 올바르지 않습니다.' });
      setCode('');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div style={{ minHeight: '100vh', display: 'grid', placeItems: 'center', background: 'var(--bg)' }}>
      <form onSubmit={submit} style={{ width: 360, padding: 28, border: '1px solid var(--border)', borderRadius: 14, background: 'var(--surface)' }}>
        <h2 style={{ marginTop: 0, fontSize: 18 }}>2단계 인증</h2>
        <div style={{ fontSize: 13, color: 'var(--text-mute)', marginBottom: 18 }}>
          authenticator 앱에 표시된 6자리 코드를 입력하세요.
        </div>
        <input
          className="input"
          inputMode="numeric"
          autoFocus
          maxLength={6}
          value={code}
          onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
          placeholder="000000"
          style={{ width: '100%', fontFamily: 'monospace', letterSpacing: 4, textAlign: 'center', fontSize: 18 }}
        />
        <button type="submit" className="btn btn--primary" disabled={submitting || code.length !== 6} style={{ width: '100%', marginTop: 16 }}>
          {submitting ? '확인 중…' : '확인'}
        </button>
        <button type="button" className="btn btn--ghost btn--sm" onClick={onLogout} style={{ width: '100%', marginTop: 8 }}>
          로그아웃
        </button>
      </form>
    </div>
  );
}
