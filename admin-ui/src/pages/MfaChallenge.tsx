import { useState } from 'react';
import { mfaApi } from '@/api/mfa';
import { useToast } from '@/shell/ToastHost';
import { formatRecoveryCode } from '@/lib/recoveryCode';

export default function MfaChallenge({ onVerified, onLogout }: { onVerified: () => void; onLogout: () => void }) {
  const [mode, setMode] = useState<'totp' | 'recovery'>('totp');
  const [code, setCode] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const toast = useToast();

  const ready = mode === 'totp' ? code.length === 6 : /^[A-Z2-9]{4}-[A-Z2-9]{4}$/.test(code);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (submitting || !ready) return;
    setSubmitting(true);
    try {
      const res = await mfaApi.verify(code.trim());
      if (res.usedRecoveryCode && typeof res.remaining === 'number') {
        toast({ kind: 'warn', title: '복구 코드를 사용했습니다.', message: `남은 복구 코드: ${res.remaining}개` });
      }
      onVerified();
    } catch {
      toast({ kind: 'err', title: '인증 실패', message: mode === 'totp' ? '코드가 올바르지 않습니다.' : '복구 코드가 올바르지 않습니다.' });
      setCode('');
    } finally {
      setSubmitting(false);
    }
  }

  function switchMode(next: 'totp' | 'recovery') {
    setMode(next);
    setCode('');
  }

  return (
    <div style={{ minHeight: '100vh', display: 'grid', placeItems: 'center', background: 'var(--bg)' }}>
      <form onSubmit={submit} style={{ width: 360, padding: 28, border: '1px solid var(--border)', borderRadius: 'var(--radius-lg)', background: 'var(--surface)', boxShadow: 'var(--shadow-xs)' }}>
        <h2 style={{ marginTop: 0, fontSize: 18 }}>2단계 인증</h2>
        <div style={{ fontSize: 13, color: 'var(--text-mute)', marginBottom: 18 }}>
          {mode === 'totp'
            ? 'authenticator 앱에 표시된 6자리 코드를 입력하세요.'
            : '복구 코드(AB3F-2K7M)를 입력하세요. 1회용입니다.'}
        </div>
        {mode === 'totp' ? (
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
        ) : (
          <input
            className="input mono"
            autoFocus
            value={code}
            onChange={(e) => setCode(formatRecoveryCode(e.target.value))}
            placeholder="AB3F-2K7M"
            style={{ width: '100%', fontFamily: 'monospace', textAlign: 'center', fontSize: 16, letterSpacing: 2 }}
          />
        )}
        <button type="submit" className="btn btn--primary" disabled={submitting || !ready} style={{ width: '100%', marginTop: 16 }}>
          {submitting ? '확인 중…' : '확인'}
        </button>
        <button
          type="button"
          className="btn btn--ghost btn--sm"
          onClick={() => switchMode(mode === 'totp' ? 'recovery' : 'totp')}
          style={{ width: '100%', marginTop: 8 }}
        >
          {mode === 'totp' ? '기기를 잃으셨나요? 복구 코드로 로그인' : '← authenticator 코드로 돌아가기'}
        </button>
        <button type="button" className="btn btn--ghost btn--sm" onClick={onLogout} style={{ width: '100%', marginTop: 8 }}>
          로그아웃
        </button>
      </form>
    </div>
  );
}
